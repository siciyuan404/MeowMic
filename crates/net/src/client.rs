//! 客户端网络层
//!
//! 连接服务端:
//! - TCP Control:握手 + 接收 SyncResp
//! - UDP Touch:发送触摸包
//! - UDP Audio:发送 Opus 音频包
//!
//! P0 阶段:基础 tokio 实现,时钟同步循环内置

use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicU16, Ordering};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpStream, UdpSocket, tcp::OwnedWriteHalf};
use tokio::sync::{Mutex, mpsc};

use meowmic_protocol::{
    AudioPacket, ControlMessage, TouchPacket, encode_control, monotonic_ns,
    HEADER_LEN, TOUCH_PAYLOAD_LEN,
};

use crate::{NetError, PeerAddr};
use crate::sync::ClockSynchronizer;

/// TCP 连接超时(秒)。不设置时依赖 OS 默认(Linux SYN 重传可达 ~2 分钟),
/// 会让上层(Java 侧 thread.join 超时)只能给出模糊的"连接超时"。
/// 缩短为 3s,使"主机不可达"能快速、准确地上报(映射为 io TimedOut)。
pub const CONNECT_TIMEOUT_SECS: u64 = 3;

/// 客户端事件(从服务端收到)
#[derive(Debug)]
pub enum ClientEvent {
    HelloAck {
        client_id: u32,
        audio_sample_rate: u32,
        audio_channels: u8,
        audio_frame_ms: u16,
    },
    /// 服务端要求客户端先完成配对(收到 PairRequired)
    PairRequired {
        server_pubkey: Vec<u8>,
        server_nonce: u64,
    },
    /// 配对响应(收到 PairResponse)
    PairResponse {
        success: bool,
        server_pubkey: Vec<u8>,
        error_msg: String,
    },
    ClockSynced {
        offset_ms: f64,
        rtt_ms: f64,
    },
    /// 服务端确认视频推流已开始
    VideoStarted {
        width: u32,
        height: u32,
        fps: u32,
        codec: u8,
    },
    Disconnected,
    Error(NetError),
}

pub struct Client {
    sync: ClockSynchronizer,
    /// TCP 控制流写入端(线程安全,read 端由后台 task 独占)
    ///
    /// 使用 into_split 分离读写,避免 run_control_recv 持有 Mutex 在 read 上挂起
    /// 导致 send_control 永久死锁。
    control_write: Arc<Mutex<OwnedWriteHalf>>,
    touch_sock: Arc<UdpSocket>,
    /// 同步触摸发送 socket(与 touch_sock 共享同一 fd,用于绕过 block_on)
    touch_sock_sync: Arc<std::net::UdpSocket>,
    audio_sock: Arc<UdpSocket>,
    /// 视频 UDP socket(接收服务端推送的 H.264 分片)
    video_sock: Arc<UdpSocket>,
    peer: PeerAddr,
    /// 触摸包序号(原子操作,避免 async Mutex 开销)
    touch_seq: Arc<AtomicU16>,
    /// 音频包序号
    audio_seq: Arc<Mutex<u16>>,
    /// 服务端 VideoStarted ACK 携带的 codec(255=未收到, 0=H.264, 1=HEVC)
    /// 由 run_control_recv 写入,nativeStartVideo 读取
    video_started_codec: Arc<std::sync::atomic::AtomicU8>,
    /// TCP 控制连接是否存活(run_control_recv 退出时自动置 false)
    is_connected: Arc<std::sync::atomic::AtomicBool>,
}

impl Client {
    /// 连接到服务端(发送普通 Hello)
    ///
    /// - server_control_addr: 服务端 TCP 控制地址(如 "192.168.1.100:28900")
    /// - 自动推导 touch/audio 端口(control+1, control+2)
    pub async fn connect(
        server_control_addr: &str,
        client_name: &str,
        event_tx: mpsc::Sender<ClientEvent>,
    ) -> Result<Self, NetError> {
        let msg = ControlMessage::Hello {
            client_name: client_name.into(),
            protocol_version: 1,
            audio_sample_rate: 48000,
            audio_channels: 1,
            audio_frame_ms: 20,
        };
        Self::connect_with_first_msg(server_control_addr, msg, event_tx).await
    }

    /// 以已配对身份连接服务端(发送 HelloPaired)
    ///
    /// - `client_pubkey`: 客户端 Ed25519 公钥(32 字节)
    /// - `nonce`: 随机 nonce(每次连接不同)
    /// - `signature`: 客户端私钥对 SHA256(client_name || client_pubkey || nonce_le) 的签名(64 字节)
    pub async fn connect_paired(
        server_control_addr: &str,
        client_name: &str,
        client_pubkey: Vec<u8>,
        nonce: u64,
        signature: Vec<u8>,
        event_tx: mpsc::Sender<ClientEvent>,
    ) -> Result<Self, NetError> {
        let msg = ControlMessage::HelloPaired {
            client_name: client_name.into(),
            protocol_version: 1,
            client_pubkey,
            nonce,
            signature,
            audio_sample_rate: 48000,
            audio_channels: 1,
            audio_frame_ms: 20,
        };
        Self::connect_with_first_msg(server_control_addr, msg, event_tx).await
    }

    /// 内部:连接服务端并发送第一条控制消息,启动后台任务
    async fn connect_with_first_msg(
        server_control_addr: &str,
        first_msg: ControlMessage,
        event_tx: mpsc::Sender<ClientEvent>,
    ) -> Result<Self, NetError> {
        let control_addr: SocketAddr = server_control_addr
            .parse()
            .map_err(|_| NetError::Handshake(format!("无效地址: {}", server_control_addr)))?;

        let peer = PeerAddr {
            control: control_addr,
            touch: SocketAddr::new(control_addr.ip(), control_addr.port() + 1),
            audio: SocketAddr::new(control_addr.ip(), control_addr.port() + 2),
            // base+3..base+5 留给 HTTP,video 在 base+6
            video: SocketAddr::new(control_addr.ip(), control_addr.port() + 6),
        };

        let stream = match tokio::time::timeout(
            std::time::Duration::from_secs(CONNECT_TIMEOUT_SECS),
            TcpStream::connect(control_addr),
        )
        .await
        {
            Ok(Ok(s)) => s,
            Ok(Err(e)) => return Err(NetError::Io(e)),
            Err(_) => {
                return Err(NetError::Io(std::io::Error::new(
                    std::io::ErrorKind::TimedOut,
                    format!("连接 {} 超时({}s)", control_addr, CONNECT_TIMEOUT_SECS),
                )))
            }
        };
        stream.set_nodelay(true)?;

        // 分离读写半部:read_half 独占给后台接收 task,write_half 用 Mutex 保护
        // 避免之前的死锁:run_control_recv 持有 Mutex 在 read 上挂起,send_control 永远拿不到锁
        let (read_half, write_half) = stream.into_split();

        // 本地 UDP socket:绑定任意端口
        // touch_sock 使用 std 创建后 try_clone,一份转 tokio(异步接收),一份留作同步发送
        let touch_sock_std = std::net::UdpSocket::bind("0.0.0.0:0")?;
        let touch_sock_sync = Arc::new(touch_sock_std.try_clone()?);
        let touch_sock = UdpSocket::from_std(touch_sock_std)?;
        let audio_sock = UdpSocket::bind("0.0.0.0:0").await?;
        let video_sock = UdpSocket::bind("0.0.0.0:0").await?;

        let sync = ClockSynchronizer::new();
        let control_write = Arc::new(Mutex::new(write_half));
        let video_started_codec = Arc::new(std::sync::atomic::AtomicU8::new(255));
        let is_connected = Arc::new(std::sync::atomic::AtomicBool::new(true));
        let client = Self {
            sync: sync.clone(),
            control_write: control_write.clone(),
            touch_sock: Arc::new(touch_sock),
            touch_sock_sync,
            audio_sock: Arc::new(audio_sock),
            video_sock: Arc::new(video_sock),
            peer,
            touch_seq: Arc::new(AtomicU16::new(0)),
            audio_seq: Arc::new(Mutex::new(0)),
            video_started_codec: video_started_codec.clone(),
            is_connected: is_connected.clone(),
        };

        // 发送第一条消息(Hello 或 HelloPaired)
        client.send_control(first_msg).await?;

        // 启动控制消息接收循环(read_half 独占,无需 Mutex)
        // run_control_recv 退出(TCP 断开/错误)后自动标记连接断开
        let sync_clone = sync.clone();
        let event_tx_clone = event_tx.clone();
        let is_conn = is_connected.clone();
        tokio::spawn(async move {
            run_control_recv(read_half, sync_clone, event_tx_clone, video_started_codec).await;
            is_conn.store(false, std::sync::atomic::Ordering::Relaxed);
        });

        // 启动时钟同步循环
        let stream_for_sync = control_write.clone();
        let sync_for_loop = sync.clone();
        tokio::spawn(async move {
            run_sync_loop(stream_for_sync, sync_for_loop).await;
        });

        Ok(client)
    }

    pub fn clock_sync(&self) -> ClockSynchronizer {
        self.sync.clone()
    }

    /// 发送触摸事件(触控板相对移动)
    pub async fn send_touch(
        &self,
        event: meowmic_protocol::TouchEventType,
        dx: f32,
        dy: f32,
    ) -> Result<(), NetError> {
        self.send_touch_with_button(event, 0, dx, dy).await
    }

    /// 发送带按钮掩码的触摸事件
    ///
    /// `button_mask` 位定义: bit0=左键 bit1=右键 bit2=中键
    /// 用于 `TouchEventType::Button` 事件表达鼠标按键按下/抬起。
    pub async fn send_touch_with_button(
        &self,
        event: meowmic_protocol::TouchEventType,
        button_mask: u8,
        dx: f32,
        dy: f32,
    ) -> Result<(), NetError> {
        let seq = self.touch_seq.fetch_add(1, Ordering::Relaxed);
        let ts = monotonic_ns() as u32;
        let pkt = TouchPacket::new_with_button(seq, ts, event, button_mask, dx, dy);
        let mut buf = Vec::with_capacity(HEADER_LEN + TOUCH_PAYLOAD_LEN);
        pkt.encode(&mut buf);
        self.touch_sock.send_to(&buf, self.peer.touch).await?;
        Ok(())
    }

    /// 同步发送触摸事件(绕过 tokio block_on,用于高频触摸 JNI 调用)
    ///
    /// 直接使用 std::net::UdpSocket::send_to,无 async runtime 开销。
    /// socket 为非阻塞模式(与 tokio 共享 fd);send buffer 满时丢弃包(触摸为实时数据)。
    pub fn send_touch_sync(
        &self,
        event: meowmic_protocol::TouchEventType,
        button_mask: u8,
        dx: f32,
        dy: f32,
    ) -> Result<(), NetError> {
        let seq = self.touch_seq.fetch_add(1, Ordering::Relaxed);
        let ts = monotonic_ns() as u32;
        let pkt = TouchPacket::new_with_button(seq, ts, event, button_mask, dx, dy);
        let mut buf = Vec::with_capacity(HEADER_LEN + TOUCH_PAYLOAD_LEN);
        pkt.encode(&mut buf);
        match self.touch_sock_sync.send_to(&buf, self.peer.touch) {
            Ok(_) => Ok(()),
            Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                // send buffer 满,丢弃触摸包(实时数据可丢)
                Ok(())
            }
            Err(e) => Err(NetError::Io(e)),
        }
    }

    /// 发送一帧 Opus 音频
    pub async fn send_audio(&self, opus: &[u8]) -> Result<(), NetError> {
        let mut seq_guard = self.audio_seq.lock().await;
        let seq = *seq_guard;
        *seq_guard = seq.wrapping_add(1);
        drop(seq_guard);

        let ts = monotonic_ns() as u32;
        let pkt = AudioPacket::new(seq, ts, opus);
        let mut buf = Vec::with_capacity(opus.len() + 8);
        pkt.encode(&mut buf);
        self.audio_sock.send_to(&buf, self.peer.audio).await?;
        Ok(())
    }

    /// 发送控制消息
    pub async fn send_control(&self, msg: ControlMessage) -> Result<(), NetError> {
        let mut stream = self.control_write.lock().await;
        let mut frame = Vec::with_capacity(256);
        encode_control(&msg, &mut frame).map_err(NetError::Protocol)?;
        stream.write_all(&frame).await?;
        Ok(())
    }

    /// 优雅断开
    pub async fn disconnect(&self) -> Result<(), NetError> {
        let _ = self.send_control(ControlMessage::Bye).await;
        Ok(())
    }

    /// 通知服务端切换外放静音状态
    pub async fn set_mute_speaker(&self, muted: bool) -> Result<(), NetError> {
        self.send_control(ControlMessage::SetMuteSpeaker { muted }).await
    }

    /// 发送配对请求(首次连接收到 PairRequired 后调用)
    ///
    /// - `client_pubkey`: 客户端 Ed25519 公钥(32 字节)
    /// - `client_name`: 客户端设备名
    /// - `pin`: 服务端显示的 6 位 PIN
    /// - `server_nonce`: 服务端在 PairRequired 中返回的 nonce
    /// - `signature`: 客户端私钥对 server_nonce.to_le_bytes() 的 Ed25519 签名(64 字节)
    pub async fn send_pair_request(
        &self,
        client_pubkey: Vec<u8>,
        client_name: String,
        pin: String,
        server_nonce: u64,
        signature: Vec<u8>,
    ) -> Result<(), NetError> {
        self.send_control(ControlMessage::PairRequest {
            client_pubkey,
            client_name,
            pin,
            server_nonce,
            signature,
        })
        .await
    }

    /// 发送键盘事件(走 TCP 控制通道,可靠传递)
    ///
    /// - key_code: Windows VK code(如 0x11=Ctrl, 0x43=C)
    /// - is_down: true=按下, false=抬起
    pub async fn send_key(&self, key_code: u16, is_down: bool) -> Result<(), NetError> {
        self.send_control(ControlMessage::KeyEvent { key_code, is_down }).await
    }

    /// 请求服务端开始视频推流(UDP push 模式)
    ///
    /// 服务端收到后在 video 端口 (base+6) 持续推送 H.264 分片。
    /// 自动从本地 `video_sock` 获取绑定端口,一并告诉服务端推送目标。
    /// 返回前需调用 `video_sock()` 获取 socket 以接收分片。
    pub async fn start_video(
        &self,
        width: u32,
        height: u32,
        fps: u32,
        bitrate: u32,
    ) -> Result<(), NetError> {
        // 取本地 video UDP 绑定端口,告知服务端 push 目标
        let client_video_port = self
            .video_sock
            .local_addr()
            .map(|a| a.port())
            .unwrap_or(0);
        self.send_control(ControlMessage::StartVideo {
            width,
            height,
            fps,
            bitrate,
            client_video_port,
        })
        .await
    }

    /// 读取服务端 VideoStarted ACK 携带的 codec
    /// - 255 = 尚未收到 ACK
    /// - 0 = H.264
    /// - 1 = HEVC
    pub fn video_started_codec(&self) -> u8 {
        self.video_started_codec.load(std::sync::atomic::Ordering::Relaxed)
    }

    /// 重置 video_started_codec 为 255(未收到),用于重新开始推流前
    pub fn reset_video_started_codec(&self) {
        self.video_started_codec.store(255, std::sync::atomic::Ordering::Relaxed);
    }

    /// 检查 TCP 控制连接是否存活(run_control_recv 退出后自动变为 false)
    pub fn is_connected(&self) -> bool {
        self.is_connected.load(std::sync::atomic::Ordering::Relaxed)
    }

    /// 请求服务端停止视频推流
    pub async fn stop_video(&self) -> Result<(), NetError> {
        self.send_control(ControlMessage::StopVideo).await
    }

    /// 上报视频接收统计(用于服务端自适应码率)
    ///
    /// 建议每 1-2 秒上报一次,字段含义见 `ControlMessage::VideoStats`
    pub async fn send_video_stats(
        &self,
        received_frames: u32,
        lost_frames: u32,
        recovered_frames: u32,
        rtt_ms: u32,
    ) -> Result<(), NetError> {
        self.send_control(ControlMessage::VideoStats {
            received_frames,
            lost_frames,
            recovered_frames,
            rtt_ms,
        })
        .await
    }

    /// 获取视频 UDP socket 引用(用于接收服务端推送的 H.264 分片)
    pub fn video_sock(&self) -> &Arc<UdpSocket> {
        &self.video_sock
    }

    /// 获取服务端 video 地址
    pub fn video_peer(&self) -> SocketAddr {
        self.peer.video
    }
}

async fn run_control_recv(
    mut read_half: tokio::net::tcp::OwnedReadHalf,
    sync: ClockSynchronizer,
    event_tx: mpsc::Sender<ClientEvent>,
    video_started_codec: Arc<std::sync::atomic::AtomicU8>,
) {
    let mut read_buf = Vec::with_capacity(4096);
    loop {
        let mut tmp = [0u8; 4096];
        // read_half 独占,无需 Mutex,不会阻塞 send_control 的写入
        match read_half.read(&mut tmp).await {
            Ok(n) => {
                if n == 0 {
                    let _ = event_tx.send(ClientEvent::Disconnected).await;
                    return;
                }
                read_buf.extend_from_slice(&tmp[..n]);
            }
            Err(e) => {
                let _ = event_tx
                    .send(ClientEvent::Error(NetError::Io(e)))
                    .await;
                return;
            }
        }

        loop {
            if read_buf.len() < 4 {
                break;
            }
            let len = u32::from_le_bytes(read_buf[..4].try_into().unwrap()) as usize;
            if read_buf.len() < 4 + len {
                break;
            }
            let msg_bytes = read_buf[..4 + len].to_vec();
            read_buf.drain(..4 + len);

            if let Ok((msg, _)) = decode_control_safe(&msg_bytes) {
                match msg {
                    ControlMessage::HelloAck {
                        client_id,
                        audio_sample_rate,
                        audio_channels,
                        audio_frame_ms,
                        ..
                    } => {
                        let _ = event_tx
                            .send(ClientEvent::HelloAck {
                                client_id,
                                audio_sample_rate,
                                audio_channels,
                                audio_frame_ms,
                            })
                            .await;
                    }
                    ControlMessage::PairRequired {
                        server_pubkey,
                        server_nonce,
                    } => {
                        let _ = event_tx
                            .send(ClientEvent::PairRequired {
                                server_pubkey,
                                server_nonce,
                            })
                            .await;
                    }
                    ControlMessage::PairResponse {
                        success,
                        server_pubkey,
                        error_msg,
                    } => {
                        let _ = event_tx
                            .send(ClientEvent::PairResponse {
                                success,
                                server_pubkey,
                                error_msg,
                            })
                            .await;
                    }
                    ControlMessage::SyncResp {
                        client_ts_ns,
                        server_recv_ts_ns,
                        server_send_ts_ns,
                    } => {
                        sync.handle_sync_resp(
                            client_ts_ns,
                            server_recv_ts_ns,
                            server_send_ts_ns,
                        )
                        .await;
                        let state = sync.state().await;
                        let _ = event_tx
                            .send(ClientEvent::ClockSynced {
                                offset_ms: state.offset.offset_ns as f64 / 1e6,
                                rtt_ms: state.offset.rtt_ns as f64 / 1e6,
                            })
                            .await;
                    }
                    ControlMessage::Pong => {
                        // 心跳响应,忽略
                    }
                    ControlMessage::VideoStarted {
                        width,
                        height,
                        fps,
                        codec,
                    } => {
                        // 同时存入 atomic,供 nativeStartVideo 同步读取
                        video_started_codec.store(codec, std::sync::atomic::Ordering::Relaxed);
                        let _ = event_tx
                            .send(ClientEvent::VideoStarted {
                                width,
                                height,
                                fps,
                                codec,
                            })
                            .await;
                    }
                    _ => {}
                }
            }
        }
    }
}

fn decode_control_safe(
    buf: &[u8],
) -> Result<(ControlMessage, usize), meowmic_protocol::ProtocolError> {
    meowmic_protocol::decode_control(buf)
}

async fn run_sync_loop(write_half: Arc<Mutex<OwnedWriteHalf>>, _sync: ClockSynchronizer) {
    let mut ticker = tokio::time::interval(Duration::from_secs(2));
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut frame = Vec::with_capacity(64);
    loop {
        ticker.tick().await;
        let ts = monotonic_ns();
        let msg = ControlMessage::SyncReq { client_ts_ns: ts };
        if let Err(e) = encode_control(&msg, &mut frame) {
            tracing::warn!("SyncReq 编码失败: {}", e);
            continue;
        }
        let mut s = write_half.lock().await;
        if s.write_all(&frame).await.is_err() {
            return;
        }
        frame.clear();
    }
}
