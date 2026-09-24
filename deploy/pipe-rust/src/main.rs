//! PACC v5.0 Rust 高性能检测数据管道（安全敏感模块 / 本地加密持久化引擎）。
//!
//! 能力：
//!   1. 事件摄入：stdin（JSON Lines）或 TCP 监听（--listen 127.0.0.1:9000）
//!   2. 事件指纹：SHA-256（规范化字段），用于去重与篡改检测
//!   3. 事件签名：HMAC-SHA256（PIPE_SECRET），上报防伪造
//!   4. 窗口聚合：按事件类型 / 威胁级别 / 活跃玩家 / 风险均值滚动统计
//!   5. 本地加密持久化：AES-256-CBC + HMAC-SHA256（Encrypt-then-MAC）落盘
//!
//! 用法：
//!   cat events.jsonl | pacc-pipe
//!   pacc-pipe --listen 127.0.0.1:9000
//!   环境变量：PIPE_SECRET(hex) PIPE_KEY(hex, 32B) PIPE_WINDOW_SECS PIPE_OUT_DIR

mod crypto;
mod sha256;
mod window;

use crate::sha256::{hex, hmac_sha256, sha256};
use std::collections::HashMap;
use std::env;
use std::fs::{self, File, OpenOptions};
use std::io::{self, BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const DEFAULT_WINDOW_SECS: u64 = 60;

/// 管道配置。
struct Config {
    secret: Vec<u8>,
    seal_key: [u8; 32],
    window_secs: u64,
    out_dir: Option<String>,
    listen: Option<String>,
}

fn load_config() -> Config {
    let secret_hex = env::var("PIPE_SECRET").unwrap_or_else(|_| String::from("pacc-dev-secret"));
    let secret = sha256(secret_hex.as_bytes()).to_vec(); // 派生 32B HMAC 密钥

    let key = match env::var("PIPE_KEY") {
        Ok(h) => crypto::parse_key(&h).unwrap_or_else(|| crypto::derive_key(&secret)),
        Err(_) => crypto::derive_key(&secret),
    };

    let window_secs = env::var("PIPE_WINDOW_SECS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(DEFAULT_WINDOW_SECS)
        .max(1);

    let out_dir = env::var("PIPE_OUT_DIR").ok();
    let listen = env::args().nth(1).and_then(|a| {
        if a == "--listen" {
            env::args().nth(2)
        } else {
            None
        }
    });

    Config {
        secret,
        seal_key: key,
        window_secs,
        out_dir,
        listen,
    }
}

/// 运行管道主循环。
fn run_pipeline(cfg: &Config) -> io::Result<()> {
    let mut agg = Aggregator::new(cfg);

    match &cfg.listen {
        Some(addr) => {
            eprintln!("[pacc-pipe] TCP 摄入监听 {addr}");
            let listener = TcpListener::bind(addr)?;
            for stream in listener.incoming() {
                match stream {
                    Ok(s) => {
                        let _ = handle_stream(&mut agg, s);
                    }
                    Err(e) => eprintln!("[pacc-pipe] 连接错误: {e}"),
                }
            }
            // incoming() 是无限迭代器，正常流程到不了这里；
            // 但这个分支必须与 None 分支同为 io::Result<()>，否则 match 两侧类型不一致。
            Ok(())
        }
        None => {
            let stdin = io::stdin();
            handle_reader(&mut agg, stdin.lock())
        }
    }
}

fn handle_stream(agg: &mut Aggregator, stream: TcpStream) -> io::Result<()> {
    let reader = BufReader::new(stream);
    handle_reader(agg, reader)
}

fn handle_reader(agg: &mut Aggregator, reader: impl BufRead) -> io::Result<()> {
    for line in reader.lines() {
        let line = match line {
            Ok(l) => l,
            Err(_) => break,
        };
        agg.ingest(&line);
    }
    Ok(())
}

/// 聚合器：负责窗口推进、指纹/签名、加密持久化。
struct Aggregator<'a> {
    cfg: &'a Config,
    window: window::Window,
    seen: HashMap<String, u64>, // 指纹 -> 次数（去重统计）
    dup_total: u64,
    ingest_total: u64,
    out_file: Option<File>,
}

impl<'a> Aggregator<'a> {
    fn new(cfg: &'a Config) -> Self {
        let start = now_millis();
        let out_file = cfg.out_dir.as_ref().map(|dir| {
            fs::create_dir_all(dir).expect("创建输出目录失败");
            let path = format!("{dir}/events-{}.pacc", start);
            OpenOptions::new()
                .create(true)
                .append(true)
                .open(&path)
                .expect("打开持久化文件失败")
        });
        Aggregator {
            cfg,
            window: window::Window::new(start, cfg.window_secs),
            seen: HashMap::new(),
            dup_total: 0,
            ingest_total: 0,
            out_file,
        }
    }

    fn ingest(&mut self, line: &str) {
        let Some(ev) = window::Event::from_json_line(line) else {
            return;
        };

        // 1) 指纹（去重 / 篡改检测）
        let canonical = ev.canonical();
        let fingerprint = sha256(canonical.as_bytes());
        let fp_hex = hex(&fingerprint);
        let entry = self.seen.entry(fp_hex).or_insert(0);
        *entry += 1;
        if *entry > 1 {
            self.dup_total += 1;
        }

        // 2) 签名（可选校验模式：管道可作为上游校验节点）
        let mac = hmac_sha256(&self.cfg.secret, canonical.as_bytes());
        let mut raw = Vec::with_capacity(canonical.len() + 40);
        raw.extend_from_slice(canonical.as_bytes());
        raw.push(b'|');
        raw.extend_from_slice(&mac);
        raw.push(b'|');
        raw.extend_from_slice(&fingerprint);

        // 3) 加密持久化（Seal 后追加，格式：u32 长度 + sealed + mac）
        if let Some(f) = self.out_file.as_mut() {
            let (sealed, mac) = crypto::seal(&self.cfg.seal_key, &raw);
            let len = (sealed.len() as u32).to_be_bytes();
            let _ = f.write_all(&len);
            let _ = f.write_all(&sealed);
            let _ = f.write_all(&mac);
            let _ = f.flush();
        }

        // 4) 窗口推进与聚合（过期事件仅计数，不污染当前窗口）
        if ev.ts_millis > self.window.window_end_millis {
            self.flush_window();
            self.window = window::Window::new(ev.ts_millis, self.cfg.window_secs);
        }
        self.ingest_total += 1;
        if ev.ts_millis >= self.window.window_start_millis {
            self.window.add(&ev);
        }
    }

    fn flush_window(&mut self) {
        if self.window.total_events == 0 {
            return;
        }
        let summary = self.window.to_json();
        let clean = format!(
            "{{\"event\":\"window_flush\",\"fingerprint_dups\":{},\"ingest_total\":{},\"window\":{},\"pipe_sig\":\"{}\"}}",
            self.dup_total,
            self.ingest_total,
            summary,
            hex(&hmac_sha256(
                &self.cfg.secret,
                summary.as_bytes()
            ))
        );
        println!("{clean}");
        let _ = io::stdout().flush();
        self.window = window::Window::default();
    }
}

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::ZERO)
        .as_millis() as u64
}

fn main() -> io::Result<()> {
    let cfg = load_config();
    eprintln!(
        "[pacc-pipe] 启动 window={}s out={} listen={}",
        cfg.window_secs,
        cfg.out_dir.as_deref().unwrap_or("-"),
        cfg.listen.as_deref().unwrap_or("stdin")
    );
    run_pipeline(&cfg)
}
