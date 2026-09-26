package com.potatotv.pbp;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩 SPI 测试。
 *
 * <p>该 SPI 当前未接到帧上（{@code FLAG_COMPRESSED} 一律被拒），但实现既然是能跑的代码，
 * 就得有用例守着——尤其是解压炸弹那条路径，它只在被真正启用后才可能被触发。</p>
 */
class PbpCompressorTest {

    private final PbpCompressor compressor = PbpCompressor.zlib();

    @Test
    void roundTripSmallPayload() {
        byte[] plain = "hello pacc".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(plain, compressor.decompress(compressor.compress(plain), 1024));
    }

    @Test
    void roundTripEmptyPayload() {
        byte[] compressed = compressor.compress(new byte[0]);
        assertArrayEquals(new byte[0], compressor.decompress(compressed, 16));
    }

    @Test
    void repetitivePayloadGetsSmaller() {
        // 压缩只在压得动的时候才值得启用（设计文档 §3.10.1 的 1KB 阈值就建立在这个前提上）
        byte[] plain = new byte[64 * 1024];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) (i % 7);
        }
        byte[] compressed = compressor.compress(plain);
        assertTrue(compressed.length < plain.length, "重复载荷应当压得更小");
        assertArrayEquals(plain, compressor.decompress(compressed, plain.length));
    }

    @Test
    void decompressionBombRejected() {
        byte[] plain = new byte[1 << 20];
        byte[] compressed = compressor.compress(plain);
        assertEquals(PbpException.Code.BAD_LENGTH,
                assertThrows(PbpException.class, () -> compressor.decompress(compressed, 1024)).code());
    }

    @Test
    void truncatedStreamRejected() {
        // 截断不是"压坏了"而是"没给够"：Inflater 只会安静地等更多输入。
        // 这里必须失败，否则半截明文会被下游当成一条完整消息继续用。
        byte[] plain = new byte[64 * 1024];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) (i % 7);
        }
        byte[] compressed = compressor.compress(plain);
        byte[] truncated = Arrays.copyOf(compressed, compressed.length / 2);
        assertEquals(PbpException.Code.BAD_LENGTH,
                assertThrows(PbpException.class, () -> compressor.decompress(truncated, plain.length)).code());
    }

    @Test
    void garbageInputRejected() {
        assertEquals(PbpException.Code.BAD_FORMAT,
                assertThrows(PbpException.class,
                        () -> compressor.decompress(new byte[]{1, 2, 3, 4}, 1024)).code());
    }

    @Test
    void nullAndBadBoundsRejected() {
        assertEquals(PbpException.Code.BAD_FORMAT,
                assertThrows(PbpException.class, () -> compressor.compress(null)).code());
        assertEquals(PbpException.Code.BAD_FORMAT,
                assertThrows(PbpException.class, () -> compressor.decompress(null, 1024)).code());
        assertEquals(PbpException.Code.BAD_FORMAT,
                assertThrows(PbpException.class, () -> compressor.decompress(new byte[]{1}, 0)).code());
    }
}