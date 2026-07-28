//! MeowMic Android JNI Core
//!
//! 暴露给 Kotlin 的 native 方法,封装在 `com.meowmic.client.NativeBridge` 类。
//!
//! 架构:
//! - 全局 tokio runtime + Client 实例
//! - Touch/Audio 同步发送(block_on),Control 接收在后台 task
//! - Audio encoder 在 Rust 端,Kotlin 传 i16 PCM 数组过来
//!
//! 线程模型:
//! - JNI 调用线程:Kotlin UI/Audio 线程
//! - Tokio runtime:独立线程池,跑控制接收 + 时钟同步
//! - block_on 调用 UDP send(非阻塞 socket,实际几乎不阻塞)

use std::sync::atomic::{AtomicU16, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use jni::objects::{JClass, JPrimitiveArray, JString, JShortArray};
use jni::sys::{jboolean, jfloat, jint, jshort, jshortArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use log::LevelFilter;
use once_cell::sync::OnceCell;
use tokio::runtime::Runtime;

use meowmic_audio::{AudioConfig, AudioEncoder, make_encoder};
use meowmic_net::Client;
use meowmic_protocol::TouchEventType;

/// 全局状态
struct State {
    rt: Runtime,
    client: Arc<Client>,
    encoder: Mutex<Box<dyn AudioEncoder>>,
    audio_cfg: AudioConfig,
    touch_seq: AtomicU16,
    audio_seq: AtomicU16,
    touch_sent: AtomicU64,
    audio_sent: AtomicU64,
}

static STATE: OnceLock<Mutex<Option<State>>> = OnceLock::new();
static LOGGER_INIT: OnceCell<()> = OnceCell::new();

fn init_logger() {
    LOGGER_INIT.get_or_init(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_tag("MeowMic")
                .with_max_level(LevelFilter::Info),
        );
    });
}

fn state() -> &'static Mutex<Option<State>> {
    STATE.get_or_init(|| Mutex::new(None))
}

/// Java: boolean nativeConnect(String serverAddr, String clientName)
#[no_mangle]
pub extern "system" fn Java_com_meowmic_client_NativeBridge_nativeConnect(
    mut env: JNIEnv,
    _class: JClass,
    server_addr: JString,
    client_name: JString,
) -> jboolean {
    init_logger();
    let server_addr: String = match env.get_string(&server_addr) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let client_name: String = match env.get_string(&client_name) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };

    log::info!("nativeConnect: {} as {}", server_addr, client_name);

    // 创建 tokio runtime
    let rt = match Runtime::new() {
        Ok(rt) => rt,
        Err(e) => {
            log::error!("创建 runtime 失败: {}", e);
            return JNI_FALSE;
        }
    };

    // 在 runtime 中连接
    let (event_tx, mut event_rx) = tokio::sync::mpsc::channel(64);
    let client_result = rt.block_on(async {
        Client::connect(&server_addr, &client_name, event_tx).await
    });

    let client = match client_result {
        Ok(c) => Arc::new(c),
        Err(e) => {
            log::error!("连接失败: {}", e);
            return JNI_FALSE;
        }
    };

    // 后台消费事件(防止 channel 满)
    rt.spawn(async move {
        while let Some(ev) = event_rx.recv().await {
            log::debug!("client event: {:?}", ev);
        }
    });

    let audio_cfg = AudioConfig::default();
    let encoder = Mutex::new(make_encoder(&audio_cfg));

    let new_state = State {
        rt,
        client: client.clone(),
        encoder,
        audio_cfg,
        touch_seq: AtomicU16::new(0),
        audio_seq: AtomicU16::new(0),
        touch_sent: AtomicU64::new(0),
        audio_sent: AtomicU64::new(0),
    };

    let mut guard = state().lock().unwrap();
    if guard.is_some() {
        log::warn!("已有连接,先断开旧连接");
        // drop 旧的
        *guard = None;
    }
    *guard = Some(new_state);
    JNI_TRUE
}

/// Java: boolean nativeSendTouch(int eventType, float dx, float dy)
///
/// 旧接口保留兼容,button_mask=0。
#[no_mangle]
pub extern "system" fn Java_com_meowmic_client_NativeBridge_nativeSendTouch(
    env: JNIEnv,
    _class: JClass,
    event_type: jint,
    dx: jfloat,
    dy: jfloat,
) -> jboolean {
    // 转发到新的带 button_mask 的实现
    Java_com_meowmic_client_NativeBridge_nativeSendTouchWithButton(
        env, _class, event_type, 0, dx, dy,
    )
}

/// Java: boolean nativeSendTouchWithButton(int eventType, int buttonMask, float dx, float dy)
///
/// `buttonMask` 位定义: bit0=左键 bit1=右键 bit2=中键
/// 用于 `TouchEventType::Button` (0x04) 事件表达鼠标按键按下/抬起。
///
/// 使用 send_touch_sync 同步发送,绕过 tokio block_on,避免 UI 线程阻塞。
#[no_mangle]
pub extern "system" fn Java_com_meowmic_client_NativeBridge_nativeSendTouchWithButton(
    _env: JNIEnv,
    _class: JClass,
    event_type: jint,
    button_mask: jint,
    dx: jfloat,
    dy: jfloat,
) -> jboolean {
    let event = match event_type as u8 {
        0x01 => TouchEventType::Down,
        0x02 => TouchEventType::Move,
        0x03 => TouchEventType::Up,
        0x04 => TouchEventType::Button,
        0x05 => TouchEventType::Scroll,
        _ => return JNI_FALSE,
    };

    let guard = state().lock().unwrap();
    let Some(s) = guard.as_ref() else {
        return JNI_FALSE;
    };

    // 同步发送:无 block_on,无 async Mutex,直接 kernel syscall
    let result = s.client.send_touch_sync(event, button_mask as u8, dx, dy);
    match result {
        Ok(_) => {
            s.touch_sent.fetch_add(1, Ordering::Relaxed);
            JNI_TRUE
        }
        Err(e) => {
            log::warn!("send_touch 失败: {}", e);
            JNI_FALSE
        }
    }
}

/// Java: boolean nativeSendAudioFrame(short[] pcm)
#[no_mangle]
pub extern "system" fn Java_com_meowmic_client_NativeBridge_nativeSendAudioFrame(
    mut env: JNIEnv,
    _class: JClass,
    pcm_array: jshortArray,
) -> jboolean {
    let guard_result = state().lock();
    let Ok(guard) = guard_result else {
        return JNI_FALSE;
    };
    let Some(s) = guard.as_ref() else {
        return JNI_FALSE;
    };

    // 把 raw jshortArray 转成安全包装(函数结束自动 release local ref)
    let pcm_array: JShortArray = unsafe { JPrimitiveArray::from_raw(pcm_array) };

    // 读取 PCM 数组长度
    let pcm_len = match env.get_array_length(&pcm_array) {
        Ok(n) => n as usize,
        Err(_) => return JNI_FALSE,
    };
    let expected = s.audio_cfg.samples_per_frame();
    if pcm_len < expected {
        log::warn!(
            "PCM 长度不足: {} < {}",
            pcm_len,
            expected
        );
        return JNI_FALSE;
    }

    let mut pcm = vec![0i16; expected];
    if let Err(e) = env.get_short_array_region(&pcm_array, 0, &mut pcm) {
        log::warn!("读取 PCM 失败: {}", e);
        return JNI_FALSE;
    }

    // 编码
    let mut encoded = vec![0u8; 8192];
    let enc_len = {
        let mut enc = s.encoder.lock().unwrap();
        match enc.encode(&pcm, &mut encoded) {
            Ok(n) => n,
            Err(e) => {
                log::warn!("编码失败: {}", e);
                return JNI_FALSE;
            }
        }
    };

    // 发送
    let result = s.rt.handle().block_on(
        s.client.send_audio(&encoded[..enc_len]),
    );
    match result {
        Ok(_) => {
            s.audio_sent.fetch_add(1, Ordering::Relaxed);
            JNI_TRUE
        }
        Err(e) => {
            log::warn!("send_audio 失败: {}", e);
            JNI_FALSE
        }
    }
}

/// Java: void nativeDisconnect()
#[no_mangle]
pub extern "system" fn Java_com_meowmic_client_NativeBridge_nativeDisconnect(
    _env: JNIEnv,
    _class: JClass,
) {
    log::info!("nativeDisconnect");
    let mut guard = state().lock().unwrap();
    if let Some(s) = guard.take() {
        let _ = s.rt.handle().block_on(s.client.disconnect());
        // runtime drop 在持有 guard 时进行,确保任务清理
    }
}

/// Java: boolean nativeSetMuteSpeaker(boolean muted)
#[no_mangle]
pub extern "system" fn Java_com_meowmic_client_NativeBridge_nativeSetMuteSpeaker(
    _env: JNIEnv,
    _class: JClass,
    muted: jboolean,
) -> jboolean {
    let guard = state().lock().unwrap();
    let Some(s) = guard.as_ref() else {
        return JNI_FALSE;
    };
    let muted_bool = muted == JNI_TRUE;
    match s.rt.handle().block_on(s.client.set_mute_speaker(muted_bool)) {
        Ok(_) => JNI_TRUE,
        Err(e) => {
            log::warn!("set_mute_speaker 失败: {}", e);
            JNI_FALSE
        }
    }
}

/// Java: String nativeGetStats()
#[no_mangle]
pub extern "system" fn Java_com_meowmic_client_NativeBridge_nativeGetStats(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let (touch_sent, audio_sent) = {
        let guard = state().lock().unwrap();
        if let Some(s) = guard.as_ref() {
            (
                s.touch_sent.load(Ordering::Relaxed),
                s.audio_sent.load(Ordering::Relaxed),
            )
        } else {
            (0, 0)
        }
    };

    let json = format!(
        r#"{{"touch_sent":{},"audio_sent":{}}}"#,
        touch_sent, audio_sent
    );

    match env.new_string(&json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
