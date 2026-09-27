package com.potatotv.paccclient.detection.federated;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.StringJoiner;

/**
 * DF §4.1.2 端侧联邦向量编解码：与云端 {@code GradientCodec} 字段布局逐位对齐——向量序列化为
 * 逗号分隔的十进制浮点串（{@code Double.toString} 往返无损），并计算其 L2 范数与 SHA-256 摘要。
 *
 * <p>对齐原因：云端以同一布局计算 {@code weights_sha256} / {@code gradient_hash}，端侧用同一算法
 * 复算即可在下载模型时校验摘要、在上报后自证未被篡改；两端实现必须字面一致，故此处不做任何
 * 「更紧凑」的变体。</p>
 */
public final class GradientCodec {

    private GradientCodec() {
    }

    /** 编码为逗号分隔的十进制浮点串。 */
    public static String encode(double[] vector) {
        StringJoiner joiner = new StringJoiner(",");
        for (double v : vector) {
            joiner.add(Double.toString(v));
        }
        return joiner.toString();
    }

    /**
     * 解码逗号分隔的浮点串。
     *
     * @throws IllegalArgumentException 串为空或含非数值字段
     */
    public static double[] decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("梯度向量为空");
        }
        String[] parts = encoded.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("梯度向量第 " + i + " 个分量非法：" + parts[i], e);
            }
        }
        return out;
    }

    /** 向量是否全部为有限值（拒绝 NaN / ±Inf，避免污染聚合）。 */
    public static boolean isFinite(double[] vector) {
        if (vector == null) {
            return false;
        }
        for (double v : vector) {
            if (!Double.isFinite(v)) {
                return false;
            }
        }
        return true;
    }

    /** L2 范数（按最大分量缩放，避免极端量级下的上溢为 Inf）。 */
    public static double l2Norm(double[] vector) {
        if (vector == null || vector.length == 0) {
            return 0.0;
        }
        double maxAbs = 0;
        for (double v : vector) {
            double a = Math.abs(v);
            if (a > maxAbs) {
                maxAbs = a;
            }
        }
        if (maxAbs == 0.0) {
            return 0.0;
        }
        double sum = 0;
        for (double v : vector) {
            double s = v / maxAbs;
            sum += s * s;
        }
        return maxAbs * Math.sqrt(sum);
    }

    /** 编码向量的 SHA-256 十六进制小写摘要（用于去重与审计）。 */
    public static String sha256Hex(double[] vector) {
        return sha256Hex(encode(vector));
    }

    /** 字符串的 SHA-256 十六进制小写摘要。 */
    public static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // JDK 必然内置 SHA-256，走到这里说明运行环境被裁剪
            throw new IllegalStateException("运行环境缺少 SHA-256 实现", e);
        }
    }
}