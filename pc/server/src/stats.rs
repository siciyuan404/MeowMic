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
        s.last_print = Some(Instant::now());
    }
}
