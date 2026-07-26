//! 音频编解码抽象
//!
//! 设计:
//! - trait 抽象,可在 Opus / PCM 直通间切换
//! - Opus 20ms 帧 + inband FEC,极致低延迟优先
//! - P0 可用 pcm-passthrough feature 跑通管线(无需 libopus)

use thiserror::Error;

#[derive(Debug, Error)]
pub enum AudioError {
    #[error("Opus 错误: {0}")]
    #[cfg(feature = "opus-codec")]
    Opus(#[from] opus::Error),
    #[error("缓冲区大小不匹配: 期望 {expected}, 实际 {got}")]
    BufferSize { expected: usize, got: usize },
    #[error("PCM 直通模式不支持此操作")]
    PcmPassthroughUnsupported,
    #[error("无效采样率: {0}")]
    InvalidSampleRate(u32),
}

/// 音频配置
#[derive(Debug, Clone, Copy)]
pub struct AudioConfig {
    pub sample_rate: u32,
    pub channels: u8,
    /// 每帧时长(毫秒),低延迟建议 10 或 20
    pub frame_ms: u16,
    /// 是否启用 inband FEC(前向纠错,牺牲码率换抗丢包)
    pub inband_fec: bool,
    /// 是否启用 DTX(静音检测,会引入抖动,低延迟场景关闭)
    pub dtx: bool,
}

impl Default for AudioConfig {
    fn default() -> Self {
        Self {
            sample_rate: 48000,
            channels: 1,
            frame_ms: 20,
            inband_fec: true,
            dtx: false,
        }
    }
}

impl AudioConfig {
    pub fn samples_per_frame(&self) -> usize {
        (self.sample_rate as usize * self.frame_ms as usize) / 1000
    }
    pub fn bytes_per_frame(&self) -> usize {
        self.samples_per_frame() * (self.channels as usize) * 2 // i16 PCM
    }
}

/// 编码器接口
pub trait AudioEncoder: Send {
    /// 编码一帧 PCM (i16 LE) → Opus 字节
    fn encode(&mut self, pcm: &[i16], dst: &mut [u8]) -> Result<usize, AudioError>;
}

/// 解码器接口
pub trait AudioDecoder: Send {
    /// 解码 Opus 字节 → PCM (i16 LE),返回写入样本数
    fn decode(&mut self, opus: &[u8], dst: &mut [i16]) -> Result<usize, AudioError>;
    /// PLC(丢包 concealment),用前一帧外推
    fn decode_lost(&mut self, dst: &mut [i16]) -> Result<usize, AudioError>;
}

pub fn make_encoder(cfg: &AudioConfig) -> Box<dyn AudioEncoder> {
    #[cfg(feature = "opus-codec")]
    {
        return Box::new(OpusEncoder::new(cfg).expect("Opus encoder init"));
    }
    #[cfg(not(feature = "opus-codec"))]
    {
        let _ = cfg;
        return Box::new(PcmPassthroughEncoder);
    }
}

pub fn make_decoder(cfg: &AudioConfig) -> Box<dyn AudioDecoder> {
    #[cfg(feature = "opus-codec")]
    {
        return Box::new(OpusDecoder::new(cfg).expect("Opus decoder init"));
    }
    #[cfg(not(feature = "opus-codec"))]
    {
        let _ = cfg;
        return Box::new(PcmPassthroughDecoder);
    }
}

// ============================================================================
// Opus 实现
// ============================================================================

#[cfg(feature = "opus-codec")]
pub struct OpusEncoder {
    enc: opus::Encoder,
    cfg: AudioConfig,
}

#[cfg(feature = "opus-codec")]
impl OpusEncoder {
    pub fn new(cfg: &AudioConfig) -> Result<Self, AudioError> {
        let mut enc = opus::Encoder::new(
            cfg.sample_rate,
            match cfg.channels {
                1 => opus::Channels::Mono,
                2 => opus::Channels::Stereo,
                _ => return Err(AudioError::InvalidSampleRate(cfg.channels as u32)),
            },
            opus::Application::LowDelay,
        )?;
        // 极致低延迟参数
        enc.set_bitrate(opus::Bitrate::Bits(32000))?;
        enc.set_vbr(false)?; // CBR,避免码率波动引入抖动
        enc.set_inband_fec(cfg.inband_fec)?;
        enc.set_packet_loss_perc(5)?; // 假设 5% 丢包,FEC 据此调强度
        enc.set_dtx(cfg.dtx)?;
        // 设置复杂度(0=最快,10=最高质量,低延迟选 1-3)
        enc.set_complexity(3)?;
        Ok(Self {
            enc,
            cfg: *cfg,
        })
    }
}

#[cfg(feature = "opus-codec")]
impl AudioEncoder for OpusEncoder {
    fn encode(&mut self, pcm: &[i16], dst: &mut [u8]) -> Result<usize, AudioError> {
        let expected = self.cfg.samples_per_frame() * self.cfg.channels as usize;
        if pcm.len() < expected {
            return Err(AudioError::BufferSize {
                expected,
                got: pcm.len(),
            });
        }
        let n = self.enc.encode(&pcm[..expected], dst)?;
        Ok(n)
    }
}

#[cfg(feature = "opus-codec")]
pub struct OpusDecoder {
    dec: opus::Decoder,
    cfg: AudioConfig,
}

#[cfg(feature = "opus-codec")]
impl OpusDecoder {
    pub fn new(cfg: &AudioConfig) -> Result<Self, AudioError> {
        let dec = opus::Decoder::new(
            cfg.sample_rate,
            match cfg.channels {
                1 => opus::Channels::Mono,
                2 => opus::Channels::Stereo,
                _ => return Err(AudioError::InvalidSampleRate(cfg.channels as u32)),
            },
        )?;
        Ok(Self {
            dec,
            cfg: *cfg,
        })
    }
}

#[cfg(feature = "opus-codec")]
impl AudioDecoder for OpusDecoder {
    fn decode(&mut self, opus: &[u8], dst: &mut [i16]) -> Result<usize, AudioError> {
        let max_samples = self.cfg.samples_per_frame() * self.cfg.channels as usize;
        if dst.len() < max_samples {
            return Err(AudioError::BufferSize {
                expected: max_samples,
                got: dst.len(),
            });
        }
        let n = self.dec.decode(opus, &mut dst[..max_samples], false)?;
        Ok(n)
    }

    fn decode_lost(&mut self, dst: &mut [i16]) -> Result<usize, AudioError> {
        let max_samples = self.cfg.samples_per_frame() * self.cfg.channels as usize;
        let n = self.dec.decode(&[], &mut dst[..max_samples], true)?;
        Ok(n)
    }
}

// ============================================================================
// PCM 直通(P0 验证管线用,无需 libopus)
// ============================================================================

#[cfg(not(feature = "opus-codec"))]
pub struct PcmPassthroughEncoder;

#[cfg(not(feature = "opus-codec"))]
impl AudioEncoder for PcmPassthroughEncoder {
    fn encode(&mut self, pcm: &[i16], dst: &mut [u8]) -> Result<usize, AudioError> {
        if dst.len() < pcm.len() * 2 {
            return Err(AudioError::BufferSize {
                expected: pcm.len() * 2,
                got: dst.len(),
            });
        }
        for (i, &s) in pcm.iter().enumerate() {
            dst[i * 2..i * 2 + 2].copy_from_slice(&s.to_le_bytes());
        }
        Ok(pcm.len() * 2)
    }
}

#[cfg(not(feature = "opus-codec"))]
pub struct PcmPassthroughDecoder;

#[cfg(not(feature = "opus-codec"))]
impl AudioDecoder for PcmPassthroughDecoder {
    fn decode(&mut self, opus: &[u8], dst: &mut [i16]) -> Result<usize, AudioError> {
        let samples = opus.len() / 2;
        if dst.len() < samples {
            return Err(AudioError::BufferSize {
                expected: samples,
                got: dst.len(),
            });
        }
        for i in 0..samples {
            dst[i] = i16::from_le_bytes([opus[i * 2], opus[i * 2 + 1]]);
        }
        Ok(samples)
    }

    fn decode_lost(&mut self, dst: &mut [i16]) -> Result<usize, AudioError> {
        // PCM 直通无 PLC,输出静音
        for s in dst.iter_mut() {
            *s = 0;
        }
        Ok(dst.len())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn config_samples_per_frame() {
        let cfg = AudioConfig::default();
        assert_eq!(cfg.samples_per_frame(), 960); // 48000 * 20 / 1000
        assert_eq!(cfg.bytes_per_frame(), 1920);
    }

    #[test]
    fn encode_decode_roundtrip() {
        let cfg = AudioConfig::default();
        let mut enc = make_encoder(&cfg);
        let mut dec = make_decoder(&cfg);

        // 生成 1kHz 正弦波测试样本
        let mut pcm = vec![0i16; cfg.samples_per_frame()];
        for (i, s) in pcm.iter_mut().enumerate() {
            let t = i as f32 / cfg.sample_rate as f32;
            let sample = (t * 2.0 * std::f32::consts::PI * 1000.0).sin() * 0.3 * i16::MAX as f32;
            *s = sample as i16;
        }

        let mut encoded = vec![0u8; 8192];
        let enc_len = enc.encode(&pcm, &mut encoded).unwrap();
        let mut decoded = vec![0i16; cfg.samples_per_frame() * 2];
        let dec_len = dec.decode(&encoded[..enc_len], &mut decoded).unwrap();

        assert!(dec_len > 0);
        // Opus 是有损,但能量应大致保留
        let energy_in: f64 = pcm.iter().map(|&s| (s as f64).powi(2)).sum();
        let energy_out: f64 = decoded[..dec_len].iter().map(|&s| (s as f64).powi(2)).sum();
        assert!(energy_out > energy_in * 0.5, "能量损失过大");
    }
}
