package com.potatotv.paccclient.detection.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.potatotv.paccclient.detection.network.protocol.BedrockPacketParser;
import com.potatotv.paccclient.detection.network.protocol.PacketDirection;
import com.potatotv.paccclient.detection.network.protocol.ParsedPacket;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

/**
 * 基岩版 RakNet 帧解析测试（网络代理层 §2.3.1）。
 *
 * <p>构造 {@code [消息 ID][序号 3 字节][游戏包 ID][字段]} 帧，验证 0x0c MovePlayer 的
 * entityId 与小端浮点坐标；长度不足的数据报应降级为 {@link ParsedPacket.Unknown}。</p>
 */
class BedrockPacketParserTest {

    private static final BedrockPacketParser PARSER = new BedrockPacketParser();

    @Test
    void 解析MovePlayer字段() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x84);                     // RakNet 消息 ID
        body.writeBytes(new byte[]{0x00, 0x00, 0x01}); // 序号 3 字节
        body.write(0x0c);                     // 游戏层包 ID VarInt
        body.write(0x01);                     // entityId VarLong = 1
        body.writeBytes(floatLE(1.5f));
        body.writeBytes(floatLE(2.5f));
        body.writeBytes(floatLE(3.5f));
        body.writeBytes(floatLE(10.0f));      // pitch
        body.writeBytes(floatLE(20.0f));      // yaw
        body.write(0x00);                     // mode VarInt
        body.write(0x01);                     // onGround

        ParsedPacket parsed = PARSER.parse(body.toByteArray(), PacketDirection.S2C);

        assertInstanceOf(ParsedPacket.MovePlayer.class, parsed);
        ParsedPacket.MovePlayer mp = (ParsedPacket.MovePlayer) parsed;
        assertEquals(1L, mp.entityId());
        assertEquals(1.5, mp.x(), 1e-5);
        assertEquals(2.5, mp.y(), 1e-5);
        assertEquals(3.5, mp.z(), 1e-5);
        assertEquals(10.0f, mp.pitch(), 1e-5f);
        assertEquals(20.0f, mp.yaw(), 1e-5f);
        assertTrue(mp.onGround());
    }

    @Test
    void 长度不足返回Unknown() {
        byte[] raw = {(byte) 0x84, 0x00, 0x00, 0x01, 0x0c, 0x01};
        assertInstanceOf(ParsedPacket.Unknown.class, PARSER.parse(raw, PacketDirection.C2S));
    }

    @Test
    void 极短数据报不抛异常() {
        assertInstanceOf(ParsedPacket.Unknown.class,
                PARSER.parse(new byte[]{0x01}, PacketDirection.C2S));
        assertInstanceOf(ParsedPacket.Unknown.class,
                PARSER.parse(null, PacketDirection.S2C));
    }

    private static byte[] floatLE(float f) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(f).array();
    }
}