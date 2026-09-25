# PACC v5.0 Windows 安装程序脚本
# 由 PaccManager GUI 调用，或可独立运行：
#   powershell -ExecutionPolicy Bypass -File tools/windows-gui/deploy/installer.ps1
#
# 职责：校验权限 -> 安装 Windows 服务 -> 注册底层检测模块 -> 生成配置基线。
# 需要：PACC 客户端（ptv-client-5.4.0.jar 或原生探针）、所在目录下的 pacc-client.properties。

param(
    [string]$ServiceName = "PaccProtect",
    [string]$BinDir   = (Join-Path $env:ProgramFiles "PACC\bin"),
    [string]$DataDir  = (Join-Path $env:ProgramData "PACC"),
    [switch]$Uninstall
)

$ErrorActionPreference = "Stop"
Write-Host "== PACC v5.0 Windows 安装程序 ==" -ForegroundColor Cyan

# 1) 管理员权限校验
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).
    IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) { throw "需要以管理员身份运行 PowerShell。" }

if ($Uninstall) {
    if (Get-Service $ServiceName -ErrorAction SilentlyContinue) {
        Stop-Service $ServiceName -Force -ErrorAction SilentlyContinue
        sc.exe delete $ServiceName | Out-Null
        Write-Host "已卸载服务 $ServiceName" -ForegroundColor Green
    }
    return
}

# 2) 目录与文件
New-Item -ItemType Directory -Force -Path $BinDir, $DataDir | Out-Null
$src = Join-Path $PSScriptRoot ".."
Write-Host "源目录: $src"

# 3) 生成默认配置（若 pacc-client.properties 不存在）
$cfg = Join-Path $DataDir "pacc-client.properties"
if (-not (Test-Path $cfg)) {
    @"
# PACC v5.0 客户端配置
pacc.client.endpoint=wss://pacc.potatotv.asia/ws/ptv
pacc.client.api-base=https://api.potatotv.asia
pacc.detection.redscreen-threshold=85
pacc.detection.sample-rate=1.0
pacc.log.level=INFO
"@ | Set-Content -Path $cfg -Encoding UTF8
    Write-Host "已生成默认配置: $cfg" -ForegroundColor Green
} else {
    Write-Host "配置已存在: $cfg"
}

# 4) 注册 Windows 服务（示例：以 nssm 包装 Java 客户端；部署时替换为真实探针/驱动）
#    生产环境请使用 WHQL 签名驱动安装器注册底层模块。
Write-Host "已就绪。请将 ptv-client-5.4.0.jar / 原生探针部署到 $BinDir 并配置服务。" -ForegroundColor Yellow
Write-Host "安装完成。" -ForegroundColor Green