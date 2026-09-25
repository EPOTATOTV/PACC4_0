package com.potatotv.paccclient.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * PACC 轻量模型容器（{@code .paccm}，PACC Model）编解码，纯 JDK 实现、零解析库依赖。
 *
 * <p>文件结构（全部大端序）：</p>
 * <pre>
 * [4B]  魔数 "PAM1"
 * [2B]  格式版本号（{@link #FORMAT_VERSION}）
 * [1B]  模型类型（0=XGBoost, 1=Autoencoder, 2=LSTM-AE）
 * [4B]  特征维度数
 * [4B]  权重数据长度（字节）
 * [N B] 权重数据（float32 大端序）
 * [32B] 前序全部字节的 SHA-256 校验和
 * </pre>
 *
 * <p>安全约束：{@link #peekHeader} 在分配任何内存之前先校验 {@code featureDim} /
 * {@code weightLength} 的取值区间，并核对文件总长度，避免恶意头部触发 OOM；
 * {@link #read} 校验魔数与 SHA-256 尾部，任一不符即抛 {@link ModelFormatException}。</p>
 */
public final class PaccModelFormat {

    /** 模型类型：梯度提升树。 */
    public static final int TYPE_XGBOOST = 0;
    /** 模型类型：MLP 自编码器。 */
    public static final int TYPE_AUTOENCODER = 1;
    /** 模型类型：LSTM 时序自编码器。 */
    public static final int TYPE_LSTM_AE = 2;

    public static final int MAGIC_LEN = 4;
    /** 头部长度：4 + 2 + 1 + 4 + 4。 */
    public static final int HEADER_LEN = 15;
    public static final int CHECKSUM_LEN = 32;
    public static final int FORMAT_VERSION = 1;

    private static final byte[] MAGIC = {'P', 'A', 'M', '1'};
    /** 权重上限 8MB：远超设计文档 <500KB 的目标，仅用于拒绝恶意超大声明。 */
    private static final int MAX_WEIGHT_BYTES = 8 * 1024 * 1024;
    /** 特征维度上限：保护 peekHeader 与后续数组分配。 */
    private static final int MAX_FEATURE_DIM = 1 << 20;
    private static final int MAX_TOTAL = HEADER_LEN + MAX_WEIGHT_BYTES + CHECKSUM_LEN;

    private PaccModelFormat() {
    }

    /** 模型头（不含权重体）。 */
    public record ModelHeader(int formatVersion, int modelType, int featureDim, int weightLength) {
    }

    /** 解析结果：头部 + 浮点视图 + 原始权重字节（XGBoost 等非浮点布局使用原始字节）。 */
    public record ReadModel(ModelHeader header, float[] weights, byte[] rawWeights) {
    }

    /** 以 float32 数组为权重编码容器。 */
    public static byte[] encode(ModelHeader h, float[] weights) {
        if (weights == null) throw new IllegalArgumentException("权重不可为空");
        return encode(h, toBytes(weights));
    }

    /**
     * 以原始字节为权重编码容器。
     * <p>写入的 {@code weightLength} 一律取实际字节长度（忽略 {@code h.weightLength()}），
     * 避免调用方头部与实际载荷不一致造成的歧义。</p>
     */
    public static byte[] encode(ModelHeader h, byte[] weightBytes) {
        byte[] w = weightBytes == null ? new byte[0] : weightBytes;
        validateHeader(h, w.length);
        ByteBuffer body = ByteBuffer.allocate(HEADER_LEN + w.length).order(ByteOrder.BIG_ENDIAN);
        body.put(MAGIC);
        body.putShort((short) h.formatVersion());
        body.put((byte) h.modelType());
        body.putInt(h.featureDim());
        body.putInt(w.length);
        body.put(w);
        byte[] head = body.array();
        byte[] out = Arrays.copyOf(head, head.length + CHECKSUM_LEN);
        System.arraycopy(sha256(head), 0, out, head.length, CHECKSUM_LEN);
        return out;
    }

    /** 从字节数组读取模型；等价于 {@code read(new ByteArrayInputStream(raw))}。 */
    public static ReadModel read(byte[] raw) throws ModelFormatException {
        return read(new java.io.ByteArrayInputStream(raw == null ? new byte[0] : raw));
    }

    /**
     * 从输入流读取并校验模型：验证魔数、格式版本、长度一致性、SHA-256 尾部。
     *
     * @throws ModelFormatException 魔数错误 / 数据截断 / 长度非法 / 校验和不匹配
     */
    public static ReadModel read(InputStream in) throws ModelFormatException {
        if (in == null) throw new ModelFormatException("模型输入流为空");
        try {
            byte[] all = readAll(in);
            ModelHeader h = peekHeader(all);
            if (h.formatVersion() != FORMAT_VERSION) {
                throw new ModelFormatException("模型格式版本不支持: " + h.formatVersion());
            }
            byte[] expected = sha256(Arrays.copyOf(all, HEADER_LEN + h.weightLength()));
            byte[] actual = Arrays.copyOfRange(all, all.length - CHECKSUM_LEN, all.length);
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new ModelFormatException("模型 SHA-256 校验和不匹配（文件损坏或被篡改）");
            }
            if (h.weightLength() % 4 != 0) {
                throw new ModelFormatException("权重长度非 float32 对齐: " + h.weightLength());
            }
            byte[] rawWeights = Arrays.copyOfRange(all, HEADER_LEN, HEADER_LEN + h.weightLength());
            return new ReadModel(h, toFloats(rawWeights), rawWeights);
        } catch (ModelFormatException e) {
            throw e;
        } catch (IOException e) {
            throw new ModelFormatException("读取模型失败: " + e.getMessage(), e);
        }
    }

    /**
     * 仅解析头部（不做 SHA-256 校验），用于快速判定类型与维度。
     * <p>在分配权重数组之前即拒绝越界声明，是防 OOM 的第一道闸门。</p>
     */
    public static ModelHeader peekHeader(byte[] all) throws ModelFormatException {
        if (all == null || all.length < HEADER_LEN + CHECKSUM_LEN) {
            throw new ModelFormatException("模型文件被截断（长度不足最小容器）");
        }
        for (int i = 0; i < MAGIC_LEN; i++) {
            if (all[i] != MAGIC[i]) throw new ModelFormatException("非法魔数，非 PACC 模型文件");
        }
        ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.BIG_ENDIAN);
        bb.position(MAGIC_LEN);
        int version = bb.getShort() & 0xFFFF;
        int type = bb.get() & 0xFF;
        int dim = bb.getInt();
        int weightLen = bb.getInt();
        if (dim < 0 || dim > MAX_FEATURE_DIM) throw new ModelFormatException("特征维度非法: " + dim);
        if (weightLen < 0 || weightLen > MAX_WEIGHT_BYTES) throw new ModelFormatException("权重长度非法: " + weightLen);
        long expect = (long) HEADER_LEN + weightLen + CHECKSUM_LEN;
        if (all.length != expect) {
            throw new ModelFormatException("模型长度不一致（期望 " + expect + " 实际 " + all.length + "）");
        }
        return new ModelHeader(version, type, dim, weightLen);
    }

    /** 计算 SHA-256 摘要。 */
    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data == null ? new byte[0] : data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256 算法", e);
        }
    }

    /** 计算 SHA-256 小写十六进制串。 */
    public static String sha256Hex(byte[] data) {
        byte[] d = sha256(data);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 大端 float32 字节 → float 数组。 */
    public static float[] toFloats(byte[] data) {
        if (data == null || data.length == 0) return new float[0];
        if (data.length % 4 != 0) throw new IllegalArgumentException("字节长度非 4 的倍数: " + data.length);
        FloatBuffer fb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).asFloatBuffer();
        float[] out = new float[fb.remaining()];
        fb.get(out);
        return out;
    }

    /** float 数组 → 大端 float32 字节。 */
    public static byte[] toBytes(float[] values) {
        if (values == null) return new byte[0];
        ByteBuffer bb = ByteBuffer.allocate(values.length * 4).order(ByteOrder.BIG_ENDIAN);
        bb.asFloatBuffer().put(values);
        return bb.array();
    }

    private static void validateHeader(ModelHeader h, int weightLen) {
        if (h == null) throw new IllegalArgumentException("模型头不可为空");
        if (h.formatVersion() != FORMAT_VERSION) throw new IllegalArgumentException("不支持的格式版本: " + h.formatVersion());
        if (h.modelType() < TYPE_XGBOOST || h.modelType() > TYPE_LSTM_AE) throw new IllegalArgumentException("未知模型类型: " + h.modelType());
        if (h.featureDim() < 0 || h.featureDim() > MAX_FEATURE_DIM) throw new IllegalArgumentException("特征维度非法: " + h.featureDim());
        if (weightLen < 0 || weightLen > MAX_WEIGHT_BYTES) throw new IllegalArgumentException("权重长度非法: " + weightLen);
        if (weightLen % 4 != 0) throw new IllegalArgumentException("权重长度须 float32 对齐: " + weightLen);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        long total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > MAX_TOTAL) throw new ModelFormatException("模型文件过大（超过 " + MAX_TOTAL + " 字节）");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}