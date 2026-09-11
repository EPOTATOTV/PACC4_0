//! 与 Java 检测引擎串起回环控制协议的客户端。
//! Java 侧 (ptv-client) 启动时开启 127.0.0.1 随机端口控制服务，
//! 并把端口 + token 写入 control.json；本模块读取后向该服务下发检测控制并查询状态。
//! 任意一步失败都返回 Err，由调用方回退为"桌面壳未托管可用 Java 服务"的友好提示。

use serde_json::Value;
use std::path::PathBuf;
use std::sync::Mutex;

/// 解析出的本地控制服务连接信息。
#[derive(Clone, Debug)]
pub struct LocalControl {
    pub port: u16,
    pub token: String,
}

/// Tauri 托管状态：缓存最近一次发现的连接信息，避免每次调用重复读盘。
#[derive(Default)]
pub struct ControlClient {
    inner: Mutex<Option<LocalControl>>,
}

impl ControlClient {
    /// 重新读盘发现控制文件；成功则更新缓存。
    pub fn refresh(&self) -> Result<LocalControl, String> {
        let file = control_file_path();
        let raw = std::fs::read_to_string(&file)
            .map_err(|e| format!("读取控制文件失败（{}）: {e}", file.display()))?;
        let v: Value = serde_json::from_str(&raw).map_err(|e| format!("解析控制文件失败: {e}"))?;
        let port = v
            .get("port")
            .and_then(|x| x.as_u64())
            .ok_or("控制文件缺少 port")? as u16;
        let token = v
            .get("token")
            .and_then(|x| x.as_str())
            .ok_or("控制文件缺少 token")?
            .to_string();
        let ctl = LocalControl { port, token };
        *self.inner.lock().expect("lock control") = Some(ctl.clone());
        Ok(ctl)
    }

    fn url(&self, path: &str) -> Result<String, String> {
        let ctl = match *self.inner.lock().expect("lock control") {
            Some(ref c) => c.clone(),
            None => self.refresh()?,
        };
        let sep = if path.contains('?') { "&" } else { "?" };
        Ok(format!(
            "http://127.0.0.1:{}/api/local{}{}token={}",
            ctl.port, path, sep, ctl.token
        ))
    }

    pub fn get(&self, path: &str) -> Result<Value, String> {
        let url = self.url(path)?;
        ureq::get(&url)
            .call()
            .map_err(|e| format!("本地控制服务请求失败: {e}"))?
            .into_json()
            .map_err(|e| format!("解析响应失败: {e}"))
    }

    pub fn post(&self, path: &str) -> Result<Value, String> {
        let url = self.url(path)?;
        ureq::post(&url)
            .send_bytes(&[])
            .map_err(|e| format!("本地控制服务请求失败: {e}"))?
            .into_json()
            .map_err(|e| format!("解析响应失败: {e}"))
    }

    pub fn post_json(&self, path: &str, body: Value) -> Result<Value, String> {
        let url = self.url(path)?;
        ureq::post(&url)
            .send_json(body)
            .map_err(|e| format!("本地控制服务请求失败: {e}"))?
            .into_json()
            .map_err(|e| format!("解析响应失败: {e}"))
    }
}

/// 控制文件位置：与查端凭据文件同目录（Win: C:\ProgramData\PACC）。
fn control_file_path() -> PathBuf {
    if let Ok(override_path) = std::env::var("PACC_SCREEN_CRED_FILE") {
        if !override_path.is_empty() {
            let p = PathBuf::from(override_path);
            return p.parent().unwrap_or(&p).join("control.json");
        }
    }
    let is_windows = std::env::consts::OS == "windows";
    if is_windows {
        PathBuf::from(r"C:\ProgramData\PACC\control.json")
    } else {
        let home = std::env::var("HOME").unwrap_or_default();
        PathBuf::from(home)
            .join(".config")
            .join("pacc")
            .join("control.json")
    }
}