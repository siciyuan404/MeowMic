//! 音频播放(PC 端)
//!
//! P0 阶段:cpal 默认输出设备直接播放
//! 已知限制:走系统混音器,延迟受输出缓冲影响(几十 ms)
//! 后续:替换为自研 WDM 虚拟麦克风设备,任意应用可选用,延迟可控

use std::sync::Arc;
use tokio::sync::mpsc;

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use meowmic_audio::AudioConfig;

/// 异步音频播放器:通过 channel 解耦解码线程与 cpal 回调
pub struct AudioPlayer {
    /// PCM 样本发送端(解码线程 → cpal 回调)
    tx: mpsc::Sender<Vec<i16>>,
}

impl AudioPlayer {
    pub async fn new(cfg: AudioConfig) -> anyhow::Result<Self> {
        let host = cpal::default_host();
        let device = host
            .default_output_device()
            .ok_or_else(|| anyhow::anyhow!("无可用音频输出设备"))?;

        let supported = device
            .supported_output_configs()?
            .find(|c| {
                c.channels() == cfg.channels.into()
                    && (c.min_sample_rate().0..=c.max_sample_rate().0)
                        .contains(&cfg.sample_rate)
            })
            .or_else(|| {
                // 找不到精确匹配,退化为任意配置
                device
                    .supported_output_configs()
                    .ok()
                    .and_then(|mut c| c.next())
            })
            .ok_or_else(|| anyhow::anyhow!("无匹配音频配置"))?;

        let stream_cfg = cpal::StreamConfig {
            channels: supported.channels(),
            sample_rate: cpal::SampleRate(cfg.sample_rate),
            buffer_size: cpal::BufferSize::Default,
        };

        let (tx, mut rx) = mpsc::channel::<Vec<i16>>(32);

        // 共享环形缓冲,缓冲 3 帧(60ms)对抗抖动
        // P0 用简化版本:直接 channel + Vec 拼接
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
        // (cpal Stream drop 即停,这里用静态持有简化 P0)
        std::mem::forget(stream);

        Ok(Self { tx })
    }

    pub async fn play(&self, pcm: &[i16]) {
        let _ = self.tx.send(pcm.to_vec()).await;
    }
}
