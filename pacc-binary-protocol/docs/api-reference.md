# PBP API 参考

这份文档按功能分组列出五个运行时的公开 API，并标出哪些是跨语言等价的、哪些不一致。签名以源码为准，分组内的名字差异只是各语言命名习惯（Java 驼峰、Rust/Python 下划线）。

读的时候注意两件事：

- **等价 API**：同一分组里名字对应、语义一致的方法。写跨语言代码时可以直接照着这一列平移。
- **不一致**：下面用「不一致」单独点出的地方，是各语言确实不一样、不能想当然平移的。

## 一、编解码

### PbpMessage

可编码消息的接口。生成类（`gen/` 或 `pbp_gen/`）实现它，字段顺序即协议。

| 概念 | Java | TypeScript | Rust | C# | Python |
|---|---|---|---|---|---|
| 消息 ID | `int messageId()` | `messageId(): number` | `fn message_id(&self) -> u16` | `int GetMessageId()` | `message_id(self)` |
| 写字段 | `void encode(PbpEncoder)` | `encode(enc: PbpEncoder): void` | `fn encode(&self, enc: &mut PbpEncoder)` | `void Encode(PbpEncoder)` | `encode(self, enc)` |
| 读字段 | `void decode(PbpDecoder)` | `decode(dec: PbpDecoder): void` | `fn decode(&mut self, dec: &mut PbpDecoder) -> Result<()>` | `void Decode(PbpDecoder)` | `decode(self, dec)` |
| 长度预估 | `int encodedSize()` | `encodedSize(): number` | `fn encoded_size(&self) -> usize` | `int EncodedSize()` | `encoded_size(self)` |

Rust 的 `decode` 会把越界作为 `Result` 返回；其余语言的 `decode` 通过抛异常表达失败。

### PbpEncoder

写入侧。内部是可增长的 `byte[]`，`encodedSize()` 只是预分配提示，不是正确性依据。

| 概念 | Java | TypeScript | Rust | C# | Python |
|---|---|---|---|---|---|
| 构造 | `new PbpEncoder()` / `new PbpEncoder(int)` | `new PbpEncoder(initialCapacity = 64)` | `PbpEncoder::new()` / `with_capacity(n)` | `new PbpEncoder()` | `PbpEncoder(initial_capacity=64)` |
| 已写长度 | `int size()` | `size(): number` | `fn len(&self)` / `is_empty()` | `Length` | `size(self)` |
| 落地 | `byte[] toByteArray()` | `toByteArray(): Uint8Array` | `fn into_bytes(self) -> Vec<u8>`（另有 `as_slice()`） | `byte[] ToByteArray()` | `to_bytes(self)` |
| 复位 | `void reset()` | `reset(): void` | `fn reset(&mut self)` | `void Reset()` | `reset(self)` |

写入方法（等价 API，跨语言一一对应，只有大小写与下划线差异）：

`writeBool` `writeInt8` `writeUInt8` `writeInt16` `writeUInt16` `writeInt32` `writeUInt32` `writeInt64` `writeUInt64` `writeEnum` `writeFloat32` `writeFloat64` `writeString` `writeBytes` `writePresence` `writeOptionalString` `writeOptionalBytes` `writeOptionalMessage` `writeMessage` `writeMessageList` `writeStringList` `writeList` `writeMap` `writeStringMap`

对应的 Rust 名称是 `write_bool` … `write_string_map`，C# 是 `WriteBool` … `WriteStringMap`，Python 是 `write_bool` … `write_string_map`。

**不一致（参数类型）**：

- `writeUInt32`：Java 收 `long`（取值上界 `2^32-1` 超出 int），C# 收 `uint`，Rust 收 `u32`，TS/Python 收普通数值。
- 字符串字段为 `null` 时抛 `BAD_FORMAT`，可空字段必须用 optional 系列。
- TS 的 `writePresence(present: readonly boolean[])` 收数组；Java/C# 是可变参数 `boolean...` / `params bool[]`；Rust/Python 收切片 / 序列。

长度预估的静态方法（仅用于预分配，不影响正确性）也逐语言等价：`varIntSize` `boolSize` `int8Size` `uint8Size` `int16Size` `uint16Size` `int32Size` `uint32Size` `int64Size` `uint64Size` `enumSize` `float32Size` `float64Size` `stringSize` `bytesSize` `presenceSize` `optionalStringSize` `optionalBytesSize` `messageSize` `optionalMessageSize` `messageListSize` `stringListSize` `mapSize` `stringMapSize`。Rust 里对应 `varint_size` … `string_map_size`。

`MAX_VARINT_BYTES = 10` 在 Java / TS / Rust 里是公开常量。

### PbpDecoder

读取侧。所有读取都做边界检查，越界抛异常，不返回默认值。

| 概念 | Java | TypeScript | Rust | C# | Python |
|---|---|---|---|---|---|
| 构造 | `new PbpDecoder(byte[])` / `(byte[], int, int)` | `new PbpDecoder(buf, offset?, length?)` | `PbpDecoder::new(&[u8])` / `with_window(buf, off, len) -> Result` | `new PbpDecoder(byte[])` / `(byte[], int, int)` | `PbpDecoder(buf, offset=0, length=None)` |
| 剩余 | `remaining()` / `hasRemaining()` | `remaining()` / `hasRemaining()` | `remaining()` / `has_remaining()` | `Remaining` / `HasRemaining` | `remaining()` / `has_remaining()` |
| 位置 | `position()` | `position()` | `position()` | `Position` | `position()` |
| 跳尾 | `skipRemaining()` | `skipRemaining()` | `skip_remaining()` | `SkipRemaining()` | `skip_remaining()` |

读取方法等价 API：`readBool` `readInt8` `readUInt8` `readInt16` `readUInt16` `readInt32` `readUInt32` `readInt64` `readUInt64` `readEnum` `readFloat32` `readFloat64` `readString` `readBytes` `readPresence` `readOptionalString` `readOptionalBytes` `readOptionalMessage` `readMessage` `readMessageList` `readStringList` `readList` `readMap` `readStringMap`。

**不一致（返回值类型）**：

- `readUInt32`：Java 返回 `long`，C# 返回 `uint`，Rust 返回 `u32`，TS/Python 是普通数值。
- `readPresence(int fieldCount)` 返回布尔数组 / `Vec<bool>`。
- `readMessage` 的实例化方式不同：Java 收 `Supplier<T>`，TS 收 `() => T`，C# 收 `Func<T>`，Python 收工厂函数；**Rust 没有工厂参数**，要求 `T: PbpMessage + Default`，用 `Default::default()` 建空实例。
- `readMap` / `readStringMap`：Java 收 `Supplier<Map>` 决定 Map 实现；TS/Python 直接返回 Map / dict；Rust 返回 `Vec<(K, V)>`（不强制去重）；C# 返回 `Dictionary`。`readStringMap` 在 Java 里默认 `LinkedHashMap`、TS 里默认 `Map`、Python 里默认 `dict`，都保持线上顺序。

### PbpException / PbpError

失败原因分类，九个取值跨语言一一对应：

`BAD_MAGIC`、`BAD_VERSION`、`UNSUPPORTED_FLAG`、`UNSUPPORTED`、`BAD_LENGTH`、`TAG_MISMATCH`、`TRUNCATED`、`BAD_VARINT`、`BAD_FORMAT`

| 语言 | 类型 | 取 code 的方式 |
|---|---|---|
| Java | `PbpException extends RuntimeException` | `Code code()`，`enum Code` |
| TypeScript | `PbpException extends Error` | 字符串联合类型 `PbpErrorCode`，属性 `code` |
| Rust | `enum PbpError`（实现了 `Display` + `std::error::Error`） | 枚举值本身；`Result<T> = Result<T, PbpError>` |
| C# | `PbpException` | `PbpErrorCode`（枚举），属性 `Code` |
| Python | `PbpException(Exception)` | `PbpErrorCode` 是字符串常量类，实例属性 `code` |

分类的用途是让调用方区分「协议不认识」和「疑似篡改」：magic/version 不符多半是版本不匹配，`TAG_MISMATCH` 与 `BAD_LENGTH` 是明确的篡改信号。

### 生成消息（Builder）

字段 setter 与 `build()` 由 `tools/pbpgen` 生成，形态各语言不同：

| 语言 | 建实例 | 编 / 解 | 备注 |
|---|---|---|---|
| Java | `X.newBuilder()...build()`，另有 `x.toBuilder()` | `byte[] toByteArray()` / `static X parseFrom(byte[])` | Builder 是内部类 |
| TypeScript | `X.newBuilder()...build()`，另有 `x.toBuilder()` | `toByteArray()` / `static parseFrom(raw)` | Builder 是独立导出的类 |
| Rust | `X::new()...set_x(..)`（**无 Builder 类**） | `to_byte_array() -> Result<Vec<u8>>` / `parse_from(&[u8]) -> Result<X>` | setter 是消费式 `self -> Self` |
| C# | `X.NewBuilder()...Build()`，另有 `x.ToBuilder()` | `ToByteArray()` / `static ParseFrom(byte[])` | |
| Python | `X.new_builder()...build()` | `to_bytes()` / `static parse_from(raw)` | |

签名相关方法：Java `byte[] signingInput()`、TS `signingInput(): Uint8Array`、C# `SigningInput()`、Rust `signing_input() -> Result<Vec<u8>>`、Python `signing_input()`。签名 setter：`setSignature(hex)` / `setSignatureBytes(bytes)`（Rust 是 `set_signature(hex) -> Result` / `set_signature_bytes(Vec<u8>)`）。

带签名的消息生成 `toByteArray` / `parseFrom` / `signingInput`（`PaccEnvelope`）；无签名的消息（`DetectionReport`）生成差分方法（见第六节）。

## 二、帧

### PbpFrame

帧头布局与长度校验。不碰密码学。

常量（等价，跨语言同名或近名）：`MAGIC = 0x5042`、`VERSION = 1`、`MIN_VERSION = 1`、`HEADER_SIZE = 30`、`SIGNATURE_SIZE = 32`、`MAX_PAYLOAD_SIZE = 16 MiB`、`FLAG_ENCRYPTED = 0x01`、`FLAG_COMPRESSED = 0x02`、`FLAG_DELTA = 0x04`、`FLAG_SIGNED = 0x08`。

| 概念 | Java | TypeScript | Rust | C# | Python |
|---|---|---|---|---|---|
| 构造 | `new PbpFrame(...)` | `new PbpFrame(...)` | `PbpFrame::new(...)` | `new PbpFrame(...)` | `PbpFrame(...)` |
| 无签名帧 | `PbpFrame.of(messageId, ts, payload)` | `PbpFrame.of(...)` | `PbpFrame::of(...)` | `PbpFrame.Of(...)` | `PbpFrame.of(...)` |
| 状态查询 | `signed()` / `compressed()` / `delta()` | 同名 | `signed()` / `compressed()` / `delta()` | `Signed` / `Compressed` / `Delta` | `signed()` / `compressed()` / `delta()` |
| 置标志位 | `withFlag(int)`（越界抛异常） | `withFlag(flag)` | `with_flag(u8) -> Result<Self>` | `WithFlag(int)` | `with_flag(flag)` |
| 加签名 | `withSignature(byte[])` | `withSignature(sig)` | `with_signature(&[u8]) -> Result<Self>` | `WithSignature(byte[])` | `with_signature(signature)` |
| 待签字节 | `signingInput()` | `signingInput()` | `signing_input()` | `SigningInput()` | `signing_input()` |
| 编码整帧 | `byte[] encode()` | `encode(): Uint8Array` | `encode() -> Result<Vec<u8>>` | `byte[] Encode()` | `encode()` |
| 解析整帧 | `static parse(byte[])` | `static parse(raw)` | `PbpFrame::parse(&[u8]) -> Result` | `static Parse(byte[])` | `PbpFrame.parse(raw)` |
| 载荷长度 | `payloadLength()` | `payloadLength()` | `payload_length()` | `PayloadLength` | `payload_length()` |
| 字段访问 | record accessor | 只读属性 | 公开字段 | 只读属性 | 属性 |

**不一致**：

- TS 的 `timestampMs` 与 `sessionId` 是 `bigint`，Java/C#/Rust/Python 是数值类型。
- TS 的结构体不可变（每次 `withFlag` / `withSignature` 返回新对象），Rust 的 `with_flag` / `with_signature` 也是消费式返回新值；C# / Python 同样是「返回副本」。
- Java `withFlag` / `withSignature` 抛异常；Rust 对应方法返回 `Result`，调用方需 `?`。

### 帧解析的错误分类

`parse` 在下列情形抛 `PbpException`，code 与条件对应：长度不足 30 → `TRUNCATED`；Magic 不符 → `BAD_MAGIC`；版本越区间 → `BAD_VERSION`；Flags 非法（含加密位）→ `UNSUPPORTED_FLAG`；`PayloadLen` 超上限或与实际长度不符 → `BAD_LENGTH`。

## 三、会话与密钥协商

**这一组只有 Java 运行时提供。** TS / Rust / C# / Python 没有会话、密钥协商、会话登记表这套 API。

### PbpX25519

| 方法 | 说明 |
|---|---|
| `static KeyPair generateKeyPair()` | 生成 X25519 密钥对 |
| `static byte[] publicKeyBytes(PublicKey)` | 公钥 → 32 字节原始表示 |
| `static byte[] privateKeyBytes(PrivateKey)` | 私钥 → 32 字节原始表示 |
| `static PublicKey publicKeyFromBytes(byte[] raw)` | 32 字节 → 公钥 |
| `static PrivateKey privateKeyFromBytes(byte[] raw)` | 32 字节 → 私钥 |
| `static byte[] sharedSecret(PrivateKey local, PublicKey peer)` | ECDH 共享密钥 |
| `static byte[] sharedSecret(PrivateKey local, byte[] peerRaw)` | 同上，对端公钥传原始字节 |

### PbpHkdf

| 方法 | 说明 |
|---|---|
| `static byte[] extract(byte[] salt, byte[] ikm)` | HKDF-Extract，返回 PRK |
| `static byte[] expand(byte[] prk, byte[] info, int length)` | HKDF-Expand，返回 `length` 字节 |
| `static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int length)` | extract + expand |
| `static byte[] derive(byte[] ikm, byte[] salt, byte[] info)` | 长度默认取 `KEY_SIZE`（32） |
| `static byte[] derive(byte[] ikm, String salt, String info)` | salt / info 按 UTF-8 处理 |

### PbpSession

一条会话的密钥与序号状态。按「一条连接一个实例、单线程使用」设计；`nextSequence()` 这类复合操作没有加锁，并发调用会丢号。

常量：`DEFAULT_SALT = "pacc-session"`、`DEFAULT_INFO = "aes-key"`。

| 方法 | 说明 |
|---|---|
| `static PbpSession of(long sessionId, byte[] key, long epoch)` | 已有会话密钥时直接构造 |
| `static PbpSession fromSharedSecret(long sessionId, byte[] sharedSecret)` | 由 ECDHE 共享密钥派生（默认 salt/info） |
| `static PbpSession fromSharedSecret(long, byte[], String salt, String info)` | 指定 salt/info |
| `static byte[] derive(byte[] ikm, byte[] salt, byte[] info)` | 用途隔离派生 |
| `static byte[] derive(byte[] ikm, String salt, String info)` | 同上，字符串参数 |
| `long sessionId()` / `String sessionIdHex()` | 会话 ID / 其十六进制 |
| `byte[] key()` | 32 字节会话密钥副本 |
| `String keyHex()` | 会话密钥的十六进制 |
| `long epoch()` / `int sequence()` | 轮换代次 / 已发出序号 |
| `long createdMs()` / `long lastSeenMs()` | 创建时间 / 最近活跃时间 |
| `int nextSequence()` | 取下一个发送序号（从 1 开始，0 保留） |
| `boolean acceptSequence(int received)` | 校验接收序号，要求无符号严格递增；把会话标记活跃 |
| `void touch()` | 刷新活跃时间 |
| `PbpSession rotate(long expectedEpoch)` | 轮换到下一 epoch，epoch 不匹配返回 `null` |

`keyHex()` 的语义要小心：它给的是密钥的十六进制文本。与按 hex 字符串持有密钥的既有链路（`WssSessionKeys`）对接时，**HMAC 密钥是这段 hex 文本的 UTF-8 字节，不是解 hex 后的 32 字节**，两者不可混用。

### PbpSession.Registry

按会话 ID 查密钥的登记表，带 TTL 与容量上限。

| 方法 | 说明 |
|---|---|
| `Registry(long ttlMs, int maxSessions)` | 构造，两者都必须为正 |
| `PbpSession open(long sessionId, byte[] key)` | 登记；容量满先清僵尸，仍满返回 `null` |
| `PbpSession get(long sessionId)` | 查并刷新活跃时间；未知返回 `null` |
| `PbpSession rotate(long sessionId, long expectedEpoch)` | 轮换；不存在或 epoch 不符返回 `null` |
| `void close(long sessionId)` | 断开时立即销毁 |
| `int size()` | 当前会话数 |

## 四、压缩

### PbpCodec

载荷 ↔ 帧的组装层，把「编码 → 压缩 → 装帧」和「解帧 → 解压」收敛到一处。

常量：`COMPRESS_THRESHOLD = 1024`。

| 概念 | Java | TypeScript | Rust | C# | Python |
|---|---|---|---|---|---|
| 编码载荷 | `payloadOf(PbpMessage)` | `payloadOf(message)` | `PbpCodec::payload_of(&M) -> Vec<u8>` | `PayloadOf(IPbpMessage)` | `payload_of(message)` |
| 压缩策略 | `maybeCompress(byte[])` | `maybeCompress(payload)` | `maybe_compress(Vec<u8>) -> Result<(Vec<u8>, bool)>` | `MaybeCompress(byte[])` | `maybe_compress(payload)` |
| 装帧 | `frameOf(PbpMessage, long ts)` | `frameOf(message, ts)` | `frame_of(&M, i64) -> Result<PbpFrame>` | `FrameOf(IPbpMessage, long)` | `frame_of(message, timestamp_ms)` |
| 取载荷 | `payloadOf(PbpFrame)` | `payloadOfFrame(frame)` | `payload_of_frame(&PbpFrame) -> Result<Vec<u8>>` | `PayloadOf(PbpFrame)` | `payload_of_frame(frame)` |

**不一致**：

- 取帧载荷的方法名：Java 与 C# 用重载 `payloadOf` / `PayloadOf`，TS / Rust / Python 用独立名 `payloadOfFrame` / `payload_of_frame`。
- `maybe_compress` 的返回值：Rust 返回 `(数据, 是否启用压缩)` 元组；其余语言返回数据本身，由调用方用引用相等（TS/C#）或身份比较（Java/Python）判断是否启用。
- Python 的 `maybe_compress` 是恒等函数（永不压缩），`payload_of_frame` 遇到 `FLAG_COMPRESSED` 抛 `UNSUPPORTED`。

`frameOf` 的语义：载荷超过阈值且压缩后更小才置 `FLAG_COMPRESSED`。

### PbpZstd（zstd 子集）

| 概念 | Java | TypeScript | Rust | C# | Python |
|---|---|---|---|---|---|
| 压缩 | `PbpZstd.compress(byte[])` | `compress(src)` | `PbpZstd::compress(&[u8]) -> Result<Vec<u8>>` | `PbpZstd.Compress(byte[])` | 未提供 |
| 解压 | `PbpZstd.decompress(byte[], int maxSize)` | `decompress(raw, maxSize)` | `PbpZstd::decompress(&[u8], usize) -> Result` | `PbpZstd.Decompress(byte[], int)` | 未提供 |

`compress` 不抛「压不小」，结果可能比原文长，是否采用由调用方比长度决定。`decompress` 的 `maxSize` 是解压上限，也是解压炸弹的拦截点。

## 五、注册表

消息 ID → 解码器工厂的登记表，显式注册，不扫描 classpath / 不反射。

**只有 Java 与 C# 提供。**

| 概念 | Java | C# |
|---|---|---|
| 区间常量 | `SYSTEM_MIN/MAX`、`CLIENT_TO_SERVER_MIN/MAX`、`SERVER_TO_CLIENT_MIN/MAX`、`BIDIRECTIONAL_MIN/MAX`、`RESERVED_MIN/MAX`、`CUSTOM_MIN/MAX` | `SystemMin/Max`、`ClientToServerMin/Max`、`ServerToClientMin/Max`、`BidirectionalMin/Max`、`ReservedMin/Max`、`CustomMin/Max` |
| 注册 | `register(int id, Supplier<T>)` | `Register<T>(int id, Func<T>)` |
| 新建实例 | `newInstance(int)`（未注册返回 `null`） | `NewInstance(int)`（未注册返回 `null`） |
| 是否注册 | `contains(int)` | `Contains(int)` |
| 数量 | `size()` | `Size` |

`register` 校验 ID 落区间内且不在预留区间，重复注册抛 `BAD_FORMAT`。

## 六、差分链

一条消息 ID 上的差分链。**五个语言都提供**：Java `PbpDelta` + `PbpDeltaChain` + `PbpDeltaMessage`，TS 同名，Rust `delta` 模块，C# `PbpDelta` + `PbpDeltaChain` + `IPbpDeltaMessage`，Python `PbpDelta` + `PbpDeltaChain` + `PbpDeltaMessage`。

常量：`MAX_CONSECUTIVE = 10`（Rust 为 `MAX_CONSECUTIVE`）。

### PbpDeltaChain

| 概念 | Java | TypeScript | Rust | C# | Python |
|---|---|---|---|---|---|
| 构造 | `new PbpDeltaChain(int messageId, Supplier<T>)` | `new PbpDeltaChain(messageId, factory)` | `PbpDeltaChain::new(u16)` | `new PbpDeltaChain<T>(int, Func<T>)` | `PbpDeltaChain(message_id, factory)` |
| 连续计数 | `consecutive()` | `consecutive()` | `consecutive()` | `Consecutive` | `consecutive()` |
| 复位 | `reset()` | `reset()` | `reset()` | `Reset()` | `reset()` |
| 发送 | `PbpFrame encode(T current, long ts)` | `encode(current, ts)` | `encode(&T, i64) -> Result<PbpFrame>` | `PbpFrame Encode(T, long)` | `encode(current, timestamp_ms)` |
| 接收 | `T decode(byte[] frameBytes)` | `decode(frameBytes)` | `decode(&[u8]) -> Result<T>` | `T Decode(byte[])` | `decode(frame_bytes)` |

**不一致**：Rust 的 `PbpDeltaChain::new` 只收 `message_id`，不传工厂（用 `T: PbpMessage + Default`）；其余语言要传工厂。

### PbpDelta（深拷贝与相异判定）

| 概念 | Java | TypeScript | Rust | C# | Python |
|---|---|---|---|---|---|
| 相异判定 | `differs(Consumer<PbpEncoder>, Consumer<PbpEncoder>)` | `differs(current, previous)` | `differs(A, B)` | `Differs(Action<PbpEncoder>, Action<PbpEncoder>)` | `differs(current, previous)` |
| 深拷贝 | `copy(T, Supplier<T>)` | `copy(message, factory)` | `copy(&T) -> Result<T>` | `Copy<T>(T, Func<T>)` | `copy(message, factory)` |
| 列表拷贝 | `copyList(List<T>, Supplier<T>)` | `copyList(list, factory)` | `copy_list(&[T]) -> Result<Vec<T>>` | `CopyList` | `copy_list(list, factory)` |
| 映射拷贝 | `copyMap(Map<K,V>, Supplier<V>)` | `copyMap(map, factory)` | `copy_map_entries(&[(K,V)]) -> Result` | `CopyMapEntries` | `copy_map_entries(map)` |
| 可选拷贝 | — | — | `copy_option(Option<&T>)` | — | — |

差分消息由生成器额外产出 `encodeDelta` / `applyDelta`（Java 生成类实现 `PbpDeltaMessage<T>`；Rust 是 `encode_delta` / `apply_delta`）。带签名的消息不生成差分方法。

## 七、跨语言等价 API 一览

下面这些是逐语言对应、语义一致的，写跨语言代码可以直接平移：

- 帧常量与 `of` / `signed` / `compressed` / `delta` / `withFlag` / `withSignature` / `signingInput` / `encode` / `parse`；
- 编码器全部写入方法与长度预估静态方法；
- 解码器全部读取方法；
- 异常 code 的九个取值；
- `PbpCodec` 的四个方法（取帧载荷的名字不同）；
- `PbpZstd` 的 `compress` / `decompress`（除 Python）；
- 消息 ID 区间常量与 `PbpRegistry`（Java / C#）。

不一致的集中在：取帧载荷的方法名、`maybeCompress` 的返回形态、`readMessage` 的实例化方式、Rust 的消费式 `Result` 返回、TS 的 `bigint`、会话与密钥协商只有 Java、zstd 与注册表在部分语言缺失。