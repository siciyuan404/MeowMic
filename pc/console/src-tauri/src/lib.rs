// windows_subsystem 在 main.rs 中设置

use serde::{Deserialize, Serialize};
use std::env;
use std::fs;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4, TcpStream};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tauri::{
    CustomMenuItem, Manager, State, SystemTray, SystemTrayEvent, SystemTrayMenu,
    SystemTrayMenuItem, WindowEvent,
};

const CONFIG_FILE: &str = "meowmic-console.json";
/// Windows 开机启动注册表键(当前用户)
#[cfg(target_os = "windows")]
const RUN_REGISTRY_KEY: &str = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
/// 注册表中开机启动项的名称
const AUTOSTART_REG_NAME: &str = "MeowMicConsole";

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct AppConfig {
    pub base_port: u16,
    pub output_device: String,
    pub mute_speaker: bool,
    pub auto_start: bool,
    pub sensitivity: f32,
    /// 开机启动(写入 Windows 注册表 HKCU\...\Run)
    #[serde(default)]
    pub launch_at_login: bool,
    /// 关闭窗口时最小化到系统托盘(而非退出)
    #[serde(default)]
    pub minimize_to_tray: bool,
    /// 远程桌面设置
    #[serde(default)]
    pub remote_desktop: RemoteDesktopConfig,
}

/// 远程桌面设置(视频编码相关)
#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct RemoteDesktopConfig {
    /// 编码器类型: "h264" | "hevc"(HEVC 需硬件支持,失败回退 H.264)
    #[serde(default = "default_codec")]
    pub codec: String,
    /// GPU 适配器选择: "auto" | "nvidia" | "amd" | "intel" | "software"
    /// auto=自动选择最佳硬件编码器,software=强制软件编码
    #[serde(default = "default_adapter")]
    pub adapter: String,
    /// 最大分辨率宽度(像素,0 表示按屏幕原始分辨率)
    #[serde(default = "default_max_width")]
    pub max_width: u32,
    /// 最大分辨率高度(像素,0 表示按屏幕原始分辨率)
    #[serde(default = "default_max_height")]
    pub max_height: u32,
    /// 目标帧率
    #[serde(default = "default_fps")]
    pub fps: u32,
    /// 目标码率(Mbps)
    #[serde(default = "default_bitrate")]
    pub bitrate_mbps: u32,
}

fn default_codec() -> String { "h264".to_string() }
fn default_adapter() -> String { "auto".to_string() }
fn default_max_width() -> u32 { 1024 }
fn default_max_height() -> u32 { 768 }
fn default_fps() -> u32 { 30 }
fn default_bitrate() -> u32 { 8 }

impl Default for RemoteDesktopConfig {
    fn default() -> Self {
        Self {
            codec: default_codec(),
            adapter: default_adapter(),
            max_width: default_max_width(),
            max_height: default_max_height(),
            fps: default_fps(),
            bitrate_mbps: default_bitrate(),
        }
    }
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            base_port: 28900,
            output_device: String::new(),
            mute_speaker: false,
            auto_start: false,
            sensitivity: 1.2,
            launch_at_login: false,
            minimize_to_tray: true,
            remote_desktop: RemoteDesktopConfig::default(),
        }
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ServiceStatus {
    pub running: bool,
    pub uptime_secs: u64,
    pub connections: u32,
    pub touches_per_sec: u32,
    pub audio_frames_per_sec: u32,
    pub last_error: Option<String>,
}

#[derive(Debug, Deserialize)]
struct StatsResponse {
    pub connections: u32,
    pub touches_per_sec: u32,
    pub audio_frames_per_sec: u32,
}

/// 配对状态响应(GET /pairing)
#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct PairingState {
    /// 当前 PIN(null 表示无 PIN,服务端未启动或配对未启用)
    pub pin: Option<String>,
    /// 已配对客户端列表
    pub paired_clients: Vec<PairedClientInfo>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct PairedClientInfo {
    pub pubkey_b64: String,
    pub client_name: String,
    pub paired_at: u64,
}

/// server 日志行:由后台线程从 meowmic-server 的 stdout/stderr 读取后,
/// 作为 `server-log` 事件推送到前端"服务日志"面板。
#[derive(Clone, Serialize)]
pub struct ServerLog {
    /// 级别:"info"(stdout) 或 "warn"(stderr)
    pub level: String,
    pub line: String,
}

pub struct ServiceManager {
    process: Option<Child>,
    started_at: Option<std::time::Instant>,
    base_port: Option<u16>,
    status: ServiceStatus,
}

impl Default for ServiceManager {
    fn default() -> Self {
        Self::new()
    }
}

impl ServiceManager {
    pub fn new() -> Self {
        Self {
            process: None,
            started_at: None,
            base_port: None,
            status: ServiceStatus {
                running: false,
                uptime_secs: 0,
                connections: 0,
                touches_per_sec: 0,
                audio_frames_per_sec: 0,
                last_error: None,
            },
        }
    }

    pub fn is_running(&mut self) -> bool {
        match &mut self.process {
            Some(p) => match p.try_wait() {
                Ok(None) => true,
                Ok(Some(status)) => {
                    // server 进程已退出,记录退出码用于诊断
                    self.status.last_error = Some(format!(
                        "server 进程已退出(退出码: {})",
                        status
                    ));
                    false
                }
                Err(e) => {
                    self.status.last_error = Some(format!("检查 server 状态失败: {}", e));
                    false
                }
            },
            None => false,
        }
    }

    /// 当前服务绑定的基础端口(服务未运行时返回 None)
    pub fn base_port(&self) -> Option<u16> {
        self.base_port
    }

    pub fn status(&mut self) -> ServiceStatus {
        if let Some(started) = self.started_at {
            self.status.uptime_secs = started.elapsed().as_secs();
        }
        self.status.running = self.is_running();
        if self.status.running {
            if let Some(base_port) = self.base_port {
                match fetch_stats(base_port) {
                    Some(stats) => {
                        self.status.connections = stats.connections;
                        self.status.touches_per_sec = stats.touches_per_sec;
                        self.status.audio_frames_per_sec = stats.audio_frames_per_sec;
                    }
                    None => {
                        self.status.connections = 0;
                        self.status.touches_per_sec = 0;
                        self.status.audio_frames_per_sec = 0;
                    }
                }
            }
        } else {
            self.status.connections = 0;
            self.status.touches_per_sec = 0;
            self.status.audio_frames_per_sec = 0;
        }
        self.status.clone()
    }

    pub fn start(&mut self, config: &AppConfig, app: &tauri::AppHandle) -> Result<(), String> {
        if self.is_running() {
            return Err("服务已在运行".to_string());
        }

        let server_exe = find_server_executable()?;

        let mut cmd = Command::new(&server_exe);
        // server 使用 --port 基础端口 (control=port, touch=port+1, audio=port+2)
        cmd.arg("--port").arg(config.base_port.to_string());

        // 静音外放:通过环境变量传递
        if config.mute_speaker {
            cmd.env("MEOWMIC_MUTE_SPEAKER", "1");
        }

        if !config.output_device.is_empty() {
            cmd.arg("--output-device").arg(&config.output_device);
        }

        // 远程桌面设置:通过环境变量传递给 server
        // (用环境变量而非 CLI 参数,避免 server 的 clap 定义膨胀,且便于扩展)
        let rd = &config.remote_desktop;
        cmd.env("MEOWMIC_VIDEO_CODEC", &rd.codec);
        cmd.env("MEOWMIC_VIDEO_ADAPTER", &rd.adapter);
        cmd.env("MEOWMIC_VIDEO_MAX_WIDTH", rd.max_width.to_string());
        cmd.env("MEOWMIC_VIDEO_MAX_HEIGHT", rd.max_height.to_string());
        cmd.env("MEOWMIC_VIDEO_FPS", rd.fps.to_string());
        cmd.env("MEOWMIC_VIDEO_BITRATE_MBPS", rd.bitrate_mbps.to_string());

        // stdout/stderr 用 piped:在后台线程持续读取并转发到前端"服务日志"面板。
        // 必须持续读取:否则 4KB 管道缓冲填满后 server 日志写入阻塞,导致无法接受新连接。
        cmd.stdout(Stdio::piped());
        cmd.stderr(Stdio::piped());
        cmd.stdin(Stdio::null());

        // Windows 下隐藏 meowmic-server 子进程的命令行窗口
        // CREATE_NO_WINDOW = 0x08000000,避免控制台程序弹出黑色 cmd 窗口
        #[cfg(target_os = "windows")]
        {
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x08000000;
            cmd.creation_flags(CREATE_NO_WINDOW);
        }

        match cmd.spawn() {
            Ok(mut child) => {
                // 捕获 server stdout/stderr,逐行作为 server-log 事件推送到前端
                if let Some(stdout) = child.stdout.take() {
                    spawn_log_reader(stdout, app.clone(), "info");
                }
                if let Some(stderr) = child.stderr.take() {
                    spawn_log_reader(stderr, app.clone(), "warn");
                }
                self.process = Some(child);
                self.started_at = Some(std::time::Instant::now());
                self.base_port = Some(config.base_port);
                self.status.last_error = None;
                Ok(())
            }
            Err(e) => {
                self.status.last_error = Some(e.to_string());
                Err(e.to_string())
            }
        }
    }

    pub fn stop(&mut self) -> Result<(), String> {
        if let Some(ref mut child) = self.process {
            child.kill().map_err(|e| e.to_string())?;
            child.wait().map_err(|e| e.to_string())?;
            self.process = None;
            self.started_at = None;
            self.status.uptime_secs = 0;
        }
        Ok(())
    }

    /// 运行时切换外放静音:通过 HTTP /mute?on=1|0 通知正在运行的 server
    /// 服务未运行时返回 Ok(()),仅靠持久化的配置在下次启动时生效
    pub fn set_mute_speaker(&mut self, muted: bool) -> Result<(), String> {
        let Some(base_port) = self.base_port else {
            return Ok(());
        };
        if !self.is_running() {
            return Ok(());
        }
        send_mute_http(base_port, muted)
    }
}

impl Drop for ServiceManager {
    fn drop(&mut self) {
        let _ = self.stop();
    }
}

/// 后台线程持续读取子进程的 stdout/stderr,逐行作为 `server-log` 事件推送到前端。
/// 持续读取是必须的:管道 4KB 缓冲填满后 server 日志写入会阻塞,导致无法接受新连接。
/// 进程退出时管道关闭,`lines()` 迭代自然结束,线程退出。
fn spawn_log_reader<R: Read + Send + 'static>(stream: R, app: tauri::AppHandle, level: &'static str) {
    std::thread::spawn(move || {
        let reader = BufReader::new(stream);
        for line in reader.lines() {
            match line {
                Ok(l) if !l.trim().is_empty() => {
                    let _ = app.emit_all(
                        "server-log",
                        ServerLog {
                            level: level.to_string(),
                            line: l,
                        },
                    );
                }
                Ok(_) => {}
                Err(_) => break,
            }
        }
    });
}

/// 通过 HTTP GET 拉取 server 的 /stats 统计接口。
/// 任何失败(连接拒绝、超时、解析错误)都返回 None,不阻断 status。
fn fetch_stats(base_port: u16) -> Option<StatsResponse> {
    let port = base_port.checked_add(3)?;
    let addr = SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::LOCALHOST, port));

    let mut stream = TcpStream::connect_timeout(&addr, Duration::from_secs(1)).ok()?;
    stream.set_read_timeout(Some(Duration::from_secs(1))).ok()?;
    stream.set_write_timeout(Some(Duration::from_secs(1))).ok()?;

    let request = "GET /stats HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";
    stream.write_all(request.as_bytes()).ok()?;

    let mut response = String::new();
    stream.read_to_string(&mut response).ok()?;

    // 跳过 HTTP 头部,取 \r\n\r\n 之后的 body
    let body = response.split("\r\n\r\n").nth(1)?;
    serde_json::from_str(body).ok()
}

/// 通过 HTTP GET /mute?on=1|0 通知 server 切换外放静音
fn send_mute_http(base_port: u16, muted: bool) -> Result<(), String> {
    let port = base_port.checked_add(3).ok_or("端口溢出")?;
    let addr = SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::LOCALHOST, port));

    let mut stream = TcpStream::connect_timeout(&addr, Duration::from_secs(1))
        .map_err(|e| format!("连接 server 失败: {}", e))?;
    stream.set_read_timeout(Some(Duration::from_secs(1))).map_err(|e| e.to_string())?;
    stream.set_write_timeout(Some(Duration::from_secs(1))).map_err(|e| e.to_string())?;

    let on = if muted { 1 } else { 0 };
    let request = format!(
        "GET /mute?on={} HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
        on
    );
    stream.write_all(request.as_bytes()).map_err(|e| e.to_string())?;

    let mut response = String::new();
    stream.read_to_string(&mut response).map_err(|e| e.to_string())?;

    if !response.starts_with("HTTP/1.1 200 OK") {
        return Err(format!("server 响应异常: {}", response.lines().next().unwrap_or("")));
    }
    Ok(())
}

/// 通过 HTTP GET 拉取 server 的 /pairing 配对状态接口。
/// 失败返回 Err(供前端显示"服务未启动"等提示)。
fn fetch_pairing_state(base_port: u16) -> Result<PairingState, String> {
    let port = base_port.checked_add(5).ok_or("端口溢出")?;
    let addr = SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::LOCALHOST, port));

    let mut stream = TcpStream::connect_timeout(&addr, Duration::from_secs(1))
        .map_err(|e| format!("连接 server 失败(服务未启动?): {}", e))?;
    stream.set_read_timeout(Some(Duration::from_secs(1))).map_err(|e| e.to_string())?;
    stream.set_write_timeout(Some(Duration::from_secs(1))).map_err(|e| e.to_string())?;

    let request = "GET /pairing HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";
    stream.write_all(request.as_bytes()).map_err(|e| e.to_string())?;

    let mut response = String::new();
    stream.read_to_string(&mut response).map_err(|e| e.to_string())?;

    let body = response.split("\r\n\r\n").nth(1).ok_or("HTTP 响应缺少 body")?;
    serde_json::from_str(body).map_err(|e| format!("解析配对状态失败: {}", e))
}

/// 通过 HTTP POST 调用 server 的配对管理接口
/// - path: "/pairing/reset" 或 "/pairing/unpair?pubkey=..."
fn post_pairing_action(base_port: u16, path: &str) -> Result<String, String> {
    let port = base_port.checked_add(5).ok_or("端口溢出")?;
    let addr = SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::LOCALHOST, port));

    let mut stream = TcpStream::connect_timeout(&addr, Duration::from_secs(1))
        .map_err(|e| format!("连接 server 失败: {}", e))?;
    stream.set_read_timeout(Some(Duration::from_secs(1))).map_err(|e| e.to_string())?;
    stream.set_write_timeout(Some(Duration::from_secs(1))).map_err(|e| e.to_string())?;

    let request = format!(
        "POST {} HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
        path
    );
    stream.write_all(request.as_bytes()).map_err(|e| e.to_string())?;

    let mut response = String::new();
    stream.read_to_string(&mut response).map_err(|e| e.to_string())?;

    let status_line = response.lines().next().unwrap_or("");
    if !status_line.contains("200 OK") {
        let body = response.split("\r\n\r\n").nth(1).unwrap_or("");
        return Err(format!("server 响应异常: {} {}", status_line, body));
    }
    Ok(response.split("\r\n\r\n").nth(1).unwrap_or("").to_string())
}

fn find_server_executable() -> Result<PathBuf, String> {
    let exe_dir = env::current_exe()
        .map_err(|e| e.to_string())?
        .parent()
        .ok_or("无法获取可执行文件目录")?
        .to_path_buf();

    let mut candidates = vec![
        exe_dir.join("meowmic-server.exe"),
        exe_dir.join("meowmic-server"),
        exe_dir.join("bin").join("meowmic-server.exe"),
        exe_dir.join("bin").join("meowmic-server"),
        exe_dir.join("resources").join("meowmic-server.exe"),
        exe_dir.join("resources").join("meowmic-server"),
        // target/debug 或 target/release 同级的另一个目录
        exe_dir.join("..").join("release").join("meowmic-server.exe"),
        exe_dir.join("..").join("release").join("meowmic-server"),
        exe_dir.join("..").join("debug").join("meowmic-server.exe"),
        exe_dir.join("..").join("debug").join("meowmic-server"),
        exe_dir.join("..").join("resources").join("meowmic-server.exe"),
        exe_dir.join("..").join("resources").join("meowmic-server"),
        exe_dir.join("..").join("bin").join("meowmic-server.exe"),
        exe_dir.join("..").join("bin").join("meowmic-server"),
    ];

    // 开发模式下从 cwd 与 workspace 根查找
    if let Ok(cwd) = env::current_dir() {
        candidates.push(cwd.join("target").join("release").join("meowmic-server.exe"));
        candidates.push(cwd.join("target").join("release").join("meowmic-server"));
        candidates.push(cwd.join("target").join("debug").join("meowmic-server.exe"));
        candidates.push(cwd.join("target").join("debug").join("meowmic-server"));
        candidates.push(cwd.join("pc").join("console").join("bin").join("meowmic-server.exe"));
        candidates.push(cwd.join("pc").join("console").join("bin").join("meowmic-server"));
        // src-tauri 作为 cwd 时,workspace 根在上两级
        candidates.push(cwd.join("..").join("..").join("target").join("release").join("meowmic-server.exe"));
        candidates.push(cwd.join("..").join("..").join("target").join("release").join("meowmic-server"));
        candidates.push(cwd.join("..").join("..").join("target").join("debug").join("meowmic-server.exe"));
        candidates.push(cwd.join("..").join("..").join("target").join("debug").join("meowmic-server"));
    }

    let search_paths: Vec<String> = candidates.iter().map(|p| format!("  {}", p.display())).collect();

    for candidate in candidates {
        if candidate.exists() {
            return Ok(candidate);
        }
    }

    Err(format!(
        "找不到 meowmic-server.exe,请确认服务端已编译并放置在正确位置。\n搜索路径:\n{}",
        search_paths.join("\n")
    ))
}

fn config_path() -> PathBuf {
    let exe_dir = env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.to_path_buf()))
        .unwrap_or_else(|| PathBuf::from("."));
    exe_dir.join(CONFIG_FILE)
}

fn load_config() -> AppConfig {
    let path = config_path();
    if path.exists() {
        if let Ok(content) = fs::read_to_string(&path) {
            if let Ok(config) = serde_json::from_str(&content) {
                return config;
            }
        }
    }
    AppConfig::default()
}

fn save_config(config: &AppConfig) -> Result<(), String> {
    let path = config_path();
    let content = serde_json::to_string_pretty(config).map_err(|e| e.to_string())?;
    fs::write(&path, content).map_err(|e| e.to_string())
}

pub struct AppState {
    pub service: std::sync::Mutex<ServiceManager>,
}

#[tauri::command]
fn get_config() -> AppConfig {
    let mut config = load_config();
    // launch_at_login 以注册表实际状态为准(避免外部删除/修改后配置文件不一致)
    config.launch_at_login = query_launch_at_login();
    config
}

#[tauri::command]
fn save_app_config(config: AppConfig) -> Result<(), String> {
    save_config(&config)
}

#[tauri::command]
fn start_service(state: State<AppState>, app: tauri::AppHandle, config: AppConfig) -> Result<(), String> {
    save_config(&config)?;
    let mut svc = state.service.lock().unwrap();
    svc.start(&config, &app)
}

#[tauri::command]
fn stop_service(state: State<AppState>) -> Result<(), String> {
    let mut svc = state.service.lock().unwrap();
    svc.stop()
}

#[tauri::command]
fn get_status(state: State<AppState>) -> ServiceStatus {
    let mut svc = state.service.lock().unwrap();
    svc.status()
}

#[tauri::command]
fn get_output_devices() -> Result<Vec<String>, String> {
    use cpal::traits::{DeviceTrait, HostTrait};
    let host = cpal::default_host();
    let mut devices = vec!["系统默认输出设备".to_string()];
    let iter = host.output_devices().map_err(|e| e.to_string())?;
    for device in iter {
        if let Ok(name) = device.name() {
            devices.push(name);
        }
    }
    Ok(devices)
}

/// GPU 适配器信息
#[derive(Debug, Serialize, Clone)]
pub struct GpuAdapter {
    pub id: String,       // "auto" | "nvidia" | "amd" | "intel" | "software"
    pub name: String,     // 显示名
    pub available: bool,  // 是否可用
}

/// 枚举系统 GPU 适配器(用于远程桌面编码器选择)
#[tauri::command]
fn list_video_adapters() -> Result<Vec<GpuAdapter>, String> {
    // 静态列表 + 简单检测:实际枚举 DXGI 适配器
    let mut adapters = vec![
        GpuAdapter { id: "auto".to_string(), name: "自动(优先硬件)".to_string(), available: true },
    ];

    #[cfg(target_os = "windows")]
    {
        use windows::Win32::Graphics::Dxgi::{CreateDXGIFactory1, IDXGIFactory1, DXGI_ADAPTER_DESC};

        // 通过 DXGI 枚举适配器,检测厂商
        let factory: IDXGIFactory1 = unsafe {
            CreateDXGIFactory1::<IDXGIFactory1>().map_err(|e| e.to_string())?
        };

        let mut idx: u32 = 0;
        let mut has_nvidia = false;
        let mut has_amd = false;
        let mut has_intel = false;

        loop {
            let adapter = unsafe { factory.EnumAdapters1(idx) };
            match adapter {
                Ok(adapter) => {
                    let desc: DXGI_ADAPTER_DESC = unsafe {
                        adapter.GetDesc().unwrap_or_default()
                    };
                    // Vendor ID: NVIDIA=0x10DE, AMD=0x1002, Intel=0x8086
                    let vendor_name = match desc.VendorId {
                        0x10DE => { has_nvidia = true; "NVIDIA" }
                        0x1002 => { has_amd = true; "AMD" }
                        0x8086 => { has_intel = true; "Intel" }
                        _ => "未知厂商",
                    };
                    let name = String::from_utf16_lossy(&desc.Description)
                        .trim_end_matches('\0')
                        .to_string();
                    adapters.push(GpuAdapter {
                        id: vendor_name.to_lowercase(),
                        name: format!("{} ({})", vendor_name, name),
                        available: true,
                    });
                    idx += 1;
                }
                Err(_) => break,
            }
        }

        if !has_nvidia {
            adapters.push(GpuAdapter { id: "nvidia".to_string(), name: "NVIDIA (未检测到)".to_string(), available: false });
        }
        if !has_amd {
            adapters.push(GpuAdapter { id: "amd".to_string(), name: "AMD (未检测到)".to_string(), available: false });
        }
        if !has_intel {
            adapters.push(GpuAdapter { id: "intel".to_string(), name: "Intel (未检测到)".to_string(), available: false });
        }
    }

    adapters.push(GpuAdapter { id: "software".to_string(), name: "软件编码(兼容性最好,性能最低)".to_string(), available: true });

    Ok(adapters)
}

/// 运行时切换外放静音。
/// 持久化配置,服务运行中则同步推送到 server;未运行时仅保存,下次启动生效。
#[tauri::command]
fn set_mute_speaker(state: State<AppState>, muted: bool) -> Result<(), String> {
    let mut config = load_config();
    config.mute_speaker = muted;
    save_config(&config)?;

    let mut svc = state.service.lock().unwrap();
    if svc.is_running() {
        svc.set_mute_speaker(muted)?;
    }
    Ok(())
}

/// 查询当前配对状态(PIN + 已配对客户端列表)
/// 服务未运行时返回错误,前端显示提示
#[tauri::command]
fn get_pairing_state(state: State<AppState>) -> Result<PairingState, String> {
    let mut svc = state.service.lock().unwrap();
    if !svc.is_running() {
        return Err("服务未启动".into());
    }
    let base_port = svc.base_port().ok_or("服务未启动")?;
    fetch_pairing_state(base_port)
}

/// 重置配对:清空所有已配对客户端并重新生成 PIN
#[tauri::command]
fn reset_pairing(state: State<AppState>) -> Result<String, String> {
    let mut svc = state.service.lock().unwrap();
    if !svc.is_running() {
        return Err("服务未启动".into());
    }
    let base_port = svc.base_port().ok_or("服务未启动")?;
    post_pairing_action(base_port, "/pairing/reset")
}

/// 刷新 PIN:旧 PIN 失效,生成新 PIN(不影响已配对客户端)
/// 用于多客户端场景:配对完一批设备后刷新 PIN 防止泄露
#[tauri::command]
fn refresh_pairing(state: State<AppState>) -> Result<String, String> {
    let mut svc = state.service.lock().unwrap();
    if !svc.is_running() {
        return Err("服务未启动".into());
    }
    let base_port = svc.base_port().ok_or("服务未启动")?;
    post_pairing_action(base_port, "/pairing/refresh")
}

/// 移除指定公钥的已配对客户端
#[tauri::command]
fn unpair_client(state: State<AppState>, pubkey_b64: String) -> Result<String, String> {
    let mut svc = state.service.lock().unwrap();
    if !svc.is_running() {
        return Err("服务未启动".into());
    }
    let base_port = svc.base_port().ok_or("服务未启动")?;
    // URL 编码 base64(含 +/= 等特殊字符)
    let encoded = url_encode(&pubkey_b64);
    let path = format!("/pairing/unpair?pubkey={}", encoded);
    post_pairing_action(base_port, &path)
}

/// 反向配对(Sunshine 方向):把手机端显示的 PIN 设为服务端期望 PIN
/// 手机端随后用该 PIN 发起 PairRequest 即可通过校验
#[tauri::command]
fn submit_pair_pin(state: State<AppState>, pin: String) -> Result<String, String> {
    let mut svc = state.service.lock().unwrap();
    if !svc.is_running() {
        return Err("服务未启动".into());
    }
    let base_port = svc.base_port().ok_or("服务未启动")?;
    if pin.len() != 6 || !pin.bytes().all(|b| b.is_ascii_digit()) {
        return Err("PIN 必须为 6 位数字".into());
    }
    post_pairing_action(base_port, &format!("/pairing/expect?pin={}", pin))
}

/// 简易 URL 编码(仅处理 base64 中可能出现的特殊字符 + / =)
fn url_encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        match b {
            b'+' => out.push_str("%2B"),
            b'/' => out.push_str("%2F"),
            b'=' => out.push_str("%3D"),
            b if b.is_ascii_alphanumeric() || b == b'-' || b == b'_' => out.push(b as char),
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

// ============ 开机启动(Windows 注册表)============

/// 设置/取消开机启动(写入 HKCU\Software\Microsoft\Windows\CurrentVersion\Run)
/// 开启时写入:"<当前 exe 路径>" --tray
/// 关闭时删除该键
#[cfg(target_os = "windows")]
fn set_launch_at_login(enabled: bool) -> Result<(), String> {
    use winreg::enums::HKEY_CURRENT_USER;
    use winreg::RegKey;

    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let (key, _) = hkcu
        .create_subkey(RUN_REGISTRY_KEY)
        .map_err(|e| format!("打开注册表失败: {}", e))?;

    if enabled {
        let exe = env::current_exe().map_err(|e| format!("获取当前路径失败: {}", e))?;
        let exe_str = exe.to_string_lossy();
        // 加 --tray 启动参数:开机启动后直接最小化到托盘,不弹窗
        let value = format!("\"{}\" --tray", exe_str);
        key.set_value(AUTOSTART_REG_NAME, &value)
            .map_err(|e| format!("写入注册表失败: {}", e))?;
    } else {
        // 删除键值(不存在时返回 Ok,无需特殊处理)
        let _ = key.delete_value(AUTOSTART_REG_NAME);
    }
    Ok(())
}

#[cfg(not(target_os = "windows"))]
fn set_launch_at_login(_enabled: bool) -> Result<(), String> {
    Ok(())
}

/// 查询当前开机启动状态(读注册表)
#[cfg(target_os = "windows")]
fn query_launch_at_login() -> bool {
    use winreg::enums::HKEY_CURRENT_USER;
    use winreg::RegKey;

    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    if let Ok(key) = hkcu.open_subkey(RUN_REGISTRY_KEY) {
        if let Ok(val) = key.get_value::<String, _>(AUTOSTART_REG_NAME) {
            // 校验路径是否是当前 exe(避免残留旧值)
            if let Ok(exe) = env::current_exe() {
                let exe_str = exe.to_string_lossy().to_lowercase();
                return val.to_lowercase().contains(&exe_str);
            }
            return true;
        }
    }
    false
}

#[cfg(not(target_os = "windows"))]
fn query_launch_at_login() -> bool {
    false
}

/// 切换开机启动(命令):同时更新注册表和配置文件
#[tauri::command]
fn set_launch_at_login_cmd(enabled: bool) -> Result<(), String> {
    set_launch_at_login(enabled)?;
    let mut config = load_config();
    config.launch_at_login = enabled;
    save_config(&config)
}

/// 切换"关闭时最小化到托盘"(命令):仅更新配置
#[tauri::command]
fn set_minimize_to_tray(enabled: bool) -> Result<(), String> {
    let mut config = load_config();
    config.minimize_to_tray = enabled;
    save_config(&config)
}

/// 退出应用(完全退出,与窗口关闭按钮的"最小化到托盘"行为区分)
#[tauri::command]
fn quit_app(app: tauri::AppHandle) {
    app.exit(0);
}

/// 显示主窗口(从托盘恢复)
#[tauri::command]
fn show_main_window(app: tauri::AppHandle) -> Result<(), String> {
    if let Some(window) = app.get_window("main") {
        window.show().map_err(|e| e.to_string())?;
        window.set_focus().map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// 隐藏主窗口到托盘
#[tauri::command]
fn hide_main_window(app: tauri::AppHandle) -> Result<(), String> {
    if let Some(window) = app.get_window("main") {
        window.hide().map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// 检查 Windows 防火墙是否已配置 MeowMic Server 入站规则
/// 返回 true 表示规则已存在(手机可连接),false 表示未配置(可能被拦截)
#[tauri::command]
fn check_firewall_rule() -> Result<bool, String> {
    #[cfg(target_os = "windows")]
    {
        let output = Command::new("netsh")
            .args(["advfirewall", "firewall", "show", "rule", "name=MeowMic Server"])
            .output()
            .map_err(|e| format!("执行 netsh 失败: {}", e))?;
        // netsh show rule 在规则不存在时退出码非 0
        let stdout = String::from_utf8_lossy(&output.stdout);
        Ok(output.status.success() && stdout.contains("MeowMic Server"))
    }
    #[cfg(not(target_os = "windows"))]
    {
        Ok(true)
    }
}

/// 获取本机所有局域网 IPv4 地址(排除 loopback)
/// 用于前端显示,让用户知道手机端该输入什么地址
#[tauri::command]
fn get_local_ips() -> Result<Vec<String>, String> {
    let ips = local_ip_address::list_afinet_netifas()
        .map_err(|e| format!("获取本机 IP 失败: {}", e))?;
    let result: Vec<String> = ips
        .into_iter()
        .filter_map(|(_name, ip)| {
            if ip.is_ipv4() && !ip.is_loopback() {
                Some(ip.to_string())
            } else {
                None
            }
        })
        .collect();
    Ok(result)
}

/// 修复 Windows 防火墙:为 meowmic-server.exe 添加入站放行规则
/// 通过 PowerShell Start-Process -Verb RunAs 以管理员权限运行(会弹 UAC)
#[tauri::command]
fn fix_firewall_rule() -> Result<String, String> {
    #[cfg(target_os = "windows")]
    {
        let server_exe = find_server_executable()?;
        let server_path = server_exe.to_string_lossy().to_string();

        // 写临时 .bat 文件,避免 PowerShell 引号转义问题
        let bat_path = env::temp_dir().join("meowmic-fix-firewall.bat");
        let bat_content = format!(
            "@echo off\r\n\
             netsh advfirewall firewall delete rule name=\"MeowMic Server\" >nul 2>&1\r\n\
             netsh advfirewall firewall add rule name=\"MeowMic Server\" dir=in action=allow program=\"{server}\" enable=yes profile=any\r\n\
             echo OK\r\n",
            server = server_path
        );
        fs::write(&bat_path, bat_content).map_err(|e| format!("写临时 bat 失败: {}", e))?;

        // 用 PowerShell Start-Process -Verb RunAs 提权运行 bat(弹 UAC)
        let bat_str = bat_path.to_string_lossy().to_string();
        let ps_cmd = format!(
            "Start-Process -FilePath '{}' -Verb RunAs -Wait",
            bat_str
        );
        let output = Command::new("powershell")
            .args(["-NoProfile", "-Command", &ps_cmd])
            .output()
            .map_err(|e| {
                let _ = fs::remove_file(&bat_path);
                format!("启动 PowerShell 失败: {}", e)
            })?;

        let _ = fs::remove_file(&bat_path);

        if output.status.success() {
            Ok("防火墙规则已添加,请重新尝试连接".into())
        } else {
            let stderr = String::from_utf8_lossy(&output.stderr);
            let msg = if stderr.is_empty() {
                "用户取消了 UAC 授权或添加失败".to_string()
            } else {
                format!("添加失败: {}", stderr)
            };
            Err(msg)
        }
    }
    #[cfg(not(target_os = "windows"))]
    {
        Ok("非 Windows 平台无需配置防火墙".into())
    }
}

pub fn run() {

    let config = load_config();
    let minimize_to_tray_default = config.minimize_to_tray;
    let state = AppState {
        service: std::sync::Mutex::new(ServiceManager::new()),
    };

    // 检查 --tray 启动参数:开机启动时直接隐藏窗口到托盘
    let start_in_tray = env::args().any(|a| a == "--tray");

    // 系统托盘菜单:显示主窗口 / 分隔线 / 退出
    let tray_menu = SystemTrayMenu::new()
        .add_item(CustomMenuItem::new("show", "显示主窗口"))
        .add_item(CustomMenuItem::new("hide", "隐藏到托盘"))
        .add_native_item(SystemTrayMenuItem::Separator)
        .add_item(CustomMenuItem::new("quit", "退出 MeowMic"));

    let tray = SystemTray::new().with_menu(tray_menu).with_tooltip("MeowMic 控制台");

    // 用于跨线程传递"是否已通过托盘退出"标志
    // (app.exit 触发的窗口关闭不应被 prevent_close 拦截)
    let quitting = Arc::new(AtomicBool::new(false));
    let quitting_tray = quitting.clone();

    tauri::Builder::default()
        .manage(state)
        .system_tray(tray)
        .on_window_event(move |event| {
            // 拦截窗口关闭按钮:若启用"最小化到托盘",则隐藏窗口而非退出
            if let WindowEvent::CloseRequested { api, .. } = event.event() {
                if minimize_to_tray_default && !quitting.load(Ordering::Relaxed) {
                    // 阻止默认关闭行为,改为隐藏
                    api.prevent_close();
                    let _ = event.window().hide();
                }
            }
        })
        .on_system_tray_event(move |app, event| match event {
            // 左键单击:切换显示/隐藏
            SystemTrayEvent::LeftClick { .. } => {
                if let Some(window) = app.get_window("main") {
                    if window.is_visible().unwrap_or(false) {
                        let _ = window.hide();
                    } else {
                        let _ = window.show();
                        let _ = window.set_focus();
                    }
                }
            }
            // 双击:显示窗口
            SystemTrayEvent::DoubleClick { .. } => {
                if let Some(window) = app.get_window("main") {
                    let _ = window.show();
                    let _ = window.set_focus();
                }
            }
            // 菜单项点击
            SystemTrayEvent::MenuItemClick { id, .. } => match id.as_str() {
                "show" => {
                    if let Some(window) = app.get_window("main") {
                        let _ = window.show();
                        let _ = window.set_focus();
                    }
                }
                "hide" => {
                    if let Some(window) = app.get_window("main") {
                        let _ = window.hide();
                    }
                }
                "quit" => {
                    // 标记正在退出,避免 CloseRequested 中的 prevent_close 拦截
                    quitting_tray.store(true, Ordering::Relaxed);
                    app.exit(0);
                }
                _ => {}
            },
            _ => {}
        })
        .setup(move |app| {
            // --tray 启动参数:启动后立即隐藏窗口(开机启动场景)
            if start_in_tray {
                if let Some(window) = app.get_window("main") {
                    let _ = window.hide();
                }
            }
            // 自动启动:在 setup 中执行,此时已可获取 AppHandle 用于日志转发
            if config.auto_start {
                let state = app.state::<AppState>();
                let mut svc = state.service.lock().unwrap();
                let handle = app.handle();
                let _ = svc.start(&config, &handle);
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_config,
            save_app_config,
            start_service,
            stop_service,
            get_status,
            get_output_devices,
            list_video_adapters,
            set_mute_speaker,
            get_pairing_state,
            reset_pairing,
            refresh_pairing,
            unpair_client,
            submit_pair_pin,
            check_firewall_rule,
            fix_firewall_rule,
            get_local_ips,
            set_launch_at_login_cmd,
            set_minimize_to_tray,
            quit_app,
            show_main_window,
            hide_main_window,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
