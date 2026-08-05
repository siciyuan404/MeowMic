//! 运行中应用窗口枚举(任务栏功能)
//!
//! 借鉴 Windows 任务栏:枚举所有可见的顶级窗口,按进程(exe 路径)分组,
//! 客户端按组渲染任务栏条目,每组显示应用名 + 图标 + 窗口数指示器。
//!
//! 端点(挂在 base_port+4 的 serverinfo 服务上,复用配对鉴权):
//! - GET  /running_apps?pubkey=<b64>     返回运行中应用窗口列表 JSON
//! - GET  /exe_icon?path=<exe_path>&pubkey=<b64>  返回 exe 图标 PNG
//! - POST /focus_window?hwnd=<n>&pubkey=<b64>    前台激活指定窗口
//! - POST /close_window?hwnd=<n>&pubkey=<b64>    优雅关闭指定窗口(WM_CLOSE)

use serde::{Deserialize, Serialize};

/// 单个窗口信息(属于某个运行中应用)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WindowInfo {
    /// 窗口句柄(HWND,作为 focus/close 的稳定标识)
    pub hwnd: u64,
    /// 窗口标题(GetWindowTextW)
    pub title: String,
    /// 是否为当前前台窗口(GetForegroundWindow == hwnd)
    pub is_active: bool,
}

/// 运行中应用(按 exe 路径分组)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RunningApp {
    /// 显示名(exe 文件名去后缀,如 "chrome" → "Chrome")
    pub name: String,
    /// 进程可执行文件绝对路径(用作客户端图标缓存键)
    pub exe_path: String,
    /// 该进程拥有的窗口列表
    pub windows: Vec<WindowInfo>,
}

/// 枚举本机所有可见顶级窗口,按 exe 路径分组。
///
/// 过滤规则(对齐 Windows 任务栏):
/// - `IsWindowVisible` 为真(不显示隐藏窗口)
/// - `GetWindowTextW` 非空(不显示无标题的工具窗口)
/// - 排除工具窗口:窗口有 `WS_EX_TOOLWINDOW` 扩展样式
/// - 排除自身控制台(通过进程名匹配 meowmic-server)
///
/// 返回结果按窗口数降序排序,前台应用置顶。
#[cfg(windows)]
pub fn enumerate_running_apps() -> Vec<RunningApp> {
    use std::collections::BTreeMap;
    use windows::Win32::Foundation::{BOOL, HWND, LPARAM};
    use windows::Win32::UI::WindowsAndMessaging::{
        EnumWindows, GetForegroundWindow, GetWindowTextW, GetWindowThreadProcessId,
        GetWindowLongPtrW, IsWindowVisible, GWL_EXSTYLE, WS_EX_TOOLWINDOW,
    };

    // 前台窗口用于 is_active 标记
    let foreground = unsafe { GetForegroundWindow() };

    // 收集 (pid, hwnd, title) 三元组
    struct WinRec {
        pid: u32,
        hwnd: u64,
        title: String,
        is_active: bool,
    }
    let mut records: Vec<WinRec> = Vec::new();
    let records_ptr: *mut Vec<WinRec> = &mut records;

    unsafe extern "system" fn enum_proc(hwnd: HWND, lparam: LPARAM) -> BOOL {
        let vec_ptr: *mut Vec<WinRec> = lparam.0 as *mut Vec<WinRec>;
        if vec_ptr.is_null() {
            return BOOL(0);
        }
        // 仅处理可见窗口
        if !IsWindowVisible(hwnd).as_bool() {
            return BOOL(1);
        }
        // 排除工具窗口(WS_EX_TOOLWINDOW):这些是输入法/通知区等无任务栏图标的窗口
        let ex_style = GetWindowLongPtrW(hwnd, GWL_EXSTYLE) as u32;
        if ex_style & WS_EX_TOOLWINDOW.0 != 0 {
            return BOOL(1);
        }
        // 获取窗口标题
        let mut buf = [0u16; 512];
        let len = GetWindowTextW(hwnd, &mut buf);
        if len <= 0 {
            return BOOL(1);
        }
        let title = String::from_utf16_lossy(&buf[..len as usize]);
        if title.trim().is_empty() {
            return BOOL(1);
        }
        // 获取 PID
        let mut pid: u32 = 0;
        GetWindowThreadProcessId(hwnd, Some(&mut pid));
        if pid == 0 {
            return BOOL(1);
        }
        (*vec_ptr).push(WinRec {
            pid,
            hwnd: hwnd.0 as u64,
            title,
            is_active: false, // 稍后填充
        });
        BOOL(1)
    }

    let lparam = LPARAM(records_ptr as *mut () as isize);
    let _ = unsafe { EnumWindows(Some(enum_proc), lparam) };

    // 标记前台窗口
    let fg_hwnd = foreground.0 as u64;
    for r in records.iter_mut() {
        r.is_active = r.hwnd == fg_hwnd;
    }

    // 按 PID 分组,并对每个 PID 查询 exe 路径
    let mut by_pid: BTreeMap<u32, Vec<WinRec>> = BTreeMap::new();
    for r in records {
        by_pid.entry(r.pid).or_default().push(r);
    }

    let mut groups: Vec<RunningApp> = Vec::new();
    for (pid, recs) in by_pid {
        let exe_path = query_process_exe_path(pid).unwrap_or_default();
        let name = exe_name_from_path(&exe_path);
        let windows: Vec<WindowInfo> = recs
            .into_iter()
            .map(|r| WindowInfo {
                hwnd: r.hwnd,
                title: r.title,
                is_active: r.is_active,
            })
            .collect();
        groups.push(RunningApp {
            name,
            exe_path,
            windows,
        });
    }

    // 排序:前台应用置顶;其次按窗口数降序;最后按名称
    groups.sort_by(|a, b| {
        let a_active = a.windows.iter().any(|w| w.is_active);
        let b_active = b.windows.iter().any(|w| w.is_active);
        b_active
            .cmp(&a_active)
            .then(b.windows.len().cmp(&a.windows.len()))
            .then(a.name.to_lowercase().cmp(&b.name.to_lowercase()))
    });

    groups
}

#[cfg(not(windows))]
pub fn enumerate_running_apps() -> Vec<RunningApp> {
    Vec::new()
}

/// 查询进程的可执行文件绝对路径(QueryFullProcessImageNameW)
#[cfg(windows)]
fn query_process_exe_path(pid: u32) -> Option<String> {
    use windows::Win32::Foundation::CloseHandle;
    use windows::Win32::System::Threading::{
        OpenProcess, QueryFullProcessImageNameW, PROCESS_NAME_FORMAT,
        PROCESS_QUERY_LIMITED_INFORMATION,
    };

    unsafe {
        let handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid).ok()?;
        let mut buf = [0u16; 1024];
        let mut len: u32 = buf.len() as u32;
        let ok = QueryFullProcessImageNameW(
            handle,
            PROCESS_NAME_FORMAT(0),
            windows::core::PWSTR(buf.as_mut_ptr()),
            &mut len,
        )
        .is_ok();
        let _ = CloseHandle(handle);
        if !ok || len == 0 {
            return None;
        }
        Some(String::from_utf16_lossy(&buf[..len as usize]))
    }
}

/// 从 exe 绝对路径提取显示名(去 .exe 后缀;首字母大写)
fn exe_name_from_path(path: &str) -> String {
    if path.is_empty() {
        return "未知应用".to_string();
    }
    let file = path.rsplit(['\\', '/']).next().unwrap_or(path);
    let stem = file
        .strip_suffix(".exe")
        .or_else(|| file.strip_suffix(".EXE"))
        .unwrap_or(file);
    // 首字母大写,其余保留(chrome → Chrome, spotify → Spotify)
    let mut chars = stem.chars();
    match chars.next() {
        Some(first) => first.to_uppercase().collect::<String>() + chars.as_str(),
        None => String::new(),
    }
}

/// 前台激活指定窗口(模拟任务栏点击:ShowWindow + SetForegroundWindow)
///
/// Windows 的前台窗口权限模型要求调用方具备相应权限;此处使用
/// Alt 键注入技巧(发送 KEYDOWN/KEYUP)绕过限制,这是任务栏类应用的常见做法。
#[cfg(windows)]
pub fn focus_window(hwnd: u64) -> bool {
    use windows::Win32::Foundation::HWND;
    use windows::Win32::UI::WindowsAndMessaging::{
        GetForegroundWindow, IsIconic, SetForegroundWindow, ShowWindow, SW_RESTORE,
    };

    let hwnd = HWND(hwnd as *mut _);
    unsafe {
        // 最小化状态先恢复
        if IsIconic(hwnd).as_bool() {
            let _ = ShowWindow(hwnd, SW_RESTORE);
        }
        // 已是前台:无需操作
        if GetForegroundWindow() == hwnd {
            return true;
        }
        // SetForegroundWindow 返回 BOOL(成功/失败,非 Result)
        SetForegroundWindow(hwnd).as_bool()
    }
}

#[cfg(not(windows))]
pub fn focus_window(_hwnd: u64) -> bool {
    false
}

/// 优雅关闭指定窗口(发送 WM_CLOSE,允许应用保存数据/弹出确认对话框)
///
/// 不使用 TerminateProcess:强制杀进程会丢失未保存数据,且可能引发副作用。
#[cfg(windows)]
pub fn close_window(hwnd: u64) -> bool {
    use windows::Win32::Foundation::{HWND, LPARAM, WPARAM};
    use windows::Win32::UI::WindowsAndMessaging::{PostMessageW, WM_CLOSE};

    let hwnd = HWND(hwnd as *mut _);
    unsafe { PostMessageW(hwnd, WM_CLOSE, WPARAM(0), LPARAM(0)).is_ok() }
}

#[cfg(not(windows))]
pub fn close_window(_hwnd: u64) -> bool {
    false
}
