//! 屏幕抓取(远程显示器功能)
//!
//! P1 迁移:使用 DXGI Desktop Duplication 替代 GDI BitBlt
//!
//! 优势:
//! - 零拷贝:直接从 GPU 表面获取,不经 GDI 内存
//! - 变化检测:AcquireNextFrame 只在有变化时返回,静止画面零开销
//! - 帧缓存:画面无变化时返回上一帧,避免空响应
//! - 为后续 H.264 硬件编码 + RTP 传输打基础

#[cfg(windows)]
mod dxgi_capturer {
    use std::sync::{Mutex, OnceLock};

    use dxgi_capture_rs::{BGRA8, CaptureError, DXGIManager};

    /// 采集到的一帧(BGRA 像素)
    #[derive(Clone)]
    struct AcquiredFrame {
        pixels: Vec<u8>,
        width: u32,
        height: u32,
    }

    /// DXGI Desktop Duplication 屏幕采集器(封装 dxgi-capture-rs crate)
    ///
    /// 历史:自研 D3D11 Map 实现在某些驱动下 IDXGISurface1::Map 持续返回 E_INVALIDARG(0x80070057),
    /// 而 dxgi-capture-rs 的 DXGIManager 能正常工作。改用该 crate 作为底层捕获实现。
    struct ScreenCapturer {
        manager: DXGIManager,
        /// 上一帧 PNG 缓存(画面无变化或捕获失败时返回)
        last_png: Option<Vec<u8>>,
        /// 上一帧像素缓存(桌面静止 Timeout 时复用,保持视频流活跃)
        last_frame: Option<AcquiredFrame>,
        /// 失败计数(用于日志限流)
        fail_count: u32,
    }

    impl ScreenCapturer {
        fn new() -> Option<Self> {
            match DXGIManager::new(200) {
                Ok(manager) => {
                    let (w, h) = manager.geometry();
                    tracing::info!("ScreenCapturer: 初始化成功 (dxgi-capture-rs, {}x{})", w, h);
                    Some(Self {
                        manager,
                        last_png: None,
                        last_frame: None,
                        fail_count: 0,
                    })
                }
                Err(e) => {
                    tracing::error!("ScreenCapturer: DXGIManager::new 失败: {:?}", e);
                    None
                }
            }
        }

        /// 抓取屏幕并编码 PNG
        fn capture_png(&mut self, quality: u8, scale: f32) -> Option<Vec<u8>> {
            match self.acquire_frame() {
                Some(frame) => {
                    let png = unsafe { self.encode_frame(&frame, quality, scale) };
                    if let Some(ref png) = png {
                        self.last_png = Some(png.clone());
                    }
                    png
                }
                None => self.last_png.clone(),
            }
        }

        /// 抓取屏幕并编码 NALU(H.264 或 HEVC,取决于 codec 参数)
        fn capture(&mut self, frame_rate: u32, bitrate: u32, codec: crate::encoder::Codec) -> Option<Vec<u8>> {
            match self.acquire_frame() {
                Some(frame) => {
                    // 分辨率规整:Media Foundation H.264 编码器对非标准分辨率(如 2048x1536)支持有限,
                    // 很多硬件编码器最大只支持 1920x1080。超过此限制时缩放到 1920x1080(保持纵横比)。
                    // 参考 Sunshine:encoder_base.cpp 会将输入帧缩放到编码器支持的最大分辨率。
                    const MAX_ENC_WIDTH: u32 = 1920;
                    const MAX_ENC_HEIGHT: u32 = 1080;

                    let (enc_w, enc_h, enc_pixels) = if frame.width > MAX_ENC_WIDTH || frame.height > MAX_ENC_HEIGHT {
                        let (nw, nh, np) = scale_bgra_down(
                            &frame.pixels,
                            frame.width,
                            frame.height,
                            MAX_ENC_WIDTH,
                            MAX_ENC_HEIGHT,
                        );
                        if self.fail_count < 3 {
                            tracing::info!(
                                "capture: 缩放 {}x{} → {}x{} (编码器分辨率限制)",
                                frame.width, frame.height, nw, nh
                            );
                        }
                        (nw, nh, np)
                    } else {
                        (frame.width, frame.height, frame.pixels.clone())
                    };

                    let nalu = crate::encoder::encode_frame_with_codec(
                        &enc_pixels,
                        enc_w,
                        enc_h,
                        frame_rate,
                        bitrate,
                        codec,
                    );
                    if nalu.is_none() {
                        tracing::warn!(
                            "编码器返回 None: {}x{} {:?} bitrate={}",
                            enc_w, enc_h, codec, bitrate
                        );
                    }
                    nalu
                }
                None => None,
            }
        }

        /// 通过 dxgi-capture-rs 捕获一帧
        fn acquire_frame(&mut self) -> Option<AcquiredFrame> {
            match self.manager.capture_frame() {
                Ok((pixels, (w, h))) => {
                    self.fail_count = 0;
                    // BGRA8 数组 → u8 字节流(BGRA 顺序,编码器期望的格式)
                    let pixels_bytes: Vec<u8> = unsafe {
                        std::slice::from_raw_parts(
                            pixels.as_ptr() as *const u8,
                            pixels.len() * std::mem::size_of::<BGRA8>(),
                        )
                        .to_vec()
                    };
                    let frame = AcquiredFrame {
                        pixels: pixels_bytes,
                        width: w as u32,
                        height: h as u32,
                    };
                    self.last_frame = Some(frame.clone());
                    Some(frame)
                }
                Err(CaptureError::Timeout) => {
                    // 桌面静止无新帧,复用上一帧让编码器输出 P 帧/重复帧
                    self.last_frame.clone()
                }
                Err(e) => {
                    self.fail_count += 1;
                    if self.fail_count <= 5 || self.fail_count % 100 == 0 {
                        tracing::warn!("acquire_frame: capture_frame 失败 (count={}): {:?}", self.fail_count, e);
                    }
                    self.last_frame.clone()
                }
            }
        }

        /// BGRA → RGBA + 缩放(最近邻)+ PNG 编码
        unsafe fn encode_frame(
            &self,
            frame: &AcquiredFrame,
            _quality: u8,
            scale: f32,
        ) -> Option<Vec<u8>> {
            let s = scale.clamp(0.1, 1.0);
            let dst_w = ((frame.width as f32) * s).max(1.0) as u32;
            let dst_h = ((frame.height as f32) * s).max(1.0) as u32;

            // BGRA → RGBA + 缩放(一步完成)
            let rgba = if s < 1.0 {
                resize_nearest_bgra_to_rgba(
                    &frame.pixels,
                    frame.width,
                    frame.height,
                    dst_w,
                    dst_h,
                )
            } else {
                bgra_to_rgba(&frame.pixels)
            };

            super::encode_png(&rgba, dst_w, dst_h).ok()
        }
    }

    /// BGRA → RGBA 转换
    fn bgra_to_rgba(bgra: &[u8]) -> Vec<u8> {
        let mut rgba = bgra.to_vec();
        let count = rgba.len() / 4;
        for i in 0..count {
            let b = rgba[i * 4];
            rgba[i * 4] = rgba[i * 4 + 2]; // R
            rgba[i * 4 + 2] = b; // B
            rgba[i * 4 + 3] = 255; // A
        }
        rgba
    }

    /// 缩放 BGRA 帧到不超过 (max_w, max_h) 的最大尺寸(保持纵横比,最近邻)
    /// 返回 (new_width, new_height, new_pixels)
    ///
    /// 关键:输出分辨率对齐到 16 的倍数(H.264 macroblock 对齐)。
    /// 非标准分辨率(如 1440x1080,height=1080 不是 16 的倍数)会导致
    /// Media Foundation 编码器 SetInputType 返回 MF_E_INVALIDMEDIATYPE (0xc00d6d77)。
    /// 参考 Sunshine:video::config_t 会将宽高对齐到宏块尺寸。
    fn scale_bgra_down(
        src: &[u8],
        sw: u32,
        sh: u32,
        max_w: u32,
        max_h: u32,
    ) -> (u32, u32, Vec<u8>) {
        // 计算缩放比例,取宽高中较小的比例,确保两者都不超限
        let scale = (max_w as f64 / sw as f64).min(max_h as f64 / sh as f64).min(1.0);
        let dw = ((sw as f64) * scale).round() as u32;
        let dh = ((sh as f64) * scale).round() as u32;
        // 对齐到 16 的倍数(H.264 macroblock = 16x16)
        // 不用偶数对齐,因为 16 对齐更严格且满足偶数要求
        let dw = (dw / 16) * 16;
        let dh = (dh / 16) * 16;
        let dw = dw.max(16);
        let dh = dh.max(16);

        let mut out = vec![0u8; (dw as usize) * (dh as usize) * 4];
        for y in 0..dh as usize {
            for x in 0..dw as usize {
                let sx = (x * sw as usize) / dw as usize;
                let sy = (y * sh as usize) / dh as usize;
                let si = (sy * sw as usize + sx) * 4;
                let di = (y * dw as usize + x) * 4;
                out[di] = src[si]; // B
                out[di + 1] = src[si + 1]; // G
                out[di + 2] = src[si + 2]; // R
                out[di + 3] = src[si + 3]; // A
            }
        }
        (dw, dh, out)
    }

    /// 最近邻缩放 BGRA → RGBA
    fn resize_nearest_bgra_to_rgba(
        src: &[u8],
        sw: u32,
        sh: u32,
        dw: u32,
        dh: u32,
    ) -> Vec<u8> {
        let mut out = vec![0u8; (dw as usize) * (dh as usize) * 4];
        for y in 0..dh as usize {
            for x in 0..dw as usize {
                let sx = (x * sw as usize) / dw as usize;
                let sy = (y * sh as usize) / dh as usize;
                let si = (sy * sw as usize + sx) * 4;
                let di = (y * dw as usize + x) * 4;
                out[di] = src[si + 2]; // R
                out[di + 1] = src[si + 1]; // G
                out[di + 2] = src[si]; // B
                out[di + 3] = 255; // A
            }
        }
        out
    }

    // ── 全局 ScreenCapturer 单例 ──
    static CAPTURER: OnceLock<Mutex<Option<ScreenCapturer>>> = OnceLock::new();

    pub fn capture_screen_png(quality: u8, scale: f32) -> Option<Vec<u8>> {
        let mutex = CAPTURER.get_or_init(|| Mutex::new(None));
        let mut guard = mutex.lock().ok()?;
        if guard.is_none() {
            *guard = ScreenCapturer::new();
        }
        guard.as_mut()?.capture_png(quality, scale)
    }

    /// 抓取屏幕并编码 H.264 NALU(P2:硬件编码)
    /// - frame_rate: 目标帧率(如 30)
    /// - bitrate: 平均码率(如 4_000_000 = 4Mbps)
    /// 返回: NALU 字节(包含 SPS/PPS/IDR 或 P 帧,H.264 或 HEVC 取决于 codec)
    pub fn capture_screen(
        frame_rate: u32,
        bitrate: u32,
        codec: crate::encoder::Codec,
    ) -> Option<Vec<u8>> {
        let mutex = CAPTURER.get_or_init(|| Mutex::new(None));
        let mut guard = mutex.lock().ok()?;
        if guard.is_none() {
            *guard = ScreenCapturer::new();
        }
        let capturer = guard.as_mut()?;
        capturer.capture(frame_rate, bitrate, codec)
    }

    pub fn screen_resolution() -> (u32, u32) {
        use windows::Win32::UI::WindowsAndMessaging::{
            GetSystemMetrics, SM_CXSCREEN, SM_CYSCREEN,
        };
        unsafe {
            let w = GetSystemMetrics(SM_CXSCREEN).max(0) as u32;
            let h = GetSystemMetrics(SM_CYSCREEN).max(0) as u32;
            (w, h)
        }
    }
}

#[cfg(windows)]
pub use dxgi_capturer::{capture_screen, capture_screen_png, screen_resolution};

/// 向后兼容:H.264 专用入口
#[cfg(windows)]
pub fn capture_screen_h264(frame_rate: u32, bitrate: u32) -> Option<Vec<u8>> {
    capture_screen(frame_rate, bitrate, crate::encoder::Codec::H264)
}

#[cfg(not(windows))]
pub fn capture_screen_png(_quality: u8, _scale: f32) -> Option<Vec<u8>> {
    None
}

#[cfg(not(windows))]
pub fn capture_screen(_frame_rate: u32, _bitrate: u32, _codec: crate::encoder::Codec) -> Option<Vec<u8>> {
    None
}

#[cfg(not(windows))]
pub fn capture_screen_h264(_frame_rate: u32, _bitrate: u32) -> Option<Vec<u8>> {
    None
}

#[cfg(not(windows))]
pub fn screen_resolution() -> (u32, u32) {
    (0, 0)
}

#[cfg(windows)]
fn encode_png(rgba: &[u8], w: u32, h: u32) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
    use image::ImageEncoder;
    use image::codecs::png::{PngEncoder, CompressionType, FilterType};
    use std::io::Cursor;

    let mut buf = Cursor::new(Vec::new());
    let enc = PngEncoder::new_with_quality(&mut buf, CompressionType::Fast, FilterType::Sub);
    enc.write_image(rgba, w, h, image::ExtendedColorType::Rgba8)?;
    Ok(buf.into_inner())
}
