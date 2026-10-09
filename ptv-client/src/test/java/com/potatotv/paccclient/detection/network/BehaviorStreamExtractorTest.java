package com.potatotv.paccclient.detection.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.potatotv.paccclient.detection.network.protocol.PacketDirection;
import com.potatotv.paccclient.detection.network.protocol.ParsedPacket;

import org.junit.jupiter.api.Test;

/**
 * 行为数据流提取器测试（网络代理层 §2.4）。
 *
 * <p>使用可控时钟（{@link BehaviorStreamExtractor#feed(ParsedPacket, long)}）喂入位移样本：
 * 5 格 / 1s 应得速度 5 格/s；单次 9 格位移应记一次瞬移。</p>
 */
class BehaviorStreamExtractorTest {

    private static final long ONE_SECOND = 1_000_000_000L;

    @Test
    void 两帧位移五格求得速度() {
        BehaviorStreamExtractor ex = new BehaviorStreamExtractor();
        ex.feed(move(0, 0, 0), 0L);
        ex.feed(move(5, 0, 0), ONE_SECOND);

        assertEquals(5.0, ex.currentSpeed(), 1e-6);
        assertEquals(2, ex.packetCount());
    }

    @Test
    void 九格位移记为瞬移() {
        BehaviorStreamExtractor ex = new BehaviorStreamExtractor();
        ex.feed(move(0, 0, 0), 0L);
        ex.feed(move(5, 0, 0), ONE_SECOND);
        ex.feed(move(14, 0, 0), 2 * ONE_SECOND);

        assertTrue(ex.teleportCount() >= 1);
    }

    @Test
    void 重置后计数与指标归零() {
        BehaviorStreamExtractor ex = new BehaviorStreamExtractor();
        ex.feed(move(0, 0, 0), 0L);
        ex.feed(move(5, 0, 0), ONE_SECOND);

        ex.reset();

        assertEquals(0, ex.packetCount());
        assertEquals(0.0, ex.currentSpeed(), 1e-9);
        assertEquals(0, ex.teleportCount());
    }

    private static ParsedPacket.MovePlayer move(double x, double y, double z) {
        return new ParsedPacket.MovePlayer(0x15, PacketDirection.C2S, 35, 0L, x, y, z, 0f, 0f, false);
    }
}