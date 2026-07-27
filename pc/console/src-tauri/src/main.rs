// 在 Windows 上强制使用 windows 子系统,避免弹出黑色控制台窗口
// (即使在 debug 模式下也隐藏)
#![cfg_attr(target_os = "windows", windows_subsystem = "windows")]

fn main() {
    meowmic_console::run();
}
