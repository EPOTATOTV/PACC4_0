package com.potatotv.pa2;

import java.util.Arrays;

/**
 * BLAKE2b（RFC 7693）纯 Java 实现，无密钥、无盐，摘要长度可变（1..64 字节）。
 *
 * <p>Argon2id 需要 Blake2b 作为底层哈希：H0 用 64 字节摘要，变长哈希 H' 依赖
 * 可变摘要长度。仅实现这两个场景用到的能力，不做流式 API。</p>
 */
final class Blake2b {

    private static final long[] IV = {
            0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL,
            0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
            0x510e527fade682d1L, 0x9b05688c2b3e6c1fL,
            0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L
    };

    /** 12 轮消息字置换表，第 10、11 轮复用第 0、1 轮。 */
    private static final byte[][] SIGMA = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
            {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
            {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
            {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
            {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
            {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
            {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
            {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
            {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
            {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0},
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
            {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3}
    };

    private Blake2b() {
    }

    /**
     * 计算各分段拼接后的 BLAKE2b 摘要。
     *
     * @param outLen 摘要长度（1..64）
     * @param parts  待哈希分段，按顺序拼接
     */
    static byte[] digest(int outLen, byte[]... parts) {
        if (outLen < 1 || outLen > 64) {
            throw new IllegalArgumentException("BLAKE2b 摘要长度需在 1..64 字节");
        }
        long[] h = IV.clone();
        h[0] ^= 0x01010000L ^ outLen;

        long counter = 0;
        byte[] block = new byte[128];
        int fill = 0;
        for (byte[] part : parts) {
            int off = 0;
            while (off < part.length) {
                // 仅当后续还有数据时才压缩当前块，保证最后一块带上 final 标记
                if (fill == 128) {
                    counter += 128;
                    compress(h, block, counter, false);
                    fill = 0;
                }
                int take = Math.min(128 - fill, part.length - off);
                System.arraycopy(part, off, block, fill, take);
                fill += take;
                off += take;
            }
        }
        // 收尾块：剩余字节补零后作为 final 块压缩（空输入也走这里）
        if (fill < 128) {
            Arrays.fill(block, fill, 128, (byte) 0);
        }
        compress(h, block, counter + fill, true);

        byte[] out = new byte[outLen];
        for (int i = 0; i < outLen; i++) {
            out[i] = (byte) (h[i >>> 3] >>> (8 * (i & 7)));
        }
        return out;
    }

    private static void compress(long[] h, byte[] block, long counter, boolean last) {
        long[] v = new long[16];
        System.arraycopy(h, 0, v, 0, 8);
        System.arraycopy(IV, 0, v, 8, 8);
        v[12] ^= counter;
        if (last) {
            v[14] = ~v[14];
        }
        long[] m = new long[16];
        for (int i = 0; i < 16; i++) {
            m[i] = load64(block, i * 8);
        }
        for (byte[] s : SIGMA) {
            g(v, 0, 4, 8, 12, m[s[0]], m[s[1]]);
            g(v, 1, 5, 9, 13, m[s[2]], m[s[3]]);
            g(v, 2, 6, 10, 14, m[s[4]], m[s[5]]);
            g(v, 3, 7, 11, 15, m[s[6]], m[s[7]]);
            g(v, 0, 5, 10, 15, m[s[8]], m[s[9]]);
            g(v, 1, 6, 11, 12, m[s[10]], m[s[11]]);
            g(v, 2, 7, 8, 13, m[s[12]], m[s[13]]);
            g(v, 3, 4, 9, 14, m[s[14]], m[s[15]]);
        }
        for (int i = 0; i < 8; i++) {
            h[i] ^= v[i] ^ v[i + 8];
        }
    }

    private static void g(long[] v, int a, int b, int c, int d, long x, long y) {
        v[a] = v[a] + v[b] + x;
        v[d] = Long.rotateRight(v[d] ^ v[a], 32);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 24);
        v[a] = v[a] + v[b] + y;
        v[d] = Long.rotateRight(v[d] ^ v[a], 16);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 63);
    }

    private static long load64(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24) | ((b[off + 4] & 0xFFL) << 32) | ((b[off + 5] & 0xFFL) << 40)
                | ((b[off + 6] & 0xFFL) << 48) | ((b[off + 7] & 0xFFL) << 56);
    }
}