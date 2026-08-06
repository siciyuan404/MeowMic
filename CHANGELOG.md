# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.28.0] - 2026-08-06

### Added
- **P1: DXGI Desktop Duplication 屏幕抓取**(PC `screen.rs`)
  - 从 GDI BitBlt 迁移到 DXGI Desktop Duplication API,大幅提升抓帧性能与画质
  - 直接获取 GPU 纹理,避免 GDI CPU 拷贝,降低延迟与 CPU 占用
  - 自动重建输出(显示器配置变化/会话切换时重试 AcquireNextFrame)
  - 保留 `capture_screen_png()` 兼容旧端点
- **P2: NVENC/AMF/QuickSync 硬件编码**(PC `encoder.rs` 新增)
  - 基于 Media Foundation H.264 MFT,优先硬件编码器,失败回退软件
  - `MFT_ENUM_FLAG_HARDWARE | MFT_ENUM_FLAG_SYNCMFT` 枚举 NVENC/AMF/QuickSync
  - 输入 BGRA → H.264 Annex-B NALU(SPS/PPS/IDR 或 P 帧)
  - 新增端点 `GET /screen/h264?fps=<n>&bitrate=<n>&pubkey=<b64>`
    - 200 OK + `application/octet-stream`:NALU 字节流
    - 204 No Content:画面无变化(客户端保持上一帧)
  - `Cargo.toml` 新增 `Win32_System_Com` feature(支持 `CoTaskMemFree`)
- **Android MediaCodec 硬解 H.264**(Android `MonitorScreen.kt` + `LauncherRepository.kt`)
  - `LauncherRepository.fetchScreenH264()`:拉取 `/screen/h264` NALU 字节流
  - `H264Decoder` 内部类:`MediaCodec.createDecoderByType(AVC)` + `ImageReader(YUV_420_888)` Surface 输出
  - YUV_420_888 → NV21 → `YuvImage.compressToJpeg` → `Bitmap`(native 加速)
  - 后台 `HandlerThread` 处理 ImageReader 回调,避免阻塞 UI 主线程
  - 设置面板:帧率选择 15/30/60 fps,码率固定 4Mbps
  - `DisposableEffect` 兜底释放 MediaCodec / ImageReader / HandlerThread
- 快捷启动排列方式切换(`GridCols.AUTO/COLS_5/COLS_6/COLS_7` + 下拉菜单)
- 各页面补齐 6 按钮页面切换组(`Touchpad/Launcher/Voice/Keyboard/Monitor/Files`)

### Changed
- Android versionCode 27 → 28, versionName 0.27.0 → 0.28.0
- Rust workspace 版本 0.27.0 → 0.28.0

## [0.27.0] - 2026-08-06

### Added
- 触控板 UI 横屏/竖屏 v2 改版（Android）
  - 新增可折叠连接栏 ConnBar，点击展开显示 IP 地址、流量统计、方向标签
  - 操作栏新增 1dp 竖向分隔线，区分返回组、视图切换组、页面开关组、断开按钮
  - 操作栏按钮尺寸按方向自适应：竖屏 24dp / 横屏 22dp
  - 横屏布局移除独立 StatusBar 与旋转按钮，使用完整 ActionBar，触控区占主导
  - 触控区圆角与底部鼠标按键高度按方向自适应（竖 16dp/44dp，横 12dp/40dp）
  - IconButtonSmall / ToggleButtonSmall 新增 buttonSize、iconSize、isDanger 参数
  - TouchStyleToggle 新增 toggleHeight、halfWidth、dividerHeight、iconSize 参数
  - MouseBtn 新增 iconSize、labelSize 参数

### Fixed
- Android ViewModel init 防重复初始化：避免断开连接返回连接页后自动重连导致闪连
- 用户主动断开连接时清空 last_addr 与 SharedPreferences 缓存，避免 autoReconnectLastPc 立即重连

### Changed
- 代码内版本号与发布 tag 对齐：Android versionName 0.1.0 → 0.23.0, versionCode 1 → 23
- Rust workspace / Tauri console 版本号 0.1.0 → 0.23.0

## [0.27.0] - 2026-08-06

### Added
- **快捷启动页面 UI 同步设计稿**（Android）
  - 可折叠连接栏 `ConnBar`：默认收起，点击展开显示 IP 地址、流量统计（T/A）、横竖屏标签
  - 顶部操作栏 `ActionBar`：页面切换组（触控/快捷启动/语音/键盘/显示器/文件 6 按钮）+ 上下文开关 + 断开按钮
  - 编辑工具栏 `EditToolbar`：编辑/添加/锁定 + `RotateRight` 翻转按钮切换横竖屏
  - 共享页面切换组件 `PageSwitcher`（SharedTopBar.kt），各页面复用
  - 分页网格竖屏 5×6 / 横屏 8×3 自适应
- **远程显示器页**（Android `MonitorScreen.kt` + PC `screen.rs`）
  - 周期拉取 `/screen/capture` PNG 显示，支持缩放比例 25%/50%/100% 与帧率 2/5/10fps 调整
  - PC 端 GDI BitBlt 屏幕抓取 + PNG 编码，提供 `/screen/capture` 和 `/screen/info` 端点
  - 底部状态栏显示分辨率/缩放/帧数/延迟
- **文件传输页**（Android `FilesScreen.kt` + PC `files.rs`）
  - 目录浏览/上传/下载/新建目录/删除/重命名
  - 面包屑路径栏 + 上级目录导航
  - 文件图标按扩展名区分，显示大小/修改时间/只读属性
  - PC 端提供 `/file/list` `/file/download` `/file/upload` `/file/mkdir` `/file/delete` `/file/rename` 端点
- `MainActivity` 注册 `monitor` 和 `files` 两个新路由

### Fixed
- PC 端 `screen.rs` 编译错误：`GetSystemMetrics` 从 `Win32::Graphics::Gdi` 改为从 `Win32::UI::WindowsAndMessaging` 导入

### Changed
- Android versionCode 24 → 27, versionName 0.24.0 → 0.27.0
- Rust workspace 版本 0.24.0 → 0.27.0

## [0.24.0] - 2026-08-06

### Added
- `AppListFetchKind` 5 类结构化错误分类 + `AppListFetchException` 自定义异常（Android）
  - NotPaired403 / Forbidden403 / HttpError(code,body) / Network(msg) / ParseError(msg,raw)
- `ServerEvent::ClientConnected` 和 `ClientDisconnected` 新增可选字段 `client_pubkey_b64`
- PC 端活跃连接公钥登记机制：`active_clients: Arc<RwLock<HashSet<String>>>`

### Fixed
- **第二台手机（华为 P40 Pro）连接后「快捷启动 → 拉取应用库失败 (HTTP 403)」问题**
  - 根因：`check_paired()` 只查持久化配对白名单，未考虑「HelloPaired 已通过但白名单尚未同步/同意」的活跃连接
  - 修复（Rust 服务端）：
    - `pc/server/src/main.rs` 增加 `active_clients` 活跃公钥集合；`run_serverinfo_server` 签名扩展为 6 参并 clone 进每个 HTTP handler
    - `check_paired()` 由 2 参扩展为 3 参，判定改为 `白名单 OR 活跃连接公钥`；10 处 `/applist`/`/launch`/图标等端点全部传入
    - 未启用配对（pairing=None）路径改为：只要在活跃集合中仍放行（更友好）
    - `max_clients` 从硬编码 1 提升为 `MAX_CLIENTS=4`，支持多手机同时连接
    - `/serverinfo` 的 `connected_clients` 改为优先用真实活跃集合大小，`max_clients=4`；修复 `connected` u32/u64 类型不匹配
  - 修复（crates/net 层）：
    - `server.rs` 的 `Server::run` / `handle_control_conn` / `handle_control_msg` 签名新增 active_clients 与 `conn_pubkey_b64: Option<String>`
    - **HelloPaired 成功分支**：base64 encode 客户端公钥 → 写入 `active_clients` → 保存到 `conn_pubkey_b64`；ServerEvent 上报 pubkey
    - **对端关闭分支**：`conn_pubkey_b64` 从 `active_clients` 中移除，避免集合无限增长
    - 非配对 Hello 路径 ServerEvent::ClientConnected 补 `client_pubkey_b64: None`
  - 修复（Android 客户端）：
    - `LauncherRepository.fetchAppList` 返回 `Result<List<AppEntry>>`；4xx 错误通过 `readBodySafe()` 读取完整响应 body（403 not-paired / 其他 403 / 其他 HTTP 分别归类）；IOException 细分 ConnectException/SocketTimeoutException 为友好中文错误
    - `MeowMicViewModel.loadAppList()`：onFailure 对 `AppListFetchException.kind` 做模式匹配，分别输出 NotPaired403/Forbidden403/HttpError/Network/ParseError 的人性化引导提示

### Changed
- Android versionCode 23 → 24, versionName 0.23.0 → 0.24.0
- Rust workspace 版本 0.23.0 → 0.24.0
- Tauri console 版本 0.23.0 → 0.24.0
