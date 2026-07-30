//! mDNS 服务发现
//!
//! 借鉴 Moonlight + Sunshine 的发现机制:
//! - 服务端在局域网广播 `_meowmic._tcp` 服务
//! - 客户端通过 mDNS 监听,自动发现服务端
//!
//! 端口约定:广播 control TCP 端口,客户端发现后自动推导 touch/audio 端口
//! (control+1, control+2)
//!
//! TXT 记录字段:
//! - `v`:协议版本(当前 "1")
//! - `name`:服务端显示名(主机名)

use std::sync::Arc;
use mdns_sd::{ServiceDaemon, ServiceInfo};

/// mDNS 服务类型(下划线前缀 + 协议名 + tcp)
pub const SERVICE_TYPE: &str = "_meowmic._tcp.";

/// 默认服务实例名(可由调用方覆盖)
pub const DEFAULT_INSTANCE_NAME: &str = "MeowMic-Server";

/// 协议版本(TXT 记录中的 `v` 字段)
pub const PROTOCOL_VERSION: &str = "1";

/// mDNS 广播错误
#[derive(Debug, thiserror::Error)]
pub enum DiscoveryError {
    #[error("mDNS 守护进程错误: {0}")]
    Daemon(String),
    #[error("mDNS 注册错误: {0}")]
    Register(String),
    #[error("mDNS 注销错误: {0}")]
    Unregister(String),
}

/// 通过 UDP "连接" 一个公网地址来探测本机出站 IP。
/// 不会真正发包,只是让 OS 选路由并返回本地接口 IP。
/// 失败时返回 None,调用方需兜底。
fn detect_local_ipv4() -> Option<std::net::Ipv4Addr> {
    let socket = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    // 8.8.8.8 是 Google DNS,通常在路由表中存在;不会真正发包
    socket.connect("8.8.8.8:80").ok()?;
    let addr = socket.local_addr().ok()?;
    match addr.ip() {
        std::net::IpAddr::V4(v4) if !v4.is_loopback() => Some(v4),
        _ => None,
    }
}

/// mDNS 广播器(服务端用)
///
/// 持有 `ServiceDaemon` 句柄,Drop 时自动注销服务。
/// 设计为不可变:一次注册,长期运行。
pub struct MdnsAdvertiser {
    daemon: Arc<ServiceDaemon>,
    /// 已注册的全名(用于注销),形如 `MeowMic-Server._meowmic._tcp.local.`
    full_name: String,
}

impl MdnsAdvertiser {
    /// 注册 mDNS 服务
    ///
    /// - `instance_name`:服务实例名(如 "MeowMic-Server"),传空则用默认
    /// - `host_name`:主机名(如 "DESKTOP-ABC"),传空则用默认
    /// - `port`:control TCP 端口(客户端发现后推导 touch=port+1, audio=port+2)
    /// - `listen_ip`:服务端绑定的本机 IP(用于 mDNS 广播地址);
    ///   传 None 则自动探测(UDP connect trick),并启用 mdns-sd 的地址自动更新
    pub fn register(
        instance_name: Option<&str>,
        host_name: Option<&str>,
        port: u16,
        listen_ip: Option<std::net::Ipv4Addr>,
    ) -> Result<Self, DiscoveryError> {
        let daemon = ServiceDaemon::new()
            .map_err(|e| DiscoveryError::Daemon(e.to_string()))?;
        let daemon = Arc::new(daemon);

        let instance = instance_name
            .filter(|s| !s.is_empty())
            .unwrap_or(DEFAULT_INSTANCE_NAME);
        let host = host_name
            .filter(|s| !s.is_empty())
            .unwrap_or("meowmic-host");

        // TXT 记录:文档明确支持 &[(&str, &str)] 格式
        let properties: Vec<(&str, &str)> = vec![
            ("v", PROTOCOL_VERSION),
            // 把 name 也放进 TXT,便于客户端 UI 直接显示
            ("name", instance),
        ];

        // 决定广播 IP:优先用调用方传入,否则自动探测
        let ip_to_register: std::net::Ipv4Addr = listen_ip
            .or_else(detect_local_ipv4)
            .unwrap_or(std::net::Ipv4Addr::new(127, 0, 0, 1));
        let addr_str = ip_to_register.to_string();

        let info = ServiceInfo::new(
            SERVICE_TYPE,
            instance,
            host,
            &addr_str,
            port,
            properties.as_slice(),
        )
        .map_err(|e| DiscoveryError::Register(e.to_string()))?;

        // full_name 在 register 之前保存,用于 Drop 时注销
        let full_name = info.get_fullname().to_string();
        daemon
            .register(info)
            .map_err(|e| DiscoveryError::Register(e.to_string()))?;

        tracing::info!(
            "mDNS 服务已注册: {} 端口={} IP={} TXT={:?}",
            full_name,
            port,
            addr_str,
            properties
        );

        Ok(Self { daemon, full_name })
    }

    /// 显式注销服务(通常不需要,Drop 会自动处理)
    pub fn unregister(&self) -> Result<(), DiscoveryError> {
        self.daemon
            .unregister(&self.full_name)
            .map_err(|e| DiscoveryError::Unregister(e.to_string()))?;
        Ok(())
    }
}

impl Drop for MdnsAdvertiser {
    fn drop(&mut self) {
        // 尝试注销,失败仅记录日志(Drop 不能返回错误)
        if let Err(e) = self.daemon.unregister(&self.full_name) {
            tracing::warn!("mDNS 注销失败: {}", e);
        }
    }
}
