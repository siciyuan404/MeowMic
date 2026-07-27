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
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpStream, UdpSocket};
use tokio::sync::{Mutex, mpsc};

use meowmic_protocol::{
    AudioPacket, ControlMessage, TouchPacket, encode_control, monotonic_ns,
};

use crate::{NetError, PeerAddr};
use crate::sync::ClockSynchronizer;

/// 客户端事件(从服务端收到)
#[derive(Debug)]
pub enum ClientEvent {
    HelloAck {
        client_id: u32,
        audio_sample_rate: u32,
        audio_channels: u8,
        audio_frame_ms: u16,
    },
    ClockSynced {
        offset_ms: f64,
        rtt_ms: f64,
    },
    Disconnected,
    Error(NetError),
}

pub struct Client {
    sync: ClockSynchronizer,
    /// TCP 控制流(线程安全写入)
    control_stream: Arc<Mutex<TcpStream>>,
    touch_sock: Arc<UdpSocket>,
    audio_sock: Arc<UdpSocket>,
    peer: PeerAddr,
    /// 触摸包序号
    touch_seq: Arc<Mutex<u16>>,
    /// 音频包序号
    audio_seq: Arc<Mutex<u16>>,
}

impl Client {
    /// 连接到服务端
    ///
    /// - server_control_addr: 服务端 TCP 控制地址(如 "192.168.1.100:28900")
    /// - 自动推导 touch/audio 端口(control+1, control+2)
    pub async fn connect(
        server_control_addr: &str,
        client_name: &str,
        event_tx: mpsc::Sender<ClientEvent>,
    ) -> Result<Self, NetError> {
        let control_addr: SocketAddr = server_control_addr
            .parse()
            .map_err(|_| NetError::Handshake(format!("无效地址: {}", server_control_addr)))?;

        let peer = PeerAddr {
            control: control_addr,
            touch: SocketAddr::new(control_addr.ip(), control_addr.port() + 1),
            audio: SocketAddr::new(control_addr.ip(), control_addr.port() + 2),
        };

        let stream = TcpStream::connect(control_addr).await?;
        stream.set_nodelay(true)?;

        // 本地 UDP socket:绑定任意端口
        let touch_sock = UdpSocket::bind("0.0.0.0:0").await?;
        let audio_sock = UdpSocket::bind("0.0.0.0:0").await?;

        let sync = ClockSynchronizer::new();
        let client = Self {
            sync: sync.clone(),
            control_stream: Arc::new(Mutex::new(stream)),
            touch_sock: Arc::new(touch_sock),
            audio_sock: Arc::new(audio_sock),
            peer,
            touch_seq: Arc::new(Mutex::new(0)),
            audio_seq: Arc::new(Mutex::new(0)),
        };

        // 发送 Hello
        client.send_control(ControlMessage::Hello {
            client_name: client_name.into(),
            protocol_version: 1,
            audio_sample_rate: 48000,
            audio_channels: 1,
            audio_frame_ms: 20,
        }).await?;

        // 启动控制消息接收循环
        let stream_clone = client.control_stream.clone();
        let sync_clone = sync.clone();
        let event_tx_clone = event_tx.clone();
        tokio::spawn(async move {
            run_control_recv(stream_clone, sync_clone, event_tx_clone).await;
        });

        // 启动时钟同步循环
        let stream_for_sync = client.control_stream.clone();
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
        let mut seq_guard = self.touch_seq.lock().await;
        let seq = *seq_guard;
        *seq_guard = seq.wrapping_add(1);
        drop(seq_guard);

        let ts = monotonic_ns() as u32;
        let pkt = TouchPacket::new_with_button(seq, ts, event, button_mask, dx, dy);
        let mut buf = Vec::with_capacity(64);
        pkt.encode(&mut buf);
        self.touch_sock.send_to(&buf, self.peer.touch).await?;
        Ok(())
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
        let mut stream = self.control_stream.lock().await;
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
}

async fn run_control_recv(
    stream: Arc<Mutex<TcpStream>>,
    sync: ClockSynchronizer,
    event_tx: mpsc::Sender<ClientEvent>,
) {
    let mut read_buf = Vec::with_capacity(4096);
    loop {
        let mut tmp = [0u8; 4096];
        let n = {
            let mut s = stream.lock().await;
            match s.read(&mut tmp).await {
                Ok(n) => {
                    if n == 0 {
                        let _ = event_tx.send(ClientEvent::Disconnected).await;
                        return;
                    }
                    n
                }
                Err(e) => {
                    let _ = event_tx
                        .send(ClientEvent::Error(NetError::Io(e)))
                        .await;
                    return;
                }
            }
        };
        read_buf.extend_from_slice(&tmp[..n]);

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

async fn run_sync_loop(stream: Arc<Mutex<TcpStream>>, _sync: ClockSynchronizer) {
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
        let mut s = stream.lock().await;
        if s.write_all(&frame).await.is_err() {
            return;
        }
        frame.clear();
    }
}
