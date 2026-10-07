package com.potatotv.paccclient.detection.network.protocol;

/**
 * Minecraft Java 版 TCP 帧解析器（网络代理层 §2.3）。
 *
 * <p>帧结构：{@code [长度 VarInt][包 ID VarInt][字段…]}，整型与浮点均大端；长度前缀指
 * 「包 ID + 字段」的字节数。位置为 3×double，旋转为 2×float。</p>
 *
 * <p><b>加密流量说明</b>：Java 版登录完成后默认启用 AES 加密（在线模式握手协商），
 * 代理只能在<b>明文阶段</b>（离线模式，或尚未协商加密的登录前流量）解释字段。一旦进入加密段，
 * 每个 TCP 帧仍可按长度前缀切出来，但包 ID 与字段都是密文，本解析器会把它们判成
 * {@link ParsedPacket.Unknown} 计数。这正是「解析失败降级为字节级检查」的落点：
 * 拿不到语义字段时，行为流提取器不产出，改由
 * {@link com.potatotv.paccclient.detection.network.ByteLevelInspector} 对原始字节做长度 /
 * 填充 / 越界检查。所有解析失败一律返回 {@link ParsedPacket.Unknown}，绝不抛异常。</p>
 */
public final class JavaPacketParser {

    /** 解析失败哨兵：无状态、复用同一实例。 */
    private static final ParseFailure FAIL = new ParseFailure();

    /** 解析一个完整 TCP 帧（含长度前缀）。 */
    public ParsedPacket parse(byte[] raw, PacketDirection dir) {
        int len = raw == null ? 0 : raw.length;
        if (raw == null || raw.length == 0) {
            return new ParsedPacket.Unknown(-1, dir, len);
        }
        try {
            Cursor c = new Cursor(raw);
            c.readVarInt();                       // 长度前缀（切帧用，此处跳过）
            int packetId = c.readVarInt();
            return switch (packetId) {
                case 0x14 -> playerPosition(c, packetId, dir, len);
                case 0x15 -> playerPositionRotation(c, packetId, dir, len);
                case 0x16 -> playerRotation(c, packetId, dir, len);
                // 0x17 PlayerMovement 仅含 onGround、无坐标；若当作 MovePlayer 输出会把
                // 零坐标混进移动采样、污染速度统计，故识别但返回 Unknown。
                case 0x17 -> new ParsedPacket.Unknown(packetId, dir, len);
                case 0x1a -> playerAction(c, packetId, dir, len);
                case 0x1f -> useItemOn(c, packetId, dir, len);
                case 0x2e -> {
                    c.readVarInt();               // hand
                    yield new ParsedPacket.Attack(packetId, dir, len);
                }
                case 0x32 -> {
                    c.readVarInt();               // hand
                    c.readVarInt();               // sequence
                    yield new ParsedPacket.UseItem(packetId, dir, len, 0, 0, 0);
                }
                default -> new ParsedPacket.Unknown(packetId, dir, len);
            };
        } catch (ParseFailure f) {
            return new ParsedPacket.Unknown(-1, dir, len);
        }
    }

    private ParsedPacket playerPosition(Cursor c, int id, PacketDirection dir, int len) {
        double x = c.readDoubleBE();
        double y = c.readDoubleBE();
        double z = c.readDoubleBE();
        boolean onGround = c.readBoolean();
        return new ParsedPacket.MovePlayer(id, dir, len, 0L, x, y, z, 0f, 0f, onGround);
    }

    private ParsedPacket playerPositionRotation(Cursor c, int id, PacketDirection dir, int len) {
        double x = c.readDoubleBE();
        double y = c.readDoubleBE();
        double z = c.readDoubleBE();
        float yaw = c.readFloatBE();              // Minecraft 惯例：yaw 在前
        float pitch = c.readFloatBE();
        boolean onGround = c.readBoolean();
        return new ParsedPacket.MovePlayer(id, dir, len, 0L, x, y, z, pitch, yaw, onGround);
    }

    private ParsedPacket playerRotation(Cursor c, int id, PacketDirection dir, int len) {
        float yaw = c.readFloatBE();
        float pitch = c.readFloatBE();
        boolean onGround = c.readBoolean();
        // 仅含旋转、无坐标：坐标留 0，提取器按「坐标全零且旋转非零」判定为纯旋转采样。
        return new ParsedPacket.MovePlayer(id, dir, len, 0L, 0, 0, 0, pitch, yaw, onGround);
    }

    private ParsedPacket playerAction(Cursor c, int id, PacketDirection dir, int len) {
        int actionType = c.readVarInt();
        long packed = c.readLongBE();             // 方块位置按 long 压缩
        int bx = (int) (packed >> 38);
        int by = (int) (packed << 52 >> 52);
        int bz = (int) (packed << 26 >> 38);
        return new ParsedPacket.PlayerAction(id, dir, len, actionType, bx, by, bz);
    }

    private ParsedPacket useItemOn(Cursor c, int id, PacketDirection dir, int len) {
        c.readVarInt();                           // hand
        long packed = c.readLongBE();
        int bx = (int) (packed >> 38);
        int by = (int) (packed << 52 >> 52);
        int bz = (int) (packed << 26 >> 38);
        return new ParsedPacket.UseItem(id, dir, len, bx, by, bz);
    }

    /** 解析失败信号（仅内部使用，public API 永不抛出）。 */
    private static final class ParseFailure extends RuntimeException {
        ParseFailure() {
            super(null, null, false, false);
        }
    }

    /** 大端字节游标；越界即抛 {@link ParseFailure}。 */
    private static final class Cursor {

        private final byte[] buf;
        private int pos;

        Cursor(byte[] buf) {
            this.buf = buf;
        }

        private int remaining() {
            return buf.length - pos;
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

        boolean readBoolean() {
            return readByte() != 0;
        }

        int readByte() {
            if (remaining() < 1) {
                throw FAIL;
            }
            return buf[pos++] & 0xFF;
        }

        long readLongBE() {
            if (remaining() < 8) {
                throw FAIL;
            }
            long bits = 0L;
            for (int i = 0; i < 8; i++) {
                bits = (bits << 8) | (buf[pos++] & 0xFFL);
            }
            return bits;
        }

        double readDoubleBE() {
            return Double.longBitsToDouble(readLongBE());
        }

        float readFloatBE() {
            if (remaining() < 4) {
                throw FAIL;
            }
            int bits = ((buf[pos] & 0xFF) << 24)
                    | ((buf[pos + 1] & 0xFF) << 16)
                    | ((buf[pos + 2] & 0xFF) << 8)
                    | (buf[pos + 3] & 0xFF);
            pos += 4;
            return Float.intBitsToFloat(bits);
        }
    }
}