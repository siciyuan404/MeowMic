//! 网络传输层
//!
//! 三通道分离:
//! - Control: TCP,变长 bincode 消息(握手/时钟同步/统计)
//! - Touch:   UDP,轻量零拷贝包(最新状态优先,丢包可丢)
//! - Audio:   UDP,带序号 Opus 帧(inband FEC + jitter buffer)
//!
//! P0 阶段:基础 tokio UDP + TCP,时钟同步 EWMA
//! 后续:SO_TIMESTAMPING、SO_BUSY_LOOP、独立高优先级线程
//!
//! ## 服务发现
//! 服务端可启用 mDNS 广播(参考 Moonlight+Sunshine 的发现机制),
//! 客户端通过监听 `_meowmic._tcp.` 自动发现局域网内的服务端。
//! 详见 [`discovery`] 模块。

pub mod client;
pub mod discovery;
pub mod pairing;
pub mod server;
pub mod sync;

pub use client::{Client, ClientEvent};
pub use discovery::{DiscoveryError, MdnsAdvertiser, DEFAULT_INSTANCE_NAME, PROTOCOL_VERSION, SERVICE_TYPE};
pub use pairing::{
    generate_nonce, generate_pin, ClientPairingState, PairedClient, PairingError, PairingManager,
    PairingState,
};
pub use server::{Server, ServerEvent};
pub use sync::ClockSynchronizer;

use std::net::SocketAddr;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum NetError {
    #[error("IO 错误: {0}")]
    Io(#[from] std::io::Error),
    #[error("协议错误: {0}")]
    Protocol(#[from] meowmic_protocol::ProtocolError),
    #[error("握手失败: {0}")]
    Handshake(String),
    #[error("连接已断开")]
    Disconnected,
}

/// 服务端固定端口分配(基础端口 + 通道偏移)
#[derive(Debug, Clone, Copy)]
pub struct PortLayout {
    pub control: u16, // TCP
    pub touch: u16,   // UDP
    pub audio: u16,   // UDP
}

impl PortLayout {
    pub fn from_base(base: u16) -> Self {
        Self {
            control: base,
            touch: base + 1,
            audio: base + 2,
        }
    }
    pub const DEFAULT_BASE: u16 = 28900;
    pub fn default() -> Self {
        Self::from_base(Self::DEFAULT_BASE)
    }
}

/// 对端地址三元组(用于客户端记录服务端三个端口)
#[derive(Debug, Clone, Copy)]
pub struct PeerAddr {
    pub control: SocketAddr,
    pub touch: SocketAddr,
    pub audio: SocketAddr,
}
