//! 剪贴板同步模块
//!
//! 功能:PC 端维护一份剪贴板历史(文本),手机端通过 HTTP 端点查看/编辑/同步:
//! - GET  /clipboard/list    历史列表
//! - POST /clipboard/set     设为 PC 当前剪贴板(手机 → PC 复制)
//! - POST /clipboard/update  编辑历史条目(编辑后同步设为当前剪贴板)
//! - POST /clipboard/delete  删除条目
//! - POST /clipboard/clear   清空历史
//!
//! 监听:后台线程每秒轮询系统剪贴板,内容变化时自动加入历史(去重置顶)。
//! PC → 手机方向靠手机端轮询 /clipboard/list 实现"同步"观感。

use serde::Serialize;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

/// 历史上限(条),防止无限增长
const MAX_ENTRIES: usize = 100;
/// 单条文本上限(字符),超长内容(如误复制大文件路径列表)截断
const MAX_TEXT_CHARS: usize = 100_000;

#[derive(Serialize, Clone, Debug)]
pub struct ClipboardEntry {
    pub id: u64,
    pub text: String,
    pub updated_at: i64,
}

static HISTORY: Mutex<Vec<ClipboardEntry>> = Mutex::new(Vec::new());
static LAST_KNOWN: Mutex<String> = Mutex::new(String::new());
static NEXT_ID: AtomicU64 = AtomicU64::new(1);

fn now_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// ─── Windows 剪贴板底层读写 ───────────────────────────────────────

/// Win32 剪贴板格式 CF_UNICODETEXT(13)。
/// 该常量定义在 Ole 模块(CLIPBOARD_FORMAT 类型),为避免引入整个 Win32_System_Ole
/// feature,这里直接使用裸值。
#[cfg(windows)]
const CF_UNICODETEXT_FMT: u32 = 13;

#[cfg(windows)]
fn read_text_raw() -> Option<String> {
    use windows::Win32::Foundation::{HGLOBAL, HWND};
    use windows::Win32::System::DataExchange::{CloseClipboard, GetClipboardData, OpenClipboard};
    use windows::Win32::System::Memory::{GlobalLock, GlobalSize, GlobalUnlock};

    unsafe {
        // 打开剪贴板(与剪贴板所有者竞争,失败直接放弃本轮轮询)
        if OpenClipboard(HWND::default()).is_err() {
            return None;
        }
        let result = (|| -> Option<String> {
            // GetClipboardData 返回 Result<HANDLE>:无文本格式(可能是图片/文件等)时为 Err
            let handle = GetClipboardData(CF_UNICODETEXT_FMT).ok()?;
            let hglobal = HGLOBAL(handle.0);
            let ptr = GlobalLock(hglobal) as *const u16;
            if ptr.is_null() {
                return None;
            }
            let bytes = GlobalSize(hglobal);
            let len = bytes / 2;
            // 找 null 终止符(可能早于 GlobalSize)
            let mut end = 0usize;
            while end < len && *ptr.add(end) != 0 {
                end += 1;
            }
            let slice = std::slice::from_raw_parts(ptr, end);
            let _ = GlobalUnlock(hglobal);
            Some(String::from_utf16_lossy(slice))
        })();
        let _ = CloseClipboard();
        result
    }
}

#[cfg(windows)]
fn write_text_raw(text: &str) -> bool {
    use windows::Win32::Foundation::{HANDLE, HWND};
    use windows::Win32::System::DataExchange::{
        CloseClipboard, EmptyClipboard, OpenClipboard, SetClipboardData,
    };
    use windows::Win32::System::Memory::{
        GlobalAlloc, GlobalLock, GlobalUnlock, GMEM_MOVEABLE,
    };

    unsafe {
        if OpenClipboard(HWND::default()).is_err() {
            return false;
        }
        let result = (|| -> Option<()> {
            EmptyClipboard().ok()?;
            let mut utf16: Vec<u16> = text.encode_utf16().collect();
            utf16.push(0); // null 终止符
            let bytes = utf16.len() * 2;
            let hglobal = GlobalAlloc(GMEM_MOVEABLE, bytes).ok()?;
            let ptr = GlobalLock(hglobal) as *mut u8;
            if ptr.is_null() {
                return None;
            }
            std::ptr::copy_nonoverlapping(utf16.as_ptr() as *const u8, ptr, bytes);
            let _ = GlobalUnlock(hglobal);
            // SetClipboardData 成功后内存所有权归剪贴板,不要再释放
            SetClipboardData(CF_UNICODETEXT_FMT, HANDLE(hglobal.0)).ok()?;
            Some(())
        })();
        let _ = CloseClipboard();
        result.is_some()
    }
}

#[cfg(not(windows))]
fn read_text_raw() -> Option<String> {
    None // P0 仅支持 Windows,其他平台无剪贴板能力
}

#[cfg(not(windows))]
fn write_text_raw(_text: &str) -> bool {
    false
}

/// ─── 历史管理 ────────────────────────────────────────────────────

/// 插入/置顶一条历史(同文本去重),超限截断
fn push_history(text: String) {
    if text.is_empty() {
        return;
    }
    let text = if text.chars().count() > MAX_TEXT_CHARS {
        text.chars().take(MAX_TEXT_CHARS).collect()
    } else {
        text
    };
    let mut h = HISTORY.lock().unwrap();
    h.retain(|e| e.text != text);
    h.insert(
        0,
        ClipboardEntry {
            id: NEXT_ID.fetch_add(1, Ordering::Relaxed),
            text,
            updated_at: now_secs(),
        },
    );
    h.truncate(MAX_ENTRIES);
}

/// 后台监听线程:每秒轮询系统剪贴板,变化时加入历史
pub fn spawn_poller() {
    std::thread::Builder::new()
        .name("clipboard-poller".into())
        .spawn(|| {
            loop {
                std::thread::sleep(std::time::Duration::from_millis(1000));
                if let Some(text) = read_text_raw() {
                    let mut last = LAST_KNOWN.lock().unwrap();
                    if text != *last {
                        *last = text.clone();
                        drop(last);
                        push_history(text);
                    }
                }
            }
        })
        .ok();
}

/// 历史列表(新的在前)
pub fn list() -> Vec<ClipboardEntry> {
    HISTORY.lock().unwrap().clone()
}

/// 设为 PC 当前剪贴板并置顶历史(手机端"复制到 PC")
pub fn set_current(text: &str) -> bool {
    if text.trim().is_empty() {
        return false;
    }
    if !write_text_raw(text) {
        return false;
    }
    // 更新 last_known,避免 poller 把自己刚写入的内容当成"新变化"重复插入
    *LAST_KNOWN.lock().unwrap() = text.to_string();
    push_history(text.to_string());
    true
}

/// 编辑历史条目:更新文本并同步设为当前剪贴板(编辑即同步)
pub fn update_entry(id: u64, text: &str) -> bool {
    if text.trim().is_empty() {
        return false;
    }
    {
        let h = HISTORY.lock().unwrap();
        if !h.iter().any(|e| e.id == id) {
            return false;
        }
    }
    // 先移除旧条目,再走 set_current(去重置顶 + 写剪贴板)
    HISTORY.lock().unwrap().retain(|e| e.id != id);
    set_current(text)
}

/// 删除历史条目
pub fn delete_entry(id: u64) -> bool {
    let mut h = HISTORY.lock().unwrap();
    let before = h.len();
    h.retain(|e| e.id != id);
    h.len() != before
}

/// 清空历史
pub fn clear() {
    HISTORY.lock().unwrap().clear();
}
