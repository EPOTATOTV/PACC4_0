package com.potatotv.pbp;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 载荷压缩 SPI。
 *
 * <p>当前没有启用：{@link PbpFrame} 的 {@code FLAG_COMPRESSED} 位保留但一律拒绝
 * （见 {@code PbpFrame#validateFlags}）。接口先立在这里，是为了让"压缩算法可替换"
 * 这件事有明确的落点，而不是等要用的时候再去改帧层。</p>
 *
 * <p>默认实现用 JDK 的 {@code Deflater}，也就是 zlib。设计文档 §3.10.1 写的是 zstd level 3，
 * 但 JDK 没有 zstd，引入即破坏 PBP 的零依赖承诺（用 maven-enforcer 强制）。
 * 这个偏离需要在文档侧承认，不能靠"先引依赖再说"混过去。</p>
 */
public interface PbpCompressor {

    /** 压缩率阈值：压缩后不比原文小就不值得启用，由调用方比较（设计文档 §3.10.1）。 */
    int MIN_USEFUL_SIZE = 1024;

    byte[] compress(byte[] plain);

    /**
     * 解压。
     *
     * @param maxSize 允许的最大输出长度；解压炸弹靠它拦住，超出直接失败而不是把内存吃干
     */
    byte[] decompress(byte[] compressed, int maxSize);

    /** 默认实现：JDK zlib，压缩级别 3。 */
    static PbpCompressor zlib() {
        return Zlib.INSTANCE;
    }

    /** 基于 JDK {@code Deflater} / {@code Inflater} 的实现，无状态、可共享。 */
    final class Zlib implements PbpCompressor {

        static final Zlib INSTANCE = new Zlib();

        /** 级别 3：设计文档选它是因为压缩速度优先，zlib 与 zstd 在这个级别上压缩率接近。 */
        private static final int LEVEL = 3;
        private static final int CHUNK = 4096;

        private Zlib() {
        }

        @Override
        public byte[] compress(byte[] plain) {
            if (plain == null) {
                throw new PbpException(PbpException.Code.BAD_FORMAT, "待压缩载荷为 null");
            }
            Deflater deflater = new Deflater(LEVEL, false);
            try {
                deflater.setInput(plain);
                deflater.finish();
                ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, plain.length / 2));
                byte[] chunk = new byte[CHUNK];
                while (!deflater.finished()) {
                    int n = deflater.deflate(chunk);
                    if (n == 0) {
                        // finish() 之后 deflate 不该返回 0；真返回了就退出，宁可少输出也不能死循环
                        break;
                    }
                    out.write(chunk, 0, n);
                }
                return out.toByteArray();
            } finally {
                deflater.end();
            }
        }

        @Override
        public byte[] decompress(byte[] compressed, int maxSize) {
            if (compressed == null) {
                throw new PbpException(PbpException.Code.BAD_FORMAT, "待解压载荷为 null");
            }
            if (maxSize <= 0) {
                throw new PbpException(PbpException.Code.BAD_FORMAT, "解压输出上限必须为正: " + maxSize);
            }
            Inflater inflater = new Inflater(false);
            try {
                inflater.setInput(compressed);
                ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxSize, 64 * 1024));
                byte[] chunk = new byte[CHUNK];
                while (!inflater.finished()) {
                    int n = inflater.inflate(chunk);
                    if (n == 0) {
                        // inflate 返回 0 表示"要更多输入"或"要预设字典"，再转一圈还是 0，先退出。
                        break;
                    }
                    if (out.size() + n > maxSize) {
                        throw new PbpException(PbpException.Code.BAD_LENGTH,
                                "解压输出超过上限 " + maxSize + " 字节");
                    }
                    out.write(chunk, 0, n);
                }
                if (!inflater.finished()) {
                    // 输入提前用光（截断）或需要预设字典。静默返回半截明文的下场是：
                    // 下游把它当成一条完整消息解析，报出来的却是格式错，查不到源头。
                    throw new PbpException(PbpException.Code.BAD_LENGTH,
                            inflater.needsDictionary()
                                    ? "压缩数据需要预设字典，当前实现不支持"
                                    : "压缩数据不完整：解压未到达流末尾");
                }
                return out.toByteArray();
            } catch (DataFormatException e) {
                throw new PbpException(PbpException.Code.BAD_FORMAT, "压缩数据格式非法", e);
            } finally {
                inflater.end();
            }
        }
    }
}