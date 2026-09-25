//! 上报器：向 PACC 后端 POST 检测事件。
//!
//! 形状对齐 Java 端 `ops.OpsClient`：`POST <baseUrl><path>`、`Content-Type: application/json`、
//! 有令牌时带 `Authorization: Bearer <token>`、`Connection: close`，因此后端两个入口
//! （玩家 JWT / 匿名网关）都不用为 Rust 端单开分支。
//!
//! 只用标准库的 TCP + 手写 HTTP/1.1 请求：不给一个检测客户端引入 HTTP/TLS 依赖树。
//! **代价是只支持 `http://`**——`https://` 会明确报错而不是静默降级成明文，
//! 生产部署请把上报指向本机网关（如 `http://127.0.0.1:8080`）或由网关终结 TLS。

use std::io::{Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::Duration;

use crate::config::CLIENT_VERSION;

/// 上报错误。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ReportError {
    /// 只有 `http://` 被支持（零依赖构建无 TLS）。
    UnsupportedScheme(String),
    /// URL 结构非法。
    InvalidUrl(String),
    /// 域名解析失败。
    Resolve(String),
    /// 连接失败/超时。
    Connect(String),
    /// 收发失败。
    Io(String),
    /// 响应不是合法 HTTP。
    BadResponse(String),
}

impl std::fmt::Display for ReportError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ReportError::UnsupportedScheme(u) => write!(
                f,
                "不支持的上报协议（仅 http://，https 需由本机网关终结）: {u}"
            ),
            ReportError::InvalidUrl(u) => write!(f, "上报地址非法: {u}"),
            ReportError::Resolve(h) => write!(f, "域名解析失败: {h}"),
            ReportError::Connect(h) => write!(f, "连接失败: {h}"),
            ReportError::Io(e) => write!(f, "网络读写失败: {e}"),
            ReportError::BadResponse(e) => write!(f, "响应非法: {e}"),
        }
    }
}

impl std::error::Error for ReportError {}

/// 解析后的端点。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Endpoint {
    pub host: String,
    pub port: u16,
    pub path: String,
}

impl Endpoint {
    /// 便于日志展示（不含令牌）。
    pub fn describe(&self) -> String {
        format!("http://{}:{}{}", self.host, self.port, self.path)
    }
}

/// 解析 `http://host[:port][/path]`。
pub fn parse_url(url: &str) -> Result<Endpoint, ReportError> {
    let trimmed = url.trim();
    let rest = if let Some(r) = trimmed.strip_prefix("http://") {
        r
    } else if trimmed.starts_with("https://") {
        return Err(ReportError::UnsupportedScheme(trimmed.to_string()));
    } else {
        return Err(ReportError::InvalidUrl(trimmed.to_string()));
    };
    if rest.is_empty() {
        return Err(ReportError::InvalidUrl(trimmed.to_string()));
    }

    let (authority, path) = match rest.find('/') {
        Some(idx) => (&rest[..idx], rest[idx..].to_string()),
        None => (rest, "/".to_string()),
    };
    if authority.is_empty() {
        return Err(ReportError::InvalidUrl(trimmed.to_string()));
    }

    // IPv6 字面量形如 [::1]:8080
    let (host, port) = if let Some(stripped) = authority.strip_prefix('[') {
        match stripped.split_once(']') {
            Some((h, tail)) => {
                let port = parse_optional_port(tail)?;
                (h.to_string(), port)
            }
            None => return Err(ReportError::InvalidUrl(trimmed.to_string())),
        }
    } else if let Some((h, p)) = authority.rsplit_once(':') {
        // 只在冒号后是纯数字时才当作端口，避免把主机名的冒号误切。
        if p.chars().all(|c| c.is_ascii_digit()) && !p.is_empty() {
            (
                h.to_string(),
                p.parse::<u16>()
                    .map_err(|_| ReportError::InvalidUrl(format!("端口非法: {p}")))?,
            )
        } else {
            (authority.to_string(), 80)
        }
    } else {
        (authority.to_string(), 80)
    };

    if host.is_empty() {
        return Err(ReportError::InvalidUrl(trimmed.to_string()));
    }
    if port == 0 {
        return Err(ReportError::InvalidUrl(format!("端口不能为 0: {trimmed}")));
    }

    Ok(Endpoint {
        host,
        port,
        path: if path.is_empty() {
            "/".to_string()
        } else {
            path
        },
    })
}

fn parse_optional_port(tail: &str) -> Result<u16, ReportError> {
    let tail = tail.trim();
    if tail.is_empty() {
        return Ok(80);
    }
    let digits = tail
        .strip_prefix(':')
        .ok_or_else(|| ReportError::InvalidUrl(format!("IPv6 地址后应为 :端口，实际 {tail}")))?;
    digits
        .parse::<u16>()
        .map_err(|_| ReportError::InvalidUrl(format!("端口非法: {digits}")))
}

/// 上报器。
pub struct Reporter {
    endpoint: Endpoint,
    token: Option<String>,
    timeout: Duration,
    agent: String,
}

impl Reporter {
    /// 由配置构造。`events_path` 若以 `http` 开头则整段覆盖 `server_uri`。
    pub fn from_config(
        server_uri: &str,
        events_path: &str,
        token: Option<String>,
        timeout_seconds: u64,
    ) -> Result<Self, ReportError> {
        let url = if events_path.starts_with("http") {
            events_path.trim().to_string()
        } else {
            let base = server_uri.trim().trim_end_matches('/');
            let path = if events_path.starts_with('/') {
                events_path.to_string()
            } else {
                format!("/{events_path}")
            };
            format!("{base}{path}")
        };
        Ok(Self {
            endpoint: parse_url(&url)?,
            token,
            timeout: Duration::from_secs(timeout_seconds.max(1)),
            agent: format!("pacc-linux-client/{CLIENT_VERSION}"),
        })
    }

    pub fn endpoint(&self) -> &Endpoint {
        &self.endpoint
    }

    /// POST 一个 JSON body，返回 HTTP 状态码。
    pub fn post_json(&self, body: &str) -> Result<u16, ReportError> {
        let addr = format!("{}:{}", self.endpoint.host, self.endpoint.port);
        let addrs: Vec<std::net::SocketAddr> = addr
            .to_socket_addrs()
            .map_err(|e| ReportError::Resolve(format!("{addr} ({e})")))?
            .collect();
        if addrs.is_empty() {
            return Err(ReportError::Resolve(addr));
        }

        let mut stream = None;
        let mut last_err = String::new();
        for candidate in &addrs {
            match TcpStream::connect_timeout(candidate, self.timeout) {
                Ok(s) => {
                    stream = Some(s);
                    break;
                }
                Err(e) => last_err = e.to_string(),
            }
        }
        let mut stream =
            stream.ok_or_else(|| ReportError::Connect(format!("{addr} ({last_err})")))?;
        let _ = stream.set_read_timeout(Some(self.timeout));
        let _ = stream.set_write_timeout(Some(self.timeout));

        let mut head = format!(
            "POST {} HTTP/1.1\r\nHost: {}\r\nUser-Agent: {}\r\nAccept: application/json\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n",
            self.endpoint.path,
            self.endpoint.host,
            self.agent,
            body.len(),
        );
        if let Some(token) = &self.token {
            head.push_str(&format!("Authorization: Bearer {token}\r\n"));
        }
        head.push_str("\r\n");

        stream
            .write_all(head.as_bytes())
            .and_then(|_| stream.write_all(body.as_bytes()))
            .map_err(|e| ReportError::Io(e.to_string()))?;
        stream.flush().map_err(|e| ReportError::Io(e.to_string()))?;

        let mut response = Vec::new();
        // 只读到状态行即可；读满 8KB 或对端关闭就停。
        let mut buf = [0u8; 2048];
        loop {
            match stream.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    response.extend_from_slice(&buf[..n]);
                    if response.len() >= 8192 || response.windows(2).any(|w| w == b"\r\n") {
                        break;
                    }
                }
                Err(e) => {
                    if response.is_empty() {
                        return Err(ReportError::Io(e.to_string()));
                    }
                    break;
                }
            }
        }

        let text = String::from_utf8_lossy(&response);
        let status_line = text.lines().next().unwrap_or_default();
        parse_status_line(status_line)
            .ok_or_else(|| ReportError::BadResponse(truncate(status_line, 120)))
    }
}

/// 从 `HTTP/1.1 200 OK` 取状态码。
pub fn parse_status_line(line: &str) -> Option<u16> {
    let mut parts = line.split_whitespace();
    let version = parts.next()?;
    if !version.starts_with("HTTP/") {
        return None;
    }
    let code = parts.next()?;
    code.parse::<u16>().ok().filter(|c| (100..600).contains(c))
}

fn truncate(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        return s.to_string();
    }
    s.chars().take(max).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_url_with_port_and_path() {
        let ep = parse_url("http://127.0.0.1:8080/api/player/security/events").expect("应可解析");
        assert_eq!(ep.host, "127.0.0.1");
        assert_eq!(ep.port, 8080);
        assert_eq!(ep.path, "/api/player/security/events");
    }

    #[test]
    fn default_port_and_root_path() {
        let ep = parse_url("http://ptv.internal").expect("应可解析");
        assert_eq!(ep.port, 80);
        assert_eq!(ep.path, "/");
    }

    #[test]
    fn hostname_without_port_is_not_misparsed() {
        // 主机名里不该有冒号，但真出现时不能被当成端口
        let ep = parse_url("http://ptv:abc/x").expect("应可解析");
        assert_eq!(ep.host, "ptv:abc");
        assert_eq!(ep.port, 80);
        assert_eq!(ep.path, "/x");
    }

    #[test]
    fn ipv6_literal_is_supported() {
        let ep = parse_url("http://[::1]:9000/events").expect("应可解析");
        assert_eq!(ep.host, "::1");
        assert_eq!(ep.port, 9000);
        assert_eq!(ep.path, "/events");
    }

    #[test]
    fn https_is_rejected_loudly() {
        let err = parse_url("https://admin.potatotv.asia/api").unwrap_err();
        assert!(matches!(err, ReportError::UnsupportedScheme(_)));
        // 报错文案要说清原因，不然运维只会看到「连不上」
        assert!(err.to_string().contains("http"));
    }

    #[test]
    fn garbage_is_rejected() {
        assert!(parse_url("ftp://x/y").is_err());
        assert!(parse_url("http://").is_err());
        assert!(parse_url("").is_err());
    }

    #[test]
    fn combines_base_and_path() {
        let r = Reporter::from_config(
            "http://127.0.0.1:8080/",
            "/api/player/security/events",
            None,
            6,
        )
        .expect("应可构造");
        assert_eq!(
            r.endpoint().describe(),
            "http://127.0.0.1:8080/api/player/security/events"
        );
    }

    #[test]
    fn absolute_events_path_overrides_server() {
        let r = Reporter::from_config(
            "http://ignored:1/",
            "http://10.0.0.5:9000/api/player/security/events",
            Some("tok".to_string()),
            3,
        )
        .expect("应可构造");
        assert_eq!(r.endpoint().host, "10.0.0.5");
        assert_eq!(r.endpoint().port, 9000);
    }

    #[test]
    fn parses_status_lines() {
        assert_eq!(parse_status_line("HTTP/1.1 200 OK"), Some(200));
        assert_eq!(parse_status_line("HTTP/1.0 401 Unauthorized"), Some(401));
        assert_eq!(parse_status_line("garbage"), None);
        assert_eq!(parse_status_line("HTTP/1.1 abc"), None);
    }
}
