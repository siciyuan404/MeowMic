# 手机充当电脑扩展屏

MeowMic 已移除旧"远程桌面"能力,转向**手机作为电脑第二显示器(虚拟扩展屏)**。
PC 端通过开源**间接显示驱动(IddCx)**虚拟出一块显示器,服务端从该屏裁剪画面编码推流到手机。

## 一、整体原理

```
Windows 虚拟显示器(IDD)  ──►  桌面采集器枚举/裁剪目标屏  ──►  UDP 推流(视频/音频)
        │                                                          │
        └────────── 手机端解码渲染(SurfaceView) ◄──────────────────┘
                              │
                              └── 相对位移鼠标控制(触控板),经 SendInput 回注 PC
```

- **屏幕来源**：`pc/server/src/screen.rs` 枚举本机所有显示器(虚拟桌面坐标,支持负坐标),`select_target` 挑选"虚拟屏"，`crop_to_target` 从整屏 BGRA 帧裁剪目标屏区域。
- **编码推流**：主视频链路为服务端主动 UDP push(`base+6`),不依赖逐帧 HTTP 轮询。
- **鼠标控制**：`touch_inject.rs` 使用相对位移 `SendInput`，与具体显示器坐标无关，直接复用。
- **接口**：手机通过 `GET /screen/info` 获取目标屏分辨率；旧的 `/screen/capture` 与 `/screen/h264` 已删除。

## 二、安装虚拟显示器驱动(一次性)

在 **PC** 上以**管理员 PowerShell** 运行安装脚本：

```powershell
# 到仓库根目录
cd f:\git\MeowMic

# 一键下载并注册开源 IDD 驱动包
.\tools\extend-display\install-idd.ps1
```

脚本会：
1. 校验管理员权限；
2. 从开源仓库(默认 `itsmattkc/VirtualDisplayDriver-Rust`)拉取最新驱动 zip；
3. 解压并 `pnputil /add-driver` 注册驱动包；
4. 打印创建"虚拟显示器设备"的后续步骤(Win+R → `hdwwiz` 手动添加，或用 `devcon.exe`)。

如需指定其他驱动包：

```powershell
.\tools\extend-display\install-idd.ps1 -ZipUrl "https://.../driver.zip"
```

## 三、驱动安装后的配置

1. 创建设备后，`Win+P` → **扩展**，把虚拟屏拖到你期望的相对位置（左/右/上/下）。
2. 在 `设置 → 系统 → 显示` 确认虚拟屏分辨率与 Windows 抓屏分辨率一致。
3. 启动 MeowMic 服务端，它会自动枚举并锁定虚拟屏作为推流目标。

## 四、前置要求

- Windows 10 1809+（IddCx 对系统版本的要求）。
- 管理员权限（安装内核驱动必须）。
- 若使用硬件编码，分辨率需对齐到 16 的倍数（服务端 `scale_bgra_down` 已自动处理）。