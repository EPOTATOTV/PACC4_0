package com.potatotv.pacc.service.detection.df.federated;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.StringJoiner;

/**
 * DF §4.1.2 联邦向量编解码：把梯度 / 全局参数向量序列化为逗号分隔的十进制浮点串（存 TEXT 列），
 * 并计算其摘要与 L2 范数。
 *
 * <p>不引入 JSON 依赖：向量以 {@code Double.toString} 往返可精确还原（十进制→二进制浮点为无损往返），
 * 因此「持久化后再读取」与内存中的向量逐位一致，聚合结果可复现。</p>
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
        for (double v : vector) {
            if (!Double.isFinite(v)) {
                return false;
            }
        }
        return true;
    }

    /** L2 范数。 */
    public static double l2Norm(double[] vector) {
        double sum = 0;
        for (double v : vector) {
            sum += v * v;
        }
        return Math.sqrt(sum);
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