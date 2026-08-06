//! 屏幕抓取(远程显示器功能)
//!
//! 使用 GDI BitBlt 抓取主显示器整屏画面,编码为 PNG 返回。
//! 适用于低帧率远程查看(每 100~200ms 轮询一帧);后续可升级为
//! DXGI Desktop Duplication + H.264 编码以支持高帧率视频流。
//!
//! 端点(挂在 base_port+4 的 serverinfo 服务上,复用配对鉴权):
//! - GET /screen/capture?pubkey=<b64>&quality=<0-100>&scale=<0.25|0.5|1>  返回 PNG 字节
//! - GET /screen/info?pubkey=<b64>  返回屏幕分辨率 JSON

#[cfg(windows)]
pub fn capture_screen_png(quality: u8, scale: f32) -> Option<Vec<u8>> {
    use windows::Win32::Foundation::HWND;
    use windows::Win32::Graphics::Gdi::{
        BitBlt, CreateCompatibleBitmap, CreateCompatibleDC, DeleteDC, DeleteObject,
        GetDIBits, ReleaseDC, SelectObject, GetDC,
        BITMAPINFO, BITMAPINFOHEADER, DIB_RGB_COLORS, SRCCOPY, BI_RGB,
    };
    use windows::Win32::UI::WindowsAndMessaging::{GetSystemMetrics, SM_CXSCREEN, SM_CYSCREEN};

    // 限制参数范围
    let _q = quality.min(100);
    let s = scale.clamp(0.1, 1.0);

    unsafe {
        // 获取屏幕 DC(GetDC(null) = 整个屏幕)
        let hwnd = HWND(std::ptr::null_mut());
        let screen_dc = GetDC(hwnd);
        if screen_dc.is_invalid() {
            return None;
        }

        let screen_w = GetSystemMetrics(SM_CXSCREEN);
        let screen_h = GetSystemMetrics(SM_CYSCREEN);
        if screen_w <= 0 || screen_h <= 0 {
            let _ = ReleaseDC(hwnd, screen_dc);
            return None;
        }

        // 目标尺寸(按比例缩放)
        let dst_w = ((screen_w as f32) * s).max(1.0) as i32;
        let dst_h = ((screen_h as f32) * s).max(1.0) as i32;

        // 创建兼容 DC + 位图
        let mem_dc = CreateCompatibleDC(screen_dc);
        if mem_dc.is_invalid() {
            let _ = ReleaseDC(hwnd, screen_dc);
            return None;
        }
        let bmp = CreateCompatibleBitmap(screen_dc, dst_w, dst_h);
        if bmp.is_invalid() {
            let _ = DeleteDC(mem_dc);
            let _ = ReleaseDC(hwnd, screen_dc);
            return None;
        }
        let old_bmp = SelectObject(mem_dc, bmp);

        // BitBlt 拷贝屏幕到内存位图(SRCCOPY)
        let _ = BitBlt(mem_dc, 0, 0, dst_w, dst_h, screen_dc, 0, 0, SRCCOPY);

        // 准备 BITMAPINFO 以读取像素(BGRA 32 位,top-down)
        let mut bi: BITMAPINFO = std::mem::zeroed();
        bi.bmiHeader.biSize = std::mem::size_of::<BITMAPINFOHEADER>() as u32;
        bi.bmiHeader.biWidth = dst_w;
        bi.bmiHeader.biHeight = -dst_h; // 负值 = top-down
        bi.bmiHeader.biPlanes = 1;
        bi.bmiHeader.biBitCount = 32;
        bi.bmiHeader.biCompression = BI_RGB.0;

        let pixel_count = (dst_w as usize) * (dst_h as usize);
        let mut pixels: Vec<u8> = vec![0u8; pixel_count * 4];

        let got = GetDIBits(
            mem_dc,
            bmp,
            0,
            dst_h as u32,
            Some(pixels.as_mut_ptr() as *mut _),
            &mut bi,
            DIB_RGB_COLORS,
        );
        if got == 0 {
            let _ = SelectObject(mem_dc, old_bmp);
            let _ = DeleteObject(bmp);
            let _ = DeleteDC(mem_dc);
            let _ = ReleaseDC(hwnd, screen_dc);
            return None;
        }

        // 清理 GDI 资源
        let _ = SelectObject(mem_dc, old_bmp);
        let _ = DeleteObject(bmp);
        let _ = DeleteDC(mem_dc);
        let _ = ReleaseDC(hwnd, screen_dc);

        // 转换 BGRA -> RGBA(image crate 需要 RGBA)
        let mut rgba: Vec<u8> = vec![0u8; pixel_count * 4];
        for i in 0..pixel_count {
            let b = pixels[i * 4];
            let g = pixels[i * 4 + 1];
            let r = pixels[i * 4 + 2];
            rgba[i * 4] = r;
            rgba[i * 4 + 1] = g;
            rgba[i * 4 + 2] = b;
            rgba[i * 4 + 3] = 255; // 不透明
        }

        encode_png(&rgba, dst_w as u32, dst_h as u32).ok()
    }
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

#[cfg(not(windows))]
pub fn capture_screen_png(_quality: u8, _scale: f32) -> Option<Vec<u8>> {
    None
}

/// 屏幕分辨率(供客户端 UI 显示)
#[cfg(windows)]
pub fn screen_resolution() -> (u32, u32) {
    use windows::Win32::UI::WindowsAndMessaging::{GetSystemMetrics, SM_CXSCREEN, SM_CYSCREEN};
    unsafe {
        let w = GetSystemMetrics(SM_CXSCREEN).max(0) as u32;
        let h = GetSystemMetrics(SM_CYSCREEN).max(0) as u32;
        (w, h)
    }
}

#[cfg(not(windows))]
pub fn screen_resolution() -> (u32, u32) {
    (0, 0)
}
