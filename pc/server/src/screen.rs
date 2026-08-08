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

    use windows::core::Interface;
    use windows::Win32::Foundation::HMODULE;
    use windows::Win32::Graphics::Direct3D::{
        D3D_DRIVER_TYPE_HARDWARE, D3D_FEATURE_LEVEL_11_0,
    };
    use windows::Win32::Graphics::Direct3D11::{
        D3D11CreateDevice, ID3D11Device, ID3D11DeviceContext, ID3D11Texture2D,
        D3D11_CREATE_DEVICE_BGRA_SUPPORT, D3D11_MAPPED_SUBRESOURCE, D3D11_MAP_READ,
        D3D11_SDK_VERSION, D3D11_TEXTURE2D_DESC, D3D11_USAGE_STAGING,
    };
    use windows::Win32::Graphics::Dxgi::{
        IDXGIAdapter, IDXGIDevice, IDXGIOutput, IDXGIOutput1,
        IDXGIOutputDuplication, IDXGIResource, DXGI_OUTDUPL_FRAME_INFO,
    };
    use windows::Win32::Graphics::Dxgi::Common::DXGI_SAMPLE_DESC;

    // DXGI 错误码
    // AccessLost = duplication 失效(如分辨率变化/锁屏),需重建
    const DXGI_ERROR_ACCESS_LOST: i32 = 0x887A0026u32 as i32;
    // WaitTimeout = timeout 内无新帧(桌面静止),需复用上一帧保持视频流活跃
    const DXGI_ERROR_WAIT_TIMEOUT: i32 = 0x887A0027u32 as i32;

    /// 采集到的一帧(BGRA 像素)
    #[derive(Clone)]
    struct AcquiredFrame {
        pixels: Vec<u8>,
        width: u32,
        height: u32,
    }

    /// DXGI Desktop Duplication 屏幕采集器(持久化 D3D11 设备 + duplication)
    struct ScreenCapturer {
        device: ID3D11Device,
        context: ID3D11DeviceContext,
        duplication: IDXGIOutputDuplication,
        /// 上一帧 PNG 缓存(画面无变化时返回)
        last_png: Option<Vec<u8>>,
        /// 上一帧像素缓存(桌面静止 WAIT_TIMEOUT 时复用,保持视频流活跃)
        last_frame: Option<AcquiredFrame>,
        /// 标记 duplication 失效(如分辨率变化/锁屏),需重建
        needs_rebuild: bool,
    }

    // COM 接口在 windows crate 中已实现 Send/Sync,此处显式声明以防编译器警告
    unsafe impl Send for ScreenCapturer {}

    impl ScreenCapturer {
        /// 初始化 D3D11 设备 + DXGI Desktop Duplication
        fn new() -> Option<Self> {
            unsafe {
                let mut device: Option<ID3D11Device> = None;
                let mut context: Option<ID3D11DeviceContext> = None;
                let mut feature_level = D3D_FEATURE_LEVEL_11_0;

                // 创建 D3D11 设备(需要 BGRA 支持,DXGI Desktop Duplication 要求)
                D3D11CreateDevice(
                    None,
                    D3D_DRIVER_TYPE_HARDWARE,
                    HMODULE::default(),
                    D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                    Some(&[D3D_FEATURE_LEVEL_11_0]),
                    D3D11_SDK_VERSION,
                    Some(&mut device),
                    Some(&mut feature_level),
                    Some(&mut context),
                )
                .ok()?;

                let device = device?;
                let context = context?;
                let duplication = Self::create_duplication(&device)?;

                Some(Self {
                    device,
                    context,
                    duplication,
                    last_png: None,
                    last_frame: None,
                    needs_rebuild: false,
                })
            }
        }

        /// 创建 IDXGIOutputDuplication(获取第一个输出)
        unsafe fn create_duplication(device: &ID3D11Device) -> Option<IDXGIOutputDuplication> {
            let dxgi_device: IDXGIDevice = device.cast().ok()?;
            let adapter: IDXGIAdapter = dxgi_device.GetParent().ok()?;
            let output: IDXGIOutput = adapter.EnumOutputs(0).ok()?;
            let output1: IDXGIOutput1 = output.cast().ok()?;
            Some(output1.DuplicateOutput(&dxgi_device).ok()?)
        }

        /// 重建 duplication(ACCESS_LOST 后调用)
        fn rebuild(&mut self) {
            if let Some(dup) = unsafe { Self::create_duplication(&self.device) } {
                self.duplication = dup;
                self.needs_rebuild = false;
            }
        }

        /// 抓取屏幕并编码 PNG
        fn capture_png(&mut self, quality: u8, scale: f32) -> Option<Vec<u8>> {
            if self.needs_rebuild {
                self.rebuild();
                if self.needs_rebuild {
                    return self.last_png.clone();
                }
            }

            match unsafe { self.acquire_frame() } {
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
            if self.needs_rebuild {
                self.rebuild();
                if self.needs_rebuild {
                    return None;
                }
            }

            match unsafe { self.acquire_frame() } {
                Some(frame) => {
                    let nalu = crate::encoder::encode_frame_with_codec(
                        &frame.pixels,
                        frame.width,
                        frame.height,
                        frame_rate,
                        bitrate,
                        codec,
                    );
                    if nalu.is_none() {
                        tracing::warn!(
                            "编码器返回 None: {}x{} {:?} bitrate={}",
                            frame.width, frame.height, codec, bitrate
                        );
                    }
                    nalu
                }
                None => None,
            }
        }

        /// AcquireNextFrame 获取新帧(200ms timeout)
        unsafe fn acquire_frame(&mut self) -> Option<AcquiredFrame> {
            let mut frame_info = DXGI_OUTDUPL_FRAME_INFO::default();
            let mut resource: Option<IDXGIResource> = None;

            let result = self
                .duplication
                .AcquireNextFrame(200, &mut frame_info, &mut resource);

            match result {
                Ok(()) => {
                    // 注意:不再因 LastPresentTime == 0 跳过帧。
                    // DXGI Desktop Duplication 在画面无变化时(如桌面静止)返回 LastPresentTime=0,
                    // 如果跳过会导致客户端永远收不到视频流(解码器无 SPS/PPS/IDR,一直转圈)。
                    // 参考 Sunshine:即使无变化也处理当前 texture,编码器会输出 P 帧/重复帧保持视频流活跃。
                    // 鼠标移动会通过 PointerPosition 更新,但当前实现未单独处理鼠标层。

                    let resource = resource?;
                    let texture: ID3D11Texture2D = resource.cast().ok()?;
                    let mut desc = D3D11_TEXTURE2D_DESC::default();
                    texture.GetDesc(&mut desc);

                    // 创建 staging texture(CPU 可读)
                    let mut staging_desc = D3D11_TEXTURE2D_DESC::default();
                    staging_desc.Width = desc.Width;
                    staging_desc.Height = desc.Height;
                    staging_desc.MipLevels = 1;
                    staging_desc.ArraySize = 1;
                    staging_desc.Format = desc.Format;
                    staging_desc.SampleDesc = DXGI_SAMPLE_DESC {
                        Count: 1,
                        Quality: 0,
                    };
                    staging_desc.Usage = D3D11_USAGE_STAGING;
                    staging_desc.CPUAccessFlags = 0x10000; // D3D11_CPU_ACCESS_READ

                    let mut staging: Option<ID3D11Texture2D> = None;
                    if self
                        .device
                        .CreateTexture2D(&staging_desc, None, Some(&mut staging))
                        .is_err()
                    {
                        let _ = self.duplication.ReleaseFrame();
                        return None;
                    }
                    let staging = staging?;

                    // GPU → staging texture
                    self.context.CopyResource(&staging, &texture);

                    // staging → CPU 内存
                    let mut mapped = D3D11_MAPPED_SUBRESOURCE::default();
                    let _ = self
                        .context
                        .Map(&staging, 0, D3D11_MAP_READ, 0, Some(&mut mapped));

                    if mapped.pData.is_null() {
                        let _ = self.duplication.ReleaseFrame();
                        return None;
                    }

                    let width = desc.Width;
                    let height = desc.Height;
                    let row_pitch = mapped.RowPitch as usize;
                    let mut pixels = vec![0u8; (width as usize) * (height as usize) * 4];

                    let src = mapped.pData as *const u8;
                    for y in 0..height as usize {
                        let src_row = src.add(y * row_pitch);
                        let dst_offset = y * width as usize * 4;
                        std::ptr::copy_nonoverlapping(
                            src_row,
                            pixels[dst_offset..].as_mut_ptr(),
                            width as usize * 4,
                        );
                    }

                    self.context.Unmap(&staging, 0);
                    let _ = self.duplication.ReleaseFrame();

                    let frame = AcquiredFrame {
                        pixels,
                        width,
                        height,
                    };
                    // 缓存上一帧:桌面静止(WAIT_TIMEOUT)时复用,保持视频流活跃
                    self.last_frame = Some(frame.clone());
                    Some(frame)
                }
                Err(e) => {
                    let code = e.code().0;
                    // WAIT_TIMEOUT: 桌面静止无新帧,复用上一帧让编码器输出 P 帧/重复帧
                    // 这是解决"解码器就绪后一直转圈"的关键 —— 没有这个分支,桌面静止时
                    // capture_screen 返回 None,服务端不发包,客户端解码器收不到任何数据
                    if code == DXGI_ERROR_WAIT_TIMEOUT {
                        return self.last_frame.clone();
                    }
                    if code == DXGI_ERROR_ACCESS_LOST {
                        self.needs_rebuild = true;
                    }
                    None
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
