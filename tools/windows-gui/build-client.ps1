# PACC v5.0 Windows 客户端一键打包脚本
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools/windows-gui/build-client.ps1
# 职责：构建 WPF 单文件 EXE -> 构建 Java 探针 jar -> 写入客户端配置（自动读取根目录 .env 的 WSS 密钥）
#      -> 组装 zip 到 download 发布目录 deploy/dl-web/files/pacc-client-windows-x64-v5.4.0.zip
#      -> 生成 version.json（客户端自动更新清单，含 sha256 防篡改）
# 前置依赖：.NET 8 SDK（dotnet）、JDK 21 + Maven（mvn）
$ErrorActionPreference = "Stop"

$root      = (Resolve-Path "$PSScriptRoot/../..").Path        # 项目根目录
$exeOut    = Join-Path $root "dist/win-x64"                   # WPF 单文件输出目录
$jarDir    = "$root/ptv-client/target"
$artifactId = "ptv-client-5.4.0.jar"
$publish   = "$root/deploy/dl-web/files/pacc-client-windows-x64-v5.4.0.zip"
$version   = "v5.4.0"

Write-Host "== PACC Windows 客户端打包 ($version) ==" -ForegroundColor Cyan

# ---------- 前置检查 ----------
foreach ($c in @('dotnet','mvn','java')) {
  if (-not (Get-Command $c -ErrorAction SilentlyContinue)) { throw "缺少命令：$c（请先安装 .NET 8 SDK / JDK21 / Maven）" }
}

# ---------- 1. 构建 WPF 单文件 EXE ----------
Write-Host "`n[1/4] 构建 WPF 单文件 EXE ..." -ForegroundColor Green
# 清理旧输出，保证产物干净（EXE 可能被残留进程/杀软锁定，失败只告警不中止）
Remove-Item $exeOut -Recurse -Force -ErrorAction SilentlyContinue
# 多文件自包含发布：托管 PaccManager.dll 独立成文件，便于后续 PCO 混淆
dotnet publish "$root/tools/windows-gui/PaccManager.csproj" -c Release -r win-x64 `
  --self-contained true -p:PublishSingleFile=false -o $exeOut
if ($LASTEXITCODE -ne 0) { throw "dotnet publish 失败 (exit=$LASTEXITCODE)" }
if (-not (Test-Path $exeOut)) { throw "publish 未产出目录 $exeOut" }

# ---------- 1b. PCO 混淆 PaccManager.dll ----------
Write-Host "`n[1b] PCO 混淆 ..." -ForegroundColor Green
$pcoProj   = Join-Path $root "tools/pco/Pco.csproj"
$pcoDll    = Join-Path $root "tools/pco/bin/Release/net8.0/pco.dll"
$pcoRules  = Join-Path $root "tools/windows-gui/pco-rules.json"
$pcoTarget = Join-Path $exeOut "PaccManager.dll"
$mapDir    = Join-Path $root "dist/pco-map"
# 混淆结果先写到临时目录：PCO 是整份读写，直接原地覆盖会让「读一半的输入」变成输出。
$pcoStage  = Join-Path $env:TEMP ("pco-" + [guid]::NewGuid().ToString("N") + ".dll")
$pcoMap    = Join-Path $env:TEMP ("pco-map-" + [guid]::NewGuid().ToString("N") + ".txt")

dotnet build $pcoProj -c Release --nologo -v quiet
if ($LASTEXITCODE -ne 0) { throw "PCO 构建失败 (exit=$LASTEXITCODE)" }
& dotnet $pcoDll -i $pcoTarget -o $pcoStage --rules $pcoRules --mapping $pcoMap
if ($LASTEXITCODE -ne 0) { throw "PCO 混淆失败 (exit=$LASTEXITCODE)" }
if (-not (Test-Path $pcoStage)) { throw "PCO 未产出程序集" }
Copy-Item $pcoStage $pcoTarget -Force

# 映射表是反混淆对照表：留着能还原崩溃栈，但一旦随包发布，等于把符号表送给逆向者。
# 因此留档到 dist 下（已在 .gitignore，不进 zip、不上下载站），发布目录里必须清掉。
New-Item -ItemType Directory -Force -Path $mapDir | Out-Null
Copy-Item $pcoMap (Join-Path $mapDir "PaccManager-$version-mapping.txt") -Force
Remove-Item $pcoStage, $pcoMap -Force -ErrorAction SilentlyContinue
Write-Host ("  已混淆: {0}" -f $pcoTarget)
Write-Host ("  映射表: {0}（留档，不随产物发布）" -f $mapDir)

# ---------- 2. 构建 Java 探针 jar ----------
Write-Host "`n[2/4] 构建 Java 探针 jar ..." -ForegroundColor Green
# 探针依赖仓库内的协议运行时（PBP）与更新核心（PCU），两者都没发到中央仓库。
# 不先 install 进本地仓库，mvn package 会以「无法解析 com.potatotv:pacc-binary-protocol /
# com.potatotv:pacc-cross-platform-updater」失败，而不是给出可读的原因。
foreach ($m in @('pacc-binary-protocol/runtime-java', 'pacc-cross-platform-updater')) {
  $mDir = Join-Path $root $m
  if (-not (Test-Path $mDir)) { throw "缺少模块目录 $m，无法构建探针 jar" }
  & mvn -B -q -f (Join-Path $mDir 'pom.xml') -DskipTests install
  if ($LASTEXITCODE -ne 0) { throw "mvn install 失败（$m）" }
}
$mvnOut = & mvn -B -f "$root/ptv-client/pom.xml" clean package -DskipTests 2>&1
$mvnCode = $LASTEXITCODE
$mvnOut | Tee-Object -FilePath "$root/build-mvn.log" | Out-Null
if ($mvnCode -ne 0) { throw "mvn package 失败 (exit=$mvnCode)" }
$jar = Join-Path $jarDir $artifactId
if (-not (Test-Path $jar)) { throw "未找到 $jar" }
# 以探针发布名放入 exeOut（zip 内）与 install.iss 的源目录一致
$probeName = "ptv-agent-5.4.0.jar"
Copy-Item $jar (Join-Path $exeOut $probeName) -Force

# ---------- 3. 生成客户端配置（自动读 .env 的 WSS 密钥）----------
Write-Host "`n[3/4] 生成客户端配置并写入 WSS 密钥 ..." -ForegroundColor Green
$envFile = Join-Path $root ".env"
if (-not (Test-Path $envFile)) { throw "找不到根目录 .env，无法读取签名密钥" }
function Read-EnvValue([string]$name) {
  (Select-String -Path $envFile -Pattern "^$name=" |
   ForEach-Object { $_.Line -replace '^[^=]+=','' }).Trim()
}
$secret = Read-EnvValue 'PACC_SECURITY_WSS_SIGN_SECRET'
if ([string]::IsNullOrWhiteSpace($secret)) { throw ".env 未设置 PACC_SECURITY_WSS_SIGN_SECRET" }
# 特征库包签名密钥：客户端缺失时拒绝启动（与后端 PACC_SIG_SECRET 一致）
$sigSecret = Read-EnvValue 'PACC_SIG_SECRET'
if ([string]::IsNullOrWhiteSpace($sigSecret)) { throw ".env 未设置 PACC_SIG_SECRET" }

@"
# PACC v5.0 客户端配置（由 build-client.ps1 自动生成）
pacc.client.endpoint=wss://pacc.potatotv.asia/ws/ptv
pacc.client.api-base=https://api.potatotv.asia
pacc.detection.redscreen-threshold=85
pacc.detection.sample-rate=1.0
pacc.log.level=INFO
pacc.client.wss-secret=$secret
pacc.client.signature.secret=$sigSecret
"@ | Set-Content (Join-Path $exeOut "pacc-client.properties") -Encoding utf8

# ---------- 组装 zip 到发布目录 ----------
$publishDir = Split-Path $publish
New-Item -ItemType Directory -Force -Path $publishDir | Out-Null
if (Test-Path $publish) { Remove-Item $publish -Force }
Compress-Archive -Path (Join-Path $exeOut "*") -DestinationPath $publish -Force

# ---------- 5. 发布独立探针 jar + 计算 SHA256 并生成 version.json（供客户端自动更新） ----------
# 探针独立发布件（JVM Agent），dl 域公开下载，供 PaccManager 相对下载替换
# $probeName 已在步骤 2 定义；此处从 exeOut 复制独立发布件到 dl 目录
$probeJar   = Join-Path $publishDir $probeName
Copy-Item (Join-Path $exeOut $probeName) $probeJar -Force
if (-not (Test-Path $probeJar)) { throw "探针发布件生成失败：$probeJar" }

$sha256 = (Get-FileHash -Algorithm SHA256 $publish).Hash.ToLowerInvariant()
$probeHash = (Get-FileHash -Algorithm SHA256 $probeJar).Hash.ToLowerInvariant()

$zipName = Split-Path $publish -Leaf
$json = @"
{
  "client_version": "$version",
  "client_url": "/files/$zipName",
  "client_sha256": "$sha256",
  "probe_version": "$version",
  "probe_url": "/files/$probeName",
  "probe_sha256": "$probeHash",
  "min_version": "5.4.0"
}
"@
$jsonPath = Join-Path $publishDir "version.json"
Set-Content -Path $jsonPath -Value $json -Encoding utf8

Write-Host "`n== 打包完成 ==" -ForegroundColor Cyan
Write-Host ("  EXE    : {0}" -f (Join-Path $exeOut "PaccManager.exe"))
Write-Host ("  JAR    : {0}（zip 内）" -f (Join-Path $exeOut $probeName))
Write-Host ("  ZIP    : {0}" -f $publish)
Write-Host ("  PROBE  : {0}" -f $probeJar)
Write-Host ("  VERSION: {0}  (zip sha256 前16位={1})" -f $jsonPath, $sha256.Substring(0,16))
Write-Host ("  配置包含 WSS 密钥 (len={0})" -f $secret.Length)