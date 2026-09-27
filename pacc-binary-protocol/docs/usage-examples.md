# PBP 使用示例

这份文档给几段能对得上真实签名的代码：上报检测事件、服务端解帧、验签、大消息压缩、跨语言互操作验证。语言以 Java 为主（它是参考实现），必要处补 TypeScript / Rust，用来说明跨语言怎么写。

约定：HMAC 密钥是密钥文本的 UTF-8 字节，不是解 hex 后的二进制。

## 1. 端侧上报一条检测事件

检测上报用的是 `DetectionReport`（消息 ID `0x0103`，客户端 → 服务端），里面装 `DetectionEvent` 列表和一个可选的 `ApmSnapshot`。

```java
import com.potatotv.pbp.gen.ApmSnapshot;
import com.potatotv.pbp.gen.DetectionEvent;
import com.potatotv.pbp.gen.DetectionReport;

import java.nio.charset.StandardCharsets;

byte[] moduleHash = /* 证据字节，比如模块哈希 */ null;

DetectionEvent event = DetectionEvent.newBuilder()
        .setEventType(2)              // 枚举语义见 mdl/detection.mdl
        .setConfidence(0.75f)
        .setTimestamp(System.currentTimeMillis())
        .putEvidence("module", "pacc-probe".getBytes(StandardCharsets.UTF_8))
        .setDetail("内存段校验不一致")
        .build();

ApmSnapshot apm = ApmSnapshot.newBuilder()
        .setCpuUsage(23.5f)
        .setMemoryUsageKb(450_000)
        .setFps(120.0f)
        .setDetectionLatencyMs(12)
        .setActiveRules(50)
        .putCustomMetrics("gc_ms", 3.5f)
        .build();

DetectionReport report = DetectionReport.newBuilder()
        .setPteid("PT0001")
        .setTimestamp(System.currentTimeMillis())
        .setClientVersion("5.4.0")
        .setPlatform("windows")
        .addEvents(event)
        .setApm(apm)
        .build();

byte[] frame = report.toByteArray();   // 帧头时间戳这一条由上层填，这里走 0
```

`DetectionReport` 不生成差分方法之外的签名方法：它的 `signature` 是**载荷内的一个 `bytes` 字段**，不是帧尾签名。带帧尾签名的消息是 `PaccEnvelope`（见第 3 节）。两者别混。

`addEvents(...)` 一次加一条；要一次给整个列表用 `setEvents(List<DetectionEvent>)`。TypeScript 侧对应 `addEvents(value)` / `setEvents(value)`，C# 是 `AddEvents` / `SetEvents`。

## 2. 服务端解帧

收到整帧字节后先 `parse`，拿到帧头再决定怎么处理载荷。

```java
import com.potatotv.pbp.PbpCodec;
import com.potatotv.pbp.PbpException;
import com.potatotv.pbp.PbpFrame;
import com.potatotv.pbp.gen.DetectionReport;

byte[] raw = /* 从连接里读到的整帧 */;

PbpFrame frame;
try {
    frame = PbpFrame.parse(raw);
} catch (PbpException e) {
    // 按 code 分流：BAD_MAGIC / BAD_VERSION 记计数日志，BAD_LENGTH 记告警
    switch (e.code()) {
        case BAD_MAGIC, BAD_VERSION, TRUNCATED -> log.debug("协议不匹配: {}", e.getMessage());
        default -> securityLog.warn("疑似篡改: code={}", e.code());
    }
    return;
}

if (frame.messageId() == DetectionReport.MESSAGE_ID) {
    // 声明压缩的帧在 payloadOf 里自动解压
    byte[] payload = PbpCodec.payloadOf(frame);
    DetectionReport report = new DetectionReport();
    report.decode(new PbpDecoder(payload));

    for (DetectionEvent ev : report.getEvents()) {
        // ...
    }
}
```

如果想直接按消息类型解，用生成类的 `parseFrom`——它会顺带校验消息 ID 是否相符：

```java
DetectionReport report = DetectionReport.parseFrom(raw); // 消息 ID 不符抛 BAD_FORMAT
```

帧头里的 `sequence` / `sessionId` / `timestampMs` 由上层做重放校验，帧层不判断它们的语义。会话序号用 `PbpSession.acceptSequence` 卡「无符号严格递增」。

## 3. HMAC 签名校验

签名覆盖面是「帧头（`FLAG_SIGNED` 已置位）+ 载荷」，不是只盖载荷。签名和验签必须用同一串待签字节 `signingInput()`。

```java
import com.potatotv.pbp.PbpCrypto;
import com.potatotv.pbp.gen.PaccEnvelope;

import java.nio.charset.StandardCharsets;

String keyText = "000102030405060708090a0b0c0d0e0f";
byte[] key = keyText.getBytes(StandardCharsets.UTF_8);   // 密钥是文本的 UTF-8 字节

// 发送侧：先算签名，再放到帧尾
PaccEnvelope msg = PaccEnvelope.newBuilder()
        .setType("inspect_result")
        .setTsMs(System.currentTimeMillis())
        .setNonce("0123456789abcdef")
        .setSessionId("sess-1")
        .setPteid("PT0001")
        .setPayloadJson("{\"result\":\"clean\"}")
        .setSigVersion(1)
        .build();

byte[] sig = PbpCrypto.hmacSha256(key, msg.signingInput());
byte[] signed = msg.toBuilder().setSignatureBytes(sig).build().toByteArray();

// 接收侧：解析后重建 signingInput，再恒定时间比较
PaccEnvelope received = PaccEnvelope.parseFrom(signed);
boolean ok = PbpCrypto.verifyHmac(key, received.signingInput(), received.signatureBytes());
if (!ok) {
    securityLog.warn("信封签名不匹配 session={} nonce={}", received.getSessionId(), received.getNonce());
    return;
}
```

要点：

- `signingInput()` 内部把帧头按 `FLAG_SIGNED` **已置位**的形态计算，所以签名方和验签方拿到的是同一串字节，不用手动置标志位。
- 若署名的帧同时压缩过，`signingInput()` 覆盖的是**压缩后**的载荷；验签端把字段重新编码一遍就能得到同样的压缩结果，不需要缓存原始压缩载荷。
- `verifyHmac` 用恒定时间比较（`MessageDigest.isEqual`），`expected` 长度不是 32 直接返回 `false`。
- 别把 `keyText` 解成 32 字节二进制再当密钥；那是另一套 key（`WssSessionKeys` 那边用的是 hex 文本的 UTF-8 字节）。`PbpSession.keyHex()` 给的是密钥的 hex 文本，和 HMAC 密钥不是一回事。

TypeScript / C# 的对应写法：

```ts
import { hmacSha256, verifyHmac } from "@potatotv/pbp";
const sig = hmacSha256(key, received.signingInput());
const ok = verifyHmac(key, received.signingInput(), received.signatureBytes());
```

```csharp
byte[] sig = PbpCrypto.HmacSha256(key, received.SigningInput());
bool ok = PbpCrypto.VerifyHmac(key, received.SigningInput(), received.SignatureBytes());
```

## 4. 大消息压缩

压缩是载荷的确定性函数，调用方不用自己判断「要不要压」——`PbpCodec.frameOf` 已经按阈值处理。

```java
import com.potatotv.pbp.PbpCodec;
import com.potatotv.pbp.PbpFrame;
import com.potatotv.pbp.gen.PaccEnvelope;

String big = "A".repeat(3000);   // 超 1KB，会触发压缩尝试

PaccEnvelope msg = PaccEnvelope.newBuilder()
        .setType("inspect_result")
        .setTsMs(1_700_000_000_000L)
        .setNonce("0123456789abcdef")
        .setSessionId("sess-1")
        .setPteid("PT0001")
        .setPayloadJson(big)
        .setSigVersion(1)
        .build();

PbpFrame frame = PbpCodec.frameOf(msg, msg.getTsMs());
System.out.println(frame.compressed());          // true：载荷被压小并置了 FLAG_COMPRESSED
byte[] wire = frame.encode();

// 接收侧：payloadOf 声明压缩就解压
byte[] payload = PbpCodec.payloadOf(PbpFrame.parse(wire));
```

策略两条：载荷 `<= 1024` 字节不压；超过阈值才尝试，**压完比原文小才启用**。所以压不动的载荷线上字节和没压缩时一致。`maybeCompress` 在不启用时返回同一个对象引用，调用方可以用引用相等判断，不必再比长度。

解压上限是 `MAX_PAYLOAD_SIZE`（16 MiB），也是解压炸弹的拦截点。zstd 是受限子集：编码只输出单段帧 + Raw/RLE/压缩块；解码遇到 Huffman literals、字典帧、内容校验和会抛 `UNSUPPORTED`，不静默降级。

Python 运行时不实现 zstd：`maybe_compress` 恒等返回，遇到 `FLAG_COMPRESSED` 的帧解压时抛 `UNSUPPORTED`。要处理压缩帧就得用其它语言。

## 5. 跨语言互操作验证

`test-vectors/interop.txt` 是 Java 参考实现导出的向量，其余四个运行时必须能逐字节复现，才算互操作成立。

### 向量文件格式

每行 `key = hex`，空行与 `#` 注释忽略。所有多字节整数小端，VarInt 为 LEB128。几类关键的 key：

- `secret_hex_text`：HMAC 密钥文本 UTF-8 字节的十六进制；
- `envelope_payload` / `envelope_frame` / `envelope_signed_frame`：信封的载荷与整帧，锚定帧头布局与 HMAC 覆盖面；
- `codec_payload`：`DetectionReport` 的载荷，覆盖列表、map、嵌套消息、枚举、float；
- `zstd_*_compressed`：六组确定性输入（repeat / rle / ramp / noise / blocky / mixed）的压缩帧；
- `big_frame_compressed`：载荷超 1KB 自动压缩的整帧，帧头应带 `FLAG_COMPRESSED`；
- `delta_frame1` / `delta_frame2` / `delta_payload2`：差分链的两条帧与第二条的载荷。

文件里的注释还写了每组 zstd 输入的生成公式，各语言不用提交明文，按同一公式造数据即可。

### 重新导出向量

向量是生成物，不要手改。要改协议行为，就改 `runtime-java` 的 `PbpInteropVectorsTest` 并重新导出一次：

```bash
cd runtime-java
mvn -B test -Dtest=PbpInteropVectorsTest -Dpbp.vectors.dump=../test-vectors/interop.txt
```

不带 `-Dpbp.vectors.dump` 这个测试默认跳过（`Assumptions.assumeTrue`）。

### 各语言跑互操作测试

每个运行时都读同一份 `test-vectors/interop.txt`：

```bash
# Java（参考实现，导出的同时也能自校验）
cd runtime-java && mvn -B test

# TypeScript
cd runtime-ts && npm ci && npm test

# Rust
cd runtime-rust && cargo test

# C#（tests 是独立控制台工程，失败用非 0 退出码）
cd runtime-csharp/tests && dotnet run

# Python（跳过涉及压缩的那一条，见下）
cd runtime-python && python -m unittest discover tests
```

Python 不实现 zstd，`big_frame_compressed` 那条在 Python 测试里显式跳过，理由写在测试文件开头。

### zstd 与 libzstd 对拍

自研 zstd 子集「输出是合法 zstd」这个说法靠 `tools/zstd-crosscheck/crosscheck.py` 验证，双向：

1. 用 Python 的 `zstandard`（内含 libzstd）压一批确定性输入，把帧交给 Java 侧解码，成功解码的参考帧数量必须达到下限；
2. 让 Java 侧导出我们自己压的帧，用 libzstd 解压并逐字节比对原文。

```bash
python -m pip install zstandard
python pacc-binary-protocol/tools/zstd-crosscheck/crosscheck.py --mvn mvn
```

`zstandard` 只作为开发 / CI 工具，不是运行时的依赖。退出码：0 通过，1 校验失败，2 缺少 `zstandard`（本地可接受，CI 视为失败）。Windows 本地 `mvn` 不在 PATH 时，用 `--mvn` 指向 `tools-local` 下的 `mvn.cmd`。