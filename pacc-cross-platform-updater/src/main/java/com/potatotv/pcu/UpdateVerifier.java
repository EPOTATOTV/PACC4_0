package com.potatotv.pcu;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.logging.Logger;

/**
 * 更新校验（设计文档 §4.4 第 3 步）：SHA-256 防损坏、RSA-2048 签名防篡改、
 * 版本号防降级、平台兼容性校验。
 *
 * <p>签名校验是 fail-closed 的：配置里给了公钥而服务端没下发签名，直接判失败，
 * 不允许「静默降级为只校验 SHA-256」——只校验哈希挡不住能改清单的攻击者。</p>
 */
public final class UpdateVerifier {

    private static final Logger LOG = Logger.getLogger(UpdateVerifier.class.getName());

    /** 签名算法：RSA-2048 + SHA-256，PKCS#1 v1.5。 */
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    private final PcuConfig config;

    public UpdateVerifier(PcuConfig config) {
        this.config = config;
        if (config.signaturePublicKey() == null) {
            // 只说一次，但要说到位：端侧接下来会接受「只有 SHA-256 对得上」的包，
            // 而哈希挡不住能改清单的攻击者。发行环境必须配上 pacc.client.update.public.key。
            LOG.severe("未配置更新包公钥（pcu public key）：本次更新链路只校验 SHA-256，"
                    + "不校验发布方签名，安装包来源无法确认。发行版本必须配置公钥。");
        }
    }

    /** 清单级校验：平台必须与自身一致，且目标版本不能低于当前版本。 */
    public void verifyManifest(UpdateManifest manifest) {
        if (!manifest.hasUpdate()) {
            return;
        }
        if (manifest.platform() != null && !manifest.platform().isBlank()) {
            UpdatePlatform declared = UpdatePlatform.fromWire(manifest.platform());
            if (declared != config.platform()) {
                throw new PcuException("服务端下发的包平台不匹配：期望 " + config.platform().wire()
                        + "，实际 " + declared.wire());
            }
        }
        SemVer latest = SemVer.parse(manifest.latestVersion());
        SemVer current = config.currentSemVer();
        if (latest.compareTo(current) <= 0) {
            throw new PcuException("疑似降级攻击：目标版本 " + latest + " 不高于当前版本 " + current);
        }
    }

    /** 制品级校验：字节数、SHA-256、RSA 签名。 */
    public void verifyArtifact(Path artifact, UpdateManifest manifest) {
        if (manifest.size() > 0) {
            long actual = lengthOf(artifact);
            if (actual != manifest.size()) {
                throw new PcuException("制品长度不符：期望 " + manifest.size() + "，实际 " + actual);
            }
        }
        verifyChecksum(artifact, manifest.checksum());
        verifySignature(artifact, manifest.signature());
    }

    /** SHA-256 校验。 */
    public void verifyChecksum(Path artifact, String expectedChecksum) {
        if (expectedChecksum == null || expectedChecksum.isBlank()) {
            throw new PcuException("清单缺少 checksum，拒绝安装未校验的包");
        }
        String actual = Sha256.hexOfFile(artifact);
        if (!Sha256.matches(expectedChecksum, actual)) {
            throw new PcuException("SHA-256 校验失败：" + artifact.getFileName());
        }
    }

    /** RSA-2048 签名校验；未配置公钥时跳过（此时只能靠 SHA-256，会在日志里明确标注）。 */
    public void verifySignature(Path artifact, String signatureBase64) {
        PublicKey publicKey = config.signaturePublicKey();
        if (publicKey == null) {
            LOG.warning("未配置更新包公钥，本次仅做 SHA-256 校验（生产环境必须配置 PCU 公钥）");
            return;
        }
        if (signatureBase64 == null || signatureBase64.isBlank()) {
            throw new PcuException("清单缺少 signature，配置了公钥时必须校验签名");
        }
        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getDecoder().decode(signatureBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new PcuException("签名不是合法的 Base64", e);
        }
        try {
            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(publicKey);
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = Files.newInputStream(artifact)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    verifier.update(buf, 0, n);
                }
            }
            if (!verifier.verify(signatureBytes)) {
                throw new PcuException("更新包签名校验失败，包可能被篡改");
            }
        } catch (PcuException e) {
            throw e;
        } catch (Exception e) {
            throw new PcuException("签名校验过程异常", e);
        }
    }

    /**
     * 是否必须更新（设计文档 §4.9）：服务端标记 {@code force_update}，
     * 或当前版本低于该版本要求的最低版本（这种情况下也不允许「稍后提醒」）。
     */
    public boolean forceRequired(UpdateManifest manifest) {
        if (manifest.forceUpdate()) {
            return true;
        }
        if (manifest.minAppVersion() == null || manifest.minAppVersion().isBlank()) {
            return false;
        }
        SemVer min = SemVer.tryParse(manifest.minAppVersion());
        if (min == null) {
            LOG.warning(() -> "服务端下发的最低版本号无法解析，忽略：" + manifest.minAppVersion());
            return false;
        }
        return config.currentSemVer().compareTo(min) < 0;
    }

    /** 解析 X.509 SubjectPublicKeyInfo（Base64）为 RSA 公钥，供宿主从配置构造。 */
    public static PublicKey decodePublicKey(String base64X509) {
        if (base64X509 == null || base64X509.isBlank()) {
            return null;
        }
        String pem = base64X509
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(pem);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new PcuException("更新包公钥解析失败", e);
        }
    }

    private static long lengthOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new PcuException("读取制品长度失败：" + path, e);
        }
    }
}