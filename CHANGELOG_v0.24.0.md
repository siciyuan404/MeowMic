# MeowMic v0.24.0 发布说明

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
