//! 远程桌面视频配置(从环境变量读取,由 PC 控制台注入)
//!
//! 环境变量:
//! - MEOWMIC_VIDEO_CODEC: "h264" | "hevc"(默认 h264)
//! - MEOWMIC_VIDEO_ADAPTER: "auto" | "nvidia" | "amd" | "intel" | "software"(默认 auto)
//! - MEOWMIC_VIDEO_MAX_WIDTH: 最大编码宽度(默认 1024,0=不限制)
//! - MEOWMIC_VIDEO_MAX_HEIGHT: 最大编码高度(默认 768,0=不限制)
//! - MEOWMIC_VIDEO_FPS: 目标帧率(默认 30,0=由客户端决定)
//! - MEOWMIC_VIDEO_BITRATE_MBPS: 目标码率 Mbps(默认 8,0=由客户端决定)

use crate::encoder::Codec;

#[derive(Debug, Clone)]
pub struct VideoConfig {
    pub codec: Codec,
    /// "auto" | "nvidia" | "amd" | "intel" | "software"
    pub adapter: String,
    pub max_width: u32,
    pub max_height: u32,
    /// 0 = 由客户端请求决定
    pub fps: u32,
    /// 0 = 由客户端请求决定
    pub bitrate_mbps: u32,
}

impl VideoConfig {
    /// 从环境变量加载,缺失值用默认
    pub fn from_env() -> Self {
        let codec = match std::env::var("MEOWMIC_VIDEO_CODEC").unwrap_or_default().to_lowercase().as_str() {
            "hevc" | "h265" => Codec::Hevc,
            _ => Codec::H264,
        };
        let adapter = std::env::var("MEOWMIC_VIDEO_ADAPTER")
            .unwrap_or_else(|_| "auto".to_string())
            .to_lowercase();
        let max_width = std::env::var("MEOWMIC_VIDEO_MAX_WIDTH")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(1024);
        let max_height = std::env::var("MEOWMIC_VIDEO_MAX_HEIGHT")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(768);
        let fps = std::env::var("MEOWMIC_VIDEO_FPS")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(0);
        let bitrate_mbps = std::env::var("MEOWMIC_VIDEO_BITRATE_MBPS")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(0);

        tracing::info!(
            "VideoConfig: codec={:?} adapter={} max={}x{} fps={} bitrate={}Mbps",
            codec, adapter, max_width, max_height, fps, bitrate_mbps
        );

        Self { codec, adapter, max_width, max_height, fps, bitrate_mbps }
    }

    /// 应用服务端覆盖:若配置了非零 fps/bitrate,覆盖客户端请求值
    pub fn override_client_request(&self, client_fps: u32, client_bitrate: u32) -> (u32, u32) {
        let fps = if self.fps > 0 { self.fps } else { client_fps };
        let bitrate = if self.bitrate_mbps > 0 {
            self.bitrate_mbps * 1_000_000
        } else {
            client_bitrate
        };
        (fps, bitrate)
    }

    /// 编码器选择偏好:返回是否强制软件编码
    pub fn force_software(&self) -> bool {
        self.adapter == "software"
    }

    /// 按厂商偏好过滤编码器(返回 true 表示该候选可接受)
    /// adapter_name 是 MFT 友好名称(通常含 NVIDIA/AMD/Intel/Qualcomm)
    pub fn accept_adapter(&self, adapter_name: &str) -> bool {
        if self.adapter == "auto" || self.adapter == "software" {
            return true;
        }
        let name_lower = adapter_name.to_lowercase();
        match self.adapter.as_str() {
            "nvidia" => name_lower.contains("nvidia") || name_lower.contains("nvenc"),
            "amd" => name_lower.contains("amd") || name_lower.contains("advanced micro") || name_lower.contains("amf"),
            "intel" => name_lower.contains("intel") || name_lower.contains("quicksync") || name_lower.contains("qsv"),
            _ => true,
        }
    }
}
