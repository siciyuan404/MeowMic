# AGENTS.md

MeowMic 是一个跨平台远程控制工具,支持 PC 桌面控制台和 Android 客户端。本文件为 AI 代理和贡献者提供项目导航。

## 项目结构

```
MeowMic/
├── crates/              # Rust 核心 crates(音频/网络/协议/触控)
├── pc/console/          # PC 桌面控制台(Tauri 1.x + 原生 HTML/JS 前端)
│   ├── src-tauri/       # Tauri 配置和 Rust 后端
│   └── frontend/        # 单页 HTML 前端
├── android/             # Android 客户端
│   ├── app/src/main/    # Kotlin UI + Rust JNI 核心
│   └── rust-core/       # Android 专属 Rust 编译目标
├── .github/workflows/   # CI: build-console.yml + build-android.yml
└── Cargo.toml           # workspace 根
```

## 滚动更新机制

两端均使用 GitHub Releases 作为更新源,通过推送 `v*` tag 触发 CI 构建并发布。

### 版本号统一(P0)

CI 从 tag 提取版本号(如 `v0.6.0` → `0.6.0`),动态注入到:
- `pc/console/src-tauri/tauri.conf.json` 的 `package.version`
- workspace 根 `Cargo.toml` 的 `version`
- `android/app/build.gradle.kts` 的 `versionName` 和 `versionCode`

代码仓库中的版本号保持 `0.1.0`(占位),实际版本号仅在 CI 构建时注入。

### PC 端 Tauri Updater(P1)

- 配置位置:`pc/console/src-tauri/tauri.conf.json` 的 `tauri.updater`
- 端点:`https://github.com/siciyuan404/MeowMic/releases/latest/download/latest.json`
- 签名:使用 minisign 密钥对,公钥已硬编码在 `tauri.conf.json` 的 `pubkey` 字段
- CI secrets:`TAURI_PRIVATE_KEY`(私钥)、`TAURI_KEY_PASSWORD`(密码)
- 前端 UI:`pc/console/frontend/index.html` 集成检查/下载/安装/重试逻辑

**关键**:Tauri 1.x updater 下载的是 `*.nsis.zip`(不是 `.exe`),签名是 `*.nsis.zip.sig`。CI 必须复制这些文件到 artifacts,`latest.json` 的 `url` 指向 `.nsis.zip`,`signature` 填入 `.nsis.zip.sig` 的内容。

### Android 自更新(P2)

- 核心类:`android/app/src/main/java/com/meowmic/client/UpdateChecker.kt`
- 通过 GitHub API (`/releases/latest`) 检查最新版本
- 下载 APK 到 `cacheDir/updates/`,使用 FileProvider 调起系统安装器
- 权限:`REQUEST_INSTALL_PACKAGES`(在 `AndroidManifest.xml`)
- FileProvider 配置:`android/app/src/main/res/xml/file_paths.xml`
- 状态管理:`MeowMicViewModel.kt` 中的 `UpdateState` 状态机(Idle/Checking/Available/Downloading/ReadyToInstall/Error)
- UI:`ConnectScreen.kt` 底部的 `UpdatePanel` 组件

### UX 完善(P3)

- PC 端:更新日志 `white-space: pre-wrap` 正常换行,失败可重试
- Android 端:底部卡片显示当前版本、检查按钮、下载进度条、更新日志(3 行省略)、错误重试

## CI 构建

### 触发条件

- 推送 `v*` tag:同时触发 PC 和 Android 构建,并上传到 Release
- 推送到 master/PR:仅构建验证,不上传到 Release

### 关键修复点

- **构建前清理 bundle 目录**:CI 缓存会残留旧版本产物,必须在 `cargo tauri build` 前清理 `target/.../bundle/{nsis,msi}/*`
- **多扩展名重命名**:`.nsis.zip`、`.nsis.zip.sig` 等多扩展名需要特殊处理,不能使用 `${f##*.}` 只取最后一段
- **pipefail 容错**:`ls *.sig` 在没有签名文件时会失败,需要 `|| true` 防止整个步骤退出

## 开发约定

- UI 样式使用 CSS 变量保持颜色/边框/间距一致
- Tauri 窗口状态(maximized/focused)通过 body CSS 类同步,所有状态变化加 0.2s 过渡
- 前端必须设置 `withGlobalTauri: true` 以启用 `window.__TAURI__.invoke`
- `start_service` 命令需要 `sensitivity` 字段
- 服务器必须在 `127.0.0.1:{base_port+3}/stats` 暴露 HTTP 端点
- `MEOWMIC_MUTE_SPEAKER` 环境变量控制音频静音
- Android 触控事件处理有已知 bug(JNI 层丢失 EVT_SCROLL、pointerIndex/pointerId 混淆)
- Kotlin `vararg` 参数必须放在函数签名最后

## 发现与连接(2026-08 重构,借鉴 Sunshine/Moonlight)

- **身份模型**:服务端公钥(`server_pubkey_b64`,持久化)即 PC 身份,类 Sunshine uniqueid;App 端 PC 列表按 `pk:<pubkey>` 键控合并地址,DHCP 换 IP 不失效
- **mDNS TXT**:`v`(协议版本)、`name`(显示名)、`pk`(服务端公钥,发现即识别)
- **serverinfo**:`GET {base_port+4}/serverinfo?pubkey=<客户端公钥b64>` 额外返回 `pair_status`(该客户端是否已配对);不带 pubkey 时无此字段(兼容旧客户端)
- **nativeConnect 返回码**:0=通用失败,1=已连接,2=需配对,3=地址无效,4=主机不可达(TCP 3s 超时),5=连接被拒绝
- **客户端 TCP 连接超时**:`crates/net/src/client.rs` 的 `CONNECT_TIMEOUT_SECS`(3s),超时映射为 io `TimedOut`;Kotlin 看门狗 join 为 15s(仅兜底)
- **地址规范化**:App 侧 `normalizeAddress()` 去空格/去 scheme、裸 IP 自动补 `:28900`;非法地址在 UI 即时提示,不发起连接
- **手动 PC**:`MeowMicViewModel` 持久化于 SharedPreferences `manual_pcs`(JSON),与 mDNS 发现同权轮询(`ServerInfoProber` 共用探测逻辑)
