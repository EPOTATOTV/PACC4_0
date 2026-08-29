//! 滑动时间窗口聚合器。
//!
//! 对流入的检测事件按时间窗口（默认 60s）聚合统计：
//!   - 按事件类型计数（memory_tamper / killaura / ...）
//!   - 按威胁级别计数（low / medium / high / critical）
//!   - 按 PTEID 去重活跃玩家数
//!   - 风险分均值 / 峰值
//! 窗口到期后 flush 输出 JSON 摘要并开启新窗口。

use std::collections::HashMap;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// 摄入的原始事件。
#[derive(Debug, Clone)]
pub struct Event {
    pub ts_millis: u64,
    pub pteid: String,
    pub event_type: String,
    pub severity: String,
    pub client_risk: u32,
}

impl Event {
    /// 将一行 JSON（宽松解析）转换为事件。
    /// 字段缺失时使用安全默认值，绝不因单条坏数据中断管道。
    pub fn from_json_line(line: &str) -> Option<Event> {
        let s = line.trim();
        if s.is_empty() {
            return None;
        }
        let mut e = Event {
            ts_millis: now_millis(),
            pteid: String::new(),
            event_type: String::from("unknown"),
            severity: String::from("low"),
            client_risk: 0,
        };
        // 极简 JSON 对象解析：仅提取所需字符串/数字字段
        for field in ["ts", "ts_millis", "pteid", "event_type", "severity", "client_risk", "client_risk_score"] {
            let key = format!("\"{}\"", field);
            let Some(rel) = s.find(&key) else { continue };
            let after = &s[rel + key.len()..];
            let Some(colon) = after.find(':') else { continue };
            let val = after[colon + 1..].trim_start();
            let val = val.trim_start_matches(|c| c == '"' || c == ' ' || c == '\t');
            let val = val.trim_end_matches(|c| c == '"' || c == ',' || c == '}' || c == ' ' || c == '\t');
            if val.is_empty() {
                continue;
            }
            match field {
                "ts" | "ts_millis" => {
                    if let Ok(n) = val.parse::<u64>() {
                        e.ts_millis = n;
                    }
                }
                "pteid" => e.pteid = val.to_string(),
                "event_type" => e.event_type = val.to_string(),
                "severity" => e.severity = val.to_string(),
                "client_risk" | "client_risk_score" => {
                    if let Ok(n) = val.parse::<u32>() {
                        e.client_risk = n.min(100);
                    }
                }
                _ => {}
            }
        }
        Some(e)
    }

    /// 规范化序列化（用于指纹/签名，字段顺序固定保证可复现）。
    pub fn canonical(&self) -> String {
        format!(
            "ts={}pteid={}type={}sev={}risk={}",
            self.ts_millis, self.pteid, self.event_type, self.severity, self.client_risk
        )
    }
}

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::ZERO)
        .as_millis() as u64
}

/// 单个时间窗口的聚合结果。
#[derive(Debug, Default)]
pub struct Window {
    pub window_start_millis: u64,
    pub window_end_millis: u64,
    pub total_events: u64,
    pub by_type: HashMap<String, u64>,
    pub by_severity: HashMap<String, u64>,
    pub active_pteid: HashMap<String, u64>,
    pub risk_sum: u64,
    pub risk_count: u64,
    pub risk_max: u32,
}

impl Window {
    pub fn new(start_millis: u64, window_secs: u64) -> Self {
        Window {
            window_start_millis: start_millis,
            window_end_millis: start_millis + window_secs * 1000,
            ..Default::default()
        }
    }

    pub fn add(&mut self, e: &Event) {
        self.total_events += 1;
        *self.by_type.entry(e.event_type.clone()).or_insert(0) += 1;
        *self.by_severity.entry(e.severity.clone()).or_insert(0) += 1;
        if !e.pteid.is_empty() {
            *self.active_pteid.entry(e.pteid.clone()).or_insert(0) += 1;
        }
        self.risk_sum += e.client_risk as u64;
        self.risk_count += 1;
        self.risk_max = self.risk_max.max(e.client_risk);
    }

    pub fn avg_risk(&self) -> f64 {
        if self.risk_count == 0 {
            0.0
        } else {
            self.risk_sum as f64 / self.risk_count as f64
        }
    }

    /// 输出为 JSON 摘要（可用于推送/落盘/监控抓取）。
    pub fn to_json(&self) -> String {
        let mut types: Vec<(&String, &u64)> = self.by_type.iter().collect();
        types.sort_by(|a, b| b.1.cmp(a.1));
        let mut sevs: Vec<(&String, &u64)> = self.by_severity.iter().collect();
        sevs.sort_by(|a, b| b.1.cmp(a.1));

        let type_json: Vec<String> = types
            .iter()
            .map(|(k, v)| format!("\"{}\":{}", escape(k), v))
            .collect();
        let sev_json: Vec<String> = sevs
            .iter()
            .map(|(k, v)| format!("\"{}\":{}", escape(k), v))
            .collect();

        format!(
            "{{\"window_start\":{},\"window_end\":{},\"total_events\":{},\"active_pteid\":{},\"avg_risk\":{:.2},\"max_risk\":{},\"by_type\":{{{}}},\"by_severity\":{{{}}}}}",
            self.window_start_millis,
            self.window_end_millis,
            self.total_events,
            self.active_pteid.len(),
            self.avg_risk(),
            self.risk_max,
            type_json.join(","),
            sev_json.join(",")
        )
    }
}

fn escape(s: &str) -> String {
    s.replace('\\', "\\\\").replace('"', "\\\"")
}
