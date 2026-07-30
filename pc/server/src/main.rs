//! MeowMic 服务端主程序(P0 管线验证版)
//!
//! P0 实现策略(验证管线,非最终方案):
//! - 触摸:win32 SendInput 模拟鼠标(后续替换为自研 HID 驱动,绕过节流)
//! - 音频:cpal 直接播放(后续替换为自研 WDM 虚拟麦克风设备)
//! - 统计:周期打印延迟/丢包/速率

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;

use anyhow::Result;
use clap::{Parser, Subcommand};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::Mutex;
use tracing::{info, warn};

use meowmic_audio::AudioConfig;
use meowmic_net::pairing::PairedClient;
use meowmic_net::{MdnsAdvertiser, NetError, PairingManager, PortLayout, Server, ServerEvent};

mod touch_inject;
mod audio_play;
mod stats;

#[derive(Parser, Debug)]
#[command(name = "meowmic-server", version, about = "MeowMic 服务端 - 极低延迟手机外设")]
struct Cli {
    #[command(subcommand)]
    cmd: Option<Cmd>,
    /// 监听地址
    #[arg(long, default_value = "0.0.0.0")]
    bind: String,
    /// 基础端口(control=base, touch=base+1, audio=base+2)
    #[arg(long, default_value_t = PortLayout::DEFAULT_BASE)]
    port: u16,
    /// 指定音频输出设备名称(空则用默认)
    #[arg(long)]
    output_device: Option<String>,
}

#[derive(Subcommand, Debug)]
enum Cmd {
    /// 运行服务端(默认)
    Run {
        #[arg(long, default_value = "0.0.0.0")]
        bind: String,
        #[arg(long, default_value_t = PortLayout::DEFAULT_BASE)]
        port: u16,
        /// 指定音频输出设备名称(空则用默认)
        #[arg(long)]
        output_device: Option<String>,
    },
    /// 打印本机所有 IP,方便手机端配置
    ListIps,
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "meowmic=info,warn".into()),
        )
        .with_target(false)
        .init();

    let cli = Cli::parse();

    match cli.cmd {
        Some(Cmd::Run { bind, port, output_device }) => run_server(&bind, port, output_device.as_deref()).await,
        Some(Cmd::ListIps) => {
            list_local_ips();
            Ok(())
        }
        None => run_server(&cli.bind, cli.port, cli.output_device.as_deref()).await,
    }
}

async fn run_server(bind: &str, port: u16, output_device: Option<&str>) -> Result<()> {
    let ports = PortLayout::from_base(port);
    let bind = bind.to_string();
    info!("MeowMic 服务端启动中...");
    info!(
        "端口分配: control={}(TCP) touch={}(UDP) audio={}(UDP) stats={}(HTTP)",
        ports.control, ports.touch, ports.audio, port + 3
    );

    list_local_ips();

    // 启动 mDNS 服务广播(参考 Moonlight + Sunshine 的发现机制)
    // 客户端监听 `_meowmic._tcp.` 即可自动发现本机服务端
    // 广播器在函数结束时自动 Drop 注销
    let host_name = hostname_string();
    let _mdns_advertiser = match MdnsAdvertiser::register(
        Some("MeowMic-Server"),
        Some(&host_name),
        ports.control,
        None, // 让 mdns-sd 自动枚举所有接口
    ) {
        Ok(a) => {
            info!("mDNS 广播已启动: 服务类型=_meowmic._tcp. 端口={}", ports.control);
            Some(a)
        }
        Err(e) => {
            // mDNS 失败不阻断服务端启动,仅影响自动发现
            warn!("mDNS 广播启动失败(自动发现将不可用): {}", e);
            None
        }
    };

    // 加载或创建配对管理器
    // 状态文件:Windows %APPDATA%/meowmic/pairing.json,其他平台 ~/.config/meowmic/pairing.json
    let pairing_dir = pairing_state_dir();
    let pairing_manager = match PairingManager::load_or_create(Some(pairing_dir.clone())) {
        Ok(pm) => {
            // 启动时若无 PIN,生成一个新 PIN(若有已配对客户端,也保留 PIN 以便新设备配对)
            let pm = Arc::new(pm);
            let pin = pm.ensure_pin().await;
            info!("配对机制已启用,当前 PIN: {} (状态文件: {})", pin, pairing_dir.join("pairing.json").display());
            Some(pm)
        }
        Err(e) => {
            warn!("配对管理器初始化失败(配对机制禁用): {}", e);
            None
        }
    };

    let server = if let Some(ref pm) = pairing_manager {
        Server::new(ports).with_pairing(pm.clone())
    } else {
        Server::new(ports)
    };
    let (event_tx, mut event_rx) = tokio::sync::mpsc::channel::<ServerEvent>(256);

    // 启动统计打印
    let stats = Arc::new(Mutex::new(stats::Stats::default()));
    let stats_clone = stats.clone();
    tokio::spawn(async move {
        stats::print_loop(stats_clone).await;
    });

    // P0:触摸注入器(SendInput)
    let touch_injector = Arc::new(touch_inject::TouchInjector::new());
    // P0:音频播放器(cpal)
    let audio_cfg = AudioConfig::default();
    // 静音外放:读取 MEOWMIC_MUTE_SPEAKER env,muted 时 stream 仍存活但不推送 PCM
    let muted = std::env::var("MEOWMIC_MUTE_SPEAKER")
        .ok()
        .as_deref()
        .map(|v| v == "1")
        .unwrap_or(false);
    info!("静音外放: {}", if muted { "开启" } else { "关闭" });
    if let Some(ref name) = output_device {
        info!("音频输出设备: {}", name);
    } else {
        info!("音频输出设备: 默认");
    }
    let audio_player = Arc::new(audio_play::AudioPlayer::new(audio_cfg, muted, output_device.map(|s| s.to_string())).await?);
    let decoder = Arc::new(Mutex::new(meowmic_audio::make_decoder(&audio_cfg)));

    // 启动 HTTP /stats 服务(监听 127.0.0.1:{base_port + 3})
    // 同时提供 /mute?on=1|0 接口,供 PC 控制台运行时切换外放静音
    let stats_port = port + 3;
    let stats_addr: SocketAddr = format!("127.0.0.1:{}", stats_port).parse()?;
    let stats_clone2 = stats.clone();
    let audio_for_http = audio_player.clone();
    tokio::spawn(async move {
        run_stats_server(stats_clone2, audio_for_http, stats_addr).await;
    });
    info!("stats HTTP 监听: http://{}/stats", stats_addr);
    info!("mute HTTP 监听: http://{}/mute?on=1|0", stats_addr);

    // 启动 HTTP /serverinfo 服务(监听 0.0.0.0:{base_port + 4})
    // 供手机端 mDNS 发现后做三态轮询(UNKNOWN/ONLINE/OFFLINE),参考 Moonlight+Sunshine
    // 与 /stats 分离:/stats 仅本机访问(PC 控制台用),/serverinfo 对局域网开放
    // 同时暴露 server_pubkey_b64 供客户端识别服务端身份(用于配对流程)
    let serverinfo_port = port + 4;
    let serverinfo_addr: SocketAddr = format!("0.0.0.0:{}", serverinfo_port).parse()?;
    let stats_for_info = stats.clone();
    let serverinfo_name = host_name.clone();
    let serverinfo_pairing = pairing_manager.clone();
    tokio::spawn(async move {
        run_serverinfo_server(stats_for_info, serverinfo_name, serverinfo_pairing, serverinfo_addr).await;
    });
    info!("serverinfo HTTP 监听: http://{}/serverinfo", serverinfo_addr);

    // 启动 HTTP /pairing 服务(监听 127.0.0.1:{base_port + 5})
    // 供 PC 控制台查询当前 PIN / 已配对客户端列表 / 重置配对
    // 仅本机访问,不暴露到局域网
    let pairing_port = port + 5;
    let pairing_addr: SocketAddr = format!("127.0.0.1:{}", pairing_port).parse()?;
    let pairing_for_http = pairing_manager.clone();
    tokio::spawn(async move {
        run_pairing_server(pairing_for_http, pairing_addr).await;
    });
    info!("pairing HTTP 监听: http://{}/pairing", pairing_addr);

    // 事件循环
    let server_handle = tokio::spawn(async move {
        if let Err(e) = server.run(&bind, event_tx).await {
            match e {
                NetError::Io(e) => {
                    anyhow::bail!("网络 IO 错误: {}", e);
                }
                _ => warn!("服务端错误: {}", e),
            }
        }
        Ok::<(), anyhow::Error>(())
    });

    while let Some(event) = event_rx.recv().await {
        match event {
            ServerEvent::ClientConnected {
                client_id,
                peer,
                audio_sample_rate,
                audio_channels,
                audio_frame_ms,
            } => {
                info!(
                    "✓ 客户端已连接 id={} peer={} audio={}/{}/{}ms",
                    client_id, peer, audio_sample_rate, audio_channels, audio_frame_ms
                );
                stats.lock().await.record_connect();
            }
            ServerEvent::Touch {
                seq,
                ts_ns,
                dx,
                dy,
                event_type,
                button_mask,
                pressure,
                ..
            } => {
                let recv_ns = meowmic_protocol::monotonic_ns() as u32;
                let latency_ns = recv_ns.wrapping_sub(ts_ns);
                touch_injector.inject(event_type, button_mask, dx, dy, pressure);
                stats.lock().await.record_touch(latency_ns);
                tracing::trace!(
                    "touch seq={} dx={:.1} dy={:.1} lat={:.2}ms",
                    seq,
                    dx,
                    dy,
                    latency_ns as f64 / 1e6
                );
            }
            ServerEvent::Audio { seq, ts_ns, opus, .. } => {
                let recv_ns = meowmic_protocol::monotonic_ns() as u32;
                let latency_ns = recv_ns.wrapping_sub(ts_ns);
                let mut dec = decoder.lock().await;
                let mut pcm = vec![0i16; audio_cfg.samples_per_frame() * 2];
                match dec.decode(&opus, &mut pcm) {
                    Ok(n) => {
                        audio_player.play(&pcm[..n]).await;
                        stats.lock().await.record_audio(latency_ns);
                        tracing::trace!(
                            "audio seq={} opus={}B pcm={}smp lat={:.2}ms",
                            seq,
                            opus.len(),
                            n,
                            latency_ns as f64 / 1e6
                        );
                    }
                    Err(e) => warn!("音频解码失败: {}", e),
                }
            }
            ServerEvent::ClientDisconnected { client_id } => {
                info!("✗ 客户端断开 id={}", client_id);
                stats.lock().await.record_disconnect();
            }
            ServerEvent::SetMuteSpeaker { client_id, muted } => {
                audio_player.set_muted(muted);
                info!("已切换外放静音: id={} muted={}", client_id, muted);
            }
            ServerEvent::KeyEvent { client_id, key_code, is_down } => {
                touch_injector.inject_key(key_code, is_down);
                tracing::debug!(
                    "key id={} vk=0x{:02X} down={}",
                    client_id, key_code, is_down
                );
            }
            ServerEvent::ClientPaired { client_name } => {
                info!("✓ 客户端配对成功: {}", client_name);
                // 配对成功后 PIN 已被 PairingManager 清空,下次新设备配对时再生成
            }
            ServerEvent::Error(e) => {
                warn!("服务端事件错误: {}", e);
            }
        }
    }

    server_handle.await??;
    Ok(())
}

/// HTTP /stats 服务:监听 127.0.0.1:port,GET /stats 返回 JSON 统计快照,其他路径 404
/// GET /mute?on=1|0 运行时切换外放静音(App 端通过 control 通道,PC 控制台通过此 HTTP 接口)
async fn run_stats_server(stats: Arc<Mutex<stats::Stats>>, audio: Arc<audio_play::AudioPlayer>, addr: SocketAddr) {
    let listener = match TcpListener::bind(addr).await {
        Ok(l) => l,
        Err(e) => {
            tracing::error!("stats HTTP 绑定 {} 失败: {}", addr, e);
            return;
        }
    };
    loop {
        let (mut stream, _peer) = match listener.accept().await {
            Ok(s) => s,
            Err(e) => {
                tracing::warn!("stats accept 失败: {}", e);
                continue;
            }
        };
        let stats = stats.clone();
        let audio = audio.clone();
        tokio::spawn(async move {
            // 读取请求(只关心第一行 METHOD PATH HTTP/1.1)
            let mut buf = [0u8; 1024];
            let n = match stream.read(&mut buf).await {
                Ok(n) if n > 0 => n,
                _ => return,
            };
            let req = String::from_utf8_lossy(&buf[..n]);
            let first_line = req.lines().next().unwrap_or("");
            let mut parts = first_line.split_whitespace();
            let method = parts.next().unwrap_or("");
            let raw_path = parts.next().unwrap_or("");
            // 分离 path 与 query
            let (path, query) = match raw_path.split_once('?') {
                Some((p, q)) => (p, q),
                None => (raw_path, ""),
            };

            let (status, body) = if method == "GET" && path == "/stats" {
                let (conn, tps, afs, uptime) = stats.lock().await.snapshot_and_reset();
                let body = format!(
                    "{{\"connections\": {}, \"touches_per_sec\": {}, \"audio_frames_per_sec\": {}, \"uptime_secs\": {}}}",
                    conn, tps, afs, uptime
                );
                ("200 OK", body)
            } else if method == "GET" && path == "/mute" {
                // 解析 on=1|0|true|false
                let muted = query
                    .split('&')
                    .find_map(|kv| {
                        let (k, v) = kv.split_once('=')?;
                        if k == "on" {
                            Some(v == "1" || v.eq_ignore_ascii_case("true"))
                        } else {
                            None
                        }
                    })
                    .unwrap_or_else(|| audio.is_muted());
                audio.set_muted(muted);
                info!("HTTP /mute 切换: muted={}", muted);
                ("200 OK", format!("{{\"muted\":{}}}", muted))
            } else {
                (
                    "404 Not Found",
                    r#"{"error":"not found"}"#.to_string(),
                )
            };

            let response = format!(
                "HTTP/1.1 {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                status,
                body.len(),
                body
            );
            let _ = stream.write_all(response.as_bytes()).await;
            let _ = stream.shutdown().await;
        });
    }
}

fn list_local_ips() {
    info!("本机网络地址:");
    match local_ip_address::list_afinet_netifas() {
        Ok(interfaces) => {
            for (_name, ip) in interfaces {
                if ip.is_ipv4() && !ip.is_loopback() {
                    info!("  {} (端口 {}-{})", ip, PortLayout::DEFAULT_BASE, PortLayout::DEFAULT_BASE + 2);
                }
            }
        }
        Err(e) => warn!("获取本机 IP 失败: {}", e),
    }
}

/// HTTP /serverinfo 服务:监听 0.0.0.0:port,GET /serverinfo 返回服务端状态 JSON
///
/// 供手机端 mDNS 发现后做三态轮询(UNKNOWN/ONLINE/OFFLINE)。
/// 借鉴 Moonlight 的 /serverinfo 端点设计,但简化为 HTTP(暂不做 HTTPS+证书)。
///
/// 返回 JSON 字段:
/// - name:服务实例名(固定 "MeowMic-Server")
/// - hostname:主机名
/// - version:协议版本
/// - state:服务端状态(始终 "ONLINE",退出时端口关闭→客户端探测失败→OFFLINE)
/// - connected_clients:当前已连接的客户端数
/// - max_clients:最大客户端数(目前固定 1)
/// - uptime_secs:服务端启动时长
/// - server_pubkey_b64:服务端 Ed25519 公钥(32 字节 base64,用于配对流程身份识别)
async fn run_serverinfo_server(
    stats: Arc<Mutex<stats::Stats>>,
    hostname: String,
    pairing: Option<Arc<PairingManager>>,
    addr: SocketAddr,
) {
    let listener = match TcpListener::bind(addr).await {
        Ok(l) => l,
        Err(e) => {
            tracing::error!("serverinfo HTTP 绑定 {} 失败: {}", addr, e);
            return;
        }
    };
    loop {
        let (mut stream, peer) = match listener.accept().await {
            Ok(s) => s,
            Err(e) => {
                tracing::warn!("serverinfo accept 失败: {}", e);
                continue;
            }
        };
        let stats = stats.clone();
        let hostname = hostname.clone();
        let pairing = pairing.clone();
        tokio::spawn(async move {
            // 读取请求(只关心第一行 METHOD PATH HTTP/1.1)
            let mut buf = [0u8; 1024];
            let n = match stream.read(&mut buf).await {
                Ok(n) if n > 0 => n,
                _ => return,
            };
            let req = String::from_utf8_lossy(&buf[..n]);
            let first_line = req.lines().next().unwrap_or("");
            let mut parts = first_line.split_whitespace();
            let method = parts.next().unwrap_or("");
            let raw_path = parts.next().unwrap_or("");
            let (path, _query) = match raw_path.split_once('?') {
                Some((p, q)) => (p, q),
                None => (raw_path, ""),
            };

            let (status, body) = if method == "GET" && path == "/serverinfo" {
                let (connected, uptime) = stats.lock().await.snapshot_info();
                let pubkey_b64 = pairing
                    .as_ref()
                    .map(|p| p.server_pubkey_b64())
                    .unwrap_or_default();
                // JSON 字段顺序稳定(便于客户端解析)
                let body = format!(
                    r#"{{"name":"MeowMic-Server","hostname":"{}","version":{},"state":"ONLINE","connected_clients":{},"max_clients":1,"uptime_secs":{},"server_pubkey_b64":"{}"}}"#,
                    hostname,
                    meowmic_net::PROTOCOL_VERSION,
                    connected,
                    uptime,
                    pubkey_b64,
                );
                ("200 OK", body)
            } else {
                (
                    "404 Not Found",
                    r#"{"error":"not found"}"#.to_string(),
                )
            };

            let response = format!(
                "HTTP/1.1 {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n{}",
                status,
                body.len(),
                body
            );
            let _ = stream.write_all(response.as_bytes()).await;
            let _ = stream.shutdown().await;
            // peer 仅用于调试日志,避免未使用警告
            let _ = peer;
        });
    }
}

/// HTTP /pairing 服务:监听 127.0.0.1:port,供 PC 控制台查询/管理配对状态
///
/// 端点:
/// - GET /pairing          返回 {pin, paired_clients:[{pubkey_b64,client_name,paired_at}]}
/// - POST /pairing/reset   清空所有已配对客户端并重新生成 PIN
/// - POST /pairing/unpair?pubkey=<b64>  移除指定公钥的客户端
async fn run_pairing_server(pairing: Option<Arc<PairingManager>>, addr: SocketAddr) {
    let listener = match TcpListener::bind(addr).await {
        Ok(l) => l,
        Err(e) => {
            tracing::error!("pairing HTTP 绑定 {} 失败: {}", addr, e);
            return;
        }
    };
    loop {
        let (mut stream, _peer) = match listener.accept().await {
            Ok(s) => s,
            Err(e) => {
                tracing::warn!("pairing accept 失败: {}", e);
                continue;
            }
        };
        let pairing = pairing.clone();
        tokio::spawn(async move {
            let mut buf = [0u8; 4096];
            let n = match stream.read(&mut buf).await {
                Ok(n) if n > 0 => n,
                _ => return,
            };
            let req = String::from_utf8_lossy(&buf[..n]);
            let first_line = req.lines().next().unwrap_or("");
            let mut parts = first_line.split_whitespace();
            let method = parts.next().unwrap_or("");
            let raw_path = parts.next().unwrap_or("");
            let (path, query) = match raw_path.split_once('?') {
                Some((p, q)) => (p, q),
                None => (raw_path, ""),
            };

            let (status, body) = if pairing.is_none() {
                (
                    "503 Service Unavailable",
                    r#"{"error":"pairing not enabled"}"#.to_string(),
                )
            } else if method == "GET" && path == "/pairing" {
                let pm = pairing.as_ref().unwrap();
                let pin = pm.current_pin().await;
                let clients = pm.paired_clients().await;
                let clients_json = clients
                    .iter()
                    .map(|c| {
                        format!(
                            r#"{{"pubkey_b64":"{}","client_name":"{}","paired_at":{}}}"#,
                            c.pubkey_b64, c.client_name, c.paired_at
                        )
                    })
                    .collect::<Vec<_>>()
                    .join(",");
                let body = format!(
                    r#"{{"pin":{},"paired_clients":[{}]}}"#,
                    pin.map(|p| format!("\"{}\"", p)).unwrap_or_else(|| "null".into()),
                    clients_json
                );
                ("200 OK", body)
            } else if method == "POST" && path == "/pairing/reset" {
                let pm = pairing.as_ref().unwrap();
                match pm.reset_paired_clients().await {
                    Ok(()) => {
                        // 重置后立即生成新 PIN
                        let pin = pm.ensure_pin().await;
                        let body = format!(r#"{{"ok":true,"new_pin":"{}"}}"#, pin);
                        ("200 OK", body)
                    }
                    Err(e) => {
                        let body = format!(r#"{{"ok":false,"error":"{}"}}"#, e);
                        ("500 Internal Server Error", body)
                    }
                }
            } else if method == "POST" && path == "/pairing/unpair" {
                let pm = pairing.as_ref().unwrap();
                // 解析 pubkey=<b64>
                let pubkey = query
                    .split('&')
                    .find_map(|kv| {
                        let (k, v) = kv.split_once('=')?;
                        if k == "pubkey" {
                            Some(v.to_string())
                        } else {
                            None
                        }
                    });
                match pubkey {
                    Some(pk) => match pm.unpair_client(&pk).await {
                        Ok(()) => ("200 OK", r#"{"ok":true}"#.to_string()),
                        Err(e) => {
                            let body = format!(r#"{{"ok":false,"error":"{}"}}"#, e);
                            ("500 Internal Server Error", body)
                        }
                    },
                    None => {
                        let body = r#"{"error":"missing pubkey param"}"#.to_string();
                        ("400 Bad Request", body)
                    }
                }
            } else {
                (
                    "404 Not Found",
                    r#"{"error":"not found"}"#.to_string(),
                )
            };

            let response = format!(
                "HTTP/1.1 {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                status,
                body.len(),
                body
            );
            let _ = stream.write_all(response.as_bytes()).await;
            let _ = stream.shutdown().await;
        });
    }
}

/// 配对状态文件所在目录
/// - Windows: %APPDATA%/meowmic
/// - Linux/macOS: ~/.config/meowmic
fn pairing_state_dir() -> PathBuf {
    #[cfg(windows)]
    {
        if let Ok(appdata) = std::env::var("APPDATA") {
            return PathBuf::from(appdata).join("meowmic");
        }
    }
    #[cfg(not(windows))]
    {
        if let Ok(home) = std::env::var("HOME") {
            return PathBuf::from(home).join(".config").join("meowmic");
        }
    }
    std::env::temp_dir().join("meowmic")
}

// 避免 PairedClient 未使用警告(后续事件处理可能用到)
#[allow(dead_code)]
fn _paired_client_marker() -> Option<PairedClient> {
    None
}

/// 获取本机主机名,用于 mDNS 服务实例标识
/// 失败时返回 "MeowMic-Host" 兜底
fn hostname_string() -> String {
    let name = gethostname::gethostname();
    let s = name.to_string_lossy();
    if s.is_empty() {
        "MeowMic-Host".to_string()
    } else {
        s.into_owned()
    }
}
