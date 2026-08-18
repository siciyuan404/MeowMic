//! PC 系统声音实时推流(手机充当电脑喇叭)
//!
//! 手机发送 `StartAudioStream { channel, client_audio_port }` 后,
//! 本模块用 WASAPI loopback 抓取系统混音(48kHz 16bit 立体声),
//! 按客户端声道(0=左 / 1=右 / 2=立体声混合)提取单声道,
//! Opus 编码后 UDP 推送到手机绑定的 audio 端口。
//!
//! 实现说明:
//! - wasapi 0.23 的 `PollingShared + autoconvert` 在 Render 设备上以
//!   Capture 方向初始化时会自动置 `AUDCLNT_STREAMFLAGS_LOOPBACK`
//! - 采集线程独立于 tokio runtime(std thread),避免 WASAPI 阻塞事件循环
//! - 多客户端共享同一个回环采集,按 client_id 分开发送

use std::collections::HashMap;
use std::net::{SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc};
use std::thread;

use meowmic_audio::{AudioConfig, AudioEncoder, make_encoder};
use meowmic_protocol::{AudioPacket, CHANNEL_LEFT, CHANNEL_RIGHT, monotonic_ns};

/// 每帧采样数(48kHz × 20ms)
const FRAME_SAMPLES: usize = 960;
/// WASAPI 缓冲区时长(100ns 单位):20ms
const BUFFER_DURATION_HNS: i64 = 200_000;

/// 单个推流目标
struct StreamTarget {
    addr: SocketAddr,
    channel: u8,
    encoder: Box<dyn AudioEncoder>,
    seq: u16,
}

enum StreamerCmd {
    Add {
        client_id: u32,
        addr: SocketAddr,
        channel: u8,
    },
    Remove { client_id: u32 },
}

/// WASAPI 回环采集会话(线程内持有,退出时停流释放设备)
struct LoopbackCapture {
    client: wasapi::AudioClient,
    capture: wasapi::AudioCaptureClient,
    bytes_per_frame: usize,
}

impl Drop for LoopbackCapture {
    fn drop(&mut self) {
        let _ = self.client.stop_stream();
    }
}

/// PC→手机 音频推流器
pub struct AudioStreamer {
    tx: mpsc::Sender<StreamerCmd>,
    handle: Option<thread::JoinHandle<()>>,
    stop: Arc<AtomicBool>,
}

impl AudioStreamer {
    pub fn new() -> Self {
        let (tx, rx) = mpsc::channel();
        let stop = Arc::new(AtomicBool::new(false));
        let mut s = Self {
            tx,
            handle: None,
            stop,
        };
        s.spawn(rx);
        s
    }

    fn spawn(&mut self, rx: mpsc::Receiver<StreamerCmd>) {
        let stop = self.stop.clone();
        self.handle = Some(
            thread::Builder::new()
                .name("audio-streamer".into())
                .spawn(move || run_streamer(rx, stop))
                .expect("spawn audio streamer thread"),
        );
    }

    /// 添加/更新客户端推流目标
    pub fn add(&mut self, client_id: u32, addr: SocketAddr, channel: u8) {
        // 线程可能因设备不可用而提前退出,此时重建线程与命令通道
        let need_respawn = match self.handle.as_ref() {
            Some(h) => h.is_finished(),
            None => true,
        };
        if need_respawn {
            let (tx, rx) = mpsc::channel();
            self.tx = tx;
            self.spawn(rx);
        }
        let _ = self.tx.send(StreamerCmd::Add {
            client_id,
            addr,
            channel,
        });
    }

    /// 移除客户端推流目标(客户端断开/停止推流时调用)
    pub fn remove(&mut self, client_id: u32) {
        let _ = self.tx.send(StreamerCmd::Remove { client_id });
    }
}

impl Drop for AudioStreamer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }
}

fn run_streamer(rx: mpsc::Receiver<StreamerCmd>, stop: Arc<AtomicBool>) {
    let capture = match init_loopback() {
        Ok(c) => c,
        Err(e) => {
            tracing::warn!("WASAPI 系统声音回环采集初始化失败: {}", e);
            return;
        }
    };
    tracing::info!("WASAPI 系统声音回环采集已启动(手机可实时收听 PC 声音)");

    let sock = match UdpSocket::bind("0.0.0.0:0") {
        Ok(s) => s,
        Err(e) => {
            tracing::warn!("音频推流 UDP socket 绑定失败: {}", e);
            return;
        }
    };

    let mut targets: HashMap<u32, StreamTarget> = HashMap::new();
    // 立体声交错样本累积(满 960 帧 = 1920 个 i16 后切帧发送)
    let mut acc: Vec<i16> = Vec::with_capacity(FRAME_SAMPLES * 2);
    let mut buf: Vec<u8> = Vec::with_capacity(FRAME_SAMPLES * 2 * 4);
    let mut opus_buf = vec![0u8; 8192];
    let cfg = AudioConfig::default();

    while !stop.load(Ordering::Relaxed) {
        // 处理命令
        while let Ok(cmd) = rx.try_recv() {
            match cmd {
                StreamerCmd::Add {
                    client_id,
                    addr,
                    channel,
                } => {
                    if !targets.contains_key(&client_id) {
                        targets.insert(
                            client_id,
                            StreamTarget {
                                addr,
                                channel,
                                encoder: make_encoder(&cfg),
                                seq: 0,
                            },
                        );
                        tracing::info!(
                            "音频推流: 客户端 {} 加入 声道={} → {}",
                            client_id,
                            channel,
                            addr
                        );
                    }
                }
                StreamerCmd::Remove { client_id } => {
                    if targets.remove(&client_id).is_some() {
                        tracing::info!("音频推流: 客户端 {} 移除", client_id);
                    }
                }
            }
        }

        if targets.is_empty() {
            thread::sleep(std::time::Duration::from_millis(50));
            continue;
        }

        // 轮询回环采集(共享模式)
        match capture.capture.get_next_packet_size() {
            Ok(Some(nframes)) if nframes > 0 => {
                let bytes = nframes as usize * capture.bytes_per_frame;
                buf.resize(bytes, 0);
                match capture.capture.read_from_device(&mut buf) {
                    Ok((frames_read, _)) if frames_read > 0 => {
                        let bytes_read = frames_read as usize * capture.bytes_per_frame;
                        for chunk in buf[..bytes_read].chunks_exact(2) {
                            acc.push(i16::from_le_bytes([chunk[0], chunk[1]]));
                        }
                        // 按 960 帧(1920 样本)切帧发送
                        while acc.len() >= FRAME_SAMPLES * 2 {
                            let frame: Vec<i16> = acc.drain(..FRAME_SAMPLES * 2).collect();
                            for target in targets.values_mut() {
                                let mono = extract_mono(&frame, target.channel);
                                match target.encoder.encode(&mono, &mut opus_buf) {
                                    Ok(n) => {
                                        let pkt = AudioPacket::new(
                                            target.seq,
                                            monotonic_ns() as u32,
                                            &opus_buf[..n],
                                        );
                                        target.seq = target.seq.wrapping_add(1);
                                        let mut out = Vec::with_capacity(n + 8);
                                        pkt.encode(&mut out);
                                        // 发送失败(网络瞬断)丢弃,接收端 jitter 用 PLC 填补
                                        let _ = sock.send_to(&out, target.addr);
                                    }
                                    Err(e) => {
                                        tracing::debug!("Opus 编码失败: {}", e);
                                    }
                                }
                            }
                        }
                    }
                    _ => {}
                }
            }
            Ok(_) => {}
            Err(e) => {
                tracing::warn!("WASAPI 采集错误: {}", e);
                thread::sleep(std::time::Duration::from_millis(50));
            }
        }
    }
}

/// 初始化 WASAPI loopback 采集(48kHz 16bit 立体声)
fn init_loopback() -> Result<LoopbackCapture, wasapi::WasapiError> {
    // COM 初始化(MTA 适合无 UI 的后台线程)
    // CoInitializeEx 返回 S_FALSE 表示当前线程已初始化,不算错误
    let _ = wasapi::initialize_mta();

    let enumerator = wasapi::DeviceEnumerator::new()?;
    let device = enumerator.get_default_device(&wasapi::Direction::Render)?;
    let mut client = device.get_iaudioclient()?;
    let wf = wasapi::WaveFormat::new(16, 16, &wasapi::SampleType::Int, 48000, 2, None);
    let bytes_per_frame = wf.get_blockalign() as usize;
    // Render 设备 + Capture 方向 + Shared → 自动置 LOOPBACK 标志
    client.initialize_client(
        &wf,
        &wasapi::Direction::Capture,
        &wasapi::StreamMode::PollingShared {
            autoconvert: true,
            buffer_duration_hns: BUFFER_DURATION_HNS,
        },
    )?;
    let capture = client.get_audiocaptureclient()?;
    client.start_stream()?;
    Ok(LoopbackCapture {
        client,
        capture,
        bytes_per_frame,
    })
}

/// 从立体声交错帧中提取目标单声道(960 采样)
fn extract_mono(stereo: &[i16], channel: u8) -> Vec<i16> {
    let mut mono = Vec::with_capacity(FRAME_SAMPLES);
    match channel {
        CHANNEL_LEFT => {
            for i in 0..FRAME_SAMPLES {
                mono.push(stereo[i * 2]);
            }
        }
        CHANNEL_RIGHT => {
            for i in 0..FRAME_SAMPLES {
                mono.push(stereo[i * 2 + 1]);
            }
        }
        _ => {
            // 立体声混合 (L+R)/2,防溢出先提升到 i32
            for i in 0..FRAME_SAMPLES {
                let l = stereo[i * 2] as i32;
                let r = stereo[i * 2 + 1] as i32;
                mono.push(((l + r) / 2) as i16);
            }
        }
    }
    mono
}