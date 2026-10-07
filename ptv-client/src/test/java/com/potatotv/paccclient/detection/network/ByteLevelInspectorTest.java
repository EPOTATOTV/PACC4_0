package com.potatotv.paccclient.detection.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.potatotv.paccclient.detection.network.protocol.PacketDirection;
import com.potatotv.paccclient.detection.network.protocol.ParsedPacket;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 字节级检查器测试（网络代理层 §2.6）：尾部填充与 pitch 越界的检出。
 */
class ByteLevelInspectorTest {

    private static final ByteLevelInspector INSPECTOR = new ByteLevelInspector();

    @Test
    void 尾部填充被检出() {
        byte[] raw = new byte[64];
        for (int i = 0; i < 32; i++) {
            raw[i] = 0x11;
        }
        // 后 32 字节保持 0x00，构成尾随填充
        ParsedPacket parsed = new ParsedPacket.Unknown(-1, PacketDirection.C2S, raw.length);

        List<ByteLevelInspector.ByteAnomaly> anomalies = INSPECTOR.inspect(raw, parsed);

        assertTrue(hasKind(anomalies, "trailing_padding"));
    }

    @Test
    void pitch越界被检出() {
        byte[] raw = new byte[32];
        Arrays.fill(raw, (byte) 0x01);
        ParsedPacket.MovePlayer mp = new ParsedPacket.MovePlayer(
                0x15, PacketDirection.S2C, raw.length, 0L, 1, 2, 3, 120f, 30f, false);

        List<ByteLevelInspector.ByteAnomaly> anomalies = INSPECTOR.inspect(raw, mp);

        assertTrue(hasKind(anomalies, "pitch_out_of_bounds"));
    }

    @Test
    void 坐标越界被检出() {
        byte[] raw = new byte[32];
        Arrays.fill(raw, (byte) 0x01);
        ParsedPacket.MovePlayer mp = new ParsedPacket.MovePlayer(
                0x15, PacketDirection.S2C, raw.length, 0L, 4.0e7, 64, 0, 0f, 0f, false);

        List<ByteLevelInspector.ByteAnomaly> anomalies = INSPECTOR.inspect(raw, mp);

        assertTrue(hasKind(anomalies, "coordinate_out_of_bounds"));
    }

    @Test
    void 正常包无异常() {
        byte[] raw = new byte[35];
        Arrays.fill(raw, (byte) 0x01);
        ParsedPacket.MovePlayer mp = new ParsedPacket.MovePlayer(
                0x15, PacketDirection.S2C, raw.length, 0L, 10, 64, -10, 12f, 34f, true);

        List<ByteLevelInspector.ByteAnomaly> anomalies = INSPECTOR.inspect(raw, mp);

        assertFalse(hasKind(anomalies, "trailing_padding"));
        assertFalse(hasKind(anomalies, "pitch_out_of_bounds"));
        assertFalse(hasKind(anomalies, "coordinate_out_of_bounds"));
    }

    private static boolean hasKind(List<ByteLevelInspector.ByteAnomaly> anomalies, String kind) {
        return anomalies.stream().anyMatch(a -> a.kind().equals(kind));
    }
}