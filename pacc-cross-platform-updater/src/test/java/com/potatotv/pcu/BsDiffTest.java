package com.potatotv.pcu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link BsDiff} 生成端的自洽与交叉校验。
 *
 * <p>生成端在服务端，客户端只按 {@link BsPatch} 应用，所以「python 的 bspatch 能应用我
 * 生成的补丁」是硬指标：只做 Java 自压自解，两个方向同时写错也能一路绿。python 不可用时
 * 用 {@code assumeTrue} 跳过交叉部分，自洽部分照跑。</p>
 */
class BsDiffTest {

    private static final Path VECTOR_DIR = Path.of("src", "test", "resources", "vectors");
    private static final Path TOOLS_DIR = Path.of("tools", "pcu-patchgen");

    /** 让「生成的补丁交给 python 参考实现应用」成为一次真实调用，不走自研的解压端。 */
    private static final String PY_APPLY = """
            import sys
            sys.path.insert(0, sys.argv[3])
            from pcu_patchgen import apply_patch
            old = open(sys.argv[1], 'rb').read()
            patch = open(sys.argv[2], 'rb').read()
            open(sys.argv[4], 'wb').write(apply_patch(old, patch))
            """;

    @TempDir
    Path work;

    private static byte[] vector(String name) {
        try {
            return Files.readAllBytes(VECTOR_DIR.resolve(name));
        } catch (IOException e) {
            throw new AssertionError("读取金标向量失败：" + name, e);
        }
    }

    private static byte[] random(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    /** 往返用的 (旧, 新) 组合，含空文件的四种边界。 */
    private static byte[][][] pairs() {
        byte[] nonEmpty = random(8192, 3L);
        byte[] changed = nonEmpty.clone();
        changed[10] ^= 0x11;
        changed[4000] ^= 0x22;
        return new byte[][][]{
                {new byte[0], new byte[0]},
                {new byte[0], nonEmpty},
                {nonEmpty, new byte[0]},
                {nonEmpty, nonEmpty},
                {nonEmpty, changed},
                {random(4096, 4L), random(4096, 5L)},
                {vector("sample-old.bin"), vector("sample-new.bin")},
        };
    }

    @Test
    void java生成的补丁能被java应用() {
        for (byte[][] pair : pairs()) {
            byte[] patch = BsDiff.diff(pair[0], pair[1]);
            assertArrayEquals(pair[1], BsPatch.patch(pair[0], patch),
                    "往返不一致：旧 " + pair[0].length + " 字节，新 " + pair[1].length + " 字节");
        }
    }

    @Test
    void 对金标向量重新生成补丁也能还原() {
        for (String scenario : new String[]{"identical", "small-change", "middle-insert",
                "tail-append", "totally-different", "empty-to-nonempty", "sample"}) {
            byte[] oldData = vector(scenario + "-old.bin");
            byte[] newData = vector(scenario + "-new.bin");
            assertArrayEquals(newData, BsPatch.patch(oldData, BsDiff.diff(oldData, newData)),
                    scenario + " 场景自产补丁应用后不一致");
        }
    }

    @Test
    void 小改动场景的补丁明显小于全量() {
        byte[] oldData = vector("small-change-old.bin");
        byte[] newData = vector("small-change-new.bin");
        byte[] patch = BsDiff.diff(oldData, newData);
        assertTrue(patch.length * 5 < newData.length * 3,
                "小改动场景补丁应小于新文件的 60%：补丁 " + patch.length
                        + " 字节，新文件 " + newData.length + " 字节");
    }

    @Test
    void 与python参考实现交叉校验() {
        assumeTrue(PythonSupport.available(), "本机没有 python，跳过交叉校验");
        for (byte[][] pair : pairs()) {
            byte[] patch = BsDiff.diff(pair[0], pair[1]);
            assertArrayEquals(pair[1], applyWithPython(pair[0], patch),
                    "python 参考实现应用 Java 的补丁失败：旧 " + pair[0].length
                            + " 字节，新 " + pair[1].length + " 字节");
        }
    }

    /** 把 Java 产出的补丁交给 python 参考实现应用，返回它写出的新文件。 */
    private byte[] applyWithPython(byte[] oldData, byte[] patch) {
        Path oldFile = work.resolve("old.bin");
        Path patchFile = work.resolve("patch.bin");
        Path outFile = work.resolve("out.bin");
        try {
            Files.write(oldFile, oldData);
            Files.write(patchFile, patch);
            Files.deleteIfExists(outFile);
            PythonSupport.runScript(PY_APPLY, oldFile.toString(), patchFile.toString(),
                    TOOLS_DIR.toString(), outFile.toString());
            return Files.readAllBytes(outFile);
        } catch (IOException e) {
            throw new AssertionError("python 交叉校验的临时文件读写失败", e);
        }
    }

    @Test
    void 生成的补丁是标准bsdiff格式() {
        byte[] patch = BsDiff.diff(random(1024, 6L), random(2048, 7L));
        assertTrue(patch.length > 32, "补丁至少要有 32 字节的头");
        assertArrayEquals("BSDIFF40".getBytes(StandardCharsets.US_ASCII),
                java.util.Arrays.copyOf(patch, 8), "补丁魔数必须是 BSDIFF40");
    }
}