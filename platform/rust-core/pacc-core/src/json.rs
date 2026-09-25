//! 极简 JSON 编解码（纯标准库，零依赖）。
//!
//! 与 Java 端 `Json` 同定位：只覆盖本项目所需结构（对象/数组/字符串/数字/布尔/null），
//! 供检测事件上报、eBPF NDJSON 事件解析等复用。**公开导出**，
//! 这样 `pacc-linux-client` 无需再引入 JSON 库。
//!
//! 解析为递归下降；编码器保证输出合法 JSON（字符串完整转义、控制字符转 `\u`）。

use std::fmt;

/// 解析错误。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JsonError {
    pub message: String,
    pub offset: usize,
}

impl fmt::Display for JsonError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "JSON 解析失败 @{}: {}", self.offset, self.message)
    }
}

impl std::error::Error for JsonError {}

/// JSON 值。
#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    Null,
    Bool(bool),
    Number(f64),
    String(String),
    Array(Vec<Value>),
    /// 对象保持插入顺序，便于字段顺序稳定的上报体。
    Object(Vec<(String, Value)>),
}

impl Value {
    /// 便捷构造对象。
    pub fn object<K, I>(entries: I) -> Self
    where
        K: Into<String>,
        I: IntoIterator<Item = (K, Value)>,
    {
        Value::Object(entries.into_iter().map(|(k, v)| (k.into(), v)).collect())
    }

    pub fn string(s: impl Into<String>) -> Self {
        Value::String(s.into())
    }

    pub fn number(n: impl Into<f64>) -> Self {
        Value::Number(n.into())
    }

    /// 按键取成员（非对象返回 `None`）。
    pub fn get(&self, key: &str) -> Option<&Value> {
        match self {
            Value::Object(entries) => entries.iter().find(|(k, _)| k == key).map(|(_, v)| v),
            _ => None,
        }
    }

    pub fn as_str(&self) -> Option<&str> {
        match self {
            Value::String(s) => Some(s),
            _ => None,
        }
    }

    pub fn as_f64(&self) -> Option<f64> {
        match self {
            Value::Number(n) => Some(*n),
            _ => None,
        }
    }

    pub fn as_i64(&self) -> Option<i64> {
        self.as_f64().map(|n| n as i64)
    }

    pub fn as_bool(&self) -> Option<bool> {
        match self {
            Value::Bool(b) => Some(*b),
            _ => None,
        }
    }

    pub fn as_array(&self) -> Option<&[Value]> {
        match self {
            Value::Array(items) => Some(items),
            _ => None,
        }
    }

    /// 编码为 JSON 文本。
    pub fn encode(&self) -> String {
        let mut out = String::new();
        self.write_to(&mut out);
        out
    }

    fn write_to(&self, out: &mut String) {
        match self {
            Value::Null => out.push_str("null"),
            Value::Bool(true) => out.push_str("true"),
            Value::Bool(false) => out.push_str("false"),
            Value::Number(n) => {
                if n.is_finite() {
                    // 整数分值不写小数点，便于后端按整型解析。
                    if n.fract() == 0.0 && n.abs() < 9.007_199_254_740_992e15 {
                        out.push_str(&format!("{}", *n as i64));
                    } else {
                        out.push_str(&format!("{n}"));
                    }
                } else {
                    out.push_str("null");
                }
            }
            Value::String(s) => encode_string(s, out),
            Value::Array(items) => {
                out.push('[');
                for (i, item) in items.iter().enumerate() {
                    if i > 0 {
                        out.push(',');
                    }
                    item.write_to(out);
                }
                out.push(']');
            }
            Value::Object(entries) => {
                out.push('{');
                for (i, (k, v)) in entries.iter().enumerate() {
                    if i > 0 {
                        out.push(',');
                    }
                    encode_string(k, out);
                    out.push(':');
                    v.write_to(out);
                }
                out.push('}');
            }
        }
    }
}

/// 转义并写入字符串字面量。
fn encode_string(s: &str, out: &mut String) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
}

/// 编码单个字符串（返回带引号的字面量）。
pub fn encode_str(s: &str) -> String {
    let mut out = String::new();
    encode_string(s, &mut out);
    out
}

/// 解析完整 JSON 文档。
pub fn parse(input: &str) -> Result<Value, JsonError> {
    let mut parser = Parser::new(input);
    let value = parser.parse_value()?;
    parser.skip_ws();
    if parser.pos < parser.input.len() {
        return Err(parser.error("文档末尾存在多余字符"));
    }
    Ok(value)
}

/// 解析并断言顶层为对象。
pub fn parse_object(input: &str) -> Result<Vec<(String, Value)>, JsonError> {
    match parse(input)? {
        Value::Object(entries) => Ok(entries),
        _ => Err(JsonError {
            message: "期望 JSON 对象".to_string(),
            offset: 0,
        }),
    }
}

struct Parser<'a> {
    input: &'a [u8],
    pos: usize,
}

impl<'a> Parser<'a> {
    fn new(input: &'a str) -> Self {
        Self {
            input: input.as_bytes(),
            pos: 0,
        }
    }

    fn error(&self, message: &str) -> JsonError {
        JsonError {
            message: message.to_string(),
            offset: self.pos,
        }
    }

    fn skip_ws(&mut self) {
        while self.pos < self.input.len() {
            match self.input[self.pos] {
                b' ' | b'\t' | b'\n' | b'\r' => self.pos += 1,
                _ => break,
            }
        }
    }

    fn peek(&mut self) -> Option<u8> {
        self.skip_ws();
        self.input.get(self.pos).copied()
    }

    fn parse_value(&mut self) -> Result<Value, JsonError> {
        match self.peek() {
            None => Err(self.error("意外结束")),
            Some(b'{') => self.parse_object(),
            Some(b'[') => self.parse_array(),
            Some(b'"') => self.parse_string().map(Value::String),
            Some(b't') => self.parse_literal(b"true", Value::Bool(true)),
            Some(b'f') => self.parse_literal(b"false", Value::Bool(false)),
            Some(b'n') => self.parse_literal(b"null", Value::Null),
            Some(_) => self.parse_number(),
        }
    }

    fn parse_object(&mut self) -> Result<Value, JsonError> {
        self.expect(b'{')?;
        let mut entries: Vec<(String, Value)> = Vec::new();
        if self.peek() == Some(b'}') {
            self.pos += 1;
            return Ok(Value::Object(entries));
        }
        loop {
            let key = self.parse_string()?;
            self.expect(b':')?;
            let value = self.parse_value()?;
            entries.push((key, value));
            match self.peek() {
                Some(b',') => self.pos += 1,
                Some(b'}') => {
                    self.pos += 1;
                    return Ok(Value::Object(entries));
                }
                _ => return Err(self.error("对象期望 ',' 或 '}'")),
            }
        }
    }

    fn parse_array(&mut self) -> Result<Value, JsonError> {
        self.expect(b'[')?;
        let mut items: Vec<Value> = Vec::new();
        if self.peek() == Some(b']') {
            self.pos += 1;
            return Ok(Value::Array(items));
        }
        loop {
            items.push(self.parse_value()?);
            match self.peek() {
                Some(b',') => self.pos += 1,
                Some(b']') => {
                    self.pos += 1;
                    return Ok(Value::Array(items));
                }
                _ => return Err(self.error("数组期望 ',' 或 ']'")),
            }
        }
    }

    fn parse_string(&mut self) -> Result<String, JsonError> {
        self.expect(b'"')?;
        let mut out = String::new();
        while self.pos < self.input.len() {
            let c = self.input[self.pos];
            self.pos += 1;
            match c {
                b'"' => return Ok(out),
                b'\\' => {
                    let esc = *self
                        .input
                        .get(self.pos)
                        .ok_or_else(|| self.error("转义未闭合"))?;
                    self.pos += 1;
                    match esc {
                        b'"' => out.push('"'),
                        b'\\' => out.push('\\'),
                        b'/' => out.push('/'),
                        b'b' => out.push('\u{0008}'),
                        b'f' => out.push('\u{000C}'),
                        b'n' => out.push('\n'),
                        b'r' => out.push('\r'),
                        b't' => out.push('\t'),
                        b'u' => out.push(self.parse_unicode()?),
                        _ => return Err(self.error("未知转义")),
                    }
                }
                _ => {
                    // 多字节 UTF-8 原样透传：按字节追加后由 String 保证编码合法。
                    let start = self.pos - 1;
                    let width = utf8_width(c);
                    let end = (start + width).min(self.input.len());
                    let chunk = std::str::from_utf8(&self.input[start..end])
                        .map_err(|_| self.error("非法 UTF-8"))?;
                    out.push_str(chunk);
                    self.pos = end;
                }
            }
        }
        Err(self.error("字符串未闭合"))
    }

    fn parse_unicode(&mut self) -> Result<char, JsonError> {
        if self.pos + 4 > self.input.len() {
            return Err(self.error("\\u 转义长度不足"));
        }
        let hex = std::str::from_utf8(&self.input[self.pos..self.pos + 4])
            .map_err(|_| self.error("\\u 转义非法"))?;
        self.pos += 4;
        let code = u32::from_str_radix(hex, 16).map_err(|_| self.error("\\u 转义非法"))?;
        char::from_u32(code).ok_or_else(|| self.error("\\u 转义非法码点"))
    }

    fn parse_literal(&mut self, lit: &[u8], value: Value) -> Result<Value, JsonError> {
        if self.input.len() >= self.pos + lit.len()
            && &self.input[self.pos..self.pos + lit.len()] == lit
        {
            self.pos += lit.len();
            Ok(value)
        } else {
            Err(self.error("非法字面量"))
        }
    }

    fn parse_number(&mut self) -> Result<Value, JsonError> {
        let start = self.pos;
        if self.input.get(self.pos) == Some(&b'-') || self.input.get(self.pos) == Some(&b'+') {
            self.pos += 1;
        }
        while self.pos < self.input.len() {
            match self.input[self.pos] {
                b'0'..=b'9' | b'.' | b'e' | b'E' | b'+' | b'-' => self.pos += 1,
                _ => break,
            }
        }
        let text = std::str::from_utf8(&self.input[start..self.pos])
            .map_err(|_| self.error("非法数字"))?;
        text.parse::<f64>()
            .map(Value::Number)
            .map_err(|_| self.error("非法数字"))
    }

    fn expect(&mut self, byte: u8) -> Result<(), JsonError> {
        if self.peek() == Some(byte) {
            self.pos += 1;
            Ok(())
        } else {
            Err(self.error("期望的字符缺失"))
        }
    }
}

/// UTF-8 首字节 → 码元宽度（非法首字节按 1 处理，交由 `from_utf8` 拒绝）。
fn utf8_width(first: u8) -> usize {
    if first < 0x80 {
        1
    } else if first >> 5 == 0b110 {
        2
    } else if first >> 4 == 0b1110 {
        3
    } else if first >> 3 == 0b11110 {
        4
    } else {
        1
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_object() {
        let v = Value::object([
            ("type", Value::string("event")),
            ("risk", Value::number(72.0)),
            ("ok", Value::Bool(true)),
            ("note", Value::Null),
        ]);
        let text = v.encode();
        assert_eq!(text, r#"{"type":"event","risk":72,"ok":true,"note":null}"#);
        let parsed = parse(&text).expect("应可解析");
        assert_eq!(parsed.get("risk").and_then(Value::as_i64), Some(72));
    }

    #[test]
    fn parses_nested_and_escapes() {
        let text = r#"{"a":[1,2.5],"b":{"c":"x\ny"}}"#;
        let v = parse(text).expect("应可解析");
        assert_eq!(
            v.get("a").and_then(Value::as_array).map(|a| a.len()),
            Some(2)
        );
        assert_eq!(
            v.get("b").and_then(|b| b.get("c")).and_then(Value::as_str),
            Some("x\ny")
        );
    }

    #[test]
    fn rejects_trailing_garbage() {
        assert!(parse(r#"{"a":1} x"#).is_err());
        assert!(parse("{").is_err());
    }

    #[test]
    fn parses_ndjson_line_shape() {
        // eBPF loader 输出的单行事件形态。
        let line = r#"{"ts":1712,"event":"exec","pid":4242,"comm":"java","path":"/usr/bin/java","dna":false}"#;
        let obj = parse_object(line).expect("应可解析");
        assert_eq!(
            obj.iter()
                .find(|(k, _)| k == "comm")
                .and_then(|(_, v)| v.as_str()),
            Some("java")
        );
    }

    #[test]
    fn non_finite_numbers_encode_as_null() {
        let v = Value::number(f64::NAN);
        assert_eq!(v.encode(), "null");
    }
}
