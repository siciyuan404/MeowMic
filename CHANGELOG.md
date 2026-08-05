# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.23.0] - 2026-08-06

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
