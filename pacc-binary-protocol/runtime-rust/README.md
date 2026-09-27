# pacc-bp

PBP（PACC Binary Protocol）二进制协议的 Rust 运行时。

帧封装、VarInt/ZigZag 有序字段编解码、HMAC-SHA256 签名，以及一个自研的零依赖 zstd 子集。
HMAC-SHA256 与 zstd 都在本 crate 内实现，不引入任何第三方 crate。

消费方式：

```toml
[dependencies]
pacc-bp = "1.0.0"
```

```rust
use pacc_bp::PbpCodec;
```

## 边界

- 协议消息类型由 `tools/pbpgen` 从 `mdl/*.mdl` 生成，生成物入库，不手改。
- 越界或非法输入一律返回 `PbpError`，不静默截断。