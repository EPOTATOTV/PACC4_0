# pacc-binary-protocol

PBP（PACC Binary Protocol）是 PACC 玩家端与管控后端之间跑的一份二进制线格式。玩家端上报的检测事件、管理端下发的查端指令，都用它编码。

它解决的问题很具体：JSON 上线的文本协议里，一条检测信封有一大半字节花在字段名和引号上面；帧头要表达的东西（消息类型、序号、会话、时间戳）又必须逐字段重复写在每一层嵌套里。PBP 把这两件事分开处理——帧头固定 30 字节定长，载荷里的字段按 MDL 定义顺序排列、不带标签。代价是编解码双方必须持有同一份字段顺序定义，新字段只能加在末尾。

仓库同时提供五个语言的运行时。它们不是「各自实现一遍、行为大致一样」，而是要求逐字节一致：`test-vectors/interop.txt` 是 Java 参考实现导出的向量，其余四个运行时必须能复现同样的字节、解开同样的帧，才允许发布。

## 目录结构

| 目录 | 内容 |
|---|---|
| `mdl/` | 消息定义。`pacc_wire.mdl` 是 WSS 查端信令信封，`detection.mdl` 是检测上报消息 |
| `runtime-java/` | Java 21 运行时（参考实现，Maven） |
| `runtime-ts/` | TypeScript 运行时（浏览器与 Node 通用，npm） |
| `runtime-rust/` | Rust 运行时（crate） |
| `runtime-csharp/` | C# / .NET 8 运行时（NuGet） |
| `runtime-python/` | Python 运行时（PyPI，不实现 zstd） |
| `tools/zstd-crosscheck/` | zstd 子集与 libzstd 的交叉校验脚本 |
| `test-vectors/` | 跨语言互操作向量，由 Java 侧导出 |
| `docs/` | 线上格式、API 参考、使用示例 |

消息类型（`gen/` 或 `pbp_gen/` 下的类）由仓库根目录的 `tools/pbpgen` 从 `mdl/*.mdl` 生成，生成物入库，不手改。重新生成：

```bash
cd tools/pbpgen && python -m pbpgen
```

## 快速开始

五个运行时的包名与入口各不相同，下面各给一段最小可用代码：从导入到编解码一条 `PaccEnvelope`。字段签名在各语言的 `docs/api-reference.md` 里列全。

`PaccEnvelope` 是双向消息（ID `0x2001`），带签名选项。编码路径是固定的：字段编码成载荷 → 载荷超 1KB 尝试压缩 → 组装帧 →（有签名时）签名放帧尾。

### Java（Maven）

```java
import com.potatotv.pbp.PbpCrypto;
import com.potatotv.pbp.gen.PaccEnvelope;

import java.nio.charset.StandardCharsets;

PaccEnvelope msg = PaccEnvelope.newBuilder()
        .setType("inspect_result")
        .setTsMs(1_700_000_000_000L)
        .setNonce("0123456789abcdef")
        .setSessionId("sess-1")
        .setPteid("PT0001")
        .setPayloadJson("{\"result\":\"clean\"}")
        .setSigVersion(1)
        .build();

byte[] frame = msg.toByteArray();                 // 编码整帧
PaccEnvelope back = PaccEnvelope.parseFrom(frame); // 解帧

// HMAC 密钥是密钥文本的 UTF-8 字节（PACC 两侧的既有约定）
byte[] key = "000102030405060708090a0b0c0d0e0f".getBytes(StandardCharsets.UTF_8);
byte[] sig = PbpCrypto.hmacSha256(key, msg.signingInput());
boolean ok = PbpCrypto.verifyHmac(key, msg.signingInput(), sig);
```

### TypeScript（npm）

`@potatotv/pbp` 的入口只导出核心类型；生成消息在 `dist/src/gen/` 子路径下。

```ts
import { hmacSha256, verifyHmac } from "@potatotv/pbp";
import { PaccEnvelope } from "@potatotv/pbp/dist/src/gen/PaccEnvelope.js";

const msg = PaccEnvelope.newBuilder()
  .setType("inspect_result")
  .setTsMs(1_700_000_000_000n)
  .setNonce("0123456789abcdef")
  .setSessionId("sess-1")
  .setPteid("PT0001")
  .setPayloadJson('{"result":"clean"}')
  .setSigVersion(1)
  .build();

const frame = msg.toByteArray();
const back = PaccEnvelope.parseFrom(frame);

const key = new TextEncoder().encode("000102030405060708090a0b0c0d0e0f");
const sig = hmacSha256(key, msg.signingInput());
const ok = verifyHmac(key, msg.signingInput(), sig);
```

### Rust（crate）

Rust 的生成类型不用单独的 Builder 类，字段 setter 按 `self -> Self` 消费式写法串起来。

```rust
use pacc_bp::crypto::{self, hmac_sha256, verify_hmac};
use pacc_bp::gen::PaccEnvelope;

let msg = PaccEnvelope::new()
    .set_type_("inspect_result")
    .set_ts_ms(1_700_000_000_000)
    .set_nonce("0123456789abcdef")
    .set_session_id("sess-1")
    .set_pteid("PT0001")
    .set_payload_json(r#"{"result":"clean"}"#)
    .set_sig_version(1);

let frame = msg.to_byte_array()?;
let back = PaccEnvelope::parse_from(&frame)?;

let key = b"000102030405060708090a0b0c0d0e0f";
let sig = hmac_sha256(key, &msg.signing_input()?);
let ok = verify_hmac(key, &msg.signing_input()?, &sig);
let _ = crypto::hex_encode(&frame);
```

### C#（NuGet）

```csharp
using Potatotv.Pbp;
using Potatotv.Pbp.Gen;

PaccEnvelope msg = PaccEnvelope.NewBuilder()
    .SetType("inspect_result")
    .SetTsMs(1_700_000_000_000L)
    .SetNonce("0123456789abcdef")
    .SetSessionId("sess-1")
    .SetPteid("PT0001")
    .SetPayloadJson("{\"result\":\"clean\"}")
    .SetSigVersion(1)
    .Build();

byte[] frame = msg.ToByteArray();
PaccEnvelope back = PaccEnvelope.ParseFrom(frame);

byte[] key = System.Text.Encoding.UTF8.GetBytes("000102030405060708090a0b0c0d0e0f");
byte[] sig = PbpCrypto.HmacSha256(key, msg.SigningInput());
bool ok = PbpCrypto.VerifyHmac(key, msg.SigningInput(), sig);
```

### Python（PyPI）

Python 运行时不实现 zstd（见下文边界）。编码路径里压缩那一步退化为原样返回。

```python
from pbp_gen import PaccEnvelope, PbpCrypto

msg = (PaccEnvelope.new_builder()
       .set_type("inspect_result")
       .set_ts_ms(1_700_000_000_000)
       .set_nonce("0123456789abcdef")
       .set_session_id("sess-1")
       .set_pteid("PT0001")
       .set_payload_json('{"result":"clean"}')
       .set_sig_version(1)
       .build())

frame = msg.to_bytes()
back = PaccEnvelope.parse_from(frame)

key = b"000102030405060708090a0b0c0d0e0f"
sig = PbpCrypto.hmac_sha256(key, msg.signing_input())
ok = PbpCrypto.verify_hmac(key, msg.signing_input(), sig)
```

## 发布坐标

五个运行时的版本号都锁在 `1.0.0`，协议运行时是独立版本号，不随 PACC 产品版本走。

| 运行时 | 坐标 | 包管理器 | 主代码依赖 |
|---|---|---|---|
| Java | `com.potatotv:pacc-binary-protocol:1.0.0` | Maven Central | 无（仅 test 作用域 JUnit） |
| TypeScript | `@potatotv/pbp@1.0.0` | npm | 无（devDependency 只有 tsc） |
| Rust | `pacc-bp = "1.0.0"`（crate `pacc_bp`） | crates.io | 无 |
| C# | `Potatotv.Pbp` 1.0.0（程序集 `Pbp`） | NuGet | 无 |
| Python | `pacc-bp` 1.0.0（模块 `pbp_gen`） | PyPI | 无 |

「无依赖」不是文档里的口号，是构建门禁：Java 的 `pom.xml` 用 `maven-enforcer-plugin` 禁掉所有 `compile` / `runtime` 作用域的依赖，只要有人给运行时加上第三方库，`mvn validate` 就失败。其余运行时同理，HMAC-SHA256、zstd 子集都在各自仓库内自实现。

## 边界

- 帧头有加密标志位（`FLAG_ENCRYPTED`），但当前版本**未实现**。置位这一位的帧会被显式拒绝（`UNSUPPORTED_FLAG`），不会当成明文载荷解析。
- Java 运行时提供了 X25519 密钥协商、HKDF 派生、会话登记表，但帧层自己不加密。其余四个运行时没有会话/密钥协商这套 API。
- zstd 是受限子集，不是完整实现。编码只输出单段帧 + Raw/RLE/压缩块，解码遇到 Huffman literals、字典帧、内容校验和等子集外能力会显式失败。压缩率低于 libzstd level 3。
- Python 运行时不实现 zstd；涉及压缩的帧要由其它语言的运行时处理。

## 文档索引

| 文档 | 内容 |
|---|---|
| [`docs/wire-format.md`](docs/wire-format.md) | 线上格式：帧头布局、VarInt/ZigZag、字段有序编解码、HMAC 覆盖面、压缩条件、版本兼容规则 |
| [`docs/api-reference.md`](docs/api-reference.md) | 五个运行时的 API 参考，按编解码 / 帧 / 会话 / 压缩 / 注册表分组 |
| [`docs/usage-examples.md`](docs/usage-examples.md) | 使用示例：上报检测事件、解帧、验签、大消息压缩、跨语言互操作验证 |