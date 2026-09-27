# PBP 线上格式

这份文档描述 PBP 在字节层面长什么样。所有布局以 `runtime-java/` 的 `PbpFrame.java`、`PbpEncoder.java`、`PbpDecoder.java`、`PbpCrypto.java` 为准，其余语言运行时是它们的镜像。

一句话概括整条链路：**消息字段 → 载荷 → （可选压缩）→ 帧头 + 载荷 → （可选）帧尾签名**。

## 帧头

帧头固定 30 字节。多字节字段一律小端，唯一的例外是开头的 2 字节 Magic。

| 偏移 | 长度 | 字段 | 类型 | 说明 |
|---|---|---|---|---|
| 0 | 2 | Magic | — | ASCII `"PB"`，按 `'P','B'` 顺序写，即字节 `0x50 0x42` |
| 2 | 1 | Version | u8 | 当前 `1` |
| 3 | 1 | Flags | u8 | 见下 |
| 4 | 2 | MessageID | u16 | 小端 |
| 6 | 4 | Sequence | u32 | 小端 |
| 10 | 8 | Timestamp | i64 | 小端，毫秒 |
| 18 | 8 | SessionID | u64 | 小端 |
| 26 | 4 | PayloadLen | u32 | 小端，载荷字节数 |
| 30 | N | Payload | bytes | 长度由 PayloadLen 给出 |
| 30+N | 32 | Signature | bytes | 仅 `FLAG_SIGNED` 置位时存在，HMAC-SHA256 |

Magic 是唯一不按小端数值展开的字段：`0x5042` 按数值小端写会变成 `0x42 0x50`，而这里是 `0x50 0x42`。四个运行时的实现都显式区分了这一点（`putHeader` 里 `MAGIC >>> 8` 在前）。

载荷长度不写进载荷内部，而是放在帧头。这样「新客户端多发了字段、旧服务端不认识」只是一段可跳过的尾巴，而不是解析错位。

### Flags

| 位 | 值 | 名称 | 状态 |
|---|---|---|---|
| bit0 | `0x01` | `FLAG_ENCRYPTED` | 未实现，置位即拒绝 |
| bit1 | `0x02` | `FLAG_COMPRESSED` | 载荷已压缩 |
| bit2 | `0x04` | `FLAG_DELTA` | 载荷是差分编码 |
| bit3 | `0x08` | `FLAG_SIGNED` | 帧尾有 32 字节签名 |

已知位掩码是这四位。其余位是保留位，置位即视为协议不认识。

两条约束：

- 加密位当前版本**不接受**。载荷加密由更高层用会话密钥处理，不在帧层；一旦这一位置位，解析和编码都直接抛 `UNSUPPORTED_FLAG`。把「对端以为已经加密」的载荷当明文解析是最危险的失败方式，所以宁可直接失败。
- 保留位被置位同样抛 `UNSUPPORTED_FLAG`。

`PbpFrame.withFlag(int)` 只允许置已知位，且拒绝 `FLAG_ENCRYPTED`。

### 载荷长度上限

`PayloadLen` 字面上是 u32，允许到 4GB。运行时给它设了硬上限 `MAX_PAYLOAD_SIZE = 16 MiB`（`16 * 1024 * 1024`）。超上限时 `parse` 与 `encode` 都抛 `BAD_LENGTH`；压缩解压的产物上限也取这个值，兼作解压炸弹的拦截点。

当前协议里最大的消息只有几百 KB，16 MiB 留得很宽。

### 解析时的校验

`PbpFrame.parse(byte[])` 按顺序检查，任何一条不满足就抛 `PbpException`：

1. 原始字节数 ≥ 30，否则 `TRUNCATED`；
2. Magic 等于 `0x5042`，否则 `BAD_MAGIC`；
3. 版本落在 `[MIN_VERSION, VERSION]`，即 `[1, 1]`，否则 `BAD_VERSION`（低于最低、高于最高分别是两条不同的报错）；
4. Flags 合法（见上），否则 `UNSUPPORTED_FLAG`；
5. `PayloadLen` ≤ 16 MiB，否则 `BAD_LENGTH`；
6. 实际长度正好等于 `30 + PayloadLen + (置位签名 ? 32 : 0)`，否则 `BAD_LENGTH`。

第 6 条意味着帧不接受尾部多余字节，也不接受签名长度的模糊地带。

### 编码时的校验

`PbpFrame.encode()`：

- Flags 合法；
- 版本必须等于当前版本（编码永远只输出 `VERSION`）；
- `PayloadLen` ≤ 16 MiB；
- 若置位 `FLAG_SIGNED`，签名字节必须正好 32 字节，否则 `BAD_LENGTH`；
- 若未置位 `FLAG_SIGNED`，不允许携带签名字节，否则 `BAD_FORMAT`。

## 签名

签名是 HMAC-SHA256，输出 32 字节，放在帧尾。

**覆盖面是「帧头 + 载荷」整串字节，不是只盖载荷。** 只盖载荷的话，`MessageID`、`Timestamp`、`SessionID` 都能被中间人改写而验签照样通过。

计算方式（`PbpFrame.signingInput()`）：

```
signingInput = header(flags | FLAG_SIGNED, payloadLen) ‖ payload
```

两个细节：

- 帧头是按 **`FLAG_SIGNED` 已置位**的形态算的，所以签名方和验签方拿到的是同一串字节，不存在「先签再置标志位、两边对不上」的问题。
- 载荷是**压缩后**的载荷（若该帧压缩过）。压缩决策是载荷的确定性函数——验签端拿字段重新编码一遍就能得到同一串待签字节，不需要保留原始压缩载荷。

签名与验签：

- `PbpCrypto.hmacSha256(byte[] key, byte[] data)` → 32 字节 Mac；
- `PbpCrypto.verifyHmac(byte[] key, byte[] data, byte[] expected)`：`expected` 长度不是 32 直接返回 `false`；比较走恒定时间（`MessageDigest.isEqual` / `CryptographicOperations.FixedTimeEquals`），不按字节提前返回。

**密钥是密钥文本的 UTF-8 字节**，不是把 hex 文本解成二进制再当密钥。`test-vectors/interop.txt` 里的 `secret_hex_text` 是这个约定的落地：它存的是密钥文本 UTF-8 字节的十六进制，各语言运行时先解出文本、再取其 UTF-8 字节当 HMAC 密钥。

签名不在载荷内部，所以帧解析可以脱离密钥单独使用（比如抓包分析）。

## 载荷编码

### 字段顺序

字段按 MDL 定义顺序写入、按同一顺序读出，**不带字段标签**。省掉每字段 1-2 字节的标签开销，代价是编解码双方必须持有同一份顺序定义：新字段只能追加在末尾，不能改动已有字段的类型或位置，否则两侧解析整体错位。

嵌套消息内联编码，不加长度前缀。边界靠字段顺序本身确定，最外层由帧头的 `PayloadLen` 兜底。

`PbpMessage` 接口就是这条约定的接口化：`messageId()`、`encode(PbpEncoder)`、`decode(PbpDecoder)`、`encodedSize()`。生成类由 `tools/pbpgen` 从 MDL 产出。

### VarInt

无符号整数用 LEB128：每字节低 7 位有效，最高位表示后续还有字节。

- 最多 10 字节（64 位需要 10 组 7 位）；
- 第 10 字节只允许 `0x00` 或 `0x01`（即 `(b & 0xFE) == 0`），否则 `BAD_VARINT`；
- 超过 10 字节仍未结束，抛 `BAD_VARINT`。

### ZigZag

有符号的 `int32` / `int64` 先 ZigZag 再 VarInt，让 -1、1 这类小绝对值不占满整宽：

```
int32： (v << 1) ^ (v >> 31)
int64： (v << 1) ^ (v >> 63)
```

解码是逆运算 `(z >>> 1) ^ -(z & 1)`。

### 各类型编码

| 类型 | 编码 | 说明 |
|---|---|---|
| `bool` | 1 字节 | 只接受 `0` / `1`，其余抛 `BAD_FORMAT` |
| `int8` | 1 字节 | |
| `uint8` | 1 字节 | |
| `int16` | 2 字节 | 小端 |
| `uint16` | 2 字节 | 小端 |
| `int32` | VarInt | ZigZag |
| `uint32` | VarInt | 取值上界 `2^32-1`，读超上界抛 `BAD_FORMAT` |
| `int64` | VarInt | ZigZag |
| `uint64` | VarInt | long 原始位模式即无符号值 |
| `enum` | VarInt | 非负；解码走 u32 路径，超 `Integer.MAX_VALUE` 抛 `BAD_FORMAT` |
| `float32` | 4 字节 | 小端，原始位模式 |
| `float64` | 8 字节 | 小端，原始位模式 |
| `string` | VarInt 长度 + UTF-8 | 严格 UTF-8，非法字节序列抛 `BAD_FORMAT` |
| `bytes` | VarInt 长度 + 数据 | |

写 `null` 的 `string` / `bytes` 会抛 `BAD_FORMAT`——可空字段必须走下面的 optional 形式，不允许用 null 表达「不存在」。

### 可空字段（presence 位图）

一批连续可空字段的值之前先写一段存在性位图。位图每字节承载 8 个字段，**低位对应更靠前的字段**：

```
byte i 的 bit b  →  第 (i*8 + b) 个可空字段是否存在
```

位图字节数 = `(fieldCount + 7) / 8`。

单个可空字段的位图正好是 1 字节，与「存在性字节」逐字节等价，所以单字段便捷方法（`writeOptionalString` / `writeOptionalBytes` / `writeOptionalMessage`）与位图形式可以混用，不会产生两种编码。

编码顺序是：位图在前，存在的值在后。也就是说 `writeOptionalString(null)` 只写 1 字节 `0x00`，`writeOptionalString("")` 写 1 字节 `0x01` 再加一个 0 长度的 string。

### 集合

列表与映射都以 VarInt 的**元素个数**开头。

- 解码时元素个数上界是 `1 << 20`（1,048,576），越界抛 `BAD_FORMAT`。不能用「每元素至少 1 字节」去卡，因为无字段的嵌套消息合法地编码成 0 字节。
- 映射按 Map 的**迭代顺序**写入。要保证两侧字节一致，必须用有序 Map；解码 `readStringMap` 默认用 `LinkedHashMap` 保持线上顺序。
- string 键的映射（`writeStringMap`）是 MDL 里绝大多数 map 的形态。

### 向后兼容

`PbpDecoder.skipRemaining()` 丢弃剩余字节，用于「新端追加了字段、旧端不认识」的场景：旧端读完自己认识的字段后把尾巴丢掉即可。

生成的 `decode` 里，末尾字段自动 optional。以 `PaccEnvelope` 为例：

```java
// 末尾字段自动 optional：旧端的载荷在这里已经读完
sigVersion = dec.remaining() > 0 ? dec.readUInt8() : 0;
```

载荷尾部多出的字节不再消费。两条合起来就是兼容落点：旧端读新端载荷，能读到默认值而不报错。

## 压缩

压缩在 `PbpCodec` 里，是载荷的确定性函数。

策略（照设计文档 §3.10.1）：

1. 载荷长度 `<= COMPRESS_THRESHOLD`（1024 字节）时**不压缩**，原样返回，帧头不带 `FLAG_COMPRESSED`；
2. 超过阈值才尝试压缩，**压完比原文小才启用**；压不小就原样返回，同样不带标志位。

阈值以下不压，意味着绝大多数握手与指令信封的线上字节与引入压缩之前完全一致。

`maybeCompress` 在不值得压缩时返回**同一个对象引用**，调用方用引用相等判断是否启用，避免「内容恰好一样长但是两个对象」的误判。

解压上限取 `MAX_PAYLOAD_SIZE`（16 MiB）。

### zstd 子集

PBP 运行时零第三方依赖，而 JDK 标准库没有 zstd，于是自研了一个受限子集（RFC 8878）。

**编码只输出**：单段帧（无字典、无校验和）+ Raw / RLE / 压缩块。压缩块内部固定是「原始 literals + 预定义 FSE 序列表」，序列一律用显式偏移，不发 repeat 码。单段帧的窗口等于内容长度。

**解码是编码输出集合的超集**，接受：Raw / RLE 块、Raw / RLE literals、预定义 / RLE / Repeat 序列表。

**解码显式拒绝**（抛 `UNSUPPORTED`，不静默降级）：Huffman literals、FSE_Compressed 表、字典帧、内容校验和。

两条额外的护栏：单段帧窗口不超过调用方给的 `maxSize`；每个块的解压结果不超过 `min(windowSize, 128KB)`。

输出的每个字节都是合法 zstd，libzstd 能解。代价是压缩率低于 libzstd level 3（没有 Huffman 与自适应 FSE 表）。`tools/zstd-crosscheck/crosscheck.py` 负责和 libzstd 对拍。

Python 运行时不实现这一层，涉及压缩的帧要交给其它语言处理。

## 差分

差分在 `PbpDeltaChain` 里，标志位是 `FLAG_DELTA`。

规则：

- 差分以「上一轮同 ID 的消息」为基线，收发双方各自维护；
- 最多连续 `MAX_CONSECUTIVE`（10）条差分后，必须发一条完整消息，防累积误差；
- 一条消息 ID 一条链实例，按「一条连接一个方向」使用，内部存基线与计数，非线程安全；
- 需要压缩的大差分结果走与普通帧相同的压缩策略，两种标志可以同时出现：`FLAG_DELTA | FLAG_COMPRESSED`；
- 接收侧先解压、再回放差分。

带签名的消息不生成差分方法（见 `PbpDeltaMessage`），需要签名时由调用方在返回的帧上补。原因很直接：差分以「上一轮」为基线，双方各自维护基线，而「两端基线必须严格一致」和「每条都签名」这两件事不好同时保证。

`reset()` 丢弃基线，下一条必定发完整消息——重连、丢帧后的复位点。

## 版本与兼容

- `VERSION = 1`，`MIN_VERSION = 1`。
- **解析**接受 `[MIN_VERSION, VERSION]` 区间内任意版本；**编码**永远输出当前版本。
- 破坏性变更走大版本升级时，过渡期靠这个区间实现新旧互通。
- 同一大版本内的兼容靠两条：新字段只加末尾、末尾字段自动 optional（见上文「向后兼容」）。

## 消息 ID 区间

| 区间 | 用途 |
|---|---|
| `0x0000`–`0x00FF` | 系统 |
| `0x0100`–`0x0FFF` | 客户端 → 服务端 |
| `0x1000`–`0x1FFF` | 服务端 → 客户端 |
| `0x2000`–`0x2FFF` | 双向 |
| `0x3000`–`0xEFFF` | 预留，禁止使用 |
| `0xF000`–`0xFFFF` | 自定义 |

`PbpRegistry.register` 在注册时校验 ID 落在这张表内，并额外拒绝预留区间的 ID，避免两条消息撞 ID 到线上才发现。同名的来源侧常量在 `tools/pbpgen/pbpgen/model.py`。

当前 MDL 里的消息：`PaccEnvelope`（`0x2001`，双向，带签名选项）、`DetectionReport`（`0x0103`，客户端 → 服务端）。`DetectionEvent`、`ApmSnapshot` 无消息 ID，是嵌套类型。

## 加密

帧层不实现加密。`PbpCrypto` 提供了 ChaCha20-Poly1305 的原语（IETF 变体，12 字节 nonce + 16 字节 tag），以及 `newNonce`（8 字节会话 ID + 4 字节序号）和 X25519 / HKDF 相关工具，但帧的 `FLAG_ENCRYPTED` 位当前是关闭状态。

注意 `PbpCrypto` 里的 `seal` / `open` 只是原语，`PbpFrame` 不调用它们。要加密需要由更高层决定 nonce 与 AAD（AAD 应是帧头字节，不含签名），并在拿到明文后再走帧层。