# pacc-bp

PBP（PACC Binary Protocol）二进制协议的 Python 运行时。

帧封装、VarInt/ZigZag 有序字段编解码、HMAC-SHA256 签名，全部用 Python 标准库实现，零第三方依赖。

安装：

```bash
pip install pacc-bp
```

```python
from pbp_gen import PbpCodec
```

## 边界

- 模块 `pbp_gen` 由 `tools/pbpgen` 从 `mdl/*.mdl` 生成，生成物入库，不手改。
- 运行时不实现 zstd；涉及压缩的帧由其它语言的运行时处理。