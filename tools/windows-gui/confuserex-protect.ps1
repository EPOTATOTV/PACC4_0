# PACC PaccManager 加强档加固脚本（可选，非发行默认路径）
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools/windows-gui/confuserex-protect.ps1
#   可选参数：
#     -ConfuserPath <path>  指定自备的 Confuser.CLI.exe（离线/受控环境推荐）
#     -ExeOut <path>        指定 dotnet publish 输出目录，默认 dist/win-x64
# 前置：
#   1) 先执行 dotnet publish（或用参数把脚本指向已有 publish 目录）：
#        dotnet publish tools/windows-gui/PaccManager.csproj -c Release -r win-x64 `
#          --self-contained true -p:PublishSingleFile=false -o dist/win-x64
#   2) 本脚本会就地覆盖 dist/win-x64/PaccManager.dll 为 ConfuserEx 加固后的版本。
#
# 与 build-client.ps1 的关系（务必理解再运行）：
#   - build-client.ps1 走 Obfuscar（发行默认档），可在一条命令里完成 EXE 打包 + Java 探针 +
#     配置生成 + zip 组装。
#   - 本脚本只做「把已经 publish 出来的 PaccManager.dll 换成 ConfuserEx 加固版」这一步，
#     不重建 zip、不生成配置。它用于**替代** Obfuscar 档，而不是在其之上叠加（两套都做重命名，
#     串起来属于二次处理，收益有限、出问题的面更大）。
param(
  [string]$ConfuserPath = "",
  [string]$ExeOut = ""
)

$ErrorActionPreference = "Stop"

$root = (Resolve-Path "$PSScriptRoot/../..").Path
if ([string]::IsNullOrWhiteSpace($ExeOut)) { $ExeOut = Join-Path $root "dist/win-x64" }
$version = "v5.4.0"

Write-Host "== PACC PaccManager 加强档加固 ($version) ==" -ForegroundColor Cyan

# ---------- 定位 Confuser.CLI.exe ----------
# 顺序：-ConfuserPath 显式指定 > 本地缓存 tools/confuserex-cache > GitHub Releases 下载（需联网）
if ([string]::IsNullOrWhiteSpace($ConfuserPath)) {
  $cacheDir = Join-Path $root "tools/confuserex-cache"
  $found = Get-ChildItem -Path $cacheDir -Filter "Confuser.CLI.exe" -Recurse -ErrorAction SilentlyContinue |
           Select-Object -First 1
  if ($found) {
    $ConfuserPath = $found.FullName
  } else {
    # 上游 mkaring/ConfuserEx 的发布件是 release zip，不在 nuget 上；此处固定一个版本以保证可复现。
    $pinned = "1.6.0"
    $zipUrl = "https://github.com/mkaring/ConfuserEx/releases/download/v$pinned/ConfuserEx-CLI.zip"
    $zipDst = Join-Path $cacheDir "ConfuserEx-CLI-$pinned.zip"
    Write-Host "[准备] 本地无 ConfuserEx，尝试从 $zipUrl 下载 ..." -ForegroundColor Yellow
    New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null
    Invoke-WebRequest -Uri $zipUrl -OutFile $zipDst -UseBasicParsing
    Expand-Archive -Path $zipDst -DestinationPath $cacheDir -Force
    $found = Get-ChildItem -Path $cacheDir -Filter "Confuser.CLI.exe" -Recurse -ErrorAction SilentlyContinue |
             Select-Object -First 1
    if ($found) { $ConfuserPath = $found.FullName }
  }
}
if (-not $ConfuserPath -or -not (Test-Path $ConfuserPath)) {
  throw "找不到 Confuser.CLI.exe。离线环境请用 -ConfuserPath 指定自备的 ConfuserEx CLI。"
}
Write-Host "  Confuser.CLI: $ConfuserPath"

# ---------- 校验 publish 目录 ----------
$dll = Join-Path $ExeOut "PaccManager.dll"
if (-not (Test-Path $dll)) {
  throw "未找到 $dll，请先 dotnet publish 到该目录，或用 -ExeOut 指定已有 publish 目录。"
}

# ---------- 回填占位符 ----------
$outDir = Join-Path $root "dist/win-x64-confused"
if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force -ErrorAction SilentlyContinue }
$projSrc = Join-Path $root "tools/windows-gui/ConfuserEx.crproj"
$projTmp = Join-Path $env:TEMP ("confuserex-" + [guid]::NewGuid() + ".crproj")
# 显式按 UTF-8 读取：本文件带中文注释，PowerShell 5.1 对无 BOM 文件默认按 ANSI 解码会致中文乱码。
$projRaw = (Get-Content $projSrc -Raw -Encoding UTF8).Replace('{{BASE_DIR}}', $ExeOut).Replace('{{OUTPUT_DIR}}', $outDir)
[System.IO.File]::WriteAllText($projTmp, $projRaw, (New-Object System.Text.UTF8Encoding($false)))

# ---------- 运行 ConfuserEx ----------
Write-Host "[1/2] ConfuserEx 加固 ..." -ForegroundColor Green
# -n：完成后不等待按键（CI/脚本环境必需）
& $ConfuserPath -n $projTmp
if ($LASTEXITCODE -ne 0) { throw "ConfuserEx 失败 (exit=$LASTEXITCODE)，详见上方日志与 $outDir/report" }

$confusedDll = Join-Path $outDir "PaccManager.dll"
if (-not (Test-Path $confusedDll)) { throw "ConfuserEx 未产出 $confusedDll" }

# ---------- 覆盖回 publish 目录 ----------
# ConfuserEx 与 Obfuscar 一样：输出目录只是加固后的模块（可能含解析到的依赖），
# 不复制 apphost 与运行时 dll。只把 PaccManager.dll 覆盖回去，保持其余运行时文件不动。
Copy-Item $confusedDll $dll -Force
Write-Host ("  已加固: {0}" -f $dll)

# ---------- 留档符号表 ----------
# symbols.map 是反混淆对照表：留着能还原崩溃栈，但绝不能随包发布。
$mapSrc = Join-Path $outDir "symbols.map"
$mapDir = Join-Path $root "dist/confuserex-map"
if (Test-Path $mapSrc) {
  New-Item -ItemType Directory -Force -Path $mapDir | Out-Null
  Copy-Item $mapSrc (Join-Path $mapDir "PaccManager-$version-symbols.map") -Force
  Write-Host ("  符号表: {0}（留档，不随产物发布）" -f $mapDir)
}

Remove-Item $outDir -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $projTmp -Force -ErrorAction SilentlyContinue

if (Test-Path (Join-Path $ExeOut 'symbols.map')) { throw "symbols.map 混入了发布目录，会随 zip 外泄" }
Write-Host "`n== 加强档加固完成 ==" -ForegroundColor Cyan
Write-Host "  提醒：anti tamper / anti debug 有误报代价，发行前请在真实终端回归。" -ForegroundColor Yellow