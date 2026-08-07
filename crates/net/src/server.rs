//! 服务端网络层
//!
//! 监听:
//! - TCP Control 端口:握手、时钟同步、统计、配对
//! - UDP Touch 端口:接收触摸包
//! - UDP Audio 端口:接收 Opus 音频包

use std::net::SocketAddr;
use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use std::collections::HashSet;
use tokio::sync::{mpsc, RwLock};

/// 服务端首选视频 codec(0=H.264, 1=HEVC)
/// 由 pc/server main.rs 在启动时根据硬件探测结果设置,
/// handle_control_msg 在收到 StartVideo 时读取此值,填入 VideoStarted.codec
/// 和 ServerEvent::StartVideo.codec,告知客户端实际编码类型。
static PREFERRED_CODEC: AtomicU8 = AtomicU8::new(0);

/// 设置首选视频 codec(由服务端主程序在启动时调用)
pub fn set_preferred_codec(codec: u8) {
    PREFERRED_CODEC.store(codec, Ordering::Relaxed);
}

/// 读取首选视频 codec
pub fn preferred_codec() -> u8 {
    PREFERRED_CODEC.load(Ordering::Relaxed)
}

use meowmic_protocol::{
    ControlMessage, decode_control, encode_control, monotonic_ns,
};

use crate::{NetError, PortLayout};
use crate::pairing::{PairingManager, generate_nonce};
use crate::sync::ClockSynchronizer;

/// 服务端收到的事件
#[derive(Debug)]
pub enum ServerEvent {
    /// 客户端已连接(握手完成)
    ClientConnected {
        client_id: u32,
        peer: SocketAddr,
        audio_sample_rate: u32,
        audio_channels: u8,
        audio_frame_ms: u16,
        /// 客户端公钥 base64(配对 HelloPaired 才有;无配对兼容路径为 None)
        client_pubkey_b64: Option<String>,
    },
    /// 收到触摸包
    Touch {
        client_id: u32,
        seq: u16,
        ts_ns: u32,
        dx: f32,
        dy: f32,
        event_type: u8,
        button_mask: u8,
        pressure: u8,
    },
    /// 收到音频包
    Audio {
        client_id: u32,
        seq: u16,
        ts_ns: u32,
        opus: Vec<u8>,
    },
    /// 客户端断开
    ClientDisconnected { client_id: u32, client_pubkey_b64: Option<String> },
    /// 客户端请求切换外放静音状态
    SetMuteSpeaker { client_id: u32, muted: bool },
    /// 客户端键盘事件(模拟键盘按下/抬起)
    KeyEvent {
        client_id: u32,
        key_code: u16,
        is_down: bool,
    },
    /// 客户端配对成功(通知 UI 更新)
    ClientPaired { client_name: String },
    /// 客户端请求开始视频推流
    /// `peer`:客户端 video UDP 接收地址(peer.ip() + client_video_port)
    /// `codec`:服务端探测后实际使用的编码器(0=H.264, 1=HEVC)
    StartVideo {
        client_id: u32,
        peer: SocketAddr,
        width: u32,
        height: u32,
        fps: u32,
        bitrate: u32,
        codec: u8,
    },
    /// 客户端请求停止视频推流
    StopVideo { client_id: u32 },
    /// 客户端上报视频统计(用于自适应码率)
    VideoStats {
        client_id: u32,
        received_frames: u32,
        lost_frames: u32,
        recovered_frames: u32,
        rtt_ms: u32,
    },
    /// 错误
    Error(NetError),
}

pub struct Server {
    ports: PortLayout,
    sync: ClockSynchronizer,
    /// 配对管理器(可选:测试或无配对需求时为 None)
    pairing: Option<Arc<PairingManager>>,
    /// 视频 UDP socket(用于推流给客户端)
    video_sock: Option<Arc<UdpSocket>>,
}

impl Server {
    pub fn new(ports: PortLayout) -> Self {
        Self {
            ports,
            sync: ClockSynchronizer::new(),
            pairing: None,
            video_sock: None,
        }
    }

    /// 启用配对机制
    pub fn with_pairing(mut self, pairing: Arc<PairingManager>) -> Self {
        self.pairing = Some(pairing);
        self
    }

    /// 获取 video UDP socket(用于推流)
    ///
    /// 在 `run` 启动后才有值,供外部 VideoStreamer 使用。
    pub fn video_sock(&self) -> Option<&Arc<UdpSocket>> {
        self.video_sock.as_ref()
    }

    pub fn clock_sync(&self) -> ClockSynchronizer {
        self.sync.clone()
    }

    /// 启动服务端,返回事件接收端
    ///
    /// - bind_addr: 监听地址(如 "0.0.0.0")
    /// - video_sock: 预绑定的 video UDP socket(供外部 VideoStreamer 使用)
    pub async fn run(
        mut self,
        bind_addr: &str,
        event_tx: mpsc::Sender<ServerEvent>,
        active_clients: std::sync::Arc<RwLock<HashSet<String>>>,
        video_sock: Arc<UdpSocket>,
    ) -> Result<(), NetError> {
        let control_addr = format!("{}:{}", bind_addr, self.ports.control);
        let touch_addr = format!("{}:{}", bind_addr, self.ports.touch);
        let audio_addr = format!("{}:{}", bind_addr, self.ports.audio);

        let tcp = TcpListener::bind(&control_addr).await?;
        let touch_sock = Arc::new(UdpSocket::bind(&touch_addr).await?);
        let audio_sock = Arc::new(UdpSocket::bind(&audio_addr).await?);

        let video_local = video_sock
            .local_addr()
            .map(|a| a.to_string())
            .unwrap_or_else(|_| "<unknown>".to_string());
        tracing::info!(
            "MeowMic 服务端启动: control={} (TCP), touch={} (UDP), audio={} (UDP), video={} (UDP)",
            control_addr, touch_addr, audio_addr, video_local
        );

        self.video_sock = Some(video_sock);

        // 启动 UDP 接收循环
        let touch_tx = event_tx.clone();
        let touch_sock_clone = touch_sock.clone();
        tokio::spawn(async move {
            run_touch_recv(touch_sock_clone, touch_tx).await;
        });

        let audio_tx = event_tx.clone();
        let audio_sock_clone = audio_sock.clone();
        tokio::spawn(async move {
            run_audio_recv(audio_sock_clone, audio_tx).await;
        });

        // TCP 接受循环
        let sync = self.sync.clone();
        let pairing = self.pairing.clone();
        loop {
            match tcp.accept().await {
                Ok((stream, peer)) => {
                    set_keepalive(&stream);
                    let sync = sync.clone();
                    let event_tx = event_tx.clone();
                    let pairing = pairing.clone();
                    let active_clients = active_clients.clone();
                    tokio::spawn(async move {
                        if let Err(e) = handle_control_conn(stream, peer, sync, event_tx, pairing, active_clients).await {
                            tracing::warn!("控制连接处理失败: {}", e);
                        }
                    });
                }
                Err(e) => {
                    tracing::error!("TCP accept 失败: {}", e);
                }
            }
        }
    }
}

/// 跨平台设置 TCP keepalive(Windows/Android/Linux)
fn set_keepalive(stream: &tokio::net::TcpStream) {
    let keepalive = socket2::TcpKeepalive::new()
        .with_time(std::time::Duration::from_secs(15))
        .with_interval(std::time::Duration::from_secs(5));

    #[cfg(windows)]
    {
        use std::os::windows::io::{AsRawSocket, FromRawSocket};
        let raw = stream.as_raw_socket();
        let sock = unsafe { socket2::Socket::from_raw_socket(raw) };
        let _ = sock.set_tcp_keepalive(&keepalive);
        std::mem::forget(sock);
    }
    #[cfg(unix)]
    {
        use std::os::unix::io::{AsRawFd, FromRawFd};
        let raw = stream.as_raw_fd();
        let sock = unsafe { socket2::Socket::from_raw_fd(raw) };
        let _ = sock.set_tcp_keepalive(&keepalive);
        std::mem::forget(sock);
    }
}

async fn run_touch_recv(sock: Arc<UdpSocket>, event_tx: mpsc::Sender<ServerEvent>) {
    let mut buf = vec![0u8; 128];
    let client_id: u32 = 0;
    loop {
        match sock.recv_from(&mut buf).await {
            Ok((n, _peer)) => {
                if let Ok(pkt) = meowmic_protocol::TouchPacket::decode(&buf[..n]) {
                    let event = ServerEvent::Touch {
                        client_id,
                        seq: pkt.header.seq,
                        ts_ns: pkt.header.ts_ns,
                        dx: pkt.dx(),
                        dy: pkt.dy(),
                        event_type: pkt.payload.event_type,
                        button_mask: pkt.payload.button_mask,
                        pressure: pkt.payload.pressure,
                    };
                    if event_tx.send(event).await.is_err() {
                        break;
                    }
                }
            }
            Err(e) => {
                tracing::warn!("Touch UDP recv 错误: {}", e);
            }
        }
    }
}

async fn run_audio_recv(sock: Arc<UdpSocket>, event_tx: mpsc::Sender<ServerEvent>) {
    let mut buf = vec![0u8; 2048];
    let client_id: u32 = 0;
    loop {
        match sock.recv_from(&mut buf).await {
            Ok((n, _peer)) => {
                if let Ok(pkt) = meowmic_protocol::AudioPacket::decode(&buf[..n]) {
                    let event = ServerEvent::Audio {
                        client_id,
                        seq: pkt.header.seq,
                        ts_ns: pkt.header.ts_ns,
                        opus: pkt.opus.to_vec(),
                    };
                    if event_tx.send(event).await.is_err() {
                        break;
                    }
                }
            }
            Err(e) => {
                tracing::warn!("Audio UDP recv 错误: {}", e);
            }
        }
    }
}

async fn handle_control_conn(
    mut stream: TcpStream,
    peer: SocketAddr,
    sync: ClockSynchronizer,
    event_tx: mpsc::Sender<ServerEvent>,
    pairing: Option<Arc<PairingManager>>,
    active_clients: std::sync::Arc<RwLock<HashSet<String>>>,
) -> Result<(), NetError> {
    tracing::info!("控制连接来自 {}", peer);

    let mut client_id: u32 = 0;
    let mut audio_cfg = (48000u32, 1u8, 20u16);
    let mut read_buf = Vec::with_capacity(4096);
    let mut frame = Vec::with_capacity(4096);
    // 每个连接独立 nonce(用于本次 PairRequired 握手)
    let mut pending_nonce: Option<u64> = None;
    // 本连接成功完成 HelloPaired 后的客户端公钥 base64(用于 ClientDisconnected 时从 active_clients 移除)
    let mut conn_pubkey_b64: Option<String> = None;
    // 不活跃超时:参考 Sunshine 10s Ping 超时,客户端每 10s 发心跳,服务端 35s 无消息则断开
    const IDLE_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(35);

    loop {
        let mut tmp = [0u8; 4096];
        let idle_sleep = tokio::time::sleep(IDLE_TIMEOUT);
        tokio::pin!(idle_sleep);

        let read_result = tokio::select! {
            r = stream.read(&mut tmp) => r,
            () = &mut idle_sleep => {
                tracing::warn!("客户端不活跃超时({}s),断开: {}", IDLE_TIMEOUT.as_secs(), peer);
                return Err(NetError::Io(std::io::Error::new(
                    std::io::ErrorKind::TimedOut,
                    format!("客户端 {}s 未发送消息", IDLE_TIMEOUT.as_secs()),
                )));
            }
        };
        let n = read_result?;
        if n == 0 {
            // 对端关闭:清理 active_clients 公钥登记
            if let Some(ref pk) = conn_pubkey_b64 {
                let mut set = active_clients.write().await;
                set.remove(pk);
            }
            let _ = event_tx
                .send(ServerEvent::ClientDisconnected { client_id, client_pubkey_b64: conn_pubkey_b64.clone() })
                .await;
            tracing::info!("控制连接关闭: {}", peer);
            return Ok(());
        }
        read_buf.extend_from_slice(&tmp[..n]);

        // 解析所有完整消息
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

            match decode_control(&msg_bytes) {
                Ok((msg, _)) => {
                    if let Some(resp) = handle_control_msg(
                        &msg,
                        &mut client_id,
                        &mut audio_cfg,
                        &sync,
                        &peer,
                        &event_tx,
                        pairing.as_deref(),
                        &mut pending_nonce,
                        &active_clients,
                        &mut conn_pubkey_b64,
                    )
                    .await
                    {
                        encode_control(&resp, &mut frame).map_err(NetError::Protocol)?;
                        stream.write_all(&frame).await?;
                        frame.clear();
                    }
                }
                Err(e) => {
                    tracing::warn!("控制消息解码失败: {}", e);
                }
            }
        }
    }
}

#[allow(clippy::too_many_arguments)]
async fn handle_control_msg(
    msg: &ControlMessage,
    client_id: &mut u32,
    audio_cfg: &mut (u32, u8, u16),
    _sync: &ClockSynchronizer,
    peer: &SocketAddr,
    event_tx: &mpsc::Sender<ServerEvent>,
    pairing: Option<&PairingManager>,
    pending_nonce: &mut Option<u64>,
    active_clients: &std::sync::Arc<RwLock<HashSet<String>>>,
    conn_pubkey_b64: &mut Option<String>,
) -> Option<ControlMessage> {
    match msg {
        ControlMessage::Hello {
            client_name,
            protocol_version,
            audio_sample_rate,
            audio_channels,
            audio_frame_ms,
        } => {
            // 若启用配对机制,普通 Hello 必须先走配对流程
            if let Some(pm) = pairing {
                // 生成 nonce 并返回 PairRequired
                let nonce = generate_nonce();
                *pending_nonce = Some(nonce);
                tracing::info!(
                    "握手: client={} 未配对,要求先完成配对 nonce={}",
                    client_name,
                    nonce
                );
                return Some(ControlMessage::PairRequired {
                    server_pubkey: pm.server_pubkey().to_vec(),
                    server_nonce: nonce,
                });
            }
            // 无配对机制:直接放行(兼容旧客户端)
            *client_id = monotonic_ns() as u32;
            *audio_cfg = (*audio_sample_rate, *audio_channels, *audio_frame_ms);
            tracing::info!(
                "握手: client={} proto={} audio={}/{}/{}ms",
                client_name,
                protocol_version,
                audio_sample_rate,
                audio_channels,
                audio_frame_ms
            );
            let _ = event_tx
                .send(ServerEvent::ClientConnected {
                    client_id: *client_id,
                    peer: *peer,
                    audio_sample_rate: *audio_sample_rate,
                    audio_channels: *audio_channels,
                    audio_frame_ms: *audio_frame_ms,
                    client_pubkey_b64: None,
                })
                .await;
            Some(ControlMessage::HelloAck {
                server_name: "MeowMic-Server".into(),
                protocol_version: 1,
                client_id: *client_id,
                audio_sample_rate: *audio_sample_rate,
                audio_channels: *audio_channels,
                audio_frame_ms: *audio_frame_ms,
            })
        }
        ControlMessage::HelloPaired {
            client_name,
            protocol_version,
            client_pubkey,
            nonce,
            signature,
            audio_sample_rate,
            audio_channels,
            audio_frame_ms,
        } => {
            let pm = pairing?;
            // 验证签名 + 白名单
            match pm
                .verify_paired_hello(client_pubkey, client_name, *nonce, signature)
                .await
            {
                Ok(()) => {
                    *client_id = monotonic_ns() as u32;
                    *audio_cfg = (*audio_sample_rate, *audio_channels, *audio_frame_ms);
                    // 登记活跃客户端公钥:HTTP /applist 等端点做 OR 鉴权用(第二台手机关键路径)
                    use base64::{Engine as _, engine::general_purpose::STANDARD as B64};
                    let pk_b64 = B64.encode(client_pubkey);
                    active_clients.write().await.insert(pk_b64.clone());
                    *conn_pubkey_b64 = Some(pk_b64.clone());
                    tracing::info!(
                        "已配对握手成功: client={} proto={} audio={}/{}/{}ms pubkey_registered",
                        client_name,
                        protocol_version,
                        audio_sample_rate,
                        audio_channels,
                        audio_frame_ms
                    );
                    let _ = event_tx
                        .send(ServerEvent::ClientConnected {
                            client_id: *client_id,
                            peer: *peer,
                            audio_sample_rate: *audio_sample_rate,
                            audio_channels: *audio_channels,
                            audio_frame_ms: *audio_frame_ms,
                            client_pubkey_b64: Some(pk_b64),
                        })
                        .await;
                    Some(ControlMessage::HelloAck {
                        server_name: "MeowMic-Server".into(),
                        protocol_version: 1,
                        client_id: *client_id,
                        audio_sample_rate: *audio_sample_rate,
                        audio_channels: *audio_channels,
                        audio_frame_ms: *audio_frame_ms,
                    })
                }
                Err(e) => {
                    tracing::warn!("已配对握手失败: client={} err={}", client_name, e);
                    Some(ControlMessage::PairResponse {
                        success: false,
                        server_pubkey: pm.server_pubkey().to_vec(),
                        error_msg: format!("认证失败: {}", e),
                    })
                }
            }
        }
        ControlMessage::PairRequest {
            client_pubkey,
            client_name,
            pin,
            server_nonce,
            signature,
        } => {
            let pm = pairing?;
            // 校验 nonce 是否匹配本连接发出的
            if let Some(expected) = *pending_nonce {
                if expected != *server_nonce {
                    return Some(ControlMessage::PairResponse {
                        success: false,
                        server_pubkey: pm.server_pubkey().to_vec(),
                        error_msg: "nonce 不匹配".into(),
                    });
                }
            }
            let (success, server_pubkey, err) = pm
                .handle_pair_request(
                    client_pubkey.clone(),
                    client_name.clone(),
                    pin.clone(),
                    *server_nonce,
                    signature.clone(),
                )
                .await;
            if success {
                let _ = event_tx
                    .send(ServerEvent::ClientPaired {
                        client_name: client_name.clone(),
                    })
                    .await;
                // 清除 nonce(本次配对完成)
                *pending_nonce = None;
            }
            Some(ControlMessage::PairResponse {
                success,
                server_pubkey,
                error_msg: err,
            })
        }
        ControlMessage::SyncReq { client_ts_ns } => {
            let server_recv_ts_ns = monotonic_ns();
            let server_send_ts_ns = monotonic_ns();
            Some(ControlMessage::SyncResp {
                client_ts_ns: *client_ts_ns,
                server_recv_ts_ns,
                server_send_ts_ns,
            })
        }
        ControlMessage::Bye => {
            tracing::info!("客户端主动断开");
            None
        }
        ControlMessage::SetMuteSpeaker { muted } => {
            tracing::info!("客户端请求切换外放静音: muted={}", muted);
            let _ = event_tx
                .send(ServerEvent::SetMuteSpeaker {
                    client_id: *client_id,
                    muted: *muted,
                })
                .await;
            None
        }
        ControlMessage::KeyEvent { key_code, is_down } => {
            let _ = event_tx
                .send(ServerEvent::KeyEvent {
                    client_id: *client_id,
                    key_code: *key_code,
                    is_down: *is_down,
                })
                .await;
            None
        }
        ControlMessage::Ping => Some(ControlMessage::Pong),
        ControlMessage::Pong => None,
        ControlMessage::StartVideo { width, height, fps, bitrate, client_video_port } => {
            // 客户端 video UDP 接收地址 = TCP 对端 IP + 客户端绑定的 video UDP 端口
            let video_peer = SocketAddr::new(peer.ip(), *client_video_port);
            // 读取服务端启动时探测的首选 codec(0=H.264, 1=HEVC)
            let codec = preferred_codec();
            let _ = event_tx
                .send(ServerEvent::StartVideo {
                    client_id: *client_id,
                    peer: video_peer,
                    width: *width,
                    height: *height,
                    fps: *fps,
                    bitrate: *bitrate,
                    codec,
                })
                .await;
            Some(ControlMessage::VideoStarted {
                width: *width,
                height: *height,
                fps: *fps,
                codec,
            })
        }
        ControlMessage::StopVideo => {
            let _ = event_tx
                .send(ServerEvent::StopVideo { client_id: *client_id })
                .await;
            None
        }
        ControlMessage::VideoStats {
            received_frames,
            lost_frames,
            recovered_frames,
            rtt_ms,
        } => {
            let _ = event_tx
                .send(ServerEvent::VideoStats {
                    client_id: *client_id,
                    received_frames: *received_frames,
                    lost_frames: *lost_frames,
                    recovered_frames: *recovered_frames,
                    rtt_ms: *rtt_ms,
                })
                .await;
            None
        }
        _ => None,
    }
}
