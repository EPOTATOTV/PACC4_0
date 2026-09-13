# PACC 桌面端客户端壳（ptv-desktop）

Tauri v2 + 复用 `ptv-frontend` 构建产物的桌面应用，负责把现有 React 玩家端/管理端页面封装为**可安装的桌面程序**，并托管 Java 客户端服务进程。

> 定位：`ptv-desktop` 是"壳"，真正的页面能力来自 `ptv-frontend` 的 `npm run build` 产物。本地开发引擎不变。

## 目录
- `src-tauri/`：Rust 源码与 Tauri 配置
  - `src/lib.rs`：系统托盘、红屏全屏窗口、开机自启、自动更新、Java 客户端进程管理与崩溃自愈、诊断导出
  - `src/main.rs`：入口
  - `tauri.conf.json`：窗口（main / redscreen）、构建、打包、updater 端点
  - `capabilities/default.json`：权限
- `package.json`：`@tauri-apps/cli` 脚本

## 构建（需在装好 Rust 工具链的机器上）
```bash
# 首次：补 icons（先运行 tauri icon <任意1024px PNG> 生成 icons/）
cd ptv-desktop
npm install
npm run build                      # 自动先构建 ptv-frontend，再 tauri build
```
产物：
- Windows：`Release/bundle/msi/*.msi`、`Release/bundle/nsis/*.exe`
- macOS：`*.dmg` / `*.pkg`（需 macOS + Xcode 公证）
- Linux：`*.AppImage` / `*.deb` / `*.rpm`（systemd 由打包器托管 Java 服务）

## 关键实现点
- **红屏全屏**：独立的 `redscreen` 窗口（`alwaysOnTop + fullscreen + 无装饰`），收到客户端红屏事件后由前端调 `toggle_redscreen(true)` 显示并置顶。
- **Java 服务进程**：启动时在资源目录查找 `ptv-client-*.jar`（经 `bundle.resources` 附带），`spawn` 该进程，日志重定向到 `%ProgramData%\PACC\logs\client-tauri.log`；后台线程轮询，崩溃自动重启。
- **系统托盘**：显示主窗口 / 导出诊断 / 退出。
- **开机自启**：安装时默认开启，前端设置页可切换。
- **自动更新**：指向下载站域名（`dl.your-domain.com`），发布前需生成 Tauri 签名密钥并把 `pubkey` 填入 `tauri.conf.json`。

## 与 v5.0 移动端的关系
移动端（`../ptv-mobile`）为远程查看/管理角色，不内嵌本地检测引擎；桌面壳才承载本地 Java 检测引擎与红屏强制覆盖。