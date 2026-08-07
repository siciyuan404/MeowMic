//! MeowMic 集成自测工具
//!
//! 同进程内启动 Server + mock Client,验证握手 / Touch / Audio 三条管线,
//! 并测量端到端延迟(同进程共用 monotonic_ns epoch,无跨时钟偏差)。
//!
//! 用法:
//!   meowmic-itest                # 默认 1000 包,间隔 1ms
//!   meowmic-itest --count 5000 --interval 200us

use std::sync::Arc;
use std::time::Duration;

use anyhow::Result;
use clap::Parser;
use meowmic_audio::AudioConfig;
use meowmic_net::{Client, ClientEvent, PortLayout, Server, ServerEvent};
use meowmic_protocol::TouchEventType;
use tokio::sync::Mutex;
use tracing::{info, warn};

#[derive(Parser)]
#[command(name = "meowmic-itest", about = "MeowMic 集成自测:同进程 Server+Client 管线验证与延迟测量")]
struct Cli {
    /// 基础端口(默认 29000,避开正式服务端 28900)
    #[arg(long, default_value_t = 29000)]
    port: u16,
    /// 测试包数
    #[arg(long, default_value_t = 1000)]
    count: u32,
    /// 包间隔(humantime,如 1ms / 200us)
    #[arg(long, default_value = "1ms")]
    interval: String,
    /// 是否测试音频管线
    #[arg(long, default_value_t = true)]
    audio: bool,
    /// 是否测试触控管线
    #[arg(long, default_value_t = true)]
    touch: bool,
}

#[derive(Debug, Default)]
struct LatencyCollector {
    touch_lat: Vec<u64>,
    audio_lat: Vec<u64>,
    hello_ack: bool,
    disconnect: bool,
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "meowmic_itest=info,warn".into()),
        )
        .with_target(false)
        .init();

    let cli = Cli::parse();
    let interval = parse_duration(&cli.interval).unwrap_or(Duration::from_millis(1));
    let ports = PortLayout::from_base(cli.port);

    info!(
        "=== MeowMic 集成自测 === port={} count={} interval={:?} touch={} audio={}",
        cli.port, cli.count, interval, cli.touch, cli.audio
    );

    // 启动 Server
    let server = Server::new(ports);
    let (server_event_tx, mut server_event_rx) = tokio::sync::mpsc::channel::<ServerEvent>(1024);
    let bind = "127.0.0.1".to_string();
    // video UDP socket(供新版 Server::run 签名使用)
    let video_sock = std::sync::Arc::new(tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap());
    let active_clients: std::sync::Arc<tokio::sync::RwLock<std::collections::HashSet<String>>> =
        std::sync::Arc::new(tokio::sync::RwLock::new(std::collections::HashSet::new()));
    tokio::spawn(async move {
        if let Err(e) = server.run(&bind, server_event_tx, active_clients, video_sock).await {
            warn!("服务端退出: {}", e);
        }
    });

    // 给服务端一点时间监听
    tokio::time::sleep(Duration::from_millis(100)).await;

    // 启动 Client
    let (client_event_tx, mut client_event_rx) = tokio::sync::mpsc::channel::<ClientEvent>(256);
    let control_addr = format!("127.0.0.1:{}", ports.control);
    let client = Client::connect(&control_addr, "itest-mock", client_event_tx).await?;

    // 等待 HelloAck
    let mut hello_received = false;
    let deadline = tokio::time::Instant::now() + Duration::from_secs(2);
    while tokio::time::Instant::now() < deadline {
        match tokio::time::timeout(Duration::from_millis(100), client_event_rx.recv()).await {
            Ok(Some(ClientEvent::HelloAck { .. })) => {
                hello_received = true;
                info!("✓ 握手成功");
                break;
            }
            Ok(_) => continue,
            Err(_) => continue,
        }
    }
    if !hello_received {
        anyhow::bail!("握手超时");
    }

    // 收集器
    let collector = Arc::new(Mutex::new(LatencyCollector::default()));
    let c1 = collector.clone();
    tokio::spawn(async move {
        while let Some(ev) = server_event_rx.recv().await {
            let mut c = c1.lock().await;
            match ev {
                ServerEvent::Touch { ts_ns, .. } => {
                    let recv_ns = meowmic_protocol::monotonic_ns() as u32;
                    c.touch_lat.push(recv_ns.wrapping_sub(ts_ns) as u64);
                }
                ServerEvent::Audio { ts_ns, .. } => {
                    let recv_ns = meowmic_protocol::monotonic_ns() as u32;
                    c.audio_lat.push(recv_ns.wrapping_sub(ts_ns) as u64);
                }
                _ => {}
            }
        }
    });

    // 时钟同步事件接收(忽略,只用于驱动)
    tokio::spawn(async move {
        while client_event_rx.recv().await.is_some() {}
    });

    // 准备音频编码器
    let audio_cfg = AudioConfig::default();
    let mut encoder = meowmic_audio::make_encoder(&audio_cfg);
    let samples_per_frame = audio_cfg.samples_per_frame();
    let mut pcm: Vec<i16> = (0..samples_per_frame)
        .map(|i| {
            let t = i as f32 / audio_cfg.sample_rate as f32;
            ((t * 2.0 * std::f32::consts::PI * 1000.0).sin() * 0.3 * i16::MAX as f32) as i16
        })
        .collect();
    let mut opus_buf = vec![0u8; 8192];

    // === Touch 管线测试 ===
    if cli.touch {
        info!("--- Touch 管线测试: {} 包 ---", cli.count);
        let t0 = tokio::time::Instant::now();
        for i in 0..cli.count {
            let dx = if i % 2 == 0 { 1.0 } else { -1.0 };
            let dy = 0.5;
            client
                .send_touch(TouchEventType::Move, dx, dy)
                .await
                .map_err(|e| anyhow::anyhow!("touch send: {}", e))?;
            tokio::time::sleep(interval).await;
        }
        let elapsed = t0.elapsed();
        info!("Touch 发送耗时: {:?}", elapsed);
        // 等待服务端收完
        tokio::time::sleep(Duration::from_millis(200)).await;
        let c = collector.lock().await;
        info!("Touch 收包: {} / {}", c.touch_lat.len(), cli.count);
        print_latency("Touch", &c.touch_lat);
    }

    // === Audio 管线测试 ===
    if cli.audio {
        info!("--- Audio 管线测试: {} 帧 ---", cli.count);
        let t0 = tokio::time::Instant::now();
        for i in 0..cli.count {
            // 微调 PCM 避免编码器走入静音检测
            let phase = i as f32 * 0.01;
            for (j, s) in pcm.iter_mut().enumerate() {
                let t = (j as f32 + phase * 100.0) / audio_cfg.sample_rate as f32;
                *s = ((t * 2.0 * std::f32::consts::PI * 1000.0).sin() * 0.3 * i16::MAX as f32)
                    as i16;
            }
            let n = encoder
                .encode(&pcm, &mut opus_buf)
                .map_err(|e| anyhow::anyhow!("encode: {}", e))?;
            client
                .send_audio(&opus_buf[..n])
                .await
                .map_err(|e| anyhow::anyhow!("audio send: {}", e))?;
            tokio::time::sleep(interval).await;
        }
        let elapsed = t0.elapsed();
        info!("Audio 发送耗时: {:?}", elapsed);
        tokio::time::sleep(Duration::from_millis(200)).await;
        let c = collector.lock().await;
        info!("Audio 收包: {} / {}", c.audio_lat.len(), cli.count);
        print_latency("Audio", &c.audio_lat);
    }

    // 优雅退出
    let _ = client.disconnect().await;
    info!("=== 自测完成 ===");
    Ok(())
}

fn print_latency(label: &str, lats: &[u64]) {
    if lats.is_empty() {
        warn!("{}: 无样本", label);
        return;
    }
    let mut sorted: Vec<u64> = lats.to_vec();
    sorted.sort_unstable();
    let n = sorted.len();
    let min = sorted[0];
    let max = sorted[n - 1];
    let avg: u64 = sorted.iter().sum::<u64>() / n as u64;
    let p50 = percentile(&sorted, 0.50);
    let p95 = percentile(&sorted, 0.95);
    let p99 = percentile(&sorted, 0.99);
    let jitter: u64 = if n > 1 {
        let sum: u64 = sorted
            .windows(2)
            .map(|w| w[0].abs_diff(w[1]))
            .sum::<u64>();
        sum / (n - 1) as u64
    } else {
        0
    };
    info!(
        "{} 延迟(n={}): min={:.3} avg={:.3} p50={:.3} p95={:.3} p99={:.3} max={:.3} jitter={:.3} ms",
        label,
        n,
        min as f64 / 1e6,
        avg as f64 / 1e6,
        p50 as f64 / 1e6,
        p95 as f64 / 1e6,
        p99 as f64 / 1e6,
        max as f64 / 1e6,
        jitter as f64 / 1e6,
    );
}

fn percentile(sorted: &[u64], p: f64) -> u64 {
    if sorted.is_empty() {
        return 0;
    }
    let idx = ((sorted.len() as f64 - 1.0) * p).round() as usize;
    sorted[idx.min(sorted.len() - 1)]
}

fn parse_duration(s: &str) -> Option<Duration> {
    let s = s.trim();
    if let Some(n) = s.strip_suffix("ms") {
        n.parse::<u64>().ok().map(Duration::from_millis)
    } else if let Some(n) = s.strip_suffix("us") {
        n.parse::<u64>().ok().map(Duration::from_micros)
    } else if let Some(n) = s.strip_suffix("s") {
        n.parse::<u64>().ok().map(Duration::from_secs)
    } else {
        s.parse::<u64>().ok().map(Duration::from_millis)
    }
}
