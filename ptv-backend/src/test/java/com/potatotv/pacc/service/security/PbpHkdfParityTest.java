package com.potatotv.pacc.service.security;

import com.potatotv.pbp.PbpHkdf;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * HKDF 双实现一致性测试。
 *
 * <p>仓库里有两份 HKDF：后端 [Hkdf]（历史实现，服务于根密钥派生子密钥）与协议运行时
 * [PbpHkdf]（零依赖约束下重写，服务于会话密钥）。协议运行时不能反向依赖后端，所以
 * 合并成一份做不到；但两份一旦在派生细节上分叉（比如空 salt 的处理、计数器位置），
 * 表现是"某些派生结果不一致"这种极难定位的线上问题。</p>
 *
 * <p>这里用同输入同输出把两份钉在一起。相异点只有一处、且是有意的：长度为 0 的输出
 * 后端返回空数组，协议运行时按非法参数拒绝。</p>
 */
class PbpHkdfParityTest {

    private static final HexFormat HEX = HexFormat.of();

    private static final byte[] IKM = HEX.parseHex("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");
    private static final byte[] SALT = "pacc-session".getBytes(StandardCharsets.UTF_8);

    /** 两参的 expand 都要 info 字节；后端 derive 收 String，协议运行时收 byte[]。 */
    private static final String INFO = "aes-key";

    @Test
    void extractMatches() {
        assertEquals(
                Hkdf.hex(Hkdf.extract(SALT, IKM)),
                HEX.formatHex(PbpHkdf.extract(SALT, IKM)));
    }

    @Test
    void extractWithEmptySaltMatches() {
        // 空 salt 退化为 32 字节全零，这条最容易两边写得不一样
        assertEquals(
                Hkdf.hex(Hkdf.extract(null, IKM)),
                HEX.formatHex(PbpHkdf.extract(null, IKM)));
        assertEquals(
                Hkdf.hex(Hkdf.extract(new byte[0], IKM)),
                HEX.formatHex(PbpHkdf.extract(new byte[0], IKM)));
    }

    @Test
    void expandMatches() {
        byte[] prk = Hkdf.extract(SALT, IKM);
        byte[] info = INFO.getBytes(StandardCharsets.UTF_8);
        // 35 字节跨两个 SHA-256 块，覆盖 T(1) || T(2) 的串接路径
        assertEquals(
                Hkdf.hex(Hkdf.expand(prk, info, 35)),
                HEX.formatHex(PbpHkdf.expand(prk, info, 35)));
    }

    @Test
    void deriveMatchesForSessionKeyUseCase() {
        // 这是会话密钥协商的真实形态：SALT + info 域隔离 + 32 字节输出
        assertEquals(
                Hkdf.hex(Hkdf.derive(IKM, SALT, INFO, 32)),
                HEX.formatHex(PbpHkdf.derive(IKM, SALT, INFO.getBytes(StandardCharsets.UTF_8), 32)));
    }

    @Test
    void differentInfoDomainsStaySeparatedInBothImplementations() {
        byte[] aes = Hkdf.derive(IKM, SALT, "aes-key", 32);
        byte[] mac = Hkdf.derive(IKM, SALT, "mac-key", 32);
        assertFalse(Hkdf.hex(aes).equals(Hkdf.hex(mac)));
        assertFalse(
                HEX.formatHex(PbpHkdf.derive(IKM, SALT, "aes-key".getBytes(StandardCharsets.UTF_8), 32))
                        .equals(HEX.formatHex(PbpHkdf.derive(IKM, SALT, "mac-key".getBytes(StandardCharsets.UTF_8), 32))));
    }
}