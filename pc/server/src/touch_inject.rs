//! 触摸事件注入(PC 端)
//!
//! P0 阶段:win32 SendInput 模拟鼠标相对移动
//! 已知限制:SendInput 有节流,且无法仿真真实触控板精度
//! 后续:替换为自研虚拟 HID 驱动(.sys),绕过节流,直接写 HID 报告

#[cfg(windows)]
use windows::Win32::UI::Input::KeyboardAndMouse::{
    INPUT, INPUT_MOUSE, MOUSEINPUT, MOUSEEVENTF_MOVE, SendInput,
};

pub struct TouchInjector;

impl TouchInjector {
    pub fn new() -> Self {
        Self
    }

    /// 注入触摸事件到系统
    ///
    /// - event_type: 0x01=Down 0x02=Move 0x03=Up 0x04=Button
    /// - button_mask: bit0=左 bit1=右 bit2=中
    /// - dx/dy: 相对位移(触控板模式,已缩放)
    /// - pressure: [0,255]
    pub fn inject(&self, event_type: u8, _button_mask: u8, dx: f32, dy: f32, _pressure: u8) {
        #[cfg(windows)]
        {
            // 相对移动,只处理有位移的 Move 事件(P0 简化)
            if event_type == 0x02 && (dx.abs() > 0.01 || dy.abs() > 0.01) {
                let dx_i = dx.round() as i32;
                let dy_i = dy.round() as i32;
                unsafe {
                    let mut input = INPUT {
                        r#type: INPUT_MOUSE,
                        Anonymous: std::mem::zeroed(),
                    };
                    input.Anonymous.mi = MOUSEINPUT {
                        dx: dx_i,
                        dy: dy_i,
                        mouseData: 0,
                        dwFlags: MOUSEEVENTF_MOVE,
                        time: 0,
                        dwExtraInfo: 0,
                    };
                    let _ = SendInput(&[input], std::mem::size_of::<INPUT>() as i32);
                }
            }
        }
        #[cfg(not(windows))]
        {
            let _ = (event_type, button_mask, dx, dy, _pressure);
            tracing::debug!(
                "touch inject (no-op on non-windows): type={} btn={} dx={:.1} dy={:.1}",
                event_type,
                button_mask,
                dx,
                dy
            );
        }
    }
}
