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

    // thread-local:保存最近一次 scale 耗时,供 capture 日志输出
    thread_local! {
        static SCALE_LAST: std::cell::Cell<std::time::Duration> = std::cell::Cell::new(std::time::Duration::ZERO);
    }

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
            // 超时 16ms:桌面无变化时快速返回 Timeout,复用上一帧
            // (原 200ms 会导致桌面静止时 acquire 耗时 200ms,帧率降到 5fps)
            match DXGIManager::new(16) {
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
            let t0 = std::time::Instant::now();
            match self.acquire_frame() {
                Some(frame) => {
                    let t_acquire = t0.elapsed();
                    // 分辨率规整:降低到 1024x768 以提升帧率
                    // 2048x1536 源缩放到 1408x1056 时,scale 耗时 51ms + encode 41ms = 92ms(仅 10fps)
                    // 降到 1024x768 后,scale 约 27ms + encode 22ms = 49ms(约 20fps)
                    // 画质换流畅度:远程桌面场景对帧率敏感度高于分辨率
                    const MAX_ENC_WIDTH: u32 = 1024;
                    const MAX_ENC_HEIGHT: u32 = 768;

                    let (enc_w, enc_h, enc_pixels) = if frame.width > MAX_ENC_WIDTH || frame.height > MAX_ENC_HEIGHT {
                        let t_scale_start = std::time::Instant::now();
                        let (nw, nh, np) = scale_bgra_down(
                            &frame.pixels,
                            frame.width,
                            frame.height,
                            MAX_ENC_WIDTH,
                            MAX_ENC_HEIGHT,
                        );
                        let t_scale = t_scale_start.elapsed();
                        // 只在第一次缩放时打印日志,避免刷屏
                        static SCALE_LOGGED: std::sync::OnceLock<()> = std::sync::OnceLock::new();
                        SCALE_LOGGED.get_or_init(|| {
                            tracing::info!(
                                "capture: 缩放 {}x{} → {}x{} (编码器分辨率限制)",
                                frame.width, frame.height, nw, nh
                            );
                        });
                        // 将 scale 耗时存入 thread-local 以便后续日志输出
                        SCALE_LAST.with(|cell| cell.set(t_scale));
                        (nw, nh, np)
                    } else {
                        SCALE_LAST.with(|cell| cell.set(std::time::Duration::ZERO));
                        (frame.width, frame.height, frame.pixels.clone())
                    };

                    let t_encode_start = std::time::Instant::now();
                    let nalu = crate::encoder::encode_frame_with_codec(
                        &enc_pixels,
                        enc_w,
                        enc_h,
                        frame_rate,
                        bitrate,
                        codec,
                    );
                    let t_encode = t_encode_start.elapsed();
                    let t_total = t0.elapsed();
                    // 每秒打印一次耗时(采集/缩放/编码),帮助定位卡顿瓶颈
                    static LAST_TIMING_LOG: std::sync::OnceLock<std::sync::Mutex<std::time::Instant>> = std::sync::OnceLock::new();
                    let last = LAST_TIMING_LOG.get_or_init(|| std::sync::Mutex::new(std::time::Instant::now()));
                    if let Ok(mut last_guard) = last.lock() {
                        if last_guard.elapsed() >= std::time::Duration::from_secs(1) {
                            *last_guard = std::time::Instant::now();
                            let t_scale = SCALE_LAST.with(|cell| cell.get());
                            tracing::info!(
                                "capture 耗时: acquire={:.1}ms scale={:.1}ms encode={:.1}ms total={:.1}ms ({}x{})",
                                t_acquire.as_secs_f64() * 1000.0,
                                t_scale.as_secs_f64() * 1000.0,
                                t_encode.as_secs_f64() * 1000.0,
                                t_total.as_secs_f64() * 1000.0,
                                enc_w, enc_h
                            );
                        }
                    }
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
    /// 关键:必须使用标准分辨率(宽高均为 16 的倍数且为常见尺寸)。
    /// Media Foundation H.264 编码器对非标准分辨率(如 1440x1088)会拒绝:
    /// SetOutputType 可能返回成功,但 GetInputAvailableType 返回 0xc00d6d77,
    /// 导致所有 SetInputType 失败(0xc00d6d60 MF_E_TRANSFORM_TYPE_NOT_SET)。
    /// 参考 Sunshine:video::config_t 会将宽高对齐到宏块尺寸。
    fn scale_bgra_down(
        src: &[u8],
        sw: u32,
        sh: u32,
        max_w: u32,
        max_h: u32,
    ) -> (u32, u32, Vec<u8>) {
        // 标准分辨率候选(宽高均为 16 的倍数,编码器普遍支持)
        const STANDARD_RESOLUTIONS: &[(u32, u32)] = &[
            (1920, 1080), // 16:9 FHD
            (1680, 1050), // 16:10
            (1600, 900),  // 16:9 HD+
            (1440, 900),  // 16:10
            (1408, 1056), // 4:3 (1088 被拒,改用 1056=16×66)
            (1280, 1024), // 5:4
            (1280, 960),  // 4:3 (960=16×60)
            (1280, 720),  // 16:9 HD
            (1024, 768),  // 4:3 XGA
            (960, 720),   // 4:3
            (800, 600),   // 4:3 SVGA
            (640, 480),   // 4:3 VGA
        ];

        // 选择策略:
        // 1. 宽高均不超过 max_w/max_h 且不放大
        // 2. 优先保持纵横比(ratio_diff 权重远大于 area_diff)
        // 3. 在比例匹配的前提下,选择面积最大的(画质最好)
        let src_ratio = sw as f64 / sh as f64;
        let best = STANDARD_RESOLUTIONS
            .iter()
            .filter(|(w, h)| *w <= max_w && *h <= max_h && *w <= sw && *h <= sh)
            .min_by_key(|(w, h)| {
                let ratio = *w as f64 / *h as f64;
                let ratio_diff = ((ratio - src_ratio).abs() * 100000.0) as u64;
                let area_diff = (((sw as f64 * sh as f64) - (*w as f64 * *h as f64)).abs() / 1000.0) as u64;
                ratio_diff + area_diff
            })
            .copied()
            .unwrap_or((640, 480));

        let (dw, dh) = best;
        let dw_us = dw as usize;
        let dh_us = dh as usize;
        let sw_us = sw as usize;
        let sh_us = sh as usize;

        let mut out = vec![0u8; dw_us * dh_us * 4];

        // 快速路径:整数倍缩放(如 2:1, 3:1)
        // 2048x1536 → 1024x768 正好是 2:1,用特化路径跳过一半行和列
        // 性能:33ms → ~8ms(减少 75%),因为减少了 75% 的源像素访问
        let x_step = sw_us / dw_us;
        let y_step = sh_us / dh_us;
        if x_step >= 2 && y_step >= 2 && sw_us % dw_us == 0 && sh_us % dh_us == 0 {
            // 整数倍缩放:每 x_step 个源像素取 1 个,每 y_step 个源行取 1 行
            // 用 u32 一次拷贝 4 字节(BGRA),unsafe 指针避免边界检查
            let src_ptr = src.as_ptr();
            let dst_ptr = out.as_mut_ptr();
            for y in 0..dh_us {
                let sy = y * y_step;
                let src_row = sy * sw_us;
                let dst_row = y * dw_us;
                unsafe {
                    let src_row_ptr = src_ptr.add(src_row * 4) as *const u32;
                    let dst_row_ptr = dst_ptr.add(dst_row * 4) as *mut u32;
                    for x in 0..dw_us {
                        // 取每个块的左上角像素(最近邻)
                        *dst_row_ptr.add(x) = *src_row_ptr.add(x * x_step);
                    }
                }
            }
            return (dw, dh, out);
        }

        // 通用路径:非整数倍缩放,预计算列映射表
        let sx_map: Vec<usize> = (0..dw_us).map(|x| (x * sw_us) / dw_us).collect();

        for y in 0..dh_us {
            let sy = (y * sh_us) / dh_us;
            let src_row_off = sy * sw_us * 4;
            let dst_row_off = y * dw_us * 4;
            for x in 0..dw_us {
                let si = src_row_off + sx_map[x] * 4;
                let di = dst_row_off + x * 4;
                out[di..di + 4].copy_from_slice(&src[si..si + 4]);
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
