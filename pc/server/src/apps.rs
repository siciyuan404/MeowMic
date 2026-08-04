//! 快捷启动应用库
//!
//! 借鉴 Sunshine/Moonlight 的 applist 机制:PC 端维护应用库,
//! 客户端通过 HTTP 拉取列表 + 图标,点击后 POST /launch 触发启动。
//!
//! 端点(挂在 base_port+4 的 serverinfo 服务上,复用 0.0.0.0 监听 + 配对鉴权):
//! - GET  /applist?pubkey=<b64>        返回应用库 JSON
//! - GET  /app_icon?id=<app_id>&pubkey=<b64>  返回 exe 图标 PNG
//! - POST /launch?id=<app_id>&pubkey=<b64>    启动指定应用

use std::path::PathBuf;

use serde::{Deserialize, Serialize};

/// 应用库条目
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppEntry {
    /// 稳定 ID(用于 /launch?id=、/app_icon?id= 引用)
    pub id: String,
    /// 显示名
    pub name: String,
    /// 可执行路径(支持 %APPDATA% 等环境变量展开)
    pub command: String,
    /// 启动参数
    #[serde(default)]
    pub args: Vec<String>,
    /// 工作目录(空则用 command 所在目录)
    #[serde(default)]
    pub working_dir: String,
}

/// apps.json 文件结构
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct AppsFile {
    #[serde(default)]
    apps: Vec<AppEntry>,
}

/// 应用库配置文件路径
/// - Windows: %APPDATA%/meowmic/apps.json
/// - 其他:   ~/.config/meowmic/apps.json
fn apps_config_path() -> PathBuf {
    #[cfg(windows)]
    {
        if let Ok(appdata) = std::env::var("APPDATA") {
            return PathBuf::from(appdata).join("meowmic").join("apps.json");
        }
    }
    #[cfg(not(windows))]
    {
        if let Ok(home) = std::env::var("HOME") {
            return PathBuf::from(home).join(".config").join("meowmic").join("apps.json");
        }
    }
    std::env::temp_dir().join("meowmic").join("apps.json")
}

/// 内置默认应用库(开箱即用;用户可在 apps.json 中增删覆盖)
///
/// 注意:command 路径为 Windows 常见位置,部分用户级应用用 %LOCALAPPDATA% 展开。
/// 若路径不存在,/app_icon 会返回 404,客户端用占位图标兜底。
fn default_apps() -> Vec<AppEntry> {
    vec![
        AppEntry {
            id: "edge".into(),
            name: "Edge".into(),
            command: r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe".into(),
            args: vec![],
            working_dir: String::new(),
        },
        AppEntry {
            id: "chrome".into(),
            name: "Chrome".into(),
            command: r"C:\Program Files\Google\Chrome\Application\chrome.exe".into(),
            args: vec![],
            working_dir: String::new(),
        },
        AppEntry {
            id: "vscode".into(),
            name: "VS Code".into(),
            command: r"%LOCALAPPDATA%\Programs\Microsoft VS Code\Code.exe".into(),
            args: vec![],
            working_dir: String::new(),
        },
        AppEntry {
            id: "terminal".into(),
            name: "终端".into(),
            command: "wt.exe".into(),
            args: vec![],
            working_dir: String::new(),
        },
        AppEntry {
            id: "explorer".into(),
            name: "文件管理器".into(),
            command: "explorer.exe".into(),
            args: vec![],
            working_dir: String::new(),
        },
        AppEntry {
            id: "settings".into(),
            name: "设置".into(),
            command: "explorer.exe".into(),
            args: vec!["ms-settings:".into()],
            working_dir: String::new(),
        },
        AppEntry {
            id: "calc".into(),
            name: "计算器".into(),
            command: "calc.exe".into(),
            args: vec![],
            working_dir: String::new(),
        },
        AppEntry {
            id: "notepad".into(),
            name: "记事本".into(),
            command: "notepad.exe".into(),
            args: vec![],
            working_dir: String::new(),
        },
        AppEntry {
            id: "snipping".into(),
            name: "截图".into(),
            command: "explorer.exe".into(),
            args: vec!["ms-screenclip:".into()],
            working_dir: String::new(),
        },
        AppEntry {
            id: "powershell".into(),
            name: "PowerShell".into(),
            command: "powershell.exe".into(),
            args: vec![],
            working_dir: String::new(),
        },
        AppEntry {
            id: "taskmgr".into(),
            name: "任务管理器".into(),
            command: "taskmgr.exe".into(),
            args: vec![],
            working_dir: String::new(),
        },
        AppEntry {
            id: "steam".into(),
            name: "Steam".into(),
            command: r"C:\Program Files (x86)\Steam\steam.exe".into(),
            args: vec![],
            working_dir: String::new(),
        },
    ]
}

/// 加载应用库:若 apps.json 存在则读取,否则返回内置默认列表
pub fn load_apps() -> Vec<AppEntry> {
    let path = apps_config_path();
    match std::fs::read_to_string(&path) {
        Ok(content) => match serde_json::from_str::<AppsFile>(&content) {
            Ok(file) => {
                tracing::info!("已加载应用库: {} ({} 个应用)", path.display(), file.apps.len());
                file.apps
            }
            Err(e) => {
                tracing::warn!("应用库 {} 解析失败({}),回退默认列表", path.display(), e);
                default_apps()
            }
        },
        Err(_) => {
            // 首次运行:尝试写入默认模板,方便用户编辑
            let default = default_apps();
            if let Some(parent) = path.parent() {
                if std::fs::create_dir_all(parent).is_ok() {
                    let template = AppsFile { apps: default.clone() };
                    if let Ok(json) = serde_json::to_string_pretty(&template) {
                        let _ = std::fs::write(&path, json);
                        tracing::info!("已生成默认应用库模板: {}", path.display());
                    }
                }
            }
            default
        }
    }
}

/// 展开环境变量(%APPDATA%、%LOCALAPPDATA% 等)
fn expand_env(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let bytes = s.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            if let Some(end) = s[i + 1..].find('%') {
                let name = &s[i + 1..i + 1 + end];
                if let Ok(val) = std::env::var(name) {
                    out.push_str(&val);
                    i += end + 2;
                    continue;
                }
            }
        }
        out.push(bytes[i] as char);
        i += 1;
    }
    out
}

/// 按 id 查找应用
pub fn find_app<'a>(apps: &'a [AppEntry], id: &str) -> Option<&'a AppEntry> {
    apps.iter().find(|a| a.id == id)
}

/// 启动指定应用(非阻塞,spawn 后立即返回)
pub fn launch_app(app: &AppEntry) -> std::io::Result<()> {
    let command = expand_env(&app.command);
    let mut cmd = std::process::Command::new(&command);
    cmd.args(&app.args);
    if !app.working_dir.is_empty() {
        cmd.current_dir(expand_env(&app.working_dir));
    }
    // Windows: 隐藏控制台窗口
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x08000000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    cmd.spawn().map(|_| {
        tracing::info!("已启动应用: {} ({})", app.name, command);
    })
}

/// 从 exe 提取图标并编码为 PNG(Windows 专属)
///
/// 流程:ExtractIconExW → GetIconInfo → GetDIBits(BGRA) → 转 RGBA → PNG
/// 失败返回 None,调用方用占位图标兜底。
#[cfg(windows)]
pub fn extract_icon_png(exe_path: &str) -> Option<Vec<u8>> {
    use windows::core::PCWSTR;
    use windows::Win32::Graphics::Gdi::DeleteObject;
    use windows::Win32::UI::Shell::ExtractIconExW;
    use windows::Win32::UI::WindowsAndMessaging::{DestroyIcon, GetIconInfo, HICON, ICONINFO};

    let path = expand_env(exe_path);
    let wide: Vec<u16> = path.encode_utf16().chain(std::iter::once(0)).collect();

    let mut hicon: HICON = HICON::default();
    let count = unsafe {
        ExtractIconExW(PCWSTR(wide.as_ptr()), 0, Some(&mut hicon), None, 1)
    };
    if count == 0 || hicon.is_invalid() {
        return None;
    }

    let mut info = ICONINFO::default();
    let got_info = unsafe { GetIconInfo(hicon, &mut info) }.is_ok();
    if !got_info {
        let _ = unsafe { DestroyIcon(hicon) };
        return None;
    }

    let png = bitmap_to_png(info.hbmColor);
    unsafe {
        let _ = DestroyIcon(hicon);
        if !info.hbmColor.is_invalid() {
            let _ = DeleteObject(info.hbmColor);
        }
        if !info.hbmMask.is_invalid() {
            let _ = DeleteObject(info.hbmMask);
        }
    }
    png
}

#[cfg(windows)]
fn bitmap_to_png(hbm_color: windows::Win32::Graphics::Gdi::HBITMAP) -> Option<Vec<u8>> {
    use std::io::Cursor;
    use windows::Win32::Graphics::Gdi::{
        CreateCompatibleDC, DeleteDC, GetDIBits, GetObjectW, BITMAP, BITMAPINFO, BITMAPINFOHEADER,
        DIB_RGB_COLORS,
    };

    let mut bmp = BITMAP::default();
    let got = unsafe {
        GetObjectW(
            hbm_color,
            std::mem::size_of::<BITMAP>() as i32,
            Some(&mut bmp as *mut _ as *mut _),
        )
    };
    if got == 0 {
        return None;
    }
    let w = bmp.bmWidth as u32;
    let h = bmp.bmHeight as u32;
    if w == 0 || h == 0 {
        return None;
    }

    let bi = BITMAPINFOHEADER {
        biSize: std::mem::size_of::<BITMAPINFOHEADER>() as u32,
        biWidth: w as i32,
        biHeight: -(h as i32), // top-down,便于直接用
        biPlanes: 1,
        biBitCount: 32,
        biCompression: 0, // BI_RGB
        biSizeImage: w * h * 4,
        ..Default::default()
    };
    let mut bmi = BITMAPINFO {
        bmiHeader: bi,
        ..Default::default()
    };

    let hdc = unsafe { CreateCompatibleDC(None) };
    if hdc.is_invalid() {
        return None;
    }

    let mut pixels = vec![0u8; (w * h * 4) as usize];
    let got = unsafe {
        GetDIBits(
            hdc,
            hbm_color,
            0,
            h,
            Some(pixels.as_mut_ptr() as *mut _),
            &mut bmi,
            DIB_RGB_COLORS,
        )
    };
    unsafe { let _ = DeleteDC(hdc); };
    if got == 0 {
        return None;
    }

    // BGRA → RGBA;Windows 图标的 alpha 通道通常有效
    for i in 0..(w * h) as usize {
        let b = pixels[i * 4];
        let g = pixels[i * 4 + 1];
        let r = pixels[i * 4 + 2];
        let a = pixels[i * 4 + 3];
        pixels[i * 4] = r;
        pixels[i * 4 + 1] = g;
        pixels[i * 4 + 2] = b;
        // alpha 为 0 时用不透明兜底(部分图标 mask 未正确转 alpha)
        pixels[i * 4 + 3] = if a == 0 { 255 } else { a };
    }

    use image::codecs::png::PngEncoder;
    use image::ImageEncoder;
    let mut buf = Cursor::new(Vec::new());
    PngEncoder::new(&mut buf)
        .write_image(&pixels, w, h, image::ExtendedColorType::Rgba8)
        .ok()?;
    Some(buf.into_inner())
}

#[cfg(not(windows))]
pub fn extract_icon_png(_exe_path: &str) -> Option<Vec<u8>> {
    None
}
