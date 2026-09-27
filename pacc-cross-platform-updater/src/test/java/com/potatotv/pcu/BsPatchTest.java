package com.potatotv.pcu;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BsPatch} 对着 python 参考实现生成的金标向量跑。
 *
 * <p>客户端只做应用，所以「能不能解开别人生成的补丁」才是这个类的核心指标；自产补丁
 * 自解只能证明编解码同错，放在 {@link BsDiffTest} 里单独测。向量由
 * {@code tools/pcu-patchgen} 生成，改动生成端逻辑后要重新生成并提交，
 * {@code python -m pcu_patchgen --check} 会在 CI 上抓出漂移。</p>
 */
class BsPatchTest {

    /** surefire 的工作目录是模块根，向量在 src/test/resources 下。 */
    private static final Path VECTOR_DIR = Path.of("src", "test", "resources", "vectors");

    private static byte[] vector(String name) {
        try {
            return Files.readAllBytes(VECTOR_DIR.resolve(name));
        } catch (IOException e) {
            throw new AssertionError("读取金标向量失败：" + name, e);
        }
    }

    private static List<Path> patches() {
        try (Stream<Path> stream = Files.list(VECTOR_DIR)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".patch"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new AssertionError("列出金标向量目录失败：" + VECTOR_DIR, e);
        }
    }

    @Test
    void 每个金标补丁都能还原出新文件() {
        List<Path> files = patches();
        assertFalse(files.isEmpty(), "金标向量目录里没有 .patch 文件：" + VECTOR_DIR);
        for (Path patchFile : files) {
            String fileName = patchFile.getFileName().toString();
            String scenario = fileName.substring(0, fileName.length() - ".patch".length());
            byte[] oldData = vector(scenario + "-old.bin");
            byte[] expected = vector(scenario + "-new.bin");
            byte[] patchData = vector(fileName);
            assertArrayEquals(expected, BsPatch.patch(oldData, patchData),
                    scenario + " 场景应用后与新文件不一致");
        }
    }

    @Test
    void 空旧文件也能插入式还原() {
        byte[] oldData = vector("empty-to-nonempty-old.bin");
        assertArrayEquals(new byte[0], oldData, "该场景的旧文件应为空");
        assertArrayEquals(vector("empty-to-nonempty-new.bin"),
                BsPatch.patch(oldData, vector("empty-to-nonempty.patch")));
    }

    @Test
    void sample场景的新文件摘要与sha256一致() {
        byte[] expected = vector("sample-new.bin");
        String line = new String(vector("sample-new.sha256"), StandardCharsets.UTF_8).trim();
        String digest = line.split("\\s+")[0];
        assertTrue(Sha256.matches(digest, Sha256.hex(expected)),
                "sample 场景的新文件摘要对不上，向量可能被手改过");
    }

    @Test
    void 坏补丁一律抛异常() {
        byte[] valid = vector("sample.patch");
        byte[] oldData = vector("sample-old.bin");

        assertThrows(PcuException.class, () -> BsPatch.patch(oldData, null),
                "null 补丁必须报错");
        assertThrows(PcuException.class, () -> BsPatch.patch(null, valid),
                "null 旧文件必须报错");
        assertThrows(PcuException.class,
                () -> BsPatch.patch(oldData, Arrays.copyOf(valid, 16)),
                "短于 32 字节装不下 BSDIFF40 头");

        byte[] badMagic = valid.clone();
        badMagic[0] = 'X';
        assertThrows(PcuException.class, () -> BsPatch.patch(oldData, badMagic),
                "魔数不对必须报错");

        byte[] negativeCtrl = valid.clone();
        negativeCtrl[8 + 7] |= (byte) 0x80; // off_t 最高位是符号位
        assertThrows(PcuException.class, () -> BsPatch.patch(oldData, negativeCtrl),
                "CTRL 块长度为负必须报错");

        byte[] negativeNew = valid.clone();
        negativeNew[24 + 7] |= (byte) 0x80;
        assertThrows(PcuException.class, () -> BsPatch.patch(oldData, negativeNew),
                "新文件长度为负必须报错");

        byte[] hugeCtrl = valid.clone();
        Arrays.fill(hugeCtrl, 8, 16, (byte) 0xFF); // 63 位量值给满，远超实际可用数据
        assertThrows(PcuException.class, () -> BsPatch.patch(oldData, hugeCtrl),
                "CTRL 长度超出实际数据必须报错");

        byte[] truncated = Arrays.copyOf(valid, valid.length / 2);
        assertThrows(PcuException.class, () -> BsPatch.patch(oldData, truncated),
                "截断的补丁必须报错，不能靠补零凑合");

        byte[] noCtrlTuple = valid.clone();
        // 把新文件长度改大，逼出了 CTRL 三元组不够的情形
        setOffset(noCtrlTuple, 24, oldData.length * 4L);
        assertThrows(PcuException.class, () -> BsPatch.patch(oldData, noCtrlTuple),
                "CTRL 三元组不足必须报错，且不能死循环");

        byte[] shortOld = Arrays.copyOf(oldData, oldData.length / 3);
        assertThrows(PcuException.class, () -> BsPatch.patch(shortOld, valid),
                "旧文件比补丁声明需要的短时必须报错");
    }

    /** 写回一个 off_t：小端 63 位量值 + 最高位符号位。 */
    private static void setOffset(byte[] buf, int off, long value) {
        for (int i = 0; i < 8; i++) {
            buf[off + i] = (byte) (value >>> (8 * i));
        }
    }
}