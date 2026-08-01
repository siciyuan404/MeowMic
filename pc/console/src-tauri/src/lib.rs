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
    pub output_device: String,
    pub mute_speaker: bool,
    pub auto_start: bool,
    pub sensitivity: f32,
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            base_port: 28900,
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

        if !config.output_device.is_empty() {
            cmd.arg("--output-device").arg(&config.output_device);
        }

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
            set_mute_speaker,
            get_pairing_state,
            reset_pairing,
            refresh_pairing,
            unpair_client,
            check_firewall_rule,
            fix_firewall_rule,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
