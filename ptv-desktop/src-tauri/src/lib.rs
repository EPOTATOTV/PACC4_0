use std::fs::OpenOptions;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde_json::{json, Value};
use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{AppHandle, Emitter, Manager, WebviewWindow};
use tauri_plugin_autostart::ManagerExt;

mod local_client;
use local_client::ControlClient;

/// 持有 Java 客户端（ptv-client）子进程句柄，用于崩溃重启与生命周期管理。
#[derive(Default)]
struct ClientProc {
    child: Mutex<Option<Child>>,
}

fn redscreen_window(app: &AppHandle) -> Option<WebviewWindow> {
    app.get_webview_window("redscreen")
}

/// 打开/关闭红屏全屏警告窗口（置顶 + 全屏 + 去装饰，覆盖所有窗口）。
#[tauri::command]
fn toggle_redscreen(app: AppHandle, on: bool) -> Result<(), String> {
    let win = redscreen_window(&app).ok_or("无红屏窗口")?;
    if on {
        win.set_always_on_top(true).map_err(|e| e.to_string())?;
        win.show().map_err(|e| e.to_string())?;
        win.set_fullscreen(true).map_err(|e| e.to_string())?;
        win.set_focus().map_err(|e| e.to_string())?;
    } else {
        win.set_fullscreen(false).map_err(|e| e.to_string())?;
        win.set_always_on_top(false).map_err(|e| e.to_string())?;
        win.hide().map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// 显示主窗口（系统托盘菜单回调使用）。
#[tauri::command]
fn show_main_window(app: AppHandle) -> Result<(), String> {
    if let Some(win) = app.get_webview_window("main") {
        win.show().map_err(|e| e.to_string())?;
        win.unminimize().map_err(|e| e.to_string())?;
        win.set_focus().map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// 返回打包进资源目录的 Java 客户端 jar 路径。
fn client_jar(app: &AppHandle) -> Option<PathBuf> {
    let resource_dir = app.path().resource_dir().ok()?;
    for e in std::fs::read_dir(resource_dir).ok()?.flatten() {
        let name = e.file_name().to_string_lossy().to_string();
        if name.starts_with("ptv-client-") && name.ends_with(".jar") {
            return Some(e.path());
        }
    }
    None
}

const LOG_DIR: &str = r"C:\ProgramData\PACC\logs";
/// Java 客户端共享的查端屏幕共享凭据文件（RT 由客户端登录后以受限权限写入）。
const WS_CRED_FILE: &str = r"C:\ProgramData\PACC\ws-credentials.json";

/// 启动 Java 客户端服务：日志重定向到本机日志目录。
fn spawn_client(app: &AppHandle) {
    let Some(jar) = client_jar(app) else {
        eprintln!("未找到 ptv-client-*.jar，跳过 Java 服务启动");
        return;
    };
    let _ = std::fs::create_dir_all(LOG_DIR);
    let log_file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(PathBuf::from(LOG_DIR).join("client-tauri.log"))
        .ok();

    let mut cmd = Command::new("java");
    cmd.arg("-jar").arg(jar);
    match log_file {
        Some(f) => {
            let stderr = f.try_clone().expect("clone stderr");
            cmd.stdout(Stdio::from(f)).stderr(Stdio::from(stderr));
        }
        None => {
            cmd.stdout(Stdio::null()).stderr(Stdio::null());
        }
    }

    match cmd.spawn() {
        Ok(child) => {
            let state = app.state::<ClientProc>();
            *state.child.lock().expect("lock child") = Some(child);
        }
        Err(e) => eprintln!("启动 Java 客户端失败: {e}"),
    }
}

/// 崩溃自动重启：后台线程轮询子进程，退出即重新拉起。
fn supervise(app: AppHandle) {
    std::thread::spawn(move || loop {
        std::thread::sleep(Duration::from_secs(5));
        let state = app.state::<ClientProc>();
        let mut guard = state.child.lock().expect("lock child");
        let exited = guard
            .as_mut()
            .and_then(|c| c.try_wait().ok().flatten())
            .is_some();
        if exited {
            *guard = None;
            drop(guard);
            spawn_client(&app);
        }
    });
}

/// 生成诊断 JSON，供"诊断工具"导出。
#[tauri::command]
fn diagnostics_json(app: AppHandle) -> String {
    let jar = client_jar(&app).map(|p| p.display().to_string());
    serde_json::json!({
        "app": "PACC 玩家端",
        "version": env!("CARGO_PKG_VERSION"),
        "client_jar": jar,
        "log_dir": LOG_DIR,
        "generated_at": SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0),
    })
    .to_string()
}

/// 桌面桥：为前端"查端屏幕共享页"注入连接 /ws/ptv 所需的 pteid+token（B2）。
/// <p>玩家 JWT 存于 HttpOnly cookie，WebView 的 JS 无法读取，故由宿主桥转发：
/// 优先读 Java 客户端共享的本地凭据文件，其次回退环境变量；未注入时返回 Err，
/// 前端据此回退到 URL 参数传参。</p>
#[tauri::command]
fn screen_share_credentials() -> Result<serde_json::Value, String> {
    if let Ok(content) = std::fs::read_to_string(WS_CRED_FILE) {
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(&content) {
            let p = v.get("pteid").and_then(|x| x.as_str()).unwrap_or("");
            let t = v.get("token").and_then(|x| x.as_str()).unwrap_or("");
            if !p.is_empty() && !t.is_empty() {
                return Ok(serde_json::json!({ "pteid": p, "token": t }));
            }
        }
    }
    let pteid = std::env::var("PACC_SCREEN_PTEID").unwrap_or_default();
    let token = std::env::var("PACC_SCREEN_TOKEN").unwrap_or_default();
    if pteid.is_empty() || token.is_empty() {
        return Err("未注入查端屏幕共享凭据（PACC_SCREEN_PTEID/PACC_SCREEN_TOKEN 或 ws-credentials.json）".into());
    }
    Ok(serde_json::json!({ "pteid": pteid, "token": token }))
}

/// ---- 检测控制桥（React → Rust → Java 本地控制服务） ----

#[tauri::command]
fn detection_status(app: AppHandle) -> Result<Value, String> {
    app.state::<ControlClient>().get("/status")
}

#[tauri::command]
fn detection_start(app: AppHandle) -> Result<Value, String> {
    app.state::<ControlClient>().post("/start")
}

#[tauri::command]
fn detection_stop(app: AppHandle) -> Result<Value, String> {
    app.state::<ControlClient>().post("/stop")
}

#[tauri::command]
fn get_detections(app: AppHandle, limit: Option<u32>) -> Result<Value, String> {
    let n = limit.unwrap_or(20);
    app.state::<ControlClient>().get(&format!("/detections?limit={n}"))
}

#[tauri::command]
fn get_detection_config(app: AppHandle) -> Result<Value, String> {
    app.state::<ControlClient>().get("/config")
}

#[tauri::command]
fn update_detection_config(app: AppHandle, cfg: Value) -> Result<Value, String> {
    app.state::<ControlClient>().post_json("/config", cfg)
}

#[tauri::command]
fn pteid_info(app: AppHandle) -> Result<Value, String> {
    app.state::<ControlClient>().get("/pteid")
}

#[tauri::command]
fn open_logs() -> Result<(), String> {
    let _ = std::fs::create_dir_all(LOG_DIR);
    if cfg!(target_os = "windows") {
        Command::new("explorer")
            .arg(LOG_DIR)
            .spawn()
            .map_err(|e| e.to_string())
            .map(|_| ())
    } else {
        Command::new("xdg-open")
            .arg(LOG_DIR)
            .spawn()
            .map_err(|e| e.to_string())
            .map(|_| ())
    }
}

#[tauri::command]
fn set_autostart(app: AppHandle, enabled: bool) -> Result<(), String> {
    if enabled {
        app.autolaunch().enable().map_err(|e| e.to_string())
    } else {
        app.autolaunch().disable().map_err(|e| e.to_string())
    }
}

/// 后台轮询 Java 侧状态，向 WebView 推送状态/告警/红屏事件（事件名见设计文档 5.1.2）。
fn poll_and_emit(app: AppHandle) {
    let ctl = app.state::<ControlClient>();
    let _ = ctl.refresh();
    let mut prev_event = String::new();
    let mut prev_rs = false;
    loop {
        std::thread::sleep(Duration::from_secs(10));
        if let Ok(st) = ctl.get("/status") {
            let cur_event = st["last_event_type"].as_str().unwrap_or("").to_string();
            let rs = st["redscreen_active"].as_bool().unwrap_or(false);
            let _ = app.emit("status_update", st.clone());
            if !cur_event.is_empty() && cur_event != prev_event {
                let _ = app.emit(
                    "detection_alert",
                    json!({ "event_type": cur_event, "level": st["redscreen_level"] }),
                );
            }
            if rs && !prev_rs {
                let _ = app.emit(
                    "redscreen_triggered",
                    json!({ "level": st["redscreen_level"] }),
                );
            } else if !rs && prev_rs {
                let _ = app.emit("redscreen_unlocked", json!({}));
            }
            prev_event = cur_event;
            prev_rs = rs;
        }
    }
}

/// 桌面壳入口：封装 React GUI（继承自 ptv-frontend）+ Java 客户端进程 + 系统托盘 + 红屏窗口。
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            Some(vec!["--auto-start"]),
        ))
        .plugin(tauri_plugin_updater::Builder::new().build())
        .manage(ClientProc::default())
        .manage(ControlClient::default())
        .setup(|app| {
            // 系统托盘
            let i_main = MenuItem::with_id(app, "show", "显示主窗口", true, None::<&str>)?;
            let i_diag = MenuItem::with_id(app, "diag", "导出诊断", true, None::<&str>)?;
            let i_quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&i_main, &i_diag, &i_quit])?;

            TrayIconBuilder::with_id("pacc-tray")
                .icon(app.default_window_icon().expect("default icon").clone())
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id().as_ref() {
                    "show" => {
                        let _ = show_main_window(app.clone());
                    }
                    "diag" => {
                        let _ = app.emit("diagnostics", diagnostics_json(app.clone()));
                    }
                    "quit" => app.exit(0),
                    _ => {}
                })
                .build(app)?;

            // 开机自启（安装时默认开启，可在设置中关闭）
            let _ = app.autolaunch().enable();

            // Java 客户端进程管理与崩溃自愈
            let handle = app.handle().clone();
            spawn_client(&handle);
            supervise(handle.clone());
            // 本地控制服务状态轮询：向 WebView 推送状态/红屏/告警事件
            std::thread::spawn(move || poll_and_emit(handle));

            let _ = app.emit(
                "app-ready",
                serde_json::json!({ "version": env!("CARGO_PKG_VERSION") }),
            );
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            toggle_redscreen,
            show_main_window,
            diagnostics_json,
            screen_share_credentials,
            detection_status,
            detection_start,
            detection_stop,
            get_detections,
            get_detection_config,
            update_detection_config,
            pteid_info,
            open_logs,
            set_autostart
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application")
}