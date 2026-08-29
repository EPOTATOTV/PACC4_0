# PACC Rust 检测数据管道（pacc-pipe）

环境清单中的 **Rust stable** 安全敏感模块 / 高性能数据管道 / 本地加密持久化引擎实现。

## 特性

| 能力 | 说明 |
| --- | --- |
| 事件摄入 | stdin（JSON Lines）或 TCP 监听（`--listen`） |
| SHA-256 指纹 | 规范化字段哈希，用于事件去重与篡改检测（纯标准库实现，FIPS 180-4） |
| HMAC-SHA256 签名 | 事件/窗口摘要签名，上报防伪造（RFC 2104） |
| 滑动窗口聚合 | 60s 窗口：类型 / 威胁级别 / 活跃玩家 / 风险均值峰值 |
| 本地加密持久化 | AES-256-CBC（PKCS#7）+ HMAC-SHA256（Encrypt-then-MAC）落盘 |
| 零依赖 | 不引用任何外部 crate，离线可构建，可交叉编译 |

## 本地构建与测试

```bash
# 需 Rust stable（rustup + cargo）
cargo build --release
cargo test          # 含 SHA-256/HMAC/AES-256 已知答案向量测试

# stdin 摄入演示
echo '{"pteid":"PT123","event_type":"memory_tamper","severity":"high","client_risk_score":85,"ts":1750000000000}' | PIPE_WINDOW_SECS=1 ./target/release/pacc-pipe

# TCP 摄入演示（另开终端发送）
PIPE_OUT_DIR=/tmp/pacc-events ./target/release/pacc-pipe --listen 127.0.0.1:9000
```

## 环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `PIPE_SECRET` | `pacc-dev-secret` | HMAC-SHA256 签名密钥（SHA-256 派生 32B） |
| `PIPE_KEY` | 派生自 PIPE_SECRET | AES-256 加密密钥（64 位 hex，可选） |
| `PIPE_WINDOW_SECS` | `60` | 聚合窗口秒数 |
| `PIPE_OUT_DIR` | 空 | 加密事件落盘目录（不设置则不落盘） |
| `PIPE_VERIFY_HMAC` | `0` | 预留：作为上游 HMAC 校验节点 |

## Docker 部署

```bash
docker build -t pacc-pipe .
docker run -d --name pacc-pipe -p 9000:9000 -v pacc-pipe-data:/pacc/data pacc-pipe
```

输出示例（窗口摘要）：

```json
{"event":"window_flush","fingerprint_dups":0,"ingest_total":2,"window":{"window_start":1750000000000,"window_end":1750000060000,"total_events":2,"active_pteid":1,"avg_risk":57.50,"max_risk":85,"by_type":{"memory_tamper":1,"killaura":1},"by_severity":{"high":2}},"pipe_sig":"..."}
```
