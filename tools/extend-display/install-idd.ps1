#requires -RunAsAdministrator
<#
    MeowMic 扩展屏 - 虚拟第二显示器驱动安装脚本
    =============================================
    作用:
      安装一个间接显示驱动(IDD/IddCx),在 Windows 上虚拟出"第二块显示器"。
      MeowMic 服务端枚举该虚拟屏并裁剪编码推流,手机端即可充当电脑扩展屏。

    前置:
      1. 必须用管理员身份运行(PowerShell -> 右键 -> 以管理员身份运行)
      2. Windows 10 1809+ (IddCx 版本要求)

    用法:
      .\install-idd.ps1               # 使用默认开源驱动(其最新 Release)
      .\install-idd.ps1 -ZipUrl "自定义驱动包zip地址"

    说明:本脚本下发的仅是"安装驱动包"这一步,安装完成后需按提示在
    PowerShell/设备管理器 里创建虚拟显示器设备(会自动检测并用 pnputil 注册)。
#>

param(
    # 开源间接显示驱动(IddCx)。默认:itsmattkc/VirtualDisplayDriver-Rust
    [string]$Repo = "itsmattkc/VirtualDisplayDriver-Rust",
    [string]$ZipUrl = ""
)

$ErrorActionPreference = "Stop"

function Write-Step([string]$msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "    $msg" -ForegroundColor Green }

# --- 0. 管理员检查 ---
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
            ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "未以管理员身份运行!请右键 PowerShell 选择'以管理员身份运行'后重试。" -ForegroundColor Red
    exit 1
}

# --- 1. 准备临时目录 ---
$tmp = Join-Path $env:TEMP "meowmic_idd_$(Get-Date -Format 'yyyyMMddHHmmss')"
New-Item -ItemType Directory -Path $tmp -Force | Out-Null
Write-Step "临时目录: $tmp"

# --- 2. 获取驱动包 ---
if (-not $ZipUrl) {
    Write-Step "从 GitHub Release 获取 '$Repo' 最新驱动包..."
    $apiUrl = "https://api.github.com/repos/$Repo/releases/latest"
    try {
        $release = Invoke-RestMethod -Headers @{ "User-Agent" = "MeowMic-extend-display" } -Uri $apiUrl
    } catch {
        Write-Host "无法访问 GitHub API($apiUrl):$($_.Exception.Message)" -ForegroundColor Red
        Write-Host "请改用 -ZipUrl 手动指定驱动包地址并重试。" -ForegroundColor Yellow
        exit 1
    }

    $asset = $release.assets | Where-Object { $_.name -match '\.zip$' } | Select-Object -First 1
    if (-not $asset) {
        Write-Host "仓库 '$Repo' 的最新 Release 中未找到 .zip 驱动包。请检查版本或改用 -ZipUrl。" -ForegroundColor Red
        exit 1
    }
    $ZipUrl = $asset.browser_download_url
    Write-Ok "定位到: $($asset.name)"
}

Write-Step "下载驱动包: $ZipUrl"
$zipPath = Join-Path $tmp "driver.zip"
try {
    Invoke-WebRequest -Uri $ZipUrl -OutFile $zipPath
} catch {
    Write-Host "下载失败:$($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
Write-Ok "下载完成 ($([math]::Round((Get-Item $zipPath).Length/1MB,1)) MB)"

# --- 3. 解压并查找 .inf ---
$extractDir = Join-Path $tmp "driver"
Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force
$inf = Get-ChildItem -Path $extractDir -Recurse -Filter "*.inf" | Select-Object -First 1
if (-not $inf) {
    Write-Host "驱动包内未找到 .inf 文件,无法安装驱动。" -ForegroundColor Red
    exit 1
}
Write-Step "找到驱动定义: $($inf.FullName)"

# --- 4. 用 pnputil 注册并安装驱动 ---
Write-Step "pnputil 注册驱动包..."
& pnputil /add-driver $inf.FullName /install
if ($LASTEXITCODE -ne 0) {
    Write-Host "pnputil 驱动安装失败($LASTEXITCODE)。可手动到设备管理器 -> 添加过时硬件 -> 手动选择该 inf。" -ForegroundColor Yellow
}

# --- 4.5 自动创建虚拟显示器设备 ---
# 硬件 ID 因驱动而异(如 Root\VirtualDisplay / Root\IddVirtualDisplay),
# 优先从 INF 中提取,再回退到常见候选,逐个 pnputil 尝试。
$autoCreated = $false
$createdHwid = $null
try {
    $infText = Get-Content -Raw $inf.FullName
} catch {
    $infText = ""
}
$candidates = @()
$candidates += [regex]::Matches($infText, '(Root\\[A-Za-z0-9_]+)') | ForEach-Object { $_.Groups[1].Value }
$candidates += 'Root\VirtualDisplay', 'Root\IddVirtualDisplay'
$candidates = @($candidates | Where-Object { $_ } | Select-Object -Unique)

Write-Step "自动创建设备(候选硬件 ID: $($candidates -join ', '))..."
foreach ($hwid in $candidates) {
    Write-Host "  尝试: $hwid"
    & pnputil /add-device "$hwid" 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        $autoCreated = $true
        $createdHwid = $hwid
        break
    }
}
if ($autoCreated) {
    Write-Ok "虚拟显示器设备已创建 ($createdHwid)。"
    # 触发即插即用重新扫描,确保 Windows 枚举出新屏
    & pnputil /scan-devices 2>&1 | Out-Null
    Write-Ok "已重新扫描设备。"
} else {
    Write-Host "自动创建设备未完成。请用下方手动方式创建虚拟显示器设备:" -ForegroundColor Yellow
}

# --- 5. 提示下一步:新建虚拟显示器设备 ---
Write-Step "完成"
Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
if ($autoCreated) {
    Write-Host " 虚拟显示器设备已自动创建!" -ForegroundColor Green
    Write-Host " 下一步:Win+P 选择'扩展',或在 设置->系统->显示 中把虚拟屏排列到想要的位置。" -ForegroundColor Green
    Write-Host " 若不生效,可重启一次 Windows 后再看显示设置。" -ForegroundColor Green
} else {
    Write-Host " 驱动包已安装,但需手动创建一块虚拟显示器设备。" -ForegroundColor Green
    Write-Host ""
    Write-Host " 方式 A(PowerShell 便捷版,若本机有 devcon.exe):" -ForegroundColor Yellow
    Write-Host "    devcon.exe install $($inf.Name) $($candidates[0])" -ForegroundColor Yellow
    Write-Host ""
    Write-Host " 方式 B(设备管理器手动):" -ForegroundColor Yellow
    Write-Host "    Win+R -> 'hdwwiz' 打开'添加硬件向导'
        1) 选择 '安装我手动从列表选择的硬件(高级)'
        2) 厂商选对应名称,型号选'虚拟显示器/Indirect Display'
        3) 下一步安装即可" -ForegroundColor Yellow
}
Write-Host ""
Write-Host " 安装后:Win+P 选择'扩展',或在 设置->系统->显示 中把虚拟屏排列到你想要的位置。" -ForegroundColor Green
Write-Host " MeowMic 服务端会自动枚举并从该屏裁剪推流到手机端。" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Green

Remove-Item -Path $tmp -Recurse -Force -ErrorAction SilentlyContinue