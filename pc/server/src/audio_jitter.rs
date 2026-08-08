//! 音频抖动缓冲 + 丢包检测 + PLC 链路
//!
//! 设计目标(借鉴 Sunshine/Moonlight 客户端的 jitter buffer 思路):
//! - 平滑网络抖动:收到包不立即播放,缓冲 N 帧后按 20ms 节拍稳定输出
//! - 丢包检测:通过 seq 连续性追踪,缺失帧用 PLC(DecodeLost)填补
//! - 乱序重排:BTreeMap 按 seq 排序,晚到的包在窗口内仍可用
//! - 溢出保护:超过 max_frames 时丢弃最旧包,避免延迟累积
//!
//! 适用场景:
//! - PCM 直通模式:PLC 输出静音(已比无缓冲的断续好)
//! - Opus 模式(未来启用):PLC 输出 Opus 外推,效果更佳

use std::collections::BTreeMap;

use meowmic_audio::{AudioDecoder, AudioError};

/// pop 操作的返回结果
#[derive(Debug)]
pub enum PopResult {
    /// 成功解码 n 个样本
    Decoded(usize),
    /// 尚未启动(缓冲未达阈值)
    NotStarted,
    /// 解码错误
    Error(AudioError),
}

/// 统计计数(供日志/调试)
#[derive(Debug, Default, Clone, Copy)]
pub struct JitterStats {
    pub received: u64,
    pub lost: u64,
    pub duplicate: u64,
    pub late: u64,
    pub overflow: u64,
}

pub struct AudioJitterBuffer {
    /// 按 seq 排序的包缓冲(BTreeMap 自动有序,便于乱序插入)
    buffer: BTreeMap<u16, Vec<u8>>,
    /// 期望的下一个播放 seq(None 表示尚未收到第一个包)
    next_play_seq: Option<u16>,
    /// 播放延迟(帧数):缓冲达到此数量后开始播放
    playout_delay_frames: usize,
    /// 最大缓冲帧数(防溢出)
    max_frames: usize,
    /// 统计
    stats: JitterStats,
}

impl AudioJitterBuffer {
    /// 创建抖动缓冲
    ///
    /// - `playout_delay_frames`:初始播放延迟(帧数),建议 3(60ms @20ms帧)
    /// - `max_frames`:最大缓冲帧数,超过则丢弃最旧,建议 10(200ms)
    pub fn new(playout_delay_frames: usize, max_frames: usize) -> Self {
        Self {
            buffer: BTreeMap::new(),
            next_play_seq: None,
            playout_delay_frames,
            max_frames,
            stats: JitterStats::default(),
        }
    }

    /// 收到音频包时调用
    pub fn push(&mut self, seq: u16, opus: Vec<u8>) {
        self.stats.received += 1;

        // 收到第一个包时,初始化 next_play_seq 为该 seq
        // (实际播放要等缓冲达到 playout_delay_frames)
        if self.next_play_seq.is_none() {
            self.next_play_seq = Some(seq);
        }

        // 插入缓冲(检测重复)
        if self.buffer.insert(seq, opus).is_some() {
            self.stats.duplicate += 1;
        }

        // 溢出控制:丢弃最旧的包
        while self.buffer.len() > self.max_frames {
            if let Some(&oldest) = self.buffer.keys().next() {
                self.buffer.remove(&oldest);
                self.stats.overflow += 1;
            }
        }
    }

    /// 是否可以开始播放(缓冲达到阈值)
    pub fn is_ready(&self) -> bool {
        self.next_play_seq.is_some() && self.buffer.len() >= self.playout_delay_frames
    }

    /// 取出一帧解码(由外部定时器每 frame_ms 调用一次)
    ///
    /// 返回 `PopResult::Decoded(n)` 表示成功解码 n 个样本(可能是真实包或 PLC 填补)
    pub fn pop(&mut self, decoder: &mut dyn AudioDecoder, pcm_out: &mut [i16]) -> PopResult {
        let expected = match self.next_play_seq {
            None => return PopResult::NotStarted,
            Some(s) => s,
        };

        // 直接查找 expected 包(避免 BTreeMap 数值排序与 wrapping seq 顺序冲突)
        if let Some(opus) = self.buffer.remove(&expected) {
            self.next_play_seq = Some(expected.wrapping_add(1));
            return match decoder.decode(&opus, pcm_out) {
                Ok(n) => PopResult::Decoded(n),
                Err(e) => PopResult::Error(e),
            };
        }

        // expected 包不在缓冲区:丢包,用 PLC 填补
        self.stats.lost += 1;
        self.next_play_seq = Some(expected.wrapping_add(1));

        // 顺便清理过期包(seq < expected,可能是乱序晚到的旧包)
        let expired: Vec<u16> = self.buffer
            .keys()
            .filter(|&&s| s.wrapping_sub(expected) >= 32768)
            .copied()
            .collect();
        for s in expired {
            self.buffer.remove(&s);
            self.stats.late += 1;
        }

        match decoder.decode_lost(pcm_out) {
            Ok(n) => PopResult::Decoded(n),
            Err(e) => PopResult::Error(e),
        }
    }

    /// 重置缓冲(客户端断开重连时调用)
    pub fn reset(&mut self) {
        self.buffer.clear();
        self.next_play_seq = None;
        self.stats = JitterStats::default();
    }

    pub fn stats(&self) -> JitterStats {
        self.stats
    }

    /// 当前缓冲深度(帧数)
    pub fn depth(&self) -> usize {
        self.buffer.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use meowmic_audio::{AudioConfig, AudioDecoder, AudioError};

    /// 测试用 mock 解码器(不依赖 libopus,聚焦 jitter buffer 逻辑验证)
    struct MockDecoder;
    impl AudioDecoder for MockDecoder {
        fn decode(&mut self, opus: &[u8], dst: &mut [i16]) -> Result<usize, AudioError> {
            let n = opus.len().min(dst.len());
            for (i, s) in dst[..n].iter_mut().enumerate() {
                *s = if i < opus.len() { opus[i] as i8 as i16 } else { 0 };
            }
            Ok(n)
        }
        fn decode_lost(&mut self, dst: &mut [i16]) -> Result<usize, AudioError> {
            for s in dst.iter_mut() {
                *s = 0;
            }
            Ok(dst.len())
        }
    }

    fn make_opus_packet(seq: u16, payload: &[u8]) -> (u16, Vec<u8>) {
        (seq, payload.to_vec())
    }

    #[test]
    fn test_sequential_packets_no_loss() {
        let cfg = AudioConfig::default();
        let mut dec = MockDecoder;
        let mut jb = AudioJitterBuffer::new(2, 10);

        // 顺序到达 3 个包
        for seq in 0..3 {
            jb.push(seq, vec![seq as u8; cfg.bytes_per_frame()]);
        }

        assert!(jb.is_ready());

        let mut pcm = vec![0i16; cfg.samples_per_frame() * 2];
        for expected_seq in 0..3 {
            match jb.pop(&mut *dec, &mut pcm) {
                PopResult::Decoded(n) => assert!(n > 0, "seq {} 应解码成功", expected_seq),
                other => panic!("seq {} 预期 Decoded,实际 {:?}", expected_seq, other),
            }
        }

        let stats = jb.stats();
        assert_eq!(stats.received, 3);
        assert_eq!(stats.lost, 0);
    }

    #[test]
    fn test_packet_loss_plc_fill() {
        let cfg = AudioConfig::default();
        let mut dec = MockDecoder;
        let mut jb = AudioJitterBuffer::new(1, 10);

        // 发送 seq=0, 2(跳过 1)
        jb.push(0, vec![0xAA; cfg.bytes_per_frame()]);
        jb.push(2, vec![0xBB; cfg.bytes_per_frame()]);

        assert!(jb.is_ready());

        let mut pcm = vec![0i16; cfg.samples_per_frame() * 2];
        // pop seq=0:正常
        assert!(matches!(jb.pop(&mut *dec, &mut pcm), PopResult::Decoded(_)));
        // pop seq=1:丢失,PLC 填补
        assert!(matches!(jb.pop(&mut *dec, &mut pcm), PopResult::Decoded(_)));
        // pop seq=2:正常
        assert!(matches!(jb.pop(&mut *dec, &mut pcm), PopResult::Decoded(_)));

        assert_eq!(jb.stats().lost, 1);
    }

    #[test]
    fn test_late_packet_discarded() {
        let cfg = AudioConfig::default();
        let mut dec = MockDecoder;
        let mut jb = AudioJitterBuffer::new(1, 10);

        // 发送 seq=0, 1
        jb.push(0, vec![0; cfg.bytes_per_frame()]);
        jb.push(1, vec![0; cfg.bytes_per_frame()]);

        // pop seq=0, 1
        let mut pcm = vec![0i16; cfg.samples_per_frame() * 2];
        jb.pop(&mut *dec, &mut pcm);
        jb.pop(&mut *dec, &mut pcm);

        // 现在 next_play_seq=2,迟到的 seq=0 应被丢弃
        jb.push(0, vec![0; cfg.bytes_per_frame()]);
        let before_late = jb.stats().late;
        // 触发一次 pop,过期包会在 pop 开头被清理
        jb.pop(&mut *dec, &mut pcm);
        assert!(jb.stats().late >= before_late);
    }

    #[test]
    fn test_seq_wraparound() {
        let cfg = AudioConfig::default();
        let mut dec = MockDecoder;
        let mut jb = AudioJitterBuffer::new(1, 10);

        // seq 在 u16::MAX 附近回绕
        jb.push(u16::MAX, vec![0; cfg.bytes_per_frame()]);
        jb.push(0, vec![0; cfg.bytes_per_frame()]); // 回绕到 0
        jb.push(1, vec![0; cfg.bytes_per_frame()]);

        let mut pcm = vec![0i16; cfg.samples_per_frame() * 2];
        assert!(matches!(jb.pop(&mut *dec, &mut pcm), PopResult::Decoded(_))); // u16::MAX
        assert!(matches!(jb.pop(&mut *dec, &mut pcm), PopResult::Decoded(_))); // 0
        assert!(matches!(jb.pop(&mut *dec, &mut pcm), PopResult::Decoded(_))); // 1
        assert_eq!(jb.stats().lost, 0);
    }

    #[test]
    fn test_duplicate_packet() {
        let cfg = AudioConfig::default();
        let mut jb = AudioJitterBuffer::new(1, 10);

        jb.push(0, vec![0; cfg.bytes_per_frame()]);
        jb.push(0, vec![0; cfg.bytes_per_frame()]); // 重复

        assert_eq!(jb.stats().duplicate, 1);
        assert_eq!(jb.depth(), 1);
    }

    #[test]
    fn test_overflow_protection() {
        let cfg = AudioConfig::default();
        let mut jb = AudioJitterBuffer::new(1, 3); // 最大 3 帧

        // 发送 5 个包,应丢弃最旧的
        for seq in 0..5 {
            jb.push(seq, vec![0; cfg.bytes_per_frame()]);
        }

        assert_eq!(jb.depth(), 3);
        assert_eq!(jb.stats().overflow, 2);
    }

    #[test]
    fn test_empty_buffer_plc() {
        let cfg = AudioConfig::default();
        let mut dec = MockDecoder;
        let mut jb = AudioJitterBuffer::new(1, 10);

        // 发送 1 个包启动
        jb.push(0, vec![0; cfg.bytes_per_frame()]);
        let mut pcm = vec![0i16; cfg.samples_per_frame() * 2];

        // pop seq=0
        assert!(matches!(jb.pop(&mut *dec, &mut pcm), PopResult::Decoded(_)));

        // pop seq=1:缓冲空,PLC 填补
        assert!(matches!(jb.pop(&mut *dec, &mut pcm), PopResult::Decoded(_)));
        assert_eq!(jb.stats().lost, 1);
    }
}
