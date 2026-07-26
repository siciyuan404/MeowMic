//! 触摸事件处理
//!
//! P0 阶段:基础坐标变换 + 速度估计
//! 后续:多指手势识别、平滑滤波

use meowmic_protocol::TouchEventType;

/// 原始触摸样本(来自 Android MotionEvent)
#[derive(Debug, Clone, Copy, Default)]
pub struct TouchSample {
    /// 事件类型
    pub event: TouchSampleType,
    /// 相对位移(触控板模式,屏幕像素 → 鼠标像素的缩放后)
    pub dx: f32,
    pub dy: f32,
    /// 压力 [0,1]
    pub pressure: f32,
    /// Android getEventTimeNanos
    pub ts_ns: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum TouchSampleType {
    #[default]
    Move,
    Down,
    Up,
}

impl TouchSampleType {
    pub fn to_packet_type(self) -> TouchEventType {
        match self {
            TouchSampleType::Down => TouchEventType::Down,
            TouchSampleType::Move => TouchEventType::Move,
            TouchSampleType::Up => TouchEventType::Up,
        }
    }
}

/// 触控板灵敏度配置
#[derive(Debug, Clone, Copy)]
pub struct TouchpadConfig {
    /// 水平灵敏度倍率(1.0 = 1:1)
    pub speed_x: f32,
    /// 垂直灵敏度倍率
    pub speed_y: f32,
    /// 是否反转垂直方向(自然滚动风格)
    pub invert_y: bool,
    /// 移动阈值,低于此值的位移忽略(降噪)
    pub dead_zone: f32,
}

impl Default for TouchpadConfig {
    fn default() -> Self {
        Self {
            speed_x: 1.0,
            speed_y: 1.0,
            invert_y: false,
            dead_zone: 0.5,
        }
    }
}

/// 应用灵敏度 + 死区,产出最终相对位移
pub fn apply_transform(sample: &TouchSample, cfg: &TouchpadConfig) -> (f32, f32) {
    let mut dx = sample.dx * cfg.speed_x;
    let mut dy = sample.dy * cfg.speed_y;
    if cfg.invert_y {
        dy = -dy;
    }
    if dx.abs() < cfg.dead_zone {
        dx = 0.0;
    }
    if dy.abs() < cfg.dead_zone {
        dy = 0.0;
    }
    (dx, dy)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dead_zone_zeroes_small_motion() {
        let sample = TouchSample {
            dx: 0.3,
            dy: -0.2,
            ..Default::default()
        };
        let cfg = TouchpadConfig::default();
        let (dx, dy) = apply_transform(&sample, &cfg);
        assert_eq!(dx, 0.0);
        assert_eq!(dy, 0.0);
    }

    #[test]
    fn invert_y_flips_vertical() {
        let sample = TouchSample {
            dx: 0.0,
            dy: 5.0,
            ..Default::default()
        };
        let cfg = TouchpadConfig {
            invert_y: true,
            ..Default::default()
        };
        let (_, dy) = apply_transform(&sample, &cfg);
        assert_eq!(dy, -5.0);
    }
}
