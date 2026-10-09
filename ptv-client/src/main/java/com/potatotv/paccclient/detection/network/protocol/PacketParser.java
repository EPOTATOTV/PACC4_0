package com.potatotv.paccclient.detection.network.protocol;

import java.util.Objects;

/**
 * 协议解析入口（网络代理层 §2.3）：按协议族分派到对应解析器。
 *
 * <p>对外的 {@link #parse(byte[], PacketDirection)} 保证永不抛异常：任何解析异常都折算成
 * {@link ParsedPacket.Unknown}。代理层只需调用它，不需要感知协议差异。</p>
 */
public final class PacketParser {

    private final GameProtocol protocol;
    private final JavaPacketParser javaParser = new JavaPacketParser();
    private final BedrockPacketParser bedrockParser = new BedrockPacketParser();

    public PacketParser(GameProtocol protocol) {
        this.protocol = Objects.requireNonNull(protocol, "protocol");
    }

    /** 解析一帧；协议按构造时指定，失败或长度不足返回 {@link ParsedPacket.Unknown}。 */
    public ParsedPacket parse(byte[] raw, PacketDirection dir) {
        int len = raw == null ? 0 : raw.length;
        if (raw == null || raw.length == 0) {
            return new ParsedPacket.Unknown(-1, dir, len);
        }
        try {
            return switch (protocol) {
                case JAVA -> javaParser.parse(raw, dir);
                case BEDROCK -> bedrockParser.parse(raw, dir);
            };
        } catch (RuntimeException e) {
            return new ParsedPacket.Unknown(-1, dir, len);
        }
    }
}