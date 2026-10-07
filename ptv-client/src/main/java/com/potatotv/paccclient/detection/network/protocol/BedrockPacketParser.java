package com.potatotv.paccclient.detection.network.protocol;

/**
 * Minecraft 基岩版 RakNet 帧解析器（网络代理层 §2.3.1）。
 *
 * <p>帧结构：{@code [消息 ID 1 字节][序号 3 字节][游戏层包 ID VarInt][字段…]}；整型 VarInt/VarLong、
 * 浮点小端（LE）。序号用于丢包率统计，由 {@code PaccProxy} 单独取用，本解析器只负责跨过它。</p>
 *
 * <p>覆盖对行为流有意义的包：0x0c MovePlayer、0x29 PlayerAuthInput、0x13 PlayerAction、
 * 0x79 CorrectPlayerMovePrediction；0x27 PlayerInput / 0x21 MobEquipment / 0x1f InventoryTransaction /
 * 0x01 Login 等只做包 ID 识别，返回 {@link ParsedPacket.Unknown}。字段读越界即停止，
 * 无法可靠解析的包降级为 {@link ParsedPacket.Unknown} 或部分字段，绝不抛异常。</p>
 */
public final class BedrockPacketParser {

    private static final ParseFailure FAIL = new ParseFailure();

    /** 解析一个 RakNet 数据帧。 */
    public ParsedPacket parse(byte[] raw, PacketDirection dir) {
        int len = raw == null ? 0 : raw.length;
        if (raw == null || raw.length < 5) {
            return new ParsedPacket.Unknown(-1, dir, len);
        }
        try {
            Cursor c = new Cursor(raw);
            c.readByte();                         // RakNet 消息 ID（0x84 等）
            c.skip(3);                            // 序号（3 字节，丢包统计在 PaccProxy 侧）
            int packetId = c.readVarInt();
            return switch (packetId) {
                case 0x0c -> movePlayer(c, packetId, dir, len);
                case 0x29 -> playerAuthInput(c, packetId, dir, len);
                case 0x13 -> playerAction(c, packetId, dir, len);
                case 0x79 -> serverCorrection(c, packetId, dir, len);
                default -> new ParsedPacket.Unknown(packetId, dir, len);
            };
        } catch (ParseFailure f) {
            return new ParsedPacket.Unknown(-1, dir, len);
        }
    }

    private ParsedPacket movePlayer(Cursor c, int id, PacketDirection dir, int len) {
        long entityId = c.readVarLong();
        float x = c.readFloatLE();
        float y = c.readFloatLE();
        float z = c.readFloatLE();
        float pitch = c.readFloatLE();
        float yaw = c.readFloatLE();
        c.readVarInt();                           // mode
        boolean onGround = c.readByte() != 0;
        return new ParsedPacket.MovePlayer(id, dir, len, entityId, x, y, z, pitch, yaw, onGround);
    }

    private ParsedPacket playerAuthInput(Cursor c, int id, PacketDirection dir, int len) {
        double pitch = c.readFloatLE();
        double yaw = c.readFloatLE();
        c.skip(12);                               // 绝对位置 vec3（float LE ×3）：增量才是本包关注点
        double deltaX = c.readFloatLE();
        double deltaZ = c.readFloatLE();
        c.skip(4);                                // headYaw
        // InputData 标志位；字段不完整时按无标志处理（退化为部分字段，不整包作废）。
        boolean jumping = false;
        boolean sneaking = false;
        if (c.remaining() >= 1) {
            long flags = c.readVarInt() & 0xFFFFFFFFL;
            jumping = (flags & 0x40L) != 0;
            sneaking = (flags & 0x20L) != 0;
        }
        return new ParsedPacket.PlayerAuthInput(id, dir, len, pitch, yaw, deltaX, deltaZ, jumping, sneaking);
    }

    private ParsedPacket playerAction(Cursor c, int id, PacketDirection dir, int len) {
        int actionType = c.readVarInt();
        int bx = c.readVarInt();
        int by = c.readVarInt();
        int bz = c.readVarInt();
        return new ParsedPacket.PlayerAction(id, dir, len, actionType, bx, by, bz);
    }

    private ParsedPacket serverCorrection(Cursor c, int id, PacketDirection dir, int len) {
        float x = c.readFloatLE();
        float y = c.readFloatLE();
        float z = c.readFloatLE();
        return new ParsedPacket.ServerCorrection(id, dir, len, x, y, z);
    }

    /** 解析失败信号（仅内部使用）。 */
    private static final class ParseFailure extends RuntimeException {
        ParseFailure() {
            super(null, null, false, false);
        }
    }

    /** 小端字节游标；越界即抛 {@link ParseFailure}。 */
    private static final class Cursor {

        private final byte[] buf;
        private int pos;

        Cursor(byte[] buf) {
            this.buf = buf;
        }

        int remaining() {
            return buf.length - pos;
        }

        void skip(int n) {
            if (remaining() < n) {
                throw FAIL;
            }
            pos += n;
        }

        int readByte() {
            if (remaining() < 1) {
                throw FAIL;
            }
            return buf[pos++] & 0xFF;
        }

        int readVarInt() {
            int value = 0;
            int shift = 0;
            while (true) {
                if (remaining() < 1) {
                    throw FAIL;
                }
                byte b = buf[pos++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
                shift += 7;
                if (shift >= 32) {
                    throw FAIL;
                }
            }
        }

        long readVarLong() {
            long value = 0L;
            int shift = 0;
            while (true) {
                if (remaining() < 1) {
                    throw FAIL;
                }
                byte b = buf[pos++];
                value |= (b & 0x7FL) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
                shift += 7;
                if (shift >= 64) {
                    throw FAIL;
                }
            }
        }

        float readFloatLE() {
            if (remaining() < 4) {
                throw FAIL;
            }
            int bits = (buf[pos] & 0xFF)
                    | ((buf[pos + 1] & 0xFF) << 8)
                    | ((buf[pos + 2] & 0xFF) << 16)
                    | ((buf[pos + 3] & 0xFF) << 24);
            pos += 4;
            return Float.intBitsToFloat(bits);
        }
    }
}