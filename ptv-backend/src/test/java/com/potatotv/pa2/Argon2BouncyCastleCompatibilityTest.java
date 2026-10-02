package com.potatotv.pa2;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PA2 与 BouncyCastle 的兼容性验证（验收 V01）。
 *
 * <p>现场用 BouncyCastle 的 {@code Argon2BytesGenerator} 生成 100 组随机参数/口令/盐的
 * 向量，逐一比对 PA2 输出，并校验 PA2 能验证由 BC 摘要按 PHC 标准格式组装的字符串。
 * 这锁定了"替换 BouncyCastle 后存量密码哈希仍可验证"这一迁移前提。</p>
 */
class Argon2BouncyCastleCompatibilityTest {

    private static final int VECTORS = 100;

    @Test
    void matchesBouncyCastleAcrossHundredVectors() {
        Random random = new Random(20261002L);
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        for (int i = 0; i < VECTORS; i++) {
            int parallelism = 1 + random.nextInt(2);
            int iterations = 1 + random.nextInt(4);
            int memoryKib = 8 * parallelism * (1 + random.nextInt(8));
            int hashLength = 16 + random.nextInt(49);

            byte[] salt = new byte[8 + random.nextInt(9)];
            random.nextBytes(salt);
            byte[] password = new byte[random.nextInt(33)];
            random.nextBytes(password);

            generator.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withIterations(iterations)
                    .withMemoryAsKB(memoryKib)
                    .withParallelism(parallelism)
                    .withSalt(salt)
                    .build());
            byte[] expected = new byte[hashLength];
            generator.generateBytes(password, expected);

            byte[] actual = new Argon2(iterations, memoryKib, parallelism, hashLength).hash(password, salt);
            assertArrayEquals(expected, actual, "向量 " + i + " 与 BouncyCastle 不一致");
        }
    }

    @Test
    void verifiesPhcStringBuiltFromBouncyCastleDigest() {
        // 生产参数：PA2 必须能验证由 BC 摘要在标准格式下组装的存量哈希
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        byte[] salt = new byte[16];
        new Random(7L).nextBytes(salt);
        byte[] password = "Pacc@Test123".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        generator.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(3)
                .withMemoryAsKB(65536)
                .withParallelism(1)
                .withSalt(salt)
                .build());
        byte[] hash = new byte[32];
        generator.generateBytes(password, hash);

        String encoded = "$argon2id$v=19$m=65536,t=3,p=1$"
                + Base64.getEncoder().withoutPadding().encodeToString(salt) + "$"
                + Base64.getEncoder().withoutPadding().encodeToString(hash);

        Argon2 argon2 = new Argon2(3, 65536, 1, 32);
        assertTrue(argon2.verify(encoded, password));
        assertTrue(argon2.verify(encoded, "Pacc@Test123".toCharArray()));
    }
}