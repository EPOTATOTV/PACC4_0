package com.potatotv.pcu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link BZip2} 的自洽与交叉校验。
 *
 * <p>自研 bzip2 只有「python 的 bz2 能解开我压的、我能解开 python 压的」才说明格式没写错；
 * 只做自压自解的话，编解码同错也能一路绿。所以两个方向都测；机器上没有 python 时
 * 用 {@code assumeTrue} 跳过交叉部分，自洽部分照跑。</p>
 */
class BZip2Test {

    private static final String PY_COMPRESS = """
            import sys, bz2
            open(sys.argv[2], 'wb').write(bz2.compress(open(sys.argv[1], 'rb').read(), 9))
            """;
    private static final String PY_DECOMPRESS = """
            import sys, bz2
            open(sys.argv[2], 'wb').write(bz2.decompress(open(sys.argv[1], 'rb').read()))
            """;

    @TempDir
    Path work;

    /** 交叉校验用的载荷：覆盖空、单字节、长 run、随机、多块几类。 */
    private static byte[][] payloads() {
        return new byte[][]{
                new byte[0],
                new byte[]{0x00},
                new byte[]{0x5A, 0x5A, 0x5A, 0x5A, 0x5A},
                new byte[3],
                new byte[1000],
                random(4096, 1L),
                random(65536, 2L),
                repeated(200_000),
                sequence(1_000_000),
        };
    }

    private static byte[] random(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static byte[] repeated(int size) {
        byte[] unit = "PACC-PCU-bzip2-往返-0123456789".getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = unit[i % unit.length];
        }
        return data;
    }

    /** 递增序列，避免连续相同字节，RLE1 后符号数仍超过一块（用来触发多块）。 */
    private static byte[] sequence(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i * 131 + (i >>> 8));
        }
        return data;
    }

    @Test
    void 压缩解压往返一致() {
        for (byte[] data : payloads()) {
            byte[] compressed = BZip2.compress(data);
            assertTrue(compressed.length > 0, "压缩结果不应为空");
            assertEquals(0x42, compressed[0] & 0xFF, "BZh 魔数首字节");
            assertEquals(0x39, compressed[3] & 0xFF, "块大小标记应为 9");
            assertArrayEquals(data, BZip2.decompress(compressed),
                    "往返不一致，原始长度 " + data.length);
        }
    }

    @Test
    void 空输入有确定行为() {
        byte[] compressed = BZip2.compress(new byte[0]);
        assertNotEquals(0, compressed.length, "空输入也要产出合法的 bzip2 流");
        assertEquals(0, BZip2.decompress(compressed).length);
        assertEquals(0, BZip2.decompress(new byte[0]).length, "空流按空数组处理");
    }

    @Test
    void 与python的bz2双向互通() {
        assumeTrue(PythonSupport.available(), "本机没有 python，跳过交叉校验");
        for (byte[] data : payloads()) {
            byte[] compressed = BZip2.compress(data);
            assertArrayEquals(data, runPython(PY_DECOMPRESS, compressed),
                    "python 解不开 Java 压的流，原始长度 " + data.length);
            assertArrayEquals(data, BZip2.decompress(runPython(PY_COMPRESS, data)),
                    "Java 解不开 python 压的流，原始长度 " + data.length);
        }
    }

    @Test
    void 坏流一律抛异常() {
        byte[] compressed = BZip2.compress(random(4096, 7L));
        assertThrows(PcuException.class, () -> BZip2.decompress(new byte[]{1, 2, 3}));
        assertThrows(PcuException.class, () -> BZip2.decompress(
                "not a bzip2 stream at all".getBytes(StandardCharsets.UTF_8)));

        byte[] truncated = Arrays.copyOf(compressed, compressed.length / 2);
        assertThrows(PcuException.class, () -> BZip2.decompress(truncated));

        byte[] wrongCrc = compressed.clone();
        wrongCrc[wrongCrc.length - 1] ^= 0x01;
        assertThrows(PcuException.class, () -> BZip2.decompress(wrongCrc));
    }

    /** 把字节交给 python 处理，返回它写出的字节。 */
    private byte[] runPython(String script, byte[] input) {
        Path in = work.resolve("in.bin");
        Path out = work.resolve("out.bin");
        try {
            Files.write(in, input);
            Files.deleteIfExists(out);
            PythonSupport.runScript(script, in.toString(), out.toString());
            return Files.readAllBytes(out);
        } catch (IOException e) {
            throw new AssertionError("python 交叉校验的临时文件读写失败", e);
        }
    }
}