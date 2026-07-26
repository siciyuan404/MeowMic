//! 时钟同步
//!
//! 策略:周期性 SyncReq/SyncResp,EWMA 平滑偏移估计
//! 参考 NTP 算法但精简:不维护多轮次过滤,只做指数平滑

use std::sync::Arc;
use std::time::Duration;
use tokio::sync::RwLock;
use tokio::time;

use meowmic_protocol::{ClockOffset, ControlMessage, monotonic_ns};

/// 时钟同步器共享状态
#[derive(Debug, Default, Clone)]
pub struct ClockSynchronizer {
    inner: Arc<RwLock<ClockState>>,
}

#[derive(Debug, Default, Clone, Copy)]
pub struct ClockState {
    pub offset: ClockOffset,
    /// 最近一次成功同步时间(单调时钟)
    pub last_sync_ns: u64,
    /// 同步成功次数
    pub sync_count: u32,
}

impl ClockSynchronizer {
    pub fn new() -> Self {
        Self::default()
    }

    /// 读取当前偏移估计
    pub async fn offset(&self) -> ClockOffset {
        self.inner.read().await.offset
    }

    pub async fn state(&self) -> ClockState {
        *self.inner.read().await
    }

    /// 客户端:处理收到的 SyncResp,更新偏移
    pub async fn handle_sync_resp(
        &self,
        client_ts_ns: u64,
        server_recv_ts_ns: u64,
        server_send_ts_ns: u64,
    ) {
        let client_recv_ts_ns = monotonic_ns();
        let new_offset = ClockOffset::from_sync(
            client_ts_ns,
            server_recv_ts_ns,
            server_send_ts_ns,
            client_recv_ts_ns,
        );

        let mut state = self.inner.write().await;
        // EWMA 平滑(alpha = 0.3,新值权重 30%)
        if state.sync_count > 0 {
            let old = state.offset;
            let blended = ClockOffset {
                offset_ns: (old.offset_ns as f64 * 0.7 + new_offset.offset_ns as f64 * 0.3) as i64,
                rtt_ns: (old.rtt_ns as f64 * 0.7 + new_offset.rtt_ns as f64 * 0.3) as u64,
            };
            state.offset = blended;
        } else {
            state.offset = new_offset;
        }
        state.last_sync_ns = client_recv_ts_ns;
        state.sync_count += 1;

        tracing::debug!(
            "时钟同步: offset={}ms rtt={}ms (raw offset={}ms rtt={}ms, sync_count={})",
            state.offset.offset_ns as f64 / 1e6,
            state.offset.rtt_ns as f64 / 1e6,
            new_offset.offset_ns as f64 / 1e6,
            new_offset.rtt_ns as f64 / 1e6,
            state.sync_count
        );
    }

    /// 服务端:响应 SyncReq
    pub fn build_sync_resp(req: &ControlMessage) -> Option<ControlMessage> {
        match req {
            ControlMessage::SyncReq { client_ts_ns } => {
                let now = monotonic_ns();
                Some(ControlMessage::SyncResp {
                    client_ts_ns: *client_ts_ns,
                    server_recv_ts_ns: now,
                    server_send_ts_ns: monotonic_ns(),
                })
            }
            _ => None,
        }
    }
}

/// 客户端:周期性发送 SyncReq 的循环
pub async fn run_client_sync_loop(
    _sync: ClockSynchronizer,
    send_fn: impl Fn(ControlMessage),
    interval: Duration,
) {
    let mut ticker = time::interval(interval);
    ticker.set_missed_tick_behavior(time::MissedTickBehavior::Delay);
    loop {
        ticker.tick().await;
        let ts = monotonic_ns();
        send_fn(ControlMessage::SyncReq { client_ts_ns: ts });
        tracing::trace!("发送 SyncReq ts_ns={}", ts);
    }
}
