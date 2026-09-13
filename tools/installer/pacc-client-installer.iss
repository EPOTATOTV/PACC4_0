; ============================================================
; PACC v5.0 Windows 客户端安装向导（Inno Setup 脚本）
; 依赖：Inno Setup 6（https://jrsoftware.org/isinfo.php）
; 用法：ISCC.exe pacc-client-installer.iss
; 产出：Output\PACCClientSetup-5.0.0.exe
; 前置：先运行 tools/windows-gui/build-client.ps1 生成 dist/win-x64 下的文件
; ============================================================

#define MyAppName "PACC 客户端"
#define MyAppVersion "5.0.0"
#define MyAppPublisher "PotatoTV"
#define MyAppExeName "PaccManager.exe"
; 单文件自包含产物目录（由 build-client.ps1 生成在项目根 dist/win-x64）
;#define DistDir "..\windows-gui\dist\win-x64"
#define DistDir "..\..\dist\win-x64"

[Setup]
AppId={{8F3C1E52-7C24-4B6E-9A0D-4E5B2C6D8F01}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppVerName={#MyAppName} {#MyAppVersion}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
; 安装到 Program Files 需要管理员权限（安装器提权一次性；客户端运行时不强制 UAC）。
; 如需安装向导图标：放一个 installer.ico 到本目录并去掉下面 SetupIconFile 的注释。
PrivilegesRequired=admin
OutputDir=Output
OutputBaseFilename=PACCClientSetup-{#MyAppVersion}
; SetupIconFile=installer.ico
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\{#MyAppExeName}

; ---------- 文件 ----------
[Files]
; 多文件自包含发布整目录（apphost PaccManager.exe + 混淆后托管 PaccManager.dll
; + .NET 运行时各 dll + deps/runtimeconfig + pacc-client.properties），整目录拷贝保证运行时可用。
Source: "{#DistDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; Java 探针独立发布件（客户端运行时需要）——发布名与 version.json 的 probe 一致，可单独更新
Source: "{#DistDir}\ptv-agent-5.0.0.jar"; DestDir: "{app}\bin"; Flags: ignoreversion
; 安装编排脚本（供手动调用，安装驱动时）
Source: "..\windows-gui\deploy\installer.ps1"; DestDir: "{app}"; Flags: ignoreversion

; ---------- 快捷方式 ----------
[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\卸载 {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

; ---------- 任务选项 ----------
[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加图标：";

; ---------- 注册表：写卸载信息 & 版本 ----------
[Registry]
; 供自动更新脚本读取安装版本
Root: HKCU; Subkey: "Software\{#MyAppPublisher}\{#MyAppName}"; ValueType: string; ValueName: "InstallPath"; ValueData: "{app}"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\{#MyAppPublisher}\{#MyAppName}"; ValueType: string; ValueName: "Version"; ValueData: "{#MyAppVersion}"; Flags: uninsdeletevalue

; ---------- 安装完成画面 ----------
[Run]
; 安装完成勾选启动；参数 --first-run 触发首次初始化
Filename: "{app}\{#MyAppExeName}"; Description: "启动 {#MyAppName}"; Flags: nowait postinstall skipifsilent

; ---------- 说明文本 ----------
[Code]
procedure InitializeWizard;
begin
  WizardForm.FilenameLabel.Caption := '{#MyAppName} {#MyAppVersion} 安装向导';
end;