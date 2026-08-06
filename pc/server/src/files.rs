//! 文件传输(远程文件管理)
//!
//! 在 apps.rs 已有的 list_directory 基础上,补齐完整的文件操作:
//! 列出所有文件(不只 .exe)、上传、下载、新建目录、删除、重命名。
//!
//! 端点(挂在 base_port+4 的 serverinfo 服务上,复用配对鉴权):
//! - GET  /file/list?path=<path>&pubkey=<b64>           返回目录 JSON(含所有文件类型)
//! - GET  /file/download?path=<path>&pubkey=<b64>       返回文件字节流
//! - POST /file/upload?path=<path>&pubkey=<b64>         上传 body 字节流写入指定路径
//! - POST /file/mkdir?path=<path>&pubkey=<b64>          创建目录
//! - POST /file/delete?path=<path>&pubkey=<b64>         删除文件或目录(递归)
//! - POST /file/rename?from=<path>&to=<path>&pubkey=<b64>  重命名/移动

use serde::Serialize;
use std::path::Path;

/// 文件条目(用于文件传输页列表)
#[derive(Debug, Clone, Serialize)]
pub struct FileEntry {
    pub name: String,
    pub path: String,
    pub is_dir: bool,
    pub size: u64,
    pub modified: u64, // Unix 秒
    pub readonly: bool,
}

/// 列出目录下所有条目(目录优先,过滤系统目录)
///
/// path 为空时:
/// - Windows:返回盘符列表
/// - 其他:返回家目录
pub fn list_files(path: &str) -> std::io::Result<(String, Option<String>, Vec<FileEntry>)> {
    #[cfg(windows)]
    {
        if path.is_empty() {
            return list_drives();
        }
    }
    #[cfg(not(windows))]
    {
        if path.is_empty() {
            let home = std::env::var("HOME").unwrap_or_else(|_| "/".into());
            return list_dir_files(&home);
        }
    }
    list_dir_files(path)
}

#[cfg(windows)]
fn list_drives() -> std::io::Result<(String, Option<String>, Vec<FileEntry>)> {
    let mut items = Vec::new();
    for c in b'C'..=b'Z' {
        let letter = c as char;
        let path = format!("{}:\\", letter);
        if Path::new(&path).exists() {
            items.push(FileEntry {
                name: format!("{}:", letter),
                path,
                is_dir: true,
                size: 0,
                modified: 0,
                readonly: false,
            });
        }
    }
    Ok((String::new(), None, items))
}

fn list_dir_files(path: &str) -> std::io::Result<(String, Option<String>, Vec<FileEntry>)> {
    let p = Path::new(path);
    let current = p.display().to_string();

    let parent = p
        .parent()
        .map(|pp| pp.display().to_string())
        .filter(|s| !s.is_empty());

    let mut dirs = Vec::new();
    let mut files = Vec::new();

    for entry in std::fs::read_dir(p)? {
        let entry = entry?;
        let file_type = entry.file_type()?;
        let name = entry.file_name().to_string_lossy().to_string();

        // 过滤系统目录
        if name.starts_with('$')
            || name == "System Volume Information"
            || name == "RECYCLER"
        {
            continue;
        }

        let entry_path = entry.path().display().to_string();
        let metadata = entry.metadata()?;
        let modified = metadata
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_secs())
            .unwrap_or(0);

        let file_entry = FileEntry {
            name,
            path: entry_path,
            is_dir: file_type.is_dir(),
            size: metadata.len(),
            modified,
            readonly: metadata.permissions().readonly(),
        };

        if file_type.is_dir() {
            dirs.push(file_entry);
        } else {
            files.push(file_entry);
        }
    }

    // 目录优先,文件按名称排序
    dirs.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
    files.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));

    dirs.extend(files);
    Ok((current, parent, dirs))
}

/// 创建目录(支持多级)
pub fn mkdir(path: &str) -> std::io::Result<()> {
    std::fs::create_dir_all(path)
}

/// 删除文件或目录(递归)
pub fn delete(path: &str) -> std::io::Result<()> {
    let p = Path::new(path);
    if p.is_dir() {
        std::fs::remove_dir_all(p)
    } else {
        std::fs::remove_file(p)
    }
}

/// 重命名/移动
pub fn rename(from: &str, to: &str) -> std::io::Result<()> {
    std::fs::rename(from, to)
}

/// 读取文件字节流(供 /file/download 使用)
pub fn read_file(path: &str) -> std::io::Result<Vec<u8>> {
    std::fs::read(path)
}

/// 写入文件字节流(供 /file/upload 使用;父目录不存在则创建)
pub fn write_file(path: &str, data: &[u8]) -> std::io::Result<()> {
    let p = Path::new(path);
    if let Some(parent) = p.parent() {
        if !parent.exists() {
            std::fs::create_dir_all(parent)?;
        }
    }
    std::fs::write(p, data)
}
