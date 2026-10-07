package com.potatotv.paccclient.detection.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.potatotv.paccclient.detection.network.protocol.JavaPacketParser;
import com.potatotv.paccclient.detection.network.protocol.PacketDirection;
import com.potatotv.paccclient.detection.network.protocol.ParsedPacket;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * Java 版 TCP 帧解析测试（网络代理层 §2.3）。
 *
 * <p>手工构造 {@code [长度 VarInt][包 ID][字段]} 帧，验证位置 / 旋转字段的字节序与顺序；
 * 再构造截断帧，验证解析失败降级为 {@link ParsedPacket.Unknown} 且不抛异常。</p>
 */
class JavaPacketParserTest {

    private static final JavaPacketParser PARSER = new JavaPacketParser();

    @Test
    void 解析位置旋转包的坐标与旋转() {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.writeBytes(doubleBE(100.5));
        payload.writeBytes(doubleBE(64.0));
        payload.writeBytes(doubleBE(-30.25));
        payload.writeBytes(floatBE(90.0f));   // yaw（Minecraft 惯例在前）
        payload.writeBytes(floatBE(45.0f));   // pitch
        payload.write(0x01);                  // onGround
        byte[] frame = frame(0x15, payload.toByteArray());

        ParsedPacket parsed = PARSER.parse(frame, PacketDirection.C2S);

        assertInstanceOf(ParsedPacket.MovePlayer.class, parsed);
        ParsedPacket.MovePlayer mp = (ParsedPacket.MovePlayer) parsed;
        assertEquals(100.5, mp.x(), 1e-9);
        assertEquals(64.0, mp.y(), 1e-9);
        assertEquals(-30.25, mp.z(), 1e-9);
        assertEquals(90.0f, mp.yaw(), 1e-6f);
        assertEquals(45.0f, mp.pitch(), 1e-6f);
        assertTrue(mp.onGround());
        assertEquals(frame.length, mp.rawLength());
    }

    @Test
    void SwingArm映射为Attack() {
        byte[] frame = frame(0x2e, new byte[]{0x00});   // hand
        assertInstanceOf(ParsedPacket.Attack.class, PARSER.parse(frame, PacketDirection.C2S));
    }

    @Test
    void 截断帧返回Unknown不抛异常() {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.writeBytes(doubleBE(1.0));
        payload.writeBytes(doubleBE(2.0));
        payload.writeBytes(doubleBE(3.0));
        payload.writeBytes(floatBE(10.0f));
        payload.writeBytes(floatBE(20.0f));
        payload.write(0x00);
        byte[] frame = frame(0x15, payload.toByteArray());
        byte[] truncated = Arrays.copyOf(frame, 8);

        ParsedPacket parsed = PARSER.parse(truncated, PacketDirection.S2C);

        assertInstanceOf(ParsedPacket.Unknown.class, parsed);
    }

    @Test
    void 随机与空字节一律返回Unknown() {
        assertInstanceOf(ParsedPacket.Unknown.class,
                PARSER.parse(new byte[]{0x7f, 0x02, 0x33}, PacketDirection.C2S));
        assertInstanceOf(ParsedPacket.Unknown.class,
                PARSER.parse(new byte[0], PacketDirection.C2S));
        assertInstanceOf(ParsedPacket.Unknown.class,
                PARSER.parse(null, PacketDirection.C2S));
    }

    private static byte[] frame(int packetId, byte[] payload) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(varInt(packetId));
        body.writeBytes(payload);
        byte[] bodyBytes = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(varInt(bodyBytes.length));
        out.writeBytes(bodyBytes);
        return out.toByteArray();
    }

    private static byte[] varInt(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int v = value;
        do {
            int temp = v & 0x7F;
            v >>>= 7;
            if (v != 0) {
                temp |= 0x80;
            }
            out.write(temp);
        } while (v != 0);
        return out.toByteArray();
    }

    private static byte[] doubleBE(double d) {
        return ByteBuffer.allocate(8).putDouble(d).array();
    }

    private static byte[] floatBE(float f) {
        return ByteBuffer.allocate(4).putFloat(f).array();
    }
}