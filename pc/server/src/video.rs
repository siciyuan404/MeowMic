//! 视频推流管线(UDP push 模式)
//!
//! 借鉴 Sunshine 架构:服务端持续采集 + 编码 + UDP 分片推送,
//! 不等待客户端请求(与 HTTP 拉模式相反)。
//!
//! 流程:
//! 1. DXGI Desktop Duplication 采集帧(screen.rs)
//! 2. Media Foundation H.264 硬件编码(encoder.rs)
//! 3. NALU 分片为 UDP 包(protocol::fragment_nalu)
//! 4. 通过 video UDP socket 推送到客户端

use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU16, AtomicU32, Ordering};
use std::time::Duration;
use tokio::net::UdpSocket;
use tokio::task::JoinHandle;

use meowmic_protocol::{fragment_nalu, xor_fragments, VideoFragment, VIDEO_FRAGMENT_HEADER_LEN, VIDEO_MAGIC};

use crate::encoder::Codec;
use crate::screen;
use crate::encoder;

/// 视频推流器
///
/// 启动后在独立 tokio task 中持续采集 → 编码 → 分片 → UDP 推送。
/// 调用 `stop()` 终止推流。
/// 通过 `set_bitrate()` 支持自适应码率(由 main.rs 根据客户端 VideoStats 反馈调整)。
pub struct VideoStreamer {
    running: Arc<AtomicBool>,
    frame_id: Arc<AtomicU16>,
    /// 当前目标码率(bps),自适应码率时由外部 set_bitrate 修改
    target_bitrate: Arc<AtomicU32>,
    /// 编码器类型(H.264 或 HEVC,由启动时探测决定)
    codec: Codec,
    handle: Option<JoinHandle<()>>,
}

impl VideoStreamer {
    /// 启动视频推流
    ///
    /// - `video_sock`: 服务端 video UDP socket (base+6)
    /// - `client_addr`: 客户端 video 接收地址(客户端绑定后通过 UDP 打洞告知)
    /// - `fps`: 目标帧率
    /// - `bitrate`: 目标码率 (bps)
    /// - `codec`: 编码器类型(H.264 或 HEVC)
    pub fn start(
        video_sock: Arc<UdpSocket>,
        client_addr: SocketAddr,
        fps: u32,
        bitrate: u32,
        codec: Codec,
    ) -> Self {
        let running = Arc::new(AtomicBool::new(true));
        let frame_id = Arc::new(AtomicU16::new(0));
        let target_bitrate = Arc::new(AtomicU32::new(bitrate));
        let running_clone = running.clone();
        let frame_id_clone = frame_id.clone();
        let target_bitrate_clone = target_bitrate.clone();

        let interval_ms = 1000 / fps.max(1);
        let handle = tokio::spawn(async move {
            tracing::info!(
                "视频推流启动: target={}fps bitrate={}bps codec={:?} client={}",
                fps, bitrate, codec, client_addr
            );

            let mut send_buf = Vec::with_capacity(VIDEO_FRAGMENT_HEADER_LEN + 1400);
            let interval = Duration::from_millis(interval_ms as u64);
            let mut keyframe_counter: u32 = 0;
            // 每 60 帧强制 keyframe (约 1 秒 @ 60fps)
            const KEYFRAME_INTERVAL: u32 = 60;

            // 诊断:每秒打印发送/丢包统计,帮助定位"解码器转圈"问题
            let mut sent_fragments: u32 = 0;
            let mut sent_frames: u32 = 0;
            let mut empty_captures: u32 = 0;
            let mut last_stats_time = std::time::Instant::now();

            loop {
                if !running_clone.load(Ordering::Relaxed) {
                    break;
                }

                let frame_start = std::time::Instant::now();

                // 1. 采集 + 编码 (复用全局 ScreenCapturer + Encoder 单例)
                // 自适应码率:每帧读取最新目标 bitrate
                let cur_bitrate = target_bitrate_clone.load(Ordering::Relaxed);
                let nalu = screen::capture_screen(fps, cur_bitrate, codec);

                if let Some(nalu_bytes) = nalu {
                    sent_frames = sent_frames.saturating_add(1);
                    // 判断是否为关键帧:HEVC 用 IDR_W_RADL (type=19/20),H.264 用 IDR (type=5)
                    let is_keyframe = is_keyframe_nalu(&nalu_bytes, codec);

                    if is_keyframe {
                        keyframe_counter = 0;
                    } else {
                        keyframe_counter += 1;
                    }

                    // 2. 分片 + 推送
                    let fid = frame_id_clone.fetch_add(1, Ordering::Relaxed);
                    let fragments = fragment_nalu(&nalu_bytes, fid, is_keyframe);

                    for (frag, payload) in &fragments {
                        if !running_clone.load(Ordering::Relaxed) {
                            break;
                        }
                        send_buf.clear();
                        frag.encode(&mut send_buf);
                        send_buf.extend_from_slice(payload);
                        if let Err(e) = video_sock.send_to(&send_buf, client_addr).await {
                            tracing::warn!("视频分片发送失败: {}", e);
                            break;
                        }
                        sent_fragments = sent_fragments.saturating_add(1);
                    }

                    // 3. FEC:对多分片 NALU 追加 1 个 XOR 冗余包(单包丢失恢复)
                    if let Some((fec_frag, fec_payload)) = xor_fragments(&fragments) {
                        if running_clone.load(Ordering::Relaxed) {
                            send_buf.clear();
                            fec_frag.encode(&mut send_buf);
                            send_buf.extend_from_slice(&fec_payload);
                            if let Err(e) = video_sock.send_to(&send_buf, client_addr).await {
                                tracing::warn!("FEC 包发送失败: {}", e);
                            } else {
                                sent_fragments = sent_fragments.saturating_add(1);
                            }
                        }
                    }
                } else {
                    empty_captures = empty_captures.saturating_add(1);
                }

                // 4. 帧节奏控制 + 每秒发送统计
                let now = std::time::Instant::now();
                if now.duration_since(last_stats_time) >= std::time::Duration::from_secs(1) {
                    tracing::info!(
                        "视频发送统计: {} 帧/秒 {} 分片/秒 (空采集={})",
                        sent_frames, sent_fragments, empty_captures
                    );
                    sent_frames = 0;
                    sent_fragments = 0;
                    empty_captures = 0;
                    last_stats_time = now;
                }
                // 帧节奏控制:如果采集+编码已经超过 interval,不额外 sleep
                // (capture_screen 是同步阻塞调用,在 tokio 任务中会阻塞 runtime)
                let elapsed = frame_start.elapsed();
                if elapsed < interval {
                    tokio::time::sleep(interval - elapsed).await;
                }
            }

            tracing::info!("视频推流停止");
        });

        Self {
            running,
            frame_id,
            target_bitrate,
            codec,
            handle: Some(handle),
        }
    }

    /// 停止推流
    pub fn stop(&mut self) {
        self.running.store(false, Ordering::Relaxed);
        if let Some(handle) = self.handle.take() {
            handle.abort();
        }
    }

    /// 自适应码率:更新目标 bitrate(bps)
    /// VideoStreamer 推流循环下一帧将采用新值
    pub fn set_bitrate(&self, bitrate: u32) {
        self.target_bitrate.store(bitrate, Ordering::Relaxed);
    }

    /// 读取当前目标 bitrate(bps)
    pub fn current_bitrate(&self) -> u32 {
        self.target_bitrate.load(Ordering::Relaxed)
    }

    /// 返回编码器类型
    pub fn codec(&self) -> Codec {
        self.codec
    }
}

impl Drop for VideoStreamer {
    fn drop(&mut self) {
        self.stop();
    }
}

/// 判断 NALU 字节流是否包含关键帧
///
/// - H.264: IDR (NAL type 5) 或 SPS (type 7)
/// - HEVC:  IDR_W_RADL (type 19) / IDR_N_LP (type 20) 或 VPS (type 32) / SPS (type 33)
fn is_keyframe_nalu(nalu: &[u8], codec: Codec) -> bool {
    // Annex-B: [00 00 00 01] 或 [00 00 01] 起始码 + NALU 数据
    let mut i = 0;
    while i < nalu.len() {
        // 查找起始码
        let start = find_start_code(&nalu[i..]);
        if start.is_none() {
            break;
        }
        let offset = i + start.unwrap();
        // 跳过起始码
        let mut j = offset;
        while j < nalu.len() && nalu[j] == 0 {
            j += 1;
        }
        if j < nalu.len() && nalu[j] == 1 {
            j += 1;
        }
        if j < nalu.len() {
            match codec {
                Codec::H264 => {
                    // H.264 NALU type = first byte & 0x1F
                    let nal_type = nalu[j] & 0x1F;
                    if nal_type == 5 || nal_type == 7 {
                        return true;
                    }
                }
                Codec::Hevc => {
                    // HEVC NALU type = (first byte >> 1) & 0x3F
                    let nal_type = (nalu[j] >> 1) & 0x3F;
                    // 19=IDR_W_RADL, 20=IDR_N_LP, 32=VPS, 33=SPS
                    if nal_type == 19 || nal_type == 20 || nal_type == 32 || nal_type == 33 {
                        return true;
                    }
                }
            }
        }
        i = j + 1;
    }
    false
}

/// 查找起始码位置 (00 00 00 01 或 00 00 01)
fn find_start_code(data: &[u8]) -> Option<usize> {
    if data.len() < 3 {
        return None;
    }
    for i in 0..data.len() - 2 {
        if data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 {
            return Some(i);
        }
        if i + 3 < data.len() && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1 {
            return Some(i);
        }
    }
    None
}
