//! MeowMic Android JNI Core
//!
//! 暴露给 Kotlin 的 native 方法,封装在 `com.meowmic.client.NativeBridge` 类。
//!
//! 架构:
//! - 全局 tokio runtime + Client 实例
//! - Touch/Audio 同步发送(block_on),Control 接收在后台 task
//! - Audio encoder 在 Rust 端,Kotlin 传 i16 PCM 数组过来
//!
//! 配对流程:
//! 1. nativeConnect 返回 1=已连接 / 2=需要配对 / 0=失败
//!    (3=地址无效 / 4=主机不可达 / 5=连接被拒绝,供 UI 精确提示)
//! 2. 若需要配对,Kotlin 弹出 PIN 输入框,调用 nativeCompletePairing(pin)
//! 3. 配对成功后自动重连(发送 HelloPaired),返回 1=已连接 / 0=失败
//! 4. 后续连接若已配对该服务端,nativeConnect 自动发送 HelloPaired

use std::path::PathBuf;
use std::sync::atomic::{AtomicU16, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use base64::Engine;
use jni::objects::{JClass, JPrimitiveArray, JString, JShortArray};
use jni::sys::{jboolean, jfloat, jint, jshortArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use log::LevelFilter;
use once_cell::sync::OnceCell;
use tokio::runtime::Runtime;

use meowmic_audio::{AudioConfig, AudioEncoder, make_encoder};
use meowmic_net::pairing::ClientPairingState;
use meowmic_net::{Client, ClientEvent};
use meowmic_protocol::TouchEventType;

/// base64 标准编码引擎
const B64: base64::engine::general_purpose::GeneralPurpose = base64::engine::general_purpose::STANDARD;

/// 已连接状态
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

/// 配对中状态(收到 PairRequired 后保存,等待用户输入 PIN)
struct PendingPairing {
    rt: Runtime,
    client: Arc<Client>,
    event_rx: tokio::sync::mpsc::Receiver<ClientEvent>,
    server_pubkey: Vec<u8>,
    server_nonce: u64,
    client_state: ClientPairingState,
    server_addr: String,
    client_name: String,
}

static STATE: OnceLock<Mutex<Option<State>>> = OnceLock::new();
static PENDING_PAIRING: OnceLock<Mutex<Option<PendingPairing>>> = OnceLock::new();
static STATE_DIR: OnceCell<PathBuf> = OnceCell::new();
static LOGGER_INIT: OnceCell<()> = OnceCell::new();
static VIDEO_RX: OnceLock<Mutex<Option<VideoReceiver>>> = OnceLock::new();

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

/// 视频接收器:在独立 tokio task 中接收 UDP 分片,重组为完整 NALU
pub struct VideoReceiver {
    /// 已重组的完整帧队列(Kotlin 通过 nativePollVideoFrame 取出)
    frames: Arc<Mutex<std::collections::VecDeque<Vec<u8>>>>,
    /// 统计计数器(自上次 nativePollVideoStats 后累计)
    stats: Arc<Mutex<VideoStats>>,
    /// 接收 task handle (stop 时 abort)
    handle: Option<tokio::task::JoinHandle<()>>,
}

/// 视频接收统计快照(每次 nativePollVideoStats 后会被清零)
#[derive(Debug, Default, Clone, Copy)]
pub struct VideoStats {
    pub received_frames: u32,
    pub lost_frames: u32,
    pub recovered_frames: u32,
}

/// 单个 frame_id 的重组条目
struct ReassemblyEntry {
    /// 原始分片按 frag_idx 索引(None 表示未到)
    parts: Vec<Option<Vec<u8>>>,
    /// FEC 冗余包(None 表示未到)
    fec: Option<Vec<u8>>,
    /// 是否关键帧(用于恢复失败时的日志)
    is_keyframe: bool,
}

/// 重组条目尝试完成后的动作
enum ReassemblyAction {
    /// 无需操作,继续等
    Pending,
    /// 全部分片到齐,可重组
    Complete,
    /// 单片丢失已用 FEC 恢复,可重组
    Recovered,
}

impl ReassemblyEntry {
    fn new(frag_total: u8, is_keyframe: bool) -> Self {
        Self {
            parts: vec![None; frag_total as usize],
            fec: None,
            is_keyframe,
        }
    }

    /// 检查是否可完成,如果缺 1 个分片且有 FEC 包,执行 XOR 恢复
    fn try_complete_or_recover(&mut self) -> ReassemblyAction {
        let have_count = self.parts.iter().filter(|p| p.is_some()).count();
        let total = self.parts.len();
        if have_count == total {
            return ReassemblyAction::Complete;
        }
        // 缺 1 个分片,且有 FEC 包 → 尝试恢复
        if total - have_count == 1 && self.fec.is_some() {
            let fec = self.fec.take().unwrap();
            let mut have: Vec<(u8, &[u8])> = Vec::with_capacity(total - 1);
            for (idx, p) in self.parts.iter().enumerate() {
                if let Some(payload) = p {
                    have.push((idx as u8, payload.as_slice()));
                }
            }
            if let Some((missing_idx, recovered)) =
                meowmic_protocol::recover_with_fec(&have, &fec, total as u8)
            {
                self.parts[missing_idx as usize] = Some(recovered);
                return ReassemblyAction::Recovered;
            }
            // 恢复失败(可能 FEC 包长度不足),还原 fec
            self.fec = Some(fec);
        }
        ReassemblyAction::Pending
    }
}

impl VideoReceiver {
    /// 启动视频接收循环
    fn start(client: Arc<Client>) -> Self {
        let frames: Arc<Mutex<std::collections::VecDeque<Vec<u8>>>> =
            Arc::new(Mutex::new(std::collections::VecDeque::with_capacity(5)));
        let stats: Arc<Mutex<VideoStats>> = Arc::new(Mutex::new(VideoStats::default()));

        let video_sock = client.video_sock().clone();
        let video_peer = client.video_peer();

        let frames_clone = frames.clone();
        let stats_clone = stats.clone();
        let handle = tokio::spawn(async move {
            let mut buf = vec![0u8; 1500];
            // 重组缓冲: frame_id → ReassemblyEntry
            let mut reassembler: std::collections::HashMap<u16, ReassemblyEntry> =
                std::collections::HashMap::new();
            let mut last_frame_id: u16 = 0;

            log::info!("视频接收循环启动: listen on {}, peer={}", video_sock.local_addr().unwrap(), video_peer);

            loop {
                match video_sock.recv_from(&mut buf).await {
                    Ok((n, _peer)) => {
                        if n < meowmic_protocol::VIDEO_FRAGMENT_HEADER_LEN {
                            continue;
                        }
                        let mut cur: &[u8] = &buf[..n];
                        let frag = match meowmic_protocol::VideoFragment::decode(&mut cur) {
                            Ok(f) => f,
                            Err(_) => continue,
                        };
                        if frag.magic != meowmic_protocol::VIDEO_MAGIC {
                            continue;
                        }
                        let payload = cur.to_vec();

                        // 丢包检测: frame_id 跳跃时丢弃旧帧,等待下一个 keyframe
                        if frag.frame_id.wrapping_sub(last_frame_id) > 1 && !frag.is_keyframe() {
                            // 丢失了帧,清空旧的重组缓冲
                            let dropped = reassembler.len() as u32;
                            if let Ok(mut s) = stats_clone.lock() {
                                s.lost_frames = s.lost_frames.saturating_add(dropped);
                            }
                            reassembler.clear();
                        }
                        last_frame_id = frag.frame_id;

                        // 取或创建该帧的重组条目
                        let entry = reassembler.entry(frag.frame_id).or_insert_with(|| {
                            ReassemblyEntry::new(frag.frag_total, frag.is_keyframe())
                        });

                        if frag.is_fec() {
                            // FEC 包:保存,尝试单包恢复
                            entry.fec = Some(payload);
                        } else if (frag.frag_idx as usize) < entry.parts.len() {
                            // 原始分片
                            entry.parts[frag.frag_idx as usize] = Some(payload);
                        }

                        // 尝试完成或恢复
                        let action = entry.try_complete_or_recover();
                        match action {
                            ReassemblyAction::Complete => {
                                let e = reassembler.remove(&frag.frame_id).unwrap();
                                let mut nalu = Vec::with_capacity(e.parts.len() * 1400);
                                for part in &e.parts {
                                    nalu.extend_from_slice(part.as_ref().unwrap());
                                }
                                let mut q = frames_clone.lock().unwrap();
                                if q.len() >= 5 {
                                    q.pop_front(); // 丢弃最旧帧,限制队列长度
                                }
                                q.push_back(nalu);
                                if let Ok(mut s) = stats_clone.lock() {
                                    s.received_frames = s.received_frames.saturating_add(1);
                                }
                            }
                            ReassemblyAction::Recovered => {
                                let e = reassembler.remove(&frag.frame_id).unwrap();
                                let mut nalu = Vec::with_capacity(e.parts.len() * 1400);
                                for part in &e.parts {
                                    nalu.extend_from_slice(part.as_ref().unwrap());
                                }
                                let mut q = frames_clone.lock().unwrap();
                                if q.len() >= 5 {
                                    q.pop_front();
                                }
                                q.push_back(nalu);
                                if let Ok(mut s) = stats_clone.lock() {
                                    s.received_frames = s.received_frames.saturating_add(1);
                                    s.recovered_frames = s.recovered_frames.saturating_add(1);
                                }
                                log::debug!("frame_id={} FEC 恢复成功", frag.frame_id);
                            }
                            ReassemblyAction::Pending => {}
                        }
                    }
                    Err(e) => {
                        log::warn!("视频 UDP 接收错误: {}", e);
                    }
                }
            }
        });

        Self {
            frames,
            stats,
            handle: Some(handle),
        }
    }

    /// 取出一个完整 NALU (非阻塞)
    fn poll_frame(&self) -> Option<Vec<u8>> {
        let mut q = self.frames.lock().unwrap();
        q.pop_front()
    }

    /// 取出并清零统计快照(供 Kotlin 周期性上报给服务端做自适应码率)
    fn poll_stats(&self) -> VideoStats {
        let mut s = self.stats.lock().unwrap();
        let snapshot = *s;
        *s = VideoStats::default();
        snapshot
    }

    /// 停止接收
    fn stop(&mut self) {
        if let Some(h) = self.handle.take() {
            h.abort();
        }
    }
}

impl Drop for VideoReceiver {
    fn drop(&mut self) {
        self.stop();
    }
}

fn video_rx() -> &'static Mutex<Option<VideoReceiver>> {
    VIDEO_RX.get_or_init(|| Mutex::new(None))
}

fn pending_pairing() -> &'static Mutex<Option<PendingPairing>> {
    PENDING_PAIRING.get_or_init(|| Mutex::new(None))
}

/// 客户端配对状态文件路径
fn pairing_state_path() -> Option<PathBuf> {
    STATE_DIR.get().map(|d| d.join("client-pairing.json"))
}

/// 加载或创建客户端配对状态
fn load_client_pairing_state() -> Option<ClientPairingState> {
    let path = pairing_state_path()?;
    match ClientPairingState::load_or_create(&path) {
        Ok(s) => Some(s),
        Err(e) => {
            log::warn!("加载客户端配对状态失败: {}", e);
            None
        }
    }
}

/// 持久化客户端配对状态
fn save_client_pairing_state(state: &ClientPairingState) {
    if let Some(path) = pairing_state_path() {
        if let Err(e) = state.save(&path) {
            log::warn!("持久化客户端配对状态失败: {}", e);
        }
    }
}

/// Java: void nativeSetStateDir(String path)
/// 设置配对状态文件所在目录(由 Kotlin 通过 Context.getFilesDir() 传入)
#[no_mangle]
pub extern "system" fn Java_com_meowmic_client_NativeBridge_nativeSetStateDir(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) {
    init_logger();
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    let path_buf = PathBuf::from(&path_str);
    log::info!("配对状态目录: {}", path_buf.display());
    let _ = STATE_DIR.set(path_buf);
}

/// 把网络层错误映射为结构化返回码(参考 Moonlight 的错误分类):
/// - 3: 地址无效(无法解析 host:port)
/// - 4: 主机不可达(TCP 连接超时,数据包无回应)
/// - 5: 连接被拒绝(主机可达但端口未开放,服务未启动或端口错误)
/// - 0: 其它失败
fn map_connect_error(e: &meowmic_net::NetError) -> jint {
    use meowmic_net::NetError;
    match e {
        NetError::Handshake(_) => 3,
        NetError::Io(io_err) => match io_err.kind() {
            std::io::ErrorKind::TimedOut => 4,
            std::io::ErrorKind::ConnectionRefused => 5,
            _ => 0,
        },
        _ => 0,
    }
}

/// Java: int nativeConnect(String serverAddr, String clientName)
/// 返回值: 0=失败, 1=已连接, 2=需要配对(等待 nativeCompletePairing),
///        3=地址无效, 4=主机不可达(超时), 5=连接被拒绝
#[no_mangle]
pub extern "system" fn Java_com_meowmic_client_NativeBridge_nativeConnect(
    mut env: JNIEnv,
    _class: JClass,
    server_addr: JString,
    client_name: JString,
) -> jint {
    init_logger();
    let server_addr: String = match env.get_string(&server_addr) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let client_name: String = match env.get_string(&client_name) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    log::info!("nativeConnect: {} as {}", server_addr, client_name);

    // 创建 tokio runtime
    let rt = match Runtime::new() {
        Ok(rt) => rt,
        Err(e) => {
            log::error!("创建 runtime 失败: {}", e);
            return 0;
        }
    };

    // 连接并发送 Hello
    let (event_tx, mut event_rx) = tokio::sync::mpsc::channel(64);
    let client_result = rt.block_on(async {
        Client::connect(&server_addr, &client_name, event_tx).await
    });

    let client = match client_result {
        Ok(c) => Arc::new(c),
        Err(e) => {
            log::error!("连接失败: {}", e);
            return map_connect_error(&e);
        }
    };

    // 等待握手响应(HelloAck 或 PairRequired),8 秒超时
    // 循环跳过 ClockSynced 等非目标事件(后台 sync_loop 每 2s 产生一次)
    enum Handshake {
        Ack,
        PairRequired { server_pubkey: Vec<u8>, server_nonce: u64 },
        Failed(&'static str),
    }

    let handshake = rt.block_on(async {
        let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(8);
        loop {
            match tokio::time::timeout_at(deadline, event_rx.recv()).await {
                Ok(Some(ClientEvent::HelloAck { .. })) => break Handshake::Ack,
                Ok(Some(ClientEvent::PairRequired { server_pubkey, server_nonce })) => {
                    break Handshake::PairRequired { server_pubkey, server_nonce };
                }
                Ok(Some(ev)) => {
                    // 跳过 ClockSynced/Error/Disconnected 等非目标事件,继续等待
                    log::debug!("握手阶段跳过事件: {:?}", ev);
                    continue;
                }
                Ok(None) => break Handshake::Failed("事件通道关闭"),
                Err(_) => break Handshake::Failed("等待握手响应超时(8s)"),
            }
        }
    });

    match handshake {
        Handshake::Ack => {
            // 已连接(服务端未启用配对,或已配对但发送的是普通 Hello 被放行)
            log::info!("握手成功(HelloAck)");
            // 启动后台事件消费
            rt.spawn(async move {
                while let Some(ev) = event_rx.recv().await {
                    log::debug!("client event: {:?}", ev);
                }
            });
            let new_state = State {
                rt,
                client: client.clone(),
                encoder: Mutex::new(make_encoder(&AudioConfig::default())),
                audio_cfg: AudioConfig::default(),
                touch_seq: AtomicU16::new(0),
                audio_seq: AtomicU16::new(0),
                touch_sent: AtomicU64::new(0),
                audio_sent: AtomicU64::new(0),
            };
            let mut guard = state().lock().unwrap();
            if guard.is_some() {
                log::warn!("已有连接,先断开旧连接");
                *guard = None;
            }
            *guard = Some(new_state);
            1
        }
        Handshake::PairRequired { server_pubkey, server_nonce } => {
            // 需要配对,保存 pending 状态
            log::info!("服务端要求配对: server_nonce={}", server_nonce);
            let client_state = match load_client_pairing_state() {
                Some(s) => s,
                None => {
                    log::error!("无法加载客户端配对状态");
                    return 0;
                }
            };
            // 检查是否已配对该服务端
            let server_pubkey_b64 = base64::engine::general_purpose::STANDARD.encode(&server_pubkey);
            if client_state.is_paired_with(&server_pubkey_b64) {
                // 客户端本地记录已配对该服务端,但服务端返回 PairRequired,
                // 说明服务端可能重置了 pairing.json。尝试用 HelloPaired 重连,
                // 若被拒(HelloAck 超时,result=0)则回退到 PairRequest 流程,
                // 让用户重新输入 PIN 完成配对。
                log::info!("已配对该服务端,尝试用 HelloPaired 重连");
                // drop 当前 client(断开 TCP)
                drop(client);
                drop(event_rx);
                let result = reconnect_paired(rt, &server_addr, &client_name, &client_state);
                if result == 1 {
                    return 1;
                }
                // result=0 表示 HelloAck 超时(服务端拒绝了 HelloPaired,白名单已失效)
                // result=3/4/5 表示地址/网络错误,直接返回让 UI 提示
                if result != 0 {
                    return result;
                }
                log::warn!(
                    "HelloPaired 被服务端拒绝(白名单已失效),回退到重新配对流程"
                );
                return reconnect_for_pairing(server_addr, client_name);
            }

            // 保存 pending 状态,等待 Kotlin 提供 PIN
            let pending = PendingPairing {
                rt,
                client,
                event_rx,
                server_pubkey,
                server_nonce,
                client_state,
                server_addr,
                client_name,
            };
            let mut guard = pending_pairing().lock().unwrap();
            if guard.is_some() {
                log::warn!("已有 pending 配对,覆盖");
            }
            *guard = Some(pending);
            2
        }
        Handshake::Failed(msg) => {
            log::warn!("{}", msg);
            0
        }
    }
}

/// 回退到 PairRequest 流程(HelloPaired 被拒后使用)
///
/// 场景:客户端本地记录已配对该服务端,但服务端 pairing.json 被重置/丢失,
/// HelloPaired 握手被服务端拒绝(返回 PairResponse{success:false} 而非 HelloAck)。
/// 此时需要重新走"连接 → Hello → PairRequired → 保存 pending → 返回 2"流程,
/// 让用户重新输入 PIN 完成配对。
///
/// 返回 2=需要配对(已保存 pending) / 0=失败 / 3/4/5=连接错误
fn reconnect_for_pairing(server_addr: String, client_name: String) -> jint {
    log::info!("回退到 PairRequest 流程:重新连接 {} as {}", server_addr, client_name);

    let rt = match Runtime::new() {
        Ok(rt) => rt,
        Err(e) => {
            log::error!("创建 runtime 失败: {}", e);
            return 0;
        }
    };

    let (event_tx, mut event_rx) = tokio::sync::mpsc::channel(64);
    let client_result = rt.block_on(async {
        Client::connect(&server_addr, &client_name, event_tx).await
    });

    let client = match client_result {
        Ok(c) => Arc::new(c),
        Err(e) => {
            log::error!("回退连接失败: {}", e);
            return map_connect_error(&e);
        }
    };

    // 等待 PairRequired(服务端启用配对时必然返回此消息)
    enum FallbackHandshake {
        PairRequired { server_pubkey: Vec<u8>, server_nonce: u64 },
        Ack, // 服务端未启用配对,直接放行(理论不会发生,兜底处理)
        Failed(&'static str),
    }

    let handshake = rt.block_on(async {
        let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(8);
        loop {
            match tokio::time::timeout_at(deadline, event_rx.recv()).await {
                Ok(Some(ClientEvent::PairRequired { server_pubkey, server_nonce })) => {
                    break FallbackHandshake::PairRequired { server_pubkey, server_nonce };
                }
                Ok(Some(ClientEvent::HelloAck { .. })) => break FallbackHandshake::Ack,
                Ok(Some(ev)) => {
                    log::debug!("回退握手阶段跳过事件: {:?}", ev);
                    continue;
                }
                Ok(None) => break FallbackHandshake::Failed("事件通道关闭"),
                Err(_) => break FallbackHandshake::Failed("等待 PairRequired 超时(8s)"),
            }
        }
    });

    match handshake {
        FallbackHandshake::PairRequired { server_pubkey, server_nonce } => {
            let client_state = match load_client_pairing_state() {
                Some(s) => s,
                None => {
                    log::error!("无法加载客户端配对状态");
                    return 0;
                }
            };
            let pending = PendingPairing {
                rt,
                client,
                event_rx,
                server_pubkey,
                server_nonce,
                client_state,
                server_addr,
                client_name,
            };
            let mut guard = pending_pairing().lock().unwrap();
            if guard.is_some() {
                log::warn!("已有 pending 配对,覆盖");
            }
            *guard = Some(pending);
            2
        }
        FallbackHandshake::Ack => {
            // 服务端未启用配对,直接放行(兜底,正常不会走到)
            log::info!("回退:服务端直接放行(未启用配对)");
            rt.spawn(async move {
                while let Some(ev) = event_rx.recv().await {
                    log::debug!("client event: {:?}", ev);
                }
            });
            let new_state = State {
                rt,
                client: client.clone(),
                encoder: Mutex::new(make_encoder(&AudioConfig::default())),
                audio_cfg: AudioConfig::default(),
                touch_seq: AtomicU16::new(0),
                audio_seq: AtomicU16::new(0),
                touch_sent: AtomicU64::new(0),
                audio_sent: AtomicU64::new(0),
            };
            let mut guard = state().lock().unwrap();
            *guard = Some(new_state);
            1
        }
        FallbackHandshake::Failed(msg) => {
            log::warn!("{}", msg);
            0
        }
    }
}

/// 用 HelloPaired 重新连接(接收 rt 所有权)
/// 返回 1=已连接 / 0=失败
fn reconnect_paired(
    rt: Runtime,
    server_addr: &str,
    client_name: &str,
    client_state: &ClientPairingState,
) -> jint {
    let nonce = meowmic_net::pairing::generate_nonce();
    let (client_pubkey_vec, signature) = match client_state.sign_paired_hello(client_name, nonce) {
        Ok((pk, sig)) => (pk, sig),
        Err(e) => {
            log::error!("签名 HelloPaired 失败: {}", e);
            return 0;
        }
    };

    let (event_tx, mut event_rx) = tokio::sync::mpsc::channel(64);
    let client_result = rt.block_on(async {
        Client::connect_paired(
            server_addr,
            client_name,
            client_pubkey_vec,
            nonce,
            signature,
            event_tx,
        ).await
    });

    let client = match client_result {
        Ok(c) => Arc::new(c),
        Err(e) => {
            log::error!("HelloPaired 连接失败: {}", e);
            return map_connect_error(&e);
        }
    };

    // 等待 HelloAck,8 秒超时,循环跳过 ClockSynced 等非目标事件
    let got_ack = rt.block_on(async {
        let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(8);
        loop {
            match tokio::time::timeout_at(deadline, event_rx.recv()).await {
                Ok(Some(ClientEvent::HelloAck { .. })) => break true,
                Ok(Some(ev)) => {
                    log::debug!("HelloPaired 握手阶段跳过事件: {:?}", ev);
                    continue;
                }
                Ok(None) => {
                    log::warn!("事件通道关闭");
                    break false;
                }
                Err(_) => {
                    log::warn!("HelloPaired 等待 HelloAck 超时(8s)");
                    break false;
                }
            }
        }
    });

    if !got_ack {
        return 0;
    }

    log::info!("HelloPaired 握手成功");
    rt.spawn(async move {
        while let Some(ev) = event_rx.recv().await {
            log::debug!("client event: {:?}", ev);
        }
    });
    let new_state = State {
        rt,
        client: client.clone(),
        encoder: Mutex::new(make_encoder(&AudioConfig::default())),
        audio_cfg: AudioConfig::default(),
        touch_seq: AtomicU16::new(0),
        audio_seq: AtomicU16::new(0),
        touch_sent: AtomicU64::new(0),
        audio_sent: AtomicU64::new(0),
    };
    let mut guard = state().lock().unwrap();
    *guard = Some(new_state);
    1
}

/// Java: int nativeCompletePairing(String pin)
/// 返回值: 0=失败(连接已坏或无 pending,需重新连接), 1=配对成功并已连接,
///         6=配对被拒绝(PIN 错误等,pending 已恢复,可换 PIN 重试),
///         7=等待响应超时/通道关闭(pending 已恢复,可重试)
///
/// 反向 PIN(Sunshine 方向)依赖 6/7 的可重试语义:
/// 手机显示自己生成的 PIN,用户在 PC 控制台输入;手机按固定间隔用同一连接
/// 重复调用本函数,直到 PC 侧设置期望 PIN 后配对通过。
#[no_mangle]
pub extern "system" fn Java_com_meowmic_client_NativeBridge_nativeCompletePairing(
    mut env: JNIEnv,
    _class: JClass,
    pin: JString,
) -> jint {
    init_logger();
    let pin: String = match env.get_string(&pin) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    // 取出 pending 配对状态;失败分支视情况放回,支持输错 PIN 后重试
    // (服务端在配对失败后保持连接与 nonce,同一连接可重发 PairRequest)
    let pending = {
        let mut guard = pending_pairing().lock().unwrap();
        match guard.take() {
            Some(p) => p,
            None => {
                log::error!("无 pending 配对状态");
                return 0;
            }
        }
    };

    log::info!("nativeCompletePairing: pin={}", pin);

    // 解构 pending,获取所有字段的所有权
    let PendingPairing {
        rt,
        client,
        mut event_rx,
        server_pubkey,
        server_nonce,
        client_state,
        server_addr,
        client_name,
    } = pending;

    // 把 pending 状态放回全局,允许 Kotlin 用新 PIN 重试
    macro_rules! restore_pending {
        () => {{
            let mut guard = pending_pairing().lock().unwrap();
            *guard = Some(PendingPairing {
                rt,
                client,
                event_rx,
                server_pubkey,
                server_nonce,
                client_state,
                server_addr,
                client_name,
            });
        }};
    }

    // 1. 用客户端私钥签名 server_nonce
    let client_pubkey = match client_state.pubkey() {
        Ok(p) => p,
        Err(e) => {
            log::error!("获取客户端公钥失败: {}", e);
            restore_pending!();
            return 0;
        }
    };
    let signature = match client_state.sign_server_nonce(server_nonce) {
        Ok(s) => s,
        Err(e) => {
            log::error!("签名 server_nonce 失败: {}", e);
            restore_pending!();
            return 0;
        }
    };

    // 2. 发送 PairRequest
    let send_result = rt.handle().block_on(
        client.send_pair_request(
            client_pubkey.to_vec(),
            client_name.clone(),
            pin,
            server_nonce,
            signature,
        ),
    );
    if let Err(e) = send_result {
        // TCP 写失败 = 连接已坏,重试无意义,不恢复 pending(Kotlin 需重新连接)
        log::error!("发送 PairRequest 失败(连接已断开): {}", e);
        return 0;
    }

    // 3. 等待 PairResponse,8 秒超时,循环跳过 ClockSynced 等非目标事件
    let response = rt.block_on(async {
        let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(8);
        loop {
            match tokio::time::timeout_at(deadline, event_rx.recv()).await {
                Ok(Some(ClientEvent::PairResponse { success, server_pubkey, error_msg })) => {
                    break Some((success, server_pubkey, error_msg));
                }
                Ok(Some(ev)) => {
                    log::debug!("配对阶段跳过事件: {:?}", ev);
                    continue;
                }
                Ok(None) => {
                    log::warn!("事件通道关闭");
                    break None;
                }
                Err(_) => {
                    log::warn!("等待 PairResponse 超时(8s)");
                    break None;
                }
            }
        }
    });

    let (success, resp_server_pubkey, error_msg) = match response {
        Some(t) => t,
        None => {
            // 超时/通道关闭:连接可能仍活着(后台 sync_loop 还在),恢复 pending 允许重试
            log::warn!("等待 PairResponse 超时/通道关闭,恢复 pending 允许重试");
            restore_pending!();
            return 7;
        }
    };

    if !success {
        // PIN 错误等服务端拒绝:连接与 nonce 保留,恢复 pending 允许换 PIN 重试
        log::warn!("配对被拒绝: {} (恢复 pending 允许重试)", error_msg);
        restore_pending!();
        return 6;
    }
    log::info!("配对成功!");

    // 4. 保存 server_pubkey 到客户端配对状态(优先用响应里的,与 PairRequired 阶段的一致)
    let server_pubkey_b64 = base64::engine::general_purpose::STANDARD.encode(&resp_server_pubkey);
    let mut new_client_state = client_state.clone();
    new_client_state.add_paired_server(server_pubkey_b64);
    save_client_pairing_state(&new_client_state);

    // 5. 断开当前连接,用 HelloPaired 重连
    // drop client(断开 TCP)
    drop(client);
    drop(event_rx);

    // 用 reconnect_paired 重连(传入 rt 所有权)
    reconnect_paired(rt, &server_addr, &client_name, &new_client_state)
}

/// Java: void nativeCancelPairing()
/// 取消 pending 配对状态
#[no_mangle]
pub extern "system" fn Java_com_meowmic_client_NativeBridge_nativeCancelPairing(
    _env: JNIEnv,
    _class: JClass,
) {
    init_logger();
    log::info!("nativeCancelPairing");
    let mut guard = pending_pairing().lock().unwrap();
    if let Some(pending) = guard.take() {
        // 断开连接(drop client 和 rt)
        let _ = pending.rt.handle().block_on(pending.client.disconnect());
    }
}

/// Java: boolean nativeIsServerPaired(String serverPubkeyB64)
/// 查询是否已配对该服务端
#[no_mangle]
pub extern "system" fn Java_com_meowmic_client_NativeBridge_nativeIsServerPaired(
    mut env: JNIEnv,
    _class: JClass,
    server_pubkey_b64: JString,
) -> jboolean {
    init_logger();
    let pubkey_b64: String = match env.get_string(&server_pubkey_b64) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let Some(client_state) = load_client_pairing_state() else {
        return JNI_FALSE;
    };
    if client_state.is_paired_with(&pubkey_b64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Java: String nativeGetClientPubkeyB64()
/// 返回本客户端的 Ed25519 公钥(base64)。
/// 用途:轮询 /serverinfo?pubkey=<此值> 查询服务端侧的配对状态(pair_status)。
/// 状态目录未初始化或读取失败时返回空字符串。
#[no_mangle]
pub extern "system" fn Java_com_meowmic_client_NativeBridge_nativeGetClientPubkeyB64(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    init_logger();
    let out = load_client_pairing_state()
        .and_then(|s| s.pubkey().ok())
        .map(|pk| B64.encode(pk))
        .unwrap_or_default();
    match env.new_string(&out) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Java: int nativeConnectPaired(String serverAddr, String clientName)
/// 直接用已配对身份连接(跳过 Hello,直接发送 HelloPaired)
/// 返回值: 0=失败, 1=已连接
#[no_mangle]
pub extern "system" fn Java_com_meowmic_client_NativeBridge_nativeConnectPaired(
    mut env: JNIEnv,
    _class: JClass,
    server_addr: JString,
    client_name: JString,
) -> jint {
    init_logger();
    let server_addr: String = match env.get_string(&server_addr) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let client_name: String = match env.get_string(&client_name) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    log::info!("nativeConnectPaired: {} as {}", server_addr, client_name);

    let Some(client_state) = load_client_pairing_state() else {
        log::error!("无法加载客户端配对状态");
        return 0;
    };

    let rt = match Runtime::new() {
        Ok(rt) => rt,
        Err(e) => {
            log::error!("创建 runtime 失败: {}", e);
            return 0;
        }
    };

    let nonce = meowmic_net::pairing::generate_nonce();
    let (client_pubkey_vec, signature) = match client_state.sign_paired_hello(&client_name, nonce) {
        Ok((pk, sig)) => (pk, sig),
        Err(e) => {
            log::error!("签名 HelloPaired 失败: {}", e);
            return 0;
        }
    };

    let (event_tx, mut event_rx) = tokio::sync::mpsc::channel(64);
    let client_result = rt.block_on(async {
        Client::connect_paired(
            &server_addr,
            &client_name,
            client_pubkey_vec,
            nonce,
            signature,
            event_tx,
        ).await
    });

    let client = match client_result {
        Ok(c) => Arc::new(c),
        Err(e) => {
            log::error!("HelloPaired 连接失败: {}", e);
            return map_connect_error(&e);
        }
    };

    // 等待 HelloAck 或 PairResponse,8 秒超时,循环跳过 ClockSynced 等非目标事件
    enum HelloPairedResult {
        Ack,
        Rejected { success: bool, error_msg: String },
        Failed(&'static str),
    }

    let result = rt.block_on(async {
        let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(8);
        loop {
            match tokio::time::timeout_at(deadline, event_rx.recv()).await {
                Ok(Some(ClientEvent::HelloAck { .. })) => break HelloPairedResult::Ack,
                Ok(Some(ClientEvent::PairResponse { success, error_msg, .. })) => {
                    break HelloPairedResult::Rejected { success, error_msg };
                }
                Ok(Some(ev)) => {
                    log::debug!("HelloPaired 阶段跳过事件: {:?}", ev);
                    continue;
                }
                Ok(None) => break HelloPairedResult::Failed("事件通道关闭"),
                Err(_) => break HelloPairedResult::Failed("HelloPaired 等待响应超时(8s)"),
            }
        }
    });

    match result {
        HelloPairedResult::Ack => {
            log::info!("HelloPaired 握手成功");
            rt.spawn(async move {
                while let Some(ev) = event_rx.recv().await {
                    log::debug!("client event: {:?}", ev);
                }
            });
            let new_state = State {
                rt,
                client: client.clone(),
                encoder: Mutex::new(make_encoder(&AudioConfig::default())),
                audio_cfg: AudioConfig::default(),
                touch_seq: AtomicU16::new(0),
                audio_seq: AtomicU16::new(0),
                touch_sent: AtomicU64::new(0),
                audio_sent: AtomicU64::new(0),
            };
            let mut guard = state().lock().unwrap();
            if guard.is_some() {
                log::warn!("已有连接,先断开旧连接");
                *guard = None;
            }
            *guard = Some(new_state);
            1
        }
        HelloPairedResult::Rejected { success, error_msg } => {
            if success {
                log::warn!("HelloPaired 返回 PairResponse success=true(异常)");
            } else {
                log::warn!("HelloPaired 被拒绝: {}", error_msg);
            }
            0
        }
        HelloPairedResult::Failed(msg) => {
            log::warn!("{}", msg);
            0
        }
    }
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
    env: JNIEnv,
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
    // 同时清理 pending pairing
    let mut pending_guard = pending_pairing().lock().unwrap();
    if let Some(pending) = pending_guard.take() {
        let _ = pending.rt.handle().block_on(pending.client.disconnect());
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

/// Java: boolean nativeSendKey(int keyCode, boolean isDown)
///
/// 发送键盘事件(走 TCP 控制通道,可靠传递)。
/// keyCode 为 Windows VK code,isDown=true 按下/false 抬起。
#[no_mangle]
pub extern "system" fn Java_com_meowmic_client_NativeBridge_nativeSendKey(
    _env: JNIEnv,
    _class: JClass,
    key_code: jint,
    is_down: jboolean,
) -> jboolean {
    let guard = state().lock().unwrap();
    let Some(s) = guard.as_ref() else {
        return JNI_FALSE;
    };
    let kc = key_code as u16;
    let down = is_down == JNI_TRUE;
    match s.rt.handle().block_on(s.client.send_key(kc, down)) {
        Ok(_) => JNI_TRUE,
        Err(e) => {
            log::warn!("send_key 失败: {}", e);
            JNI_FALSE
        }
    }
}

/// Java: int nativeStartVideo(int width, int height, int fps, int bitrate)
/// 返回:-1 = 失败,0 = H.264,1 = HEVC
#[no_mangle]
pub extern "system" fn Java_com_meowmic_client_NativeBridge_nativeStartVideo(
    _env: JNIEnv,
    _class: JClass,
    width: jint,
    height: jint,
    fps: jint,
    bitrate: jint,
) -> jint {
    let guard = state().lock().unwrap();
    let Some(s) = guard.as_ref() else {
        return -1;
    };

    // 重置 codec 标记,发送 StartVideo,等待 VideoStarted ACK(最多 500ms)
    s.client.reset_video_started_codec();
    match s.rt.handle().block_on(
        s.client.start_video(width as u32, height as u32, fps as u32, bitrate as u32),
    ) {
        Ok(()) => {
            // 等待 VideoStarted ACK 携带的 codec(轮询,最多 500ms)
            let codec = {
                let mut got: i32 = -1;
                for _ in 0..50 {
                    let c = s.client.video_started_codec();
                    if c != 255 {
                        got = c as i32;
                        break;
                    }
                    s.rt.handle().block_on(tokio::time::sleep(std::time::Duration::from_millis(10)));
                }
                got
            };
            // 启动视频接收循环
            let rx = VideoReceiver::start(s.client.clone());
            let mut video_guard = video_rx().lock().unwrap();
            if let Some(old) = video_guard.as_mut() {
                old.stop();
            }
            *video_guard = Some(rx);
            if codec < 0 {
                log::warn!("VideoStarted ACK 超时,默认 H.264");
                0 // 超时默认 H.264
            } else {
                codec
            }
        }
        Err(e) => {
            log::warn!("start_video 失败: {}", e);
            -1
        }
    }
}

/// Java: byte[] nativePollVideoFrame()
/// 返回完整 NALU 字节数组,或 null 表示无完整帧
#[no_mangle]
pub extern "system" fn Java_com_meowmic_client_NativeBridge_nativePollVideoFrame(
    env: JNIEnv,
    _class: JClass,
) -> jbyteArray {
    let guard = video_rx().lock().unwrap();
    let Some(rx) = guard.as_ref() else {
        return std::ptr::null_mut();
    };
    match rx.poll_frame() {
        Some(nalu) => {
            match env.new_byte_array(nalu.len() as i32) {
                Ok(arr) => {
                    // jbyte = i8,需要 transmute
                    let nalu_signed: &[i8] = unsafe {
                        std::slice::from_raw_parts(nalu.as_ptr() as *const i8, nalu.len())
                    };
                    let _ = env.set_byte_array_region(&arr, 0, nalu_signed);
                    arr.into_raw()
                }
                Err(_) => std::ptr::null_mut(),
            }
        }
        None => std::ptr::null_mut(),
    }
}

/// Java: void nativeStopVideo()
#[no_mangle]
pub extern "system" fn Java_com_meowmic_client_NativeBridge_nativeStopVideo(
    _env: JNIEnv,
    _class: JClass,
) {
    // 发送 StopVideo 控制消息
    let guard = state().lock().unwrap();
    if let Some(s) = guard.as_ref() {
        let _ = s.rt.handle().block_on(s.client.stop_video());
    }
    drop(guard);

    // 停止接收循环
    let mut video_guard = video_rx().lock().unwrap();
    if let Some(rx) = video_guard.as_mut() {
        rx.stop();
    }
    *video_guard = None;
}

/// Java: String nativePollVideoStats()
///
/// 返回 JSON: `{"received":N,"lost":N,"recovered":N}`,并清零内部计数器。
/// Kotlin 每秒调用一次,通过 `Client.send_video_stats` 上报给服务端做自适应码率。
/// 视频未启动时返回全 0。
#[no_mangle]
pub extern "system" fn Java_com_meowmic_client_NativeBridge_nativePollVideoStats(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let stats = {
        let guard = video_rx().lock().unwrap();
        guard.as_ref().map(|rx| rx.poll_stats()).unwrap_or_default()
    };
    let json = format!(
        r#"{{"received":{},"lost":{},"recovered":{}}}"#,
        stats.received_frames, stats.lost_frames, stats.recovered_frames
    );
    match env.new_string(&json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Java: boolean nativeSendVideoStats(received, lost, recovered, rttMs)
///
/// 上报视频统计给服务端(用于自适应码率)。视频未启动/未连接时返回 false。
#[no_mangle]
pub extern "system" fn Java_com_meowmic_client_NativeBridge_nativeSendVideoStats(
    _env: JNIEnv,
    _class: JClass,
    received: jint,
    lost: jint,
    recovered: jint,
    rtt_ms: jint,
) -> jboolean {
    let guard = state().lock().unwrap();
    if let Some(s) = guard.as_ref() {
        let res = s.rt.handle().block_on(
            s.client
                .send_video_stats(received as u32, lost as u32, recovered as u32, rtt_ms as u32),
        );
        match res {
            Ok(_) => JNI_TRUE,
            Err(e) => {
                log::warn!("send_video_stats 失败: {}", e);
                JNI_FALSE
            }
        }
    } else {
        JNI_FALSE
    }
}

/// Java: String nativeGetStats()
#[no_mangle]
pub extern "system" fn Java_com_meowmic_client_NativeBridge_nativeGetStats(
    env: JNIEnv,
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
