package com.potatotv.paccclient.detection.network.protocol;

/**
 * 解析后的协议包（网络代理层 §2.3 / §2.4）。
 *
 * <p>sealed 接口，全部实现是本文件内的嵌套 record，覆盖两个协议族里对行为流有意义的字段。
 * 解析器对「看不懂」的包统一产出 {@link Unknown}，绝不抛异常；这样字节级检查
 * （{@link com.potatotv.paccclient.detection.network.ByteLevelInspector}）仍有原始字节可用，
 * 也就是文档里「协议解析失败降级为字节级检查」的落点。</p>
 *
 * <p>字段语义以「能被行为流提取器直接消费」为准，不追求与官方协议逐字段对齐：
 * 位置类坐标统一为 double、旋转统一 float；{@code rawLength} 记录原始帧长度。</p>
 */
public sealed interface ParsedPacket {

    /** 协议层包 ID（Java/基岩各自命名空间；无法解析时为 -1）。 */
    int packetId();

    /** 数据包方向。 */
    PacketDirection direction();

    /** 原始帧长度（字节）。 */
    int rawLength();

    /**
     * 移动 / 位置包（Java 0x14 / 0x15、基岩 0x0c）。
     *
     * <p>{@code pitch}/{@code yaw} 在源包不含旋转字段时记 0；仅含旋转、不含位置的包
     * （Java 0x16）坐标为 0，由提取器按「坐标全零且含旋转」判定为纯旋转采样。</p>
     */
    record MovePlayer(int packetId, PacketDirection direction, int rawLength,
                      long entityId, double x, double y, double z,
                      float pitch, float yaw, boolean onGround) implements ParsedPacket {
    }

    /** 基岩版 PlayerAuthInput（0x29）：携带旋转与移动增量，不含绝对坐标。 */
    record PlayerAuthInput(int packetId, PacketDirection direction, int rawLength,
                           double pitch, double yaw, double deltaX, double deltaZ,
                           boolean jumping, boolean sneaking) implements ParsedPacket {
    }

    /** 玩家动作（Java 0x1a / 基岩 0x13）：破坏方块等动作类型 + 方块坐标。 */
    record PlayerAction(int packetId, PacketDirection direction, int rawLength,
                        int actionType, int blockX, int blockY, int blockZ) implements ParsedPacket {
    }

    /** 攻击类包统称（Java SwingArm 0x2e / 基岩攻击类包）。 */
    record Attack(int packetId, PacketDirection direction, int rawLength) implements ParsedPacket {
    }

    /** 服务器纠正位置（基岩 0x79 CorrectPlayerMovePrediction / Java 服务端纠正类）。 */
    record ServerCorrection(int packetId, PacketDirection direction, int rawLength,
                            double x, double y, double z) implements ParsedPacket {
    }

    /** 放置方块（Java 0x1f UseItemOn / 0x32 UseItem）。 */
    record UseItem(int packetId, PacketDirection direction, int rawLength,
                   int blockX, int blockY, int blockZ) implements ParsedPacket {
    }

    /** 无法解析 / 无行为意义的包（长度不足、加密段、仅识别的包 ID）。 */
    record Unknown(int packetId, PacketDirection direction, int rawLength) implements ParsedPacket {
    }
}