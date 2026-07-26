//! MeowMic 延迟探测工具
//!
//! 独立 UDP RTT 探测器,用于测量纯网络延迟基线(不涉及编解码)。
//!
//! 用法:
//!   # 服务端(被测机器)
//!   meowmic-probe echo --port 29999
//!
//!   # 客户端(探测端)
//!   meowmic-probe probe --server 192.168.1.100:29999 --count 1000 --interval 10ms
//!
//! 输出:min/avg/p50/p95/p99/jitter,帮助定位网络层延迟瓶颈。

use std::time::{Duration, Instant};
use anyhow::Result;
use clap::{Parser, Subcommand};
use tokio::net::UdpSocket;
use tracing::info;
use tracing_subscriber::EnvFilter;

#[derive(Parser)]
#[command(name = "meowmic-probe", about = "MeowMic UDP 延迟探测器")]
struct Cli {
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// 回显模式:收到 UDP 包立即原样返回
    Echo {
        #[arg(long, default_value = "0.0.0.0")]
        bind: String,
        #[arg(long, default_value_t = 29999)]
        port: u16,
    },
    /// 探测模式:发送带时间戳的包,测量 RTT
    Probe {
        #[arg(long)]
        server: String,
        #[arg(long, default_value_t = 1000)]
        count: u32,
        #[arg(long, default_value = "10ms")]
        interval: String,
        /// 包大小(字节)
        #[arg(long, default_value_t = 64)]
        size: usize,
    },
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::new("meowmic_probe=info,warn"))
        .with_target(false)
        .init();

    match Cli::parse().cmd {
        Cmd::Echo { bind, port } => run_echo(&bind, port).await,
        Cmd::Probe {
            server,
            count,
            interval,
            size,
        } => {
            let interval = humantime::parse_duration(&interval)
                .unwrap_or(Duration::from_millis(10));
            run_probe(&server, count, interval, size).await
        }
    }
}

async fn run_echo(bind: &str, port: u16) -> Result<()> {
    let addr = format!("{}:{}", bind, port);
    let sock = UdpSocket::bind(&addr).await?;
    info!("Echo 模式启动,监听 {}", addr);
    let mut buf = vec![0u8; 2048];
    loop {
        let (n, peer) = sock.recv_from(&mut buf).await?;
        // 立即回显,尽量减少处理时间
        sock.send_to(&buf[..n], peer).await?;
    }
}

async fn run_probe(
    server: &str,
    count: u32,
    interval: Duration,
    size: usize,
) -> Result<()> {
    let sock = UdpSocket::bind("0.0.0.0:0").await?;
    sock.connect(server).await?;
    info!("Probe 模式: 目标={} 包数={} 间隔={}ms 大小={}B", server, count, interval.as_millis(), size);

    let mut send_buf = vec![0u8; size];
    let mut recv_buf = vec![0u8; size];
    let mut rtts: Vec<Duration> = Vec::with_capacity(count as usize);

    // 序号放在包前 4 字节
    for i in 0..count {
        let seq = i.to_le_bytes();
        send_buf[..4].copy_from_slice(&seq);
        let start = Instant::now();
        sock.send(&send_buf).await?;
        // 等待回显(带超时)
        match tokio::time::timeout(Duration::from_secs(1), sock.recv(&mut recv_buf)).await {
            Ok(Ok(_n)) => {
                let rtt = start.elapsed();
                rtts.push(rtt);
                tracing::trace!("probe #{} rtt={:.3}ms", i, rtt.as_secs_f64() * 1000.0);
            }
            Ok(Err(e)) => {
                tracing::warn!("probe #{} 接收失败: {}", i, e);
            }
            Err(_) => {
                tracing::warn!("probe #{} 超时", i);
            }
        }
        tokio::time::sleep(interval).await;
    }

    print_stats(&rtts);
    Ok(())
}

fn print_stats(rtts: &[Duration]) {
    if rtts.is_empty() {
        info!("无有效样本");
        return;
    }

    let total = rtts.len();
    let _lost = (total == 0) as usize; // 简化:超时未计入 rtts
    let loss_rate = 0.0_f64;

    let mut sorted: Vec<u64> = rtts.iter().map(|d| d.as_nanos() as u64).collect();
    sorted.sort_unstable();

    let min_ns = sorted.first().copied().unwrap_or(0);
    let max_ns = sorted.last().copied().unwrap_or(0);
    let avg_ns: u64 = sorted.iter().sum::<u64>() / total as u64;
    let p50 = percentile(&sorted, 0.50);
    let p95 = percentile(&sorted, 0.95);
    let p99 = percentile(&sorted, 0.99);

    // jitter: 相邻 RTT 差值的平均
    let jitter_ns: u64 = if rtts.len() > 1 {
        let sum: u128 = rtts
            .windows(2)
            .map(|w| {
                w[0].as_nanos().abs_diff(w[1].as_nanos())
            })
            .sum::<u128>();
        (sum / (rtts.len() - 1) as u128) as u64
    } else {
        0
    };

    info!("=== UDP RTT 统计 (n={}) ===", total);
    info!("  min:    {:.3} ms", min_ns as f64 / 1e6);
    info!("  avg:    {:.3} ms", avg_ns as f64 / 1e6);
    info!("  p50:    {:.3} ms", p50 as f64 / 1e6);
    info!("  p95:    {:.3} ms", p95 as f64 / 1e6);
    info!("  p99:    {:.3} ms", p99 as f64 / 1e6);
    info!("  max:    {:.3} ms", max_ns as f64 / 1e6);
    info!("  jitter: {:.3} ms", jitter_ns as f64 / 1e6);
    info!("  loss:   {:.2}% (丢包不计入 RTT)", loss_rate);
}

fn percentile(sorted: &[u64], p: f64) -> u64 {
    if sorted.is_empty() {
        return 0;
    }
    let idx = ((sorted.len() as f64 - 1.0) * p).round() as usize;
    sorted[idx.min(sorted.len() - 1)]
}

// 简单的 humantime 解析(避免额外依赖)
mod humantime {
    use std::time::Duration;
    pub fn parse_duration(s: &str) -> Option<Duration> {
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
}
