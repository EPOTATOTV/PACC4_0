package com.potatotv.paccclient.detection.network;

import com.potatotv.paccclient.detection.network.protocol.ParsedPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 字节级检查器（网络代理层 §2.6）：协议字段解析不出来时对原始字节做的兜底检查。
 *
 * <p>纯计算、无副作用。覆盖：超大包（> 期望长度 ×3）、单包长度 >2048、尾部填充
 * （尾随 0x00 / 0xFF 超过 16 字节）、坐标越界（|x| 或 |z| >3e7）、pitch 越界（|pitch|>90）。
 * 期望长度用简单映射表，未知包按 512 处理。</p>
 */
public final class ByteLevelInspector {

    /**
     * 一条字节级异常。
     *
     * @param kind  异常类型（large_packet / oversized_packet / trailing_padding /
     *              coordinate_out_of_bounds / pitch_out_of_bounds）
     * @param value 相关数值（长度 / 填充字节数 / 越界坐标 / pitch）
     */
    public record ByteAnomaly(String kind, double value) {
    }

    private static final int UNKNOWN_EXPECTED = 512;
    private static final int LARGE_PACKET_THRESHOLD = 2048;
    private static final int TRAILING_PADDING_THRESHOLD = 16;
    private static final double COORD_LIMIT = 3.0e7;
    private static final double PITCH_LIMIT = 90.0;

    /** 各包 ID 的粗略期望长度（Java/基岩 ID 空间重叠，取宽容值即可）。 */
    private static final Map<Integer, Integer> EXPECTED_SIZE = Map.ofEntries(
            Map.entry(0x14, 27),   // Java PlayerPosition
            Map.entry(0x15, 35),   // Java PlayerPositionRotation
            Map.entry(0x16, 10),   // Java PlayerRotation
            Map.entry(0x17, 2),    // Java PlayerMovement
            Map.entry(0x1a, 12),   // Java PlayerAction
            Map.entry(0x1f, 16),   // Java UseItemOn
            Map.entry(0x2e, 2),    // Java SwingArm
            Map.entry(0x32, 4),    // Java UseItem
            Map.entry(0x0c, 40),   // 基岩 MovePlayer
            Map.entry(0x27, 12),   // 基岩 PlayerInput
            Map.entry(0x29, 40),   // 基岩 PlayerAuthInput
            Map.entry(0x13, 14),   // 基岩 PlayerAction
            Map.entry(0x79, 16));  // 基岩 CorrectPlayerMovePrediction

    /** 检查一帧原始字节与（可空的）解析结果，返回命中的全部异常。 */
    public List<ByteAnomaly> inspect(byte[] raw, ParsedPacket parsed) {
        List<ByteAnomaly> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        int len = raw.length;
        if (len > LARGE_PACKET_THRESHOLD) {
            out.add(new ByteAnomaly("large_packet", len));
        }
        int expected = parsed == null
                ? UNKNOWN_EXPECTED
                : EXPECTED_SIZE.getOrDefault(parsed.packetId(), UNKNOWN_EXPECTED);
        if (len > expected * 3) {
            out.add(new ByteAnomaly("oversized_packet", len));
        }
        int trailing = trailingPadding(raw);
        if (trailing > TRAILING_PADDING_THRESHOLD) {
            out.add(new ByteAnomaly("trailing_padding", trailing));
        }
        if (parsed instanceof ParsedPacket.MovePlayer mp) {
            checkCoords(out, mp.x(), mp.z());
            checkPitch(out, mp.pitch());
        } else if (parsed instanceof ParsedPacket.PlayerAuthInput pi) {
            checkPitch(out, pi.pitch());
        } else if (parsed instanceof ParsedPacket.ServerCorrection sc) {
            checkCoords(out, sc.x(), sc.z());
        }
        return out;
    }

    private static void checkCoords(List<ByteAnomaly> out, double x, double z) {
        double max = Math.max(Math.abs(x), Math.abs(z));
        if (max > COORD_LIMIT) {
            out.add(new ByteAnomaly("coordinate_out_of_bounds", max));
        }
    }

    private static void checkPitch(List<ByteAnomaly> out, double pitch) {
        if (Math.abs(pitch) > PITCH_LIMIT) {
            out.add(new ByteAnomaly("pitch_out_of_bounds", pitch));
        }
    }

    private static int trailingPadding(byte[] raw) {
        int n = 0;
        for (int i = raw.length - 1; i >= 0; i--) {
            byte b = raw[i];
            if (b == 0x00 || (b & 0xFF) == 0xFF) {
                n++;
            } else {
                break;
            }
        }
        return n;
    }
}