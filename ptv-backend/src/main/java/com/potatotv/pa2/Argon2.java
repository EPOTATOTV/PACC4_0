package com.potatotv.pa2;

/**
 * Argon2id（RFC 9106 v1.3）纯 Java 实现，零第三方依赖，仅用 JDK 标准库。
 *
 * <p>替换后端原先用于密码哈希的 BouncyCastle（bcprov-jdk18on，约 5MB）。
 * 相同参数下与 BouncyCastle 的 {@code Argon2BytesGenerator} 输出逐字节一致，
 * 存量密码哈希无需重置（见 Argon2Test 中的兼容性向量）。</p>
 *
 * <p>只实现 Argon2id（y=2）与 1.3 版本；Argon2d/Argon2i 与旧版本不提供。</p>
 */
public final class Argon2 {

    /** Argon2id 类型编号（RFC 9106 §3.1）。 */
    public static final int ARGON2_ID = 2;

    private static final int VERSION = 0x13;
    private static final int SYNC_POINTS = 4;
    private static final int WORDS_PER_BLOCK = 128;
    private static final int BYTES_PER_BLOCK = WORDS_PER_BLOCK * 8;
    private static final int ADDRESSES_IN_BLOCK = 128;

    /** BLAKE2_ROUND_NOMSG 的列轮索引（块内连续 16 字）。 */
    private static final int[][] COLUMN_INDEX = new int[8][];
    /** BLAKE2_ROUND_NOMSG 的行轮索引（块内跨行取 16 字）。 */
    private static final int[][] ROW_INDEX = new int[8][];

    static {
        for (int i = 0; i < 8; i++) {
            int[] col = new int[16];
            for (int j = 0; j < 16; j++) {
                col[j] = 16 * i + j;
            }
            COLUMN_INDEX[i] = col;

            ROW_INDEX[i] = new int[]{
                    2 * i, 2 * i + 1, 2 * i + 16, 2 * i + 17,
                    2 * i + 32, 2 * i + 33, 2 * i + 48, 2 * i + 49,
                    2 * i + 64, 2 * i + 65, 2 * i + 80, 2 * i + 81,
                    2 * i + 96, 2 * i + 97, 2 * i + 112, 2 * i + 113
            };
        }
    }

    private final int iterations;
    private final int memoryKib;
    private final int parallelism;
    private final int hashLength;

    /**
     * @param iterations  迭代轮数 t
     * @param memoryKib   内存成本 m（KB）
     * @param parallelism 并行度 p（lane 数）
     * @param hashLength  输出长度 T（字节）
     */
    public Argon2(int iterations, int memoryKib, int parallelism, int hashLength) {
        if (iterations < 1) {
            throw new IllegalArgumentException("Argon2 迭代轮数至少为 1");
        }
        if (parallelism < 1) {
            throw new IllegalArgumentException("Argon2 并行度至少为 1");
        }
        if (hashLength < 4) {
            throw new IllegalArgumentException("Argon2 输出长度至少为 4 字节");
        }
        // 每 lane 至少要分配到 SYNC_POINTS 个块，否则分段长度为 0
        if (memoryKib < 8L * parallelism) {
            throw new IllegalArgumentException("Argon2 内存成本至少为 8 * 并行度（KB）");
        }
        this.iterations = iterations;
        this.memoryKib = memoryKib;
        this.parallelism = parallelism;
        this.hashLength = hashLength;
    }

    /** 计算 {@code Argon2id(password, salt)}，返回 hashLength 字节摘要。 */
    public byte[] hash(byte[] password, byte[] salt) {
        int lanes = parallelism;
        // m' = 4p * floor(m / 4p)，并对齐到 lane / 分段长度的整数倍
        int memoryBlocks = SYNC_POINTS * lanes * (memoryKib / (SYNC_POINTS * lanes));
        int laneLength = memoryBlocks / lanes;
        int segmentLength = laneLength / SYNC_POINTS;

        long[] memory = new long[memoryBlocks * WORDS_PER_BLOCK];
        fillFirstBlocks(memory, password, salt, memoryBlocks, laneLength);

        for (int pass = 0; pass < iterations; pass++) {
            for (int slice = 0; slice < SYNC_POINTS; slice++) {
                for (int lane = 0; lane < lanes; lane++) {
                    fillSegment(memory, pass, slice, lane, memoryBlocks, laneLength, segmentLength);
                }
            }
        }
        return finalizeHash(memory, laneLength);
    }

    // ---------------- 初始化 ----------------

    private void fillFirstBlocks(long[] memory, byte[] password, byte[] salt,
                                 int memoryBlocks, int laneLength) {
        byte[] h0 = Blake2b.digest(64,
                le32(parallelism), le32(hashLength), le32(memoryKib), le32(iterations),
                le32(VERSION), le32(ARGON2_ID),
                le32(password.length), password,
                le32(salt.length), salt,
                le32(0), le32(0));
        for (int lane = 0; lane < parallelism; lane++) {
            storeBlock(blake2bLong(BYTES_PER_BLOCK, h0, le32(0), le32(lane)),
                    memory, lane * laneLength);
            storeBlock(blake2bLong(BYTES_PER_BLOCK, h0, le32(1), le32(lane)),
                    memory, lane * laneLength + 1);
        }
    }

    // ---------------- 填充与混合 ----------------

    private void fillSegment(long[] memory, int pass, int slice, int lane,
                             int memoryBlocks, int laneLength, int segmentLength) {
        // Argon2id：仅第一轮的前半段（slice 0/1）使用数据无关寻址
        boolean dataIndependent = pass == 0 && slice < SYNC_POINTS / 2;

        // 数据无关寻址的工作区：零块 / 输入块 / 地址块，共 3 块
        long[] addressScratch = null;
        int inputOff = 0;
        int addressOff = 0;
        if (dataIndependent) {
            addressScratch = new long[3 * WORDS_PER_BLOCK];
            inputOff = WORDS_PER_BLOCK;
            addressOff = 2 * WORDS_PER_BLOCK;
            addressScratch[inputOff] = pass;
            addressScratch[inputOff + 1] = lane;
            addressScratch[inputOff + 2] = slice;
            addressScratch[inputOff + 3] = memoryBlocks;
            addressScratch[inputOff + 4] = iterations;
            addressScratch[inputOff + 5] = ARGON2_ID;
        }

        int startIndex = 0;
        if (pass == 0 && slice == 0) {
            // 每个 lane 的前两个块已在初始化阶段生成
            startIndex = 2;
            if (dataIndependent) {
                nextAddresses(addressScratch, inputOff, addressOff);
            }
        }

        int currOffset = lane * laneLength + slice * segmentLength + startIndex;
        int prevOffset = currOffset % laneLength == 0 ? currOffset + laneLength - 1 : currOffset - 1;
        boolean withXor = pass != 0;

        for (int i = startIndex; i < segmentLength; i++, currOffset++, prevOffset++) {
            if (currOffset % laneLength == 1) {
                prevOffset = currOffset - 1;
            }

            long pseudoRand;
            if (dataIndependent) {
                if (i % ADDRESSES_IN_BLOCK == 0) {
                    nextAddresses(addressScratch, inputOff, addressOff);
                }
                pseudoRand = addressScratch[addressOff + i % ADDRESSES_IN_BLOCK];
            } else {
                pseudoRand = memory[prevOffset * WORDS_PER_BLOCK];
            }

            int refLane = (int) ((pseudoRand >>> 32) % parallelism);
            if (pass == 0 && slice == 0) {
                // 第一轮第一段尚不能引用其他 lane
                refLane = lane;
            }

            int refIndex = indexAlpha(pass, slice, i, (int) pseudoRand,
                    refLane == lane, laneLength, segmentLength);
            fillBlock(memory, prevOffset * WORDS_PER_BLOCK,
                    (refLane * laneLength + refIndex) * WORDS_PER_BLOCK,
                    currOffset * WORDS_PER_BLOCK, withXor);
        }
    }

    /** 由输入块生成一整块伪随机地址（数据无关寻址）。 */
    private static void nextAddresses(long[] scratch, int inputOff, int addressOff) {
        scratch[inputOff + 6]++;
        fillBlock(scratch, 0, inputOff, addressOff, false);
        fillBlock(scratch, 0, addressOff, addressOff, false);
    }

    /** RFC 9106 §3.4.1.2 的参考块索引映射。 */
    private static int indexAlpha(int pass, int slice, int index, int pseudoRand,
                                  boolean sameLane, int laneLength, int segmentLength) {
        int referenceAreaSize;
        if (pass == 0) {
            if (slice == 0) {
                referenceAreaSize = index - 1;
            } else if (sameLane) {
                referenceAreaSize = slice * segmentLength + index - 1;
            } else {
                referenceAreaSize = slice * segmentLength + (index == 0 ? -1 : 0);
            }
        } else if (sameLane) {
            referenceAreaSize = laneLength - segmentLength + index - 1;
        } else {
            referenceAreaSize = laneLength - segmentLength + (index == 0 ? -1 : 0);
        }

        long relative = pseudoRand & 0xFFFFFFFFL;
        relative = relative * relative >>> 32;
        relative = referenceAreaSize - 1 - (referenceAreaSize * relative >>> 32);

        int startPosition = 0;
        if (pass != 0) {
            startPosition = slice == SYNC_POINTS - 1 ? 0 : (slice + 1) * segmentLength;
        }
        return (int) ((startPosition + relative) % laneLength);
    }

    /**
     * Argon2 压缩函数 G：{@code next = tmp ^ round(tmp)}，其中
     * {@code tmp = ref ^ prev}（非首轮再异或原 next）。
     */
    private static void fillBlock(long[] memory, int prevOff, int refOff, int nextOff, boolean withXor) {
        long[] mixed = new long[WORDS_PER_BLOCK];
        long[] accumulated = new long[WORDS_PER_BLOCK];
        for (int i = 0; i < WORDS_PER_BLOCK; i++) {
            long x = memory[refOff + i] ^ memory[prevOff + i];
            mixed[i] = x;
            accumulated[i] = x;
        }
        if (withXor) {
            for (int i = 0; i < WORDS_PER_BLOCK; i++) {
                accumulated[i] ^= memory[nextOff + i];
            }
        }
        for (int[] idx : COLUMN_INDEX) {
            round(mixed, idx);
        }
        for (int[] idx : ROW_INDEX) {
            round(mixed, idx);
        }
        for (int i = 0; i < WORDS_PER_BLOCK; i++) {
            memory[nextOff + i] = accumulated[i] ^ mixed[i];
        }
    }

    /** BLAKE2_ROUND_NOMSG：对 16 个字做 8 次 G。 */
    private static void round(long[] v, int[] i) {
        g(v, i[0], i[4], i[8], i[12]);
        g(v, i[1], i[5], i[9], i[13]);
        g(v, i[2], i[6], i[10], i[14]);
        g(v, i[3], i[7], i[11], i[15]);
        g(v, i[0], i[5], i[10], i[15]);
        g(v, i[1], i[6], i[11], i[12]);
        g(v, i[2], i[7], i[8], i[13]);
        g(v, i[3], i[4], i[9], i[14]);
    }

    /** Argon2 的 G：带 64x64->64 低位乘法的 fBlaMka 混合。 */
    private static void g(long[] v, int a, int b, int c, int d) {
        v[a] = blaMka(v[a], v[b]);
        v[d] = Long.rotateRight(v[d] ^ v[a], 32);
        v[c] = blaMka(v[c], v[d]);
        v[b] = Long.rotateRight(v[b] ^ v[c], 24);
        v[a] = blaMka(v[a], v[b]);
        v[d] = Long.rotateRight(v[d] ^ v[a], 16);
        v[c] = blaMka(v[c], v[d]);
        v[b] = Long.rotateRight(v[b] ^ v[c], 63);
    }

    private static long blaMka(long x, long y) {
        return x + y + 2 * (x & 0xFFFFFFFFL) * (y & 0xFFFFFFFFL);
    }

    // ---------------- 输出 ----------------

    private byte[] finalizeHash(long[] memory, int laneLength) {
        long[] last = new long[WORDS_PER_BLOCK];
        int off = (laneLength - 1) * WORDS_PER_BLOCK;
        System.arraycopy(memory, off, last, 0, WORDS_PER_BLOCK);
        for (int lane = 1; lane < parallelism; lane++) {
            off = (lane * laneLength + laneLength - 1) * WORDS_PER_BLOCK;
            for (int i = 0; i < WORDS_PER_BLOCK; i++) {
                last[i] ^= memory[off + i];
            }
        }
        byte[] blockBytes = new byte[BYTES_PER_BLOCK];
        for (int i = 0; i < WORDS_PER_BLOCK; i++) {
            store64(blockBytes, i * 8, last[i]);
        }
        return blake2bLong(hashLength, blockBytes);
    }

    private static void storeBlock(byte[] src, long[] memory, int blockIndex) {
        int base = blockIndex * WORDS_PER_BLOCK;
        for (int i = 0; i < WORDS_PER_BLOCK; i++) {
            memory[base + i] = load64(src, i * 8);
        }
    }

    /**
     * Argon2 的变长哈希 H'（RFC 9106 §3.3）：输出长度 > 64 时按 32 字节步进
     * 链式派生，最后一块补齐剩余长度。
     */
    private static byte[] blake2bLong(int outLen, byte[]... parts) {
        byte[] lengthPrefix = le32(outLen);
        if (outLen <= 64) {
            return Blake2b.digest(outLen, concat(lengthPrefix, parts));
        }
        byte[] out = new byte[outLen];
        byte[] buffer = Blake2b.digest(64, concat(lengthPrefix, parts));
        int pos = 0;
        System.arraycopy(buffer, 0, out, pos, 32);
        pos += 32;
        int toProduce = outLen - 32;
        while (toProduce > 64) {
            buffer = Blake2b.digest(64, buffer);
            System.arraycopy(buffer, 0, out, pos, 32);
            pos += 32;
            toProduce -= 32;
        }
        System.arraycopy(Blake2b.digest(toProduce, buffer), 0, out, pos, toProduce);
        return out;
    }

    // ---------------- 字节工具 ----------------

    private static byte[] le32(int value) {
        return new byte[]{
                (byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)
        };
    }

    private static byte[] concat(byte[] first, byte[]... rest) {
        int total = first.length;
        for (byte[] part : rest) {
            total += part.length;
        }
        byte[] out = new byte[total];
        System.arraycopy(first, 0, out, 0, first.length);
        int pos = first.length;
        for (byte[] part : rest) {
            System.arraycopy(part, 0, out, pos, part.length);
            pos += part.length;
        }
        return out;
    }

    private static void store64(byte[] dst, int off, long value) {
        for (int i = 0; i < 8; i++) {
            dst[off + i] = (byte) (value >>> (8 * i));
        }
    }

    private static long load64(byte[] src, int off) {
        return (src[off] & 0xFFL) | ((src[off + 1] & 0xFFL) << 8) | ((src[off + 2] & 0xFFL) << 16)
                | ((src[off + 3] & 0xFFL) << 24) | ((src[off + 4] & 0xFFL) << 32)
                | ((src[off + 5] & 0xFFL) << 40) | ((src[off + 6] & 0xFFL) << 48)
                | ((src[off + 7] & 0xFFL) << 56);
    }
}