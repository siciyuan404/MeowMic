// windows_subsystem 在 main.rs 中设置

use serde::{Deserialize, Serialize};
use std::env;
use std::fs;
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4, TcpStream};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::time::Duration;
use tauri::State;

const CONFIG_FILE: &str = "meowmic-console.json";

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct AppConfig {
    pub base_port: u16,
    pub mute_speaker: bool,
    pub auto_start: bool,
    pub sensitivity: f32,
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            base_port: 28900,
            mute_speaker: false,
            auto_start: false,
            sensitivity: 1.2,
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
            Some(p) => matches!(p.try_wait(), Ok(None)),
            None => false,
        }
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

    pub fn start(&mut self, config: &AppConfig) -> Result<(), String> {
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

        cmd.stdout(Stdio::piped());
        cmd.stderr(Stdio::piped());
        cmd.stdin(Stdio::null());

        match cmd.spawn() {
            Ok(child) => {
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
}

impl Drop for ServiceManager {
    fn drop(&mut self) {
        let _ = self.stop();
    }
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
    load_config()
}

#[tauri::command]
fn save_app_config(config: AppConfig) -> Result<(), String> {
    save_config(&config)
}

#[tauri::command]
fn start_service(state: State<AppState>, config: AppConfig) -> Result<(), String> {
    save_config(&config)?;
    let mut svc = state.service.lock().unwrap();
    svc.start(&config)
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

pub fn run() {
    let config = load_config();
    let state = AppState {
        service: std::sync::Mutex::new(ServiceManager::new()),
    };

    if config.auto_start {
        let mut svc = state.service.lock().unwrap();
        let _ = svc.start(&config);
    }

    tauri::Builder::default()
        .manage(state)
        .invoke_handler(tauri::generate_handler![
            get_config,
            save_app_config,
            start_service,
            stop_service,
            get_status,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
