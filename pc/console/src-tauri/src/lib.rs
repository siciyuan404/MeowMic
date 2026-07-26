#![cfg_attr(
    all(not(debug_assertions), target_os = "windows"),
    windows_subsystem = "windows"
)]

use serde::{Deserialize, Serialize};
use std::env;
use std::fs;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use tauri::State;

const CONFIG_FILE: &str = "meowmic-console.json";

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct AppConfig {
    pub tcp_port: u16,
    pub touch_port: u16,
    pub audio_port: u16,
    pub output_device: String,
    pub mute_speaker: bool,
    pub auto_start: bool,
    pub sensitivity: f32,
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            tcp_port: 28900,
            touch_port: 28901,
            audio_port: 28902,
            output_device: String::new(),
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

pub struct ServiceManager {
    process: Option<Child>,
    started_at: Option<std::time::Instant>,
    status: ServiceStatus,
}

impl ServiceManager {
    pub fn new() -> Self {
        Self {
            process: None,
            started_at: None,
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
        self.status.clone()
    }

    pub fn start(&mut self, config: &AppConfig) -> Result<(), String> {
        if self.is_running() {
            return Err("服务已在运行".to_string());
        }

        let server_exe = find_server_executable()?;

        let mut cmd = Command::new(&server_exe);
        cmd.arg("--tcp-port").arg(config.tcp_port.to_string());
        cmd.arg("--touch-port").arg(config.touch_port.to_string());
        cmd.arg("--audio-port").arg(config.audio_port.to_string());

        if config.mute_speaker {
            cmd.arg("--mute");
        }

        if !config.output_device.is_empty() {
            cmd.arg("--output-device").arg(&config.output_device);
        }

        cmd.stdout(Stdio::piped());
        cmd.stderr(Stdio::piped());
        cmd.stdin(Stdio::null());

        match cmd.spawn() {
            Ok(child) => {
                self.process = Some(child);
                self.started_at = Some(std::time::Instant::now());
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
        exe_dir.join("..").join("resources").join("meowmic-server.exe"),
        exe_dir.join("..").join("resources").join("meowmic-server"),
        exe_dir.join("..").join("bin").join("meowmic-server.exe"),
        exe_dir.join("..").join("bin").join("meowmic-server"),
    ];

    // 开发模式下从 target/release 查找
    if let Ok(cwd) = env::current_dir() {
        candidates.push(cwd.join("target").join("release").join("meowmic-server.exe"));
        candidates.push(cwd.join("target").join("release").join("meowmic-server"));
        candidates.push(cwd.join("pc").join("console").join("bin").join("meowmic-server.exe"));
        candidates.push(cwd.join("pc").join("console").join("bin").join("meowmic-server"));
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

#[tauri::command]
fn get_output_devices() -> Result<Vec<String>, String> {
    Ok(vec![
        "默认设备".to_string(),
        "扬声器 (Realtek High Definition Audio)".to_string(),
        "显示器 (HDMI Audio)".to_string(),
        "耳机 (USB Audio Device)".to_string(),
    ])
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
            get_output_devices,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
