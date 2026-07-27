//! 服务端网络层
//!
//! 监听:
//! - TCP Control 端口:握手、时钟同步、统计
//! - UDP Touch 端口:接收触摸包
//! - UDP Audio 端口:接收 Opus 音频包

use std::net::SocketAddr;
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::sync::mpsc;

use meowmic_protocol::{
    ControlMessage, decode_control, encode_control, monotonic_ns,
};

use crate::{NetError, PortLayout};
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
    ClientDisconnected { client_id: u32 },
    /// 客户端请求切换外放静音状态
    SetMuteSpeaker { client_id: u32, muted: bool },
    /// 错误
    Error(NetError),
}

pub struct Server {
    ports: PortLayout,
    sync: ClockSynchronizer,
}

impl Server {
    pub fn new(ports: PortLayout) -> Self {
        Self {
            ports,
            sync: ClockSynchronizer::new(),
        }
    }

    pub fn clock_sync(&self) -> ClockSynchronizer {
        self.sync.clone()
    }

    /// 启动服务端,返回事件接收端
    ///
    /// - bind_addr: 监听地址(如 "0.0.0.0")
    pub async fn run(self, bind_addr: &str, event_tx: mpsc::Sender<ServerEvent>) -> Result<(), NetError> {
        let control_addr = format!("{}:{}", bind_addr, self.ports.control);
        let touch_addr = format!("{}:{}", bind_addr, self.ports.touch);
        let audio_addr = format!("{}:{}", bind_addr, self.ports.audio);

        let tcp = TcpListener::bind(&control_addr).await?;
        let touch_sock = Arc::new(UdpSocket::bind(&touch_addr).await?);
        let audio_sock = Arc::new(UdpSocket::bind(&audio_addr).await?);

        tracing::info!(
            "MeowMic 服务端启动: control={} (TCP), touch={} (UDP), audio={} (UDP)",
            control_addr, touch_addr, audio_addr
        );

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
        loop {
            match tcp.accept().await {
                Ok((stream, peer)) => {
                    let sync = sync.clone();
                    let event_tx = event_tx.clone();
                    tokio::spawn(async move {
                        if let Err(e) = handle_control_conn(stream, peer, sync, event_tx).await {
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
) -> Result<(), NetError> {
    tracing::info!("控制连接来自 {}", peer);

    let mut client_id: u32 = 0;
    let mut audio_cfg = (48000u32, 1u8, 20u16);
    let mut read_buf = Vec::with_capacity(4096);
    let mut frame = Vec::with_capacity(4096);

    loop {
        let mut tmp = [0u8; 4096];
        let n = stream.read(&mut tmp).await?;
        if n == 0 {
            // 对端关闭
            let _ = event_tx
                .send(ServerEvent::ClientDisconnected { client_id })
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

async fn handle_control_msg(
    msg: &ControlMessage,
    client_id: &mut u32,
    audio_cfg: &mut (u32, u8, u16),
    _sync: &ClockSynchronizer,
    peer: &SocketAddr,
    event_tx: &mpsc::Sender<ServerEvent>,
) -> Option<ControlMessage> {
    match msg {
        ControlMessage::Hello {
            client_name,
            protocol_version,
            audio_sample_rate,
            audio_channels,
            audio_frame_ms,
        } => {
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
        ControlMessage::Ping => Some(ControlMessage::Pong),
        ControlMessage::Pong => None,
        _ => None,
    }
}
