//! 触摸事件注入(PC 端)
//!
//! P0 阶段:win32 SendInput 模拟鼠标相对移动 + 按键点击
//! 已知限制:SendInput 有节流,且无法仿真真实触控板精度
//! 后续:替换为自研虚拟 HID 驱动(.sys),绕过节流,直接写 HID 报告

#[cfg(windows)]
use windows::Win32::UI::Input::KeyboardAndMouse::{
    INPUT, INPUT_MOUSE, MOUSE_EVENT_FLAGS, MOUSEINPUT, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP,
    MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP, MOUSEEVENTF_MOVE, MOUSEEVENTF_RIGHTDOWN,
    MOUSEEVENTF_RIGHTUP, MOUSEEVENTF_WHEEL, SendInput,
};

/// 事件类型常量(与 protocol::TouchEventType 对应)
const EVT_DOWN: u8 = 0x01;
const EVT_MOVE: u8 = 0x02;
const EVT_UP: u8 = 0x03;
const EVT_BUTTON: u8 = 0x04;
const EVT_SCROLL: u8 = 0x05;

/// 按钮掩码位
const BTN_LEFT: u8 = 0x01;
const BTN_RIGHT: u8 = 0x02;
const BTN_MIDDLE: u8 = 0x04;

pub struct TouchInjector;

impl TouchInjector {
    pub fn new() -> Self {
        Self
    }

    /// 注入触摸事件到系统
    ///
    /// - event_type: 0x01=Down 0x02=Move 0x03=Up 0x04=Button 0x05=Scroll
    /// - button_mask: bit0=左 bit1=右 bit2=中
    ///   对于 Button 事件:表示"哪些键被触发",配合 dx 表达按下(dx>0)/抬起(dx<=0)
    ///   对于 Scroll 事件:button_mask 未使用,dy 为滚动量
    /// - dx/dy: 相对位移(触控板模式,已缩放)
    /// - pressure: [0,255]
    pub fn inject(&self, event_type: u8, button_mask: u8, dx: f32, dy: f32, _pressure: u8) {
        #[cfg(windows)]
        {
            unsafe {
                match event_type {
                    EVT_MOVE => {
                        if dx.abs() > 0.01 || dy.abs() > 0.01 {
                            let dx_i = dx.round() as i32;
                            let dy_i = dy.round() as i32;
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
                    EVT_SCROLL => {
                        // dy 为滚动量(像素),转换为滚轮点击数(每 100 像素约 1 个刻度)
                        let wheel_delta = (dy * -12.0).round() as i32;
                        if wheel_delta != 0 {
                            let mut input = INPUT {
                                r#type: INPUT_MOUSE,
                                Anonymous: std::mem::zeroed(),
                            };
                            input.Anonymous.mi = MOUSEINPUT {
                                dx: 0,
                                dy: 0,
                                mouseData: wheel_delta as u32,
                                dwFlags: MOUSEEVENTF_WHEEL,
                                time: 0,
                                dwExtraInfo: 0,
                            };
                            let _ = SendInput(&[input], std::mem::size_of::<INPUT>() as i32);
                        }
                    }
                    EVT_BUTTON => {
                        // dx > 0 表示按下, dx <= 0 表示抬起
                        let press = dx > 0.0;
                        let flags = button_flags(button_mask, press);
                        if flags.0 != 0 {
                            let mut input = INPUT {
                                r#type: INPUT_MOUSE,
                                Anonymous: std::mem::zeroed(),
                            };
                            input.Anonymous.mi = MOUSEINPUT {
                                dx: 0,
                                dy: 0,
                                mouseData: 0,
                                dwFlags: flags,
                                time: 0,
                                dwExtraInfo: 0,
                            };
                            let _ = SendInput(&[input], std::mem::size_of::<INPUT>() as i32);
                        }
                    }
                    _ => {
                        // Down/Up 在触控板模式下不直接映射到按键,忽略
                    }
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

/// 根据 button_mask 和 press/释放 计算对应的 mouse event flags
#[cfg(windows)]
fn button_flags(button_mask: u8, press: bool) -> MOUSE_EVENT_FLAGS {
    let mut flags = MOUSE_EVENT_FLAGS(0);
    if button_mask & BTN_LEFT != 0 {
        flags |= if press { MOUSEEVENTF_LEFTDOWN } else { MOUSEEVENTF_LEFTUP };
    }
    if button_mask & BTN_RIGHT != 0 {
        flags |= if press { MOUSEEVENTF_RIGHTDOWN } else { MOUSEEVENTF_RIGHTUP };
    }
    if button_mask & BTN_MIDDLE != 0 {
        flags |= if press { MOUSEEVENTF_MIDDLEDOWN } else { MOUSEEVENTF_MIDDLEUP };
    }
    flags
}
