//! 统计信息收集与打印

use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::Mutex;
use tracing::info;

#[derive(Debug)]
pub struct Stats {
    pub touch_count: u64,
    pub audio_count: u64,
    /// 延迟样本(纳秒)
    pub touch_latency_ns: u64,
    pub audio_latency_ns: u64,
    pub touch_latency_max_ns: u64,
    pub audio_latency_max_ns: u64,
    pub last_print: Option<Instant>,
    pub window_start: Instant,
    /// 当前连接数(HTTP /stats 用)
    pub current_connections: u32,
    /// server 启动时刻(用于 uptime)
    pub started_at: Instant,
}

impl Default for Stats {
    fn default() -> Self {
        Self {
            touch_count: 0,
            audio_count: 0,
            touch_latency_ns: 0,
            audio_latency_ns: 0,
            touch_latency_max_ns: 0,
            audio_latency_max_ns: 0,
            last_print: None,
            window_start: Instant::now(),
            current_connections: 0,
            started_at: Instant::now(),
        }
    }
}

impl Stats {
    pub fn record_touch(&mut self, latency_ns: u32) {
        self.touch_count += 1;
        self.touch_latency_ns += latency_ns as u64;
        if latency_ns as u64 > self.touch_latency_max_ns {
            self.touch_latency_max_ns = latency_ns as u64;
        }
    }

    pub fn record_audio(&mut self, latency_ns: u32) {
        self.audio_count += 1;
        self.audio_latency_ns += latency_ns as u64;
        if latency_ns as u64 > self.audio_latency_max_ns {
            self.audio_latency_max_ns = latency_ns as u64;
        }
    }

    /// 客户端连接 +1
    pub fn record_connect(&mut self) {
        self.current_connections = self.current_connections.saturating_add(1);
    }

    /// 客户端断开 -1
    pub fn record_disconnect(&mut self) {
        self.current_connections = self.current_connections.saturating_sub(1);
    }

    /// 快照当前统计并重置窗口计数
    /// 返回 (connections, touches_per_sec, audio_frames_per_sec, uptime_secs)
    pub fn snapshot_and_reset(&mut self) -> (u32, u32, u32, u64) {
        let elapsed = self.window_start.elapsed().as_secs_f64().max(0.001);
        let touches_per_sec = (self.touch_count as f64 / elapsed) as u32;
        let audio_frames_per_sec = (self.audio_count as f64 / elapsed) as u32;
        let uptime_secs = self.started_at.elapsed().as_secs();

        // 重置窗口
        self.touch_count = 0;
        self.audio_count = 0;
        self.touch_latency_ns = 0;
        self.audio_latency_ns = 0;
        self.touch_latency_max_ns = 0;
        self.audio_latency_max_ns = 0;
        self.window_start = Instant::now();
        self.last_print = Some(Instant::now());

        (
            self.current_connections,
            touches_per_sec,
            audio_frames_per_sec,
            uptime_secs,
        )
    }
}

pub async fn print_loop(stats: Arc<Mutex<Stats>>) {
    loop {
        tokio::time::sleep(Duration::from_secs(5)).await;
        let mut s = stats.lock().await;
        let elapsed = s.last_print.map(|t| t.elapsed()).unwrap_or_else(|| s.window_start.elapsed());
        let touch_rate = s.touch_count as f64 / elapsed.as_secs_f64().max(0.001);
        let audio_rate = s.audio_count as f64 / elapsed.as_secs_f64().max(0.001);

        let touch_avg_ms = if s.touch_count > 0 {
            s.touch_latency_ns as f64 / s.touch_count as f64 / 1e6
        } else {
            0.0
        };
        let audio_avg_ms = if s.audio_count > 0 {
            s.audio_latency_ns as f64 / s.audio_count as f64 / 1e6
        } else {
            0.0
        };

        info!(
            "统计: touch={} ({:.1}/s avg={:.2}ms max={:.2}ms) | audio={} ({:.1}/s avg={:.2}ms max={:.2}ms)",
            s.touch_count,
            touch_rate,
            touch_avg_ms,
            s.touch_latency_max_ns as f64 / 1e6,
            s.audio_count,
            audio_rate,
            audio_avg_ms,
            s.audio_latency_max_ns as f64 / 1e6,
        );

        // 重置窗口
        s.touch_count = 0;
        s.audio_count = 0;
        s.touch_latency_ns = 0;
        s.audio_latency_ns = 0;
        s.touch_latency_max_ns = 0;
        s.audio_latency_max_ns = 0;
        s.window_start = Instant::now();
        s.last_print = Some(Instant::now());
    }
}
