//! 配置加载：properties 文件 + `PACC_CLIENT_*` 环境变量。
//!
//! 优先级 **环境变量 > 配置文件 > 内置默认值**，与 Java 端
//! `ClientConfig.get(properties, key, env, default)` 的口径完全一致，
//! 这样同一套部署文档对两端都成立。
//!
//! 刻意**不**提供任何签名密钥的默认值：产物里一旦有明文默认密钥，
//! 拿到发行件的人就能伪造上报。缺失就是缺失，由服务端拒收。

use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};

/// 默认配置文件路径（systemd 单元与 install.sh 使用同一路径）。
pub const DEFAULT_CONFIG_PATH: &str = "/etc/pacc/pacc-client.properties";

/// 客户端版本号（上报体 `client_version`）。
pub const CLIENT_VERSION: &str = "5.4.0";

/// 运行时配置。
#[derive(Debug, Clone)]
pub struct Config {
    /// 后端基础地址，如 `http://127.0.0.1:8080`（不含路径）。
    pub server_uri: String,
    /// 事件上报路径；若以 `http://` 开头则视为完整 URL，覆盖 `server_uri`。
    pub events_path: String,
    /// 玩家访问令牌（`Authorization: Bearer`）。`None` 表示匿名上报。
    pub token: Option<String>,
    /// 玩家 PTEID。
    pub pteid: String,
    /// 版本渠道（后端 edition 白名单需包含该值）。
    pub edition: String,
    /// 检测周期（秒）。
    pub heartbeat_seconds: u64,
    /// eBPF 事件源开关。
    pub ebpf_enabled: bool,
    /// kernel-linux loader 监听的 UNIX socket 路径。
    pub ebpf_socket: String,
    /// procfs 回退扫描开关。
    pub procfs_enabled: bool,
    /// 是否只上报可疑事件（低分周期事件不上报，避免刷云端）。
    pub report_only_suspicious: bool,
    /// 一次上报最多携带的事件数。
    pub max_events_per_report: usize,
    /// HTTP 超时（秒）。
    pub http_timeout_seconds: u64,
    /// 端侧 AI 模型文件路径（可选）。
    pub model_path: Option<String>,
    /// 端侧 AI 模型期望签名（可选，提供则强校验）。
    pub model_signature: Option<String>,
    /// 实际读取的配置文件路径（诊断用）。
    pub config_path: PathBuf,
    /// 配置文件是否存在（不存在时全部取默认值）。
    pub config_found: bool,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            server_uri: "http://127.0.0.1:8080".to_string(),
            events_path: "/api/player/security/events".to_string(),
            token: None,
            pteid: "PT0000000001".to_string(),
            edition: "LINUX".to_string(),
            heartbeat_seconds: 15,
            ebpf_enabled: true,
            ebpf_socket: "/run/pacc/pacc-ldm.sock".to_string(),
            procfs_enabled: true,
            report_only_suspicious: true,
            max_events_per_report: 20,
            http_timeout_seconds: 6,
            model_path: None,
            model_signature: None,
            config_path: PathBuf::from(DEFAULT_CONFIG_PATH),
            config_found: false,
        }
    }
}

/// 加载配置。
///
/// `path_override` 来自命令行 `--config`；给出但文件不存在时**报错**（不能让运维
/// 以为改了配置其实没生效），未给出则按默认路径尝试，缺文件只记一条告警。
pub fn load(path_override: Option<&str>) -> Result<Config, String> {
    let env_path = std::env::var("PACC_CLIENT_CONFIG").ok();
    let explicit = path_override.is_some() || env_path.is_some();
    let path = path_override
        .map(PathBuf::from)
        .or_else(|| env_path.map(PathBuf::from))
        .unwrap_or_else(|| PathBuf::from(DEFAULT_CONFIG_PATH));

    let props = if Path::new(&path).is_file() {
        let text = fs::read_to_string(&path)
            .map_err(|e| format!("读取配置 {} 失败: {e}", path.display()))?;
        parse_properties(&text)
    } else if explicit {
        return Err(format!("指定的配置文件不存在: {}", path.display()));
    } else {
        HashMap::new()
    };

    let found = !props.is_empty();
    let mut cfg = Config {
        config_path: path,
        config_found: found,
        ..Config::default()
    };

    cfg.server_uri = get(
        &props,
        "pacc.client.server.uri",
        "PACC_CLIENT_SERVER_URI",
        &cfg.server_uri,
    );
    cfg.events_path = get(
        &props,
        "pacc.client.events.path",
        "PACC_CLIENT_EVENTS_PATH",
        &cfg.events_path,
    );
    cfg.token = get_opt(&props, "pacc.client.token", "PACC_CLIENT_TOKEN");
    cfg.pteid = get(&props, "pacc.client.pteid", "PACC_CLIENT_PTEID", &cfg.pteid);
    cfg.edition = get(
        &props,
        "pacc.client.edition",
        "PACC_CLIENT_EDITION",
        &cfg.edition,
    );
    cfg.heartbeat_seconds = get_u64(
        &props,
        "pacc.client.heartbeat.seconds",
        "PACC_CLIENT_HEARTBEAT_SECONDS",
        cfg.heartbeat_seconds,
    )?;
    cfg.ebpf_enabled = get_bool(
        &props,
        "pacc.client.ebpf.enabled",
        "PACC_CLIENT_EBPF_ENABLED",
        cfg.ebpf_enabled,
    );
    cfg.ebpf_socket = get(
        &props,
        "pacc.client.ebpf.socket",
        "PACC_CLIENT_EBPF_SOCKET",
        &cfg.ebpf_socket,
    );
    cfg.procfs_enabled = get_bool(
        &props,
        "pacc.client.procfs.enabled",
        "PACC_CLIENT_PROCFS_ENABLED",
        cfg.procfs_enabled,
    );
    cfg.report_only_suspicious = get_bool(
        &props,
        "pacc.client.report.only.suspicious",
        "PACC_CLIENT_REPORT_ONLY_SUSPICIOUS",
        cfg.report_only_suspicious,
    );
    cfg.max_events_per_report = get_u64(
        &props,
        "pacc.client.max.events.per.report",
        "PACC_CLIENT_MAX_EVENTS_PER_REPORT",
        cfg.max_events_per_report as u64,
    )? as usize;
    cfg.http_timeout_seconds = get_u64(
        &props,
        "pacc.client.http.timeout.seconds",
        "PACC_CLIENT_HTTP_TIMEOUT_SECONDS",
        cfg.http_timeout_seconds,
    )?;
    cfg.model_path = get_opt(&props, "pacc.client.model.path", "PACC_CLIENT_MODEL_PATH");
    cfg.model_signature = get_opt(
        &props,
        "pacc.client.model.signature",
        "PACC_CLIENT_MODEL_SIGNATURE",
    );

    validate(&cfg)?;
    Ok(cfg)
}

/// 基本校验：周期为 0 会变成忙循环，端口/地址明显非法时提前失败。
fn validate(cfg: &Config) -> Result<(), String> {
    if cfg.heartbeat_seconds == 0 {
        return Err("pacc.client.heartbeat.seconds 不能为 0（会变成忙循环）".to_string());
    }
    if cfg.heartbeat_seconds > 3600 {
        return Err("pacc.client.heartbeat.seconds 超过 3600 秒，请确认单位是秒".to_string());
    }
    if cfg.events_path.trim().is_empty() {
        return Err("pacc.client.events.path 不能为空".to_string());
    }
    if !cfg.events_path.starts_with("http") && !cfg.server_uri.starts_with("http") {
        return Err(format!(
            "上报地址必须以 http:// 开头（当前 server.uri={} events.path={}）",
            cfg.server_uri, cfg.events_path
        ));
    }
    Ok(())
}

/// 解析 properties：`key=value` 或 `key: value`，`#`/`!` 起头为注释。
///
/// 不做转义处理（配置里放 URL 与路径即可）；同名键后者覆盖前者。
pub fn parse_properties(text: &str) -> HashMap<String, String> {
    let mut map = HashMap::new();
    for raw in text.lines() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') || line.starts_with('!') {
            continue;
        }
        let split = line
            .find('=')
            .map(|i| (i, 1))
            .or_else(|| line.find(':').map(|i| (i, 1)));
        if let Some((idx, _)) = split {
            let key = line[..idx].trim();
            let value = line[idx + 1..].trim();
            if !key.is_empty() {
                map.insert(key.to_string(), value.to_string());
            }
        }
    }
    map
}

/// 环境变量 > 文件 > 默认值。
fn get(props: &HashMap<String, String>, key: &str, env: &str, default: &str) -> String {
    if let Ok(v) = std::env::var(env) {
        if !v.trim().is_empty() {
            return v.trim().to_string();
        }
    }
    match props.get(key) {
        Some(v) if !v.trim().is_empty() => v.trim().to_string(),
        _ => default.to_string(),
    }
}

/// 同上，但允许「没有」。
fn get_opt(props: &HashMap<String, String>, key: &str, env: &str) -> Option<String> {
    if let Ok(v) = std::env::var(env) {
        if !v.trim().is_empty() {
            return Some(v.trim().to_string());
        }
    }
    props
        .get(key)
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
}

fn get_u64(
    props: &HashMap<String, String>,
    key: &str,
    env: &str,
    default: u64,
) -> Result<u64, String> {
    let raw = get(props, key, env, &default.to_string());
    raw.parse::<u64>()
        .map_err(|_| format!("{key} 不是合法整数: {raw}"))
}

fn get_bool(props: &HashMap<String, String>, key: &str, env: &str, default: bool) -> bool {
    let raw = get(props, key, env, if default { "true" } else { "false" });
    matches!(
        raw.to_ascii_lowercase().as_str(),
        "true" | "1" | "yes" | "on"
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_comments_and_both_separators() {
        let text =
            "# 注释\n! 也是注释\n\npacc.client.pteid=PT123\npacc.client.edition: LINUX\n\n坏行\n";
        let map = parse_properties(text);
        assert_eq!(
            map.get("pacc.client.pteid").map(String::as_str),
            Some("PT123")
        );
        assert_eq!(
            map.get("pacc.client.edition").map(String::as_str),
            Some("LINUX")
        );
        assert_eq!(map.len(), 2);
    }

    #[test]
    fn later_key_wins() {
        let map = parse_properties("a=1\na=2\n");
        assert_eq!(map.get("a").map(String::as_str), Some("2"));
    }

    #[test]
    fn explicit_missing_config_is_an_error() {
        let err = load(Some("/definitely/not/here/pacc.properties")).unwrap_err();
        assert!(err.contains("不存在"), "实际: {err}");
    }

    #[test]
    fn defaults_are_sane() {
        let cfg = Config::default();
        assert_eq!(cfg.events_path, "/api/player/security/events");
        assert_eq!(cfg.heartbeat_seconds, 15);
        assert!(cfg.token.is_none());
        validate(&cfg).expect("默认配置必须合法");
    }

    #[test]
    fn zero_interval_is_rejected() {
        let cfg = Config {
            heartbeat_seconds: 0,
            ..Config::default()
        };
        assert!(validate(&cfg).is_err());
    }
}
