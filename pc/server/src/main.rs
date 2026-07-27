//! MeowMic 服务端主程序(P0 管线验证版)
//!
//! P0 实现策略(验证管线,非最终方案):
//! - 触摸:win32 SendInput 模拟鼠标(后续替换为自研 HID 驱动,绕过节流)
//! - 音频:cpal 直接播放(后续替换为自研 WDM 虚拟麦克风设备)
//! - 统计:周期打印延迟/丢包/速率

use std::net::SocketAddr;
use std::sync::Arc;

use anyhow::Result;
use clap::{Parser, Subcommand};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::Mutex;
use tracing::{info, warn};

use meowmic_audio::AudioConfig;
use meowmic_net::{NetError, PortLayout, Server, ServerEvent};

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

    let server = Server::new(ports);
    let (event_tx, mut event_rx) = tokio::sync::mpsc::channel::<ServerEvent>(256);

    // 启动统计打印
    let stats = Arc::new(Mutex::new(stats::Stats::default()));
    let stats_clone = stats.clone();
    tokio::spawn(async move {
        stats::print_loop(stats_clone).await;
    });

    // 启动 HTTP /stats 服务(监听 127.0.0.1:{base_port + 3})
    let stats_port = port + 3;
    let stats_addr: SocketAddr = format!("127.0.0.1:{}", stats_port).parse()?;
    let stats_clone2 = stats.clone();
    tokio::spawn(async move {
        run_stats_server(stats_clone2, stats_addr).await;
    });
    info!("stats HTTP 监听: http://{}/stats", stats_addr);

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
            ServerEvent::Error(e) => {
                warn!("服务端事件错误: {}", e);
            }
        }
    }

    server_handle.await??;
    Ok(())
}

/// HTTP /stats 服务:监听 127.0.0.1:port,GET /stats 返回 JSON 统计快照,其他路径 404
async fn run_stats_server(stats: Arc<Mutex<stats::Stats>>, addr: SocketAddr) {
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
            let path = parts.next().unwrap_or("");

            let (status, body) = if method == "GET" && path == "/stats" {
                let (conn, tps, afs, uptime) = stats.lock().await.snapshot_and_reset();
                let body = format!(
                    "{{\"connections\": {}, \"touches_per_sec\": {}, \"audio_frames_per_sec\": {}, \"uptime_secs\": {}}}",
                    conn, tps, afs, uptime
                );
                ("200 OK", body)
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
