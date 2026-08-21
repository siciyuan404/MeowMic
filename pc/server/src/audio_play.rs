//! 音频播放(PC 端)
//!
//! P0 阶段:cpal 默认输出设备直接播放
//! 已知限制:走系统混音器,延迟受输出缓冲影响(几十 ms)
//! 后续:替换为自研 WDM 虚拟麦克风设备,任意应用可选用,延迟可控

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use tokio::sync::mpsc;

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use meowmic_audio::AudioConfig;

/// 异步音频播放器:通过 channel 解耦解码线程与 cpal 回调
pub struct AudioPlayer {
    /// PCM 样本发送端(解码线程 → cpal 回调)
    tx: mpsc::Sender<Vec<i16>>,
    /// 静音模式:运行时可通过 set_muted() 切换
    muted: Arc<AtomicBool>,
}

impl AudioPlayer {
    pub async fn new(cfg: AudioConfig, muted: bool, output_device: Option<String>) -> anyhow::Result<Self> {
        let host = cpal::default_host();
        let device = if let Some(ref name) = output_device {
            // 尝试查找指定设备
            host.output_devices()?
                .find(|d| d.name().ok().as_deref() == Some(name.as_str()))
                .ok_or_else(|| anyhow::anyhow!("找不到指定的音频输出设备: {}", name))?
        } else {
            host.default_output_device()
                .ok_or_else(|| anyhow::anyhow!("无可用音频输出设备"))?
        };

        // 声道:优先精确匹配 cfg.channels,否则退化到设备原生(输出设备基本为立体声)。
        // 采样率:改用设备默认输出配置的采样率(Windows 共享模式下即混音格式,如 44.1kHz),
        // 而非强制 cfg.sample_rate(48000);否则用 48k 初始化混音格式为 44.1k 的 WASAPI
        // 客户端会返回 AUDCLNT_E_UNSUPPORTED_FORMAT,导致启动失败(exit code 1)。
        let selected = device
            .supported_output_configs()?
            .find(|c| c.channels() == u16::from(cfg.channels))
            .or_else(|| {
                device
                    .supported_output_configs()
                    .ok()
                    .and_then(|mut c| c.next())
            })
            .ok_or_else(|| anyhow::anyhow!("无匹配音频配置"))?;

        // 采样率优先取设备默认(即混音格式);若取不到则改用所选配置 min/max 范围内收敛后的值
        let sample_rate = device
            .default_output_config()
            .ok()
            .map(|c| c.sample_rate())
            .unwrap_or_else(|| {
                let min = selected.min_sample_rate().0;
                let max = selected.max_sample_rate().0;
                cpal::SampleRate(cfg.sample_rate.clamp(min, max))
            });

        let stream_cfg = cpal::StreamConfig {
            channels: selected.channels(),
            sample_rate,
            buffer_size: cpal::BufferSize::Default,
        };

        let (tx, mut rx) = mpsc::channel::<Vec<i16>>(32);

        // 共享环形缓冲,缓冲 3 帧(60ms)对抗抖动
        let buffer: Arc<std::sync::Mutex<std::collections::VecDeque<i16>>> =
            Arc::new(std::sync::Mutex::new(std::collections::VecDeque::with_capacity(
                cfg.samples_per_frame() * 4,
            )));

        let buffer_clone = buffer.clone();
        let channels = stream_cfg.channels as usize;

        let stream = device.build_output_stream(
            &stream_cfg,
            move |out: &mut [f32], _: &cpal::OutputCallbackInfo| {
                let mut buf = buffer_clone.lock().unwrap();
                let need = out.len();
                for i in 0..need {
                    if let Some(s) = buf.pop_front() {
                        out[i] = s as f32 / i16::MAX as f32;
                    } else {
                        out[i] = 0.0;
                    }
                }
            },
            |e| tracing::error!("cpal 输出错误: {}", e),
            None,
        )?;

        // 接收循环:从 channel 取 PCM 写入环形缓冲
        let buffer_for_recv = buffer.clone();
        tokio::spawn(async move {
            while let Some(pcm) = rx.recv().await {
                let mut buf = buffer_for_recv.lock().unwrap();
                // 通道数转换(简化:Mono → 复制到所有通道)
                match channels {
                    1 => buf.extend(pcm),
                    2 => {
                        for &s in &pcm {
                            buf.push_back(s);
                            buf.push_back(s);
                        }
                    }
                    _ => buf.extend(pcm),
                }
                // 限制缓冲深度,避免堆积引入延迟
                let max_len = cfg.samples_per_frame() * 4 * channels;
                while buf.len() > max_len {
                    buf.pop_front();
                }
            }
        });

        stream.play()?;

        // stream 必须保持存活:用 OnceCell 持有
        std::mem::forget(stream);

        Ok(Self {
            tx,
            muted: Arc::new(AtomicBool::new(muted)),
        })
    }

    /// 运行时切换静音状态
    pub fn set_muted(&self, muted: bool) {
        self.muted.store(muted, Ordering::Relaxed);
    }

    pub fn is_muted(&self) -> bool {
        self.muted.load(Ordering::Relaxed)
    }

    pub async fn play(&self, pcm: &[i16]) {
        // 静音模式:不把 PCM 推入 cpal 缓冲,直接丢弃
        // stream 仍然存活(输出静音),麦克风声音不会从扬声器外放
        if self.is_muted() {
            return;
        }
        let _ = self.tx.send(pcm.to_vec()).await;
    }
}
