package com.potatotv.pcu;

import java.util.Arrays;

/**
 * 纯 Java 的 bzip2（BZh 文件格式）编解码，零第三方依赖。
 *
 * <p>为什么自己写：PCU 的差分包是 bsdiff 格式，三个子流按约定都是 bzip2。差分包由服务端
 * 生成、客户端应用，所以压缩和解压两侧都要有；而两端必须与参考实现（libbz2，也就是
 * python 标准库的 {@code bz2}）互通，否则服务端产出的包客户端解不开。核心模块不允许引入
 * 第三方依赖，因此这里照着 bzip2 的文件格式逐位实现。</p>
 *
 * <p>实现范围：块大小 1..9（{@link #compress} 固定用 9，与 libbz2 默认值一致）、多块流、
 * 任意块大小的输入流。随机化块不实现——那是 bzip2 0.9.5 之前的历史产物，
 * 现代实现（含 python）从不产出，遇到就直接报错而不是猜着解。</p>
 *
 * <p>块内链路与 libbz2 一致：RLE1（4 个相同字节 + 计数）→ 循环旋转排序（BWT）→ MTF →
 * RLE2（RUNA/RUNB）→ 分组 Huffman。块 CRC 与流整体 CRC 在解压时逐块核对，
 * 对不上说明数据被改过或版本不兼容，一律抛 {@link PcuException}。</p>
 */
public final class BZip2 {

    /** 与 libbz2 同名的常量，便于和参考实现对照。 */
    private static final int BLOCK_SIZE_100K = 9;
    private static final int N_GROUPS = 6;
    private static final int G_SIZE = 50;
    private static final int N_ITERS = 4;
    private static final int MAX_ALPHA_SIZE = 258;
    private static final int MAX_CODE_LEN = 23;
    private static final int MAX_SELECTORS = 2 + (900000 / G_SIZE);
    private static final int RUNA = 0;
    private static final int RUNB = 1;

    /** RLE1 之后一块最多 900000 个符号；块满判定用 900000-19，与 libbz2 一致。 */
    private static final int NBLOCK_MAX = 100000 * BLOCK_SIZE_100K - 19;
    /** 块满判定后仍可能追加一个完整 run（最多 6 个符号）并在收尾时再刷一个，留足余量。 */
    private static final int BLOCK_CAPACITY = NBLOCK_MAX + 16;

    /**
     * bzip2 用的是 CRC-32/BZIP2，跟 zip 的标准 CRC-32 不是一回事：同为 0x04C11DB7
     * 多项式，但这里是 MSB 优先、输入输出都不反射（标准 CRC-32 反射）。
     * 所以不能用 {@code java.util.zip.CRC32}——拿它算出来的值与 libbz2 对不上，
     * 自己解自己的流能过，交叉校验就露馅。
     */
    private static final int CRC_INIT = 0xFFFFFFFF;
    private static final int[] CRC_TABLE = buildCrcTable();

    private BZip2() {
    }

    private static int[] buildCrcTable() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int c = i << 24;
            for (int k = 0; k < 8; k++) {
                c = (c & 0x80000000) != 0 ? (c << 1) ^ 0x04C11DB7 : c << 1;
            }
            table[i] = c;
        }
        return table;
    }

    private static int updateCrc(int crc, int value) {
        return (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ value) & 0xFF];
    }

    /** 收尾：取反并按无符号 32 位返回。 */
    private static long crcFinal(int crc) {
        return (crc ^ 0xFFFFFFFFL) & 0xFFFFFFFFL;
    }

    // ---------------------------------------------------------------- 压缩

    /** 压缩成标准 bzip2 流（块大小 9）。 */
    public static byte[] compress(byte[] data) {
        if (data == null) {
            throw new PcuException("bzip2 压缩输入为 null");
        }
        Encoder enc = new Encoder();
        enc.out.writeBits(8, 0x42);
        enc.out.writeBits(8, 0x5A);
        enc.out.writeBits(8, 0x68);
        enc.out.writeBits(8, 0x30 + BLOCK_SIZE_100K);

        for (byte value : data) {
            if (enc.blockFull()) {
                enc.emitBlock(false);
                enc.resetBlock();
            }
            enc.feed(value & 0xFF);
        }
        if (enc.stateInCh < 256) {
            enc.addPairToBlock();
        }
        enc.emitBlock(true);
        return enc.out.finish();
    }

    private static final class Encoder {

        final BitWriter out = new BitWriter();
        final byte[] block = new byte[BLOCK_CAPACITY];
        final boolean[] inUse = new boolean[256];
        final int[] unseqToSeq = new int[256];
        final int[] mtfv = new int[BLOCK_CAPACITY];
        final int[] mtfFreq = new int[MAX_ALPHA_SIZE];

        long combinedCrc;
        int crc = CRC_INIT;
        int nblock;
        int nInUse;
        int alphaSize;
        int eob;
        int nMTF;
        int selectorCount;

        /** 待定 run：256 表示当前没有待定字节（对应 libbz2 的 state_in_ch）。 */
        int stateInCh = 256;
        int stateInLen;

        void addPairToBlock() {
            int ch = stateInCh;
            for (int i = 0; i < stateInLen; i++) {
                crc = updateCrc(crc, ch);
            }
            inUse[ch] = true;
            if (stateInLen <= 3) {
                for (int i = 0; i < stateInLen; i++) {
                    block[nblock++] = (byte) ch;
                }
            } else {
                // RLE1 的长 run：4 个相同字节 + 一个 (长度-4) 计数。
                // 计数本身也是块内符号，同样计入 inUse。
                inUse[stateInLen - 4] = true;
                block[nblock++] = (byte) ch;
                block[nblock++] = (byte) ch;
                block[nblock++] = (byte) ch;
                block[nblock++] = (byte) ch;
                block[nblock++] = (byte) (stateInLen - 4);
            }
            stateInCh = 256;
            stateInLen = 0;
        }

        void feed(int ch) {
            if (ch != stateInCh || stateInLen == 255) {
                if (stateInCh < 256) {
                    addPairToBlock();
                }
                stateInCh = ch;
                stateInLen = 1;
            } else {
                stateInLen++;
            }
        }

        /** 块满判定点：喂入下一个字节之前检查，与 libbz2 的位置一致。 */
        boolean blockFull() {
            return nblock >= NBLOCK_MAX;
        }

        void resetBlock() {
            nblock = 0;
            crc = CRC_INIT;
            Arrays.fill(inUse, false);
        }

        /** 写一个块；{@code isLast} 时补流尾。块内无符号则只写流尾，与 libbz2 相同。 */
        void emitBlock(boolean isLast) {
            if (nblock > 0) {
                long blockCrc = crcFinal(crc);
                combinedCrc = ((combinedCrc << 1) | (combinedCrc >>> 31)) & 0xFFFFFFFFL;
                combinedCrc ^= blockCrc;

                out.writeBits(8, 0x31);
                out.writeBits(8, 0x41);
                out.writeBits(8, 0x59);
                out.writeBits(8, 0x26);
                out.writeBits(8, 0x53);
                out.writeBits(8, 0x59);
                out.writeBits(32, blockCrc);
                out.writeBits(1, 0); // 现代 bzip2 不再随机化

                int[] sa = sortRotations(block, nblock);
                out.writeBits(24, origPtrOf(sa));
                generateMtfValues(sa);
                sendMtfValues();
            }
            if (isLast) {
                out.writeBits(8, 0x17);
                out.writeBits(8, 0x72);
                out.writeBits(8, 0x45);
                out.writeBits(8, 0x38);
                out.writeBits(8, 0x50);
                out.writeBits(8, 0x90);
                out.writeBits(32, combinedCrc);
            }
        }

        /** MTF + RLE2，产物在 {@link #mtfv}，与 libbz2 的 generateMTFValues 等价。 */
        void generateMtfValues(int[] ptr) {
            nInUse = 0;
            for (int i = 0; i < 256; i++) {
                if (inUse[i]) {
                    unseqToSeq[i] = nInUse++;
                }
            }
            eob = nInUse + 1;
            alphaSize = nInUse + 2;
            Arrays.fill(mtfFreq, 0);

            byte[] yy = new byte[nInUse];
            for (int i = 0; i < nInUse; i++) {
                yy[i] = (byte) i;
            }

            int wr = 0;
            int zPend = 0;
            for (int i = 0; i < nblock; i++) {
                int j = ptr[i] - 1;
                if (j < 0) {
                    j += nblock;
                }
                int llI = unseqToSeq[block[j] & 0xFF];
                if ((yy[0] & 0xFF) == llI) {
                    zPend++;
                    continue;
                }
                if (zPend > 0) {
                    wr = flushZeroRun(wr, zPend);
                    zPend = 0;
                }
                // MTF：把 llI 挪到队首，它的原下标就是 MTF 值。
                int jj = 1;
                while ((yy[jj] & 0xFF) != llI) {
                    jj++;
                }
                System.arraycopy(yy, 0, yy, 1, jj);
                yy[0] = (byte) llI;
                mtfv[wr] = jj + 1;
                mtfFreq[jj + 1]++;
                wr++;
            }
            if (zPend > 0) {
                wr = flushZeroRun(wr, zPend);
            }
            mtfv[wr] = eob;
            mtfFreq[eob]++;
            wr++;
            nMTF = wr;
        }

        /** RLE2：把 MTF 值 0 连续出现 z 次编码成 RUNA/RUNB 序列。 */
        private int flushZeroRun(int wr, int z) {
            int t = z - 1;
            while (true) {
                if ((t & 1) != 0) {
                    mtfv[wr] = RUNB;
                    mtfFreq[RUNB]++;
                } else {
                    mtfv[wr] = RUNA;
                    mtfFreq[RUNA]++;
                }
                wr++;
                if (t < 2) {
                    break;
                }
                t = (t - 2) / 2;
            }
            return wr;
        }

        /** 分组 Huffman 编码并写出映射表 / 选择子 / 码长表 / 数据，对应 sendMTFValues。 */
        void sendMtfValues() {
            int[][] len = new int[N_GROUPS][MAX_ALPHA_SIZE];
            int[][] code = new int[N_GROUPS][MAX_ALPHA_SIZE];
            int[][] rfreq = new int[N_GROUPS][MAX_ALPHA_SIZE];
            for (int t = 0; t < N_GROUPS; t++) {
                Arrays.fill(len[t], 15);
            }

            int nGroups;
            if (nMTF < 200) {
                nGroups = 2;
            } else if (nMTF < 600) {
                nGroups = 3;
            } else if (nMTF < 1200) {
                nGroups = 4;
            } else if (nMTF < 2400) {
                nGroups = 5;
            } else {
                nGroups = 6;
            }

            // 初始分组：按频率把字母表切成 nGroups 段，段内给短码、段外给长码。
            int nPart = nGroups;
            int remF = nMTF;
            int gs = 0;
            while (nPart > 0) {
                int tFreq = remF / nPart;
                int ge = gs - 1;
                int aFreq = 0;
                while (aFreq < tFreq && ge < alphaSize - 1) {
                    ge++;
                    aFreq += mtfFreq[ge];
                }
                if (ge > gs && nPart != nGroups && nPart != 1 && ((nGroups - nPart) % 2 == 1)) {
                    aFreq -= mtfFreq[ge];
                    ge--;
                }
                for (int v = 0; v < alphaSize; v++) {
                    len[nPart - 1][v] = (v >= gs && v <= ge) ? 0 : 15;
                }
                nPart--;
                gs = ge + 1;
                remF -= aFreq;
            }

            int[] selector = new int[MAX_SELECTORS];
            for (int iter = 0; iter < N_ITERS; iter++) {
                for (int t = 0; t < nGroups; t++) {
                    Arrays.fill(rfreq[t], 0, alphaSize, 0);
                }
                int nSelectors = 0;
                gs = 0;
                while (gs < nMTF) {
                    int ge = Math.min(gs + G_SIZE - 1, nMTF - 1);
                    int bc = Integer.MAX_VALUE;
                    int bt = -1;
                    for (int t = 0; t < nGroups; t++) {
                        int cost = 0;
                        for (int i = gs; i <= ge; i++) {
                            cost += len[t][mtfv[i]];
                        }
                        if (cost < bc) {
                            bc = cost;
                            bt = t;
                        }
                    }
                    if (nSelectors >= MAX_SELECTORS) {
                        throw new PcuException("bzip2 块的选择子数量超出上限");
                    }
                    selector[nSelectors] = bt;
                    nSelectors++;
                    for (int i = gs; i <= ge; i++) {
                        rfreq[bt][mtfv[i]]++;
                    }
                    gs = ge + 1;
                }
                for (int t = 0; t < nGroups; t++) {
                    hbMakeCodeLengths(len[t], rfreq[t], alphaSize, 17);
                }
                selectorCount = nSelectors;
            }

            int[] selectorMtf = new int[selectorCount];
            int[] pos = new int[N_GROUPS];
            for (int i = 0; i < nGroups; i++) {
                pos[i] = i;
            }
            for (int i = 0; i < selectorCount; i++) {
                int llI = selector[i];
                int j = 0;
                int tmp = pos[0];
                while (llI != tmp) {
                    j++;
                    int tmp2 = tmp;
                    tmp = pos[j];
                    pos[j] = tmp2;
                }
                pos[0] = tmp;
                selectorMtf[i] = j;
            }

            for (int t = 0; t < nGroups; t++) {
                int minLen = 32;
                int maxLen = 0;
                for (int i = 0; i < alphaSize; i++) {
                    if (len[t][i] > maxLen) {
                        maxLen = len[t][i];
                    }
                    if (len[t][i] < minLen) {
                        minLen = len[t][i];
                    }
                }
                if (maxLen > 20 || minLen < 1) {
                    throw new PcuException("bzip2 码长表非法：min=" + minLen + " max=" + maxLen);
                }
                hbAssignCodes(code[t], len[t], minLen, maxLen, alphaSize);
            }

            // 映射表：inUse16 位图 + 每个置位的 16 字节段。
            boolean[] inUse16 = new boolean[16];
            for (int i = 0; i < 16; i++) {
                for (int j = 0; j < 16; j++) {
                    if (inUse[i * 16 + j]) {
                        inUse16[i] = true;
                        break;
                    }
                }
            }
            for (int i = 0; i < 16; i++) {
                out.writeBits(1, inUse16[i] ? 1 : 0);
            }
            for (int i = 0; i < 16; i++) {
                if (inUse16[i]) {
                    for (int j = 0; j < 16; j++) {
                        out.writeBits(1, inUse[i * 16 + j] ? 1 : 0);
                    }
                }
            }

            out.writeBits(3, nGroups);
            out.writeBits(15, selectorCount);
            for (int i = 0; i < selectorCount; i++) {
                for (int j = 0; j < selectorMtf[i]; j++) {
                    out.writeBits(1, 1);
                }
                out.writeBits(1, 0);
            }

            for (int t = 0; t < nGroups; t++) {
                int curr = len[t][0];
                out.writeBits(5, curr);
                for (int i = 0; i < alphaSize; i++) {
                    while (curr < len[t][i]) {
                        out.writeBits(2, 2);
                        curr++;
                    }
                    while (curr > len[t][i]) {
                        out.writeBits(2, 3);
                        curr--;
                    }
                    out.writeBits(1, 0);
                }
            }

            int selCtr = 0;
            gs = 0;
            while (gs < nMTF) {
                int ge = Math.min(gs + G_SIZE - 1, nMTF - 1);
                int sel = selector[selCtr];
                for (int i = gs; i <= ge; i++) {
                    int sym = mtfv[i];
                    out.writeBits(len[sel][sym], code[sel][sym]);
                }
                gs = ge + 1;
                selCtr++;
            }
            if (selCtr != selectorCount) {
                throw new PcuException("bzip2 选择子与实际分组数不一致");
            }
        }
    }

    // ------------------------------------------------------------ 旋转排序

    /**
     * 对 {@code block[0..n)} 做循环旋转排序，返回排序后的起始下标（即 libbz2 的 ptr[]）。
     *
     * <p>用倍增 + 基数排序，O(n log n)。这里不能用普通后缀排序顶替：后缀序与旋转序只在
     * 周期串上一致，像 {@code "baab"} 这种就会给出不同顺序，而解码端按旋转序做逆变换，
     * 顺序错了整块都成乱码。</p>
     */
    private static int[] sortRotations(byte[] block, int n) {
        int[] rank = new int[n];
        int[] sa = new int[n];
        int[] tmp = new int[n];
        int[] newRank = new int[n];
        int[] cnt = new int[Math.max(n, 256) + 1];
        for (int i = 0; i < n; i++) {
            rank[i] = block[i] & 0xFF;
            sa[i] = i;
        }

        int classes = 256;
        for (int k = 1; k < n; k <<= 1) {
            // LSD 基数排序：先按第二关键字排，再按第一关键字稳定排。
            countSort(tmp, sa, rank, k, n, classes, cnt);
            countSort(sa, tmp, rank, 0, n, classes, cnt);
            int newClasses = 1;
            newRank[sa[0]] = 0;
            for (int i = 1; i < n; i++) {
                int prev = sa[i - 1];
                int cur = sa[i];
                if (rank[prev] != rank[cur] || rank[(prev + k) % n] != rank[(cur + k) % n]) {
                    newClasses++;
                }
                newRank[cur] = newClasses - 1;
            }
            int[] swap = rank;
            rank = newRank;
            newRank = swap;
            classes = newClasses;
            if (classes >= n) {
                break;
            }
        }
        return sa;
    }

    /** 稳定计数排序：{@code dst} 是 {@code src} 按 {@code rank[(i+k)%n]} 排序的结果。 */
    private static void countSort(int[] dst, int[] src, int[] rank, int k, int n,
                                  int classes, int[] cnt) {
        Arrays.fill(cnt, 0, classes, 0);
        for (int i = 0; i < n; i++) {
            cnt[rank[(src[i] + k) % n]]++;
        }
        int sum = 0;
        for (int i = 0; i < classes; i++) {
            int c = cnt[i];
            cnt[i] = sum;
            sum += c;
        }
        for (int i = 0; i < n; i++) {
            int key = rank[(src[i] + k) % n];
            dst[cnt[key]++] = src[i];
        }
    }

    private static int origPtrOf(int[] sa) {
        for (int i = 0; i < sa.length; i++) {
            if (sa[i] == 0) {
                return i;
            }
        }
        throw new PcuException("bzip2 旋转排序结果里找不到原串");
    }

    // ------------------------------------------------ Huffman 码长（按 libbz2 移植）

    /** 与 libbz2 的 BZ2_hbMakeCodeLengths 等价：限长 Huffman 码长。 */
    private static void hbMakeCodeLengths(int[] len, int[] freq, int alphaSize, int maxLen) {
        int[] heap = new int[MAX_ALPHA_SIZE + 2];
        int[] weight = new int[MAX_ALPHA_SIZE * 2];
        int[] parent = new int[MAX_ALPHA_SIZE * 2];

        for (int i = 0; i < alphaSize; i++) {
            weight[i + 1] = (freq[i] == 0 ? 1 : freq[i]) << 8;
        }

        while (true) {
            int nNodes = alphaSize;
            int nHeap = 0;

            heap[0] = 0;
            weight[0] = 0;
            parent[0] = -2;

            for (int i = 1; i <= alphaSize; i++) {
                parent[i] = -1;
                nHeap++;
                heap[nHeap] = i;
                upHeap(heap, weight, nHeap);
            }

            while (nHeap > 1) {
                int n1 = heap[1];
                heap[1] = heap[nHeap];
                nHeap--;
                downHeap(heap, weight, nHeap);
                int n2 = heap[1];
                heap[1] = heap[nHeap];
                nHeap--;
                downHeap(heap, weight, nHeap);

                nNodes++;
                parent[n1] = nNodes;
                parent[n2] = nNodes;
                weight[nNodes] = addWeights(weight[n1], weight[n2]);
                parent[nNodes] = -1;
                nHeap++;
                heap[nHeap] = nNodes;
                upHeap(heap, weight, nHeap);
            }

            boolean tooLong = false;
            for (int i = 1; i <= alphaSize; i++) {
                int j = 0;
                int k = i;
                while (parent[k] >= 0) {
                    k = parent[k];
                    j++;
                }
                len[i - 1] = j;
                if (j > maxLen) {
                    tooLong = true;
                }
            }
            if (!tooLong) {
                return;
            }

            // 超长时把频率整体压平重算，libbz2 的做法。
            for (int i = 1; i <= alphaSize; i++) {
                int j = weight[i] >> 8;
                j = 1 + (j / 2);
                weight[i] = j << 8;
            }
        }
    }

    private static int addWeights(int a, int b) {
        int weight = (a & 0xFFFFFF00) + (b & 0xFFFFFF00);
        int depth = 1 + Math.max(a & 0xFF, b & 0xFF);
        return weight | depth;
    }

    private static void upHeap(int[] heap, int[] weight, int z) {
        int tmp = heap[z];
        while (weight[tmp] < weight[heap[z >> 1]]) {
            heap[z] = heap[z >> 1];
            z >>= 1;
        }
        heap[z] = tmp;
    }

    private static void downHeap(int[] heap, int[] weight, int nHeap) {
        int z = 1;
        int tmp = heap[z];
        while (true) {
            int yy = z << 1;
            if (yy > nHeap) {
                break;
            }
            if (yy < nHeap && weight[heap[yy + 1]] < weight[heap[yy]]) {
                yy++;
            }
            if (weight[tmp] < weight[heap[yy]]) {
                break;
            }
            heap[z] = heap[yy];
            z = yy;
        }
        heap[z] = tmp;
    }

    private static void hbAssignCodes(int[] code, int[] length, int minLen, int maxLen,
                                      int alphaSize) {
        int vec = 0;
        for (int n = minLen; n <= maxLen; n++) {
            for (int i = 0; i < alphaSize; i++) {
                if (length[i] == n) {
                    code[i] = vec;
                    vec++;
                }
            }
            vec <<= 1;
        }
    }

    private static void hbCreateDecodeTables(int[] limit, int[] base, int[] perm, int[] length,
                                             int minLen, int maxLen, int alphaSize) {
        int pp = 0;
        for (int i = minLen; i <= maxLen; i++) {
            for (int j = 0; j < alphaSize; j++) {
                if (length[j] == i) {
                    perm[pp++] = j;
                }
            }
        }
        for (int i = 0; i < MAX_CODE_LEN; i++) {
            base[i] = 0;
        }
        for (int i = 0; i < alphaSize; i++) {
            base[length[i] + 1]++;
        }
        for (int i = 1; i < MAX_CODE_LEN; i++) {
            base[i] += base[i - 1];
        }
        for (int i = 0; i < MAX_CODE_LEN; i++) {
            limit[i] = 0;
        }
        int vec = 0;
        for (int i = minLen; i <= maxLen; i++) {
            vec += (base[i + 1] - base[i]);
            limit[i] = vec - 1;
            vec <<= 1;
        }
        for (int i = minLen + 1; i <= maxLen; i++) {
            base[i] = ((limit[i - 1] + 1) << 1) - base[i];
        }
    }

    // ---------------------------------------------------------------- 解压

    /**
     * 单次解压的输出硬上限。bzip2 的块级上限（blockSize100k * 100000 个符号）只管单块，
     * 挡不住「一个块套一个块」把输出叠上去：短输入配一堆块就能放大成任意大小。补丁是外部
     * 输入，所以这里再压一道全局上限，宁可失败也不让端侧内存被打满。
     */
    public static final long MAX_DECOMPRESS_BYTES = 256L * 1024 * 1024;

    /** 解压标准 bzip2 流。空数组按"空流"处理，直接返回空数组。 */
    public static byte[] decompress(byte[] data) {
        return decompress(data, MAX_DECOMPRESS_BYTES);
    }

    /**
     * 解压标准 bzip2 流，输出超过 {@code maxOut} 字节立即失败。
     *
     * <p>调用方知道上界时应显式传入（例如 bsdiff 补丁的 DIFF 块最多提供 {@code newSize}
     * 字节），这样压缩炸弹在写出第一个越界字节时就被挡住，而不是等分配完再校验。</p>
     */
    public static byte[] decompress(byte[] data, long maxOut) {
        if (data == null) {
            throw new PcuException("bzip2 解压输入为 null");
        }
        if (maxOut < 0) {
            throw new PcuException("bzip2 解压输出上限不能为负数：" + maxOut);
        }
        if (data.length == 0) {
            return new byte[0];
        }
        return new Decoder(data, maxOut).run();
    }

    private static final class Decoder {

        private final BitReader in;
        private final Growable out;
        private long combinedCrc;
        private int blockSize100k;

        Decoder(byte[] data, long maxOut) {
            this.in = new BitReader(data);
            this.out = new Growable(maxOut);
        }

        byte[] run() {
            if (in.readBits(8) != 0x42 || in.readBits(8) != 0x5A || in.readBits(8) != 0x68) {
                throw new PcuException("bzip2 流缺少 BZh 魔数");
            }
            int level = (int) in.readBits(8);
            if (level < 0x31 || level > 0x39) {
                throw new PcuException("bzip2 流的块大小字段非法：" + level);
            }
            blockSize100k = level - 0x30;

            while (true) {
                long magic = in.readBits(48);
                if (magic == 0x177245385090L) {
                    long stored = in.readBits(32);
                    if (stored != combinedCrc) {
                        throw new PcuException("bzip2 流整体 CRC 校验失败");
                    }
                    return out.toArray();
                }
                if (magic != 0x314159265359L) {
                    throw new PcuException("bzip2 块魔数非法：0x" + Long.toHexString(magic));
                }
                decodeBlock();
            }
        }

        private void decodeBlock() {
            long storedBlockCrc = in.readBits(32);
            if (in.readBits(1) != 0) {
                throw new PcuException("不支持随机化的 bzip2 块");
            }
            int origPtr = (int) in.readBits(24);
            if (origPtr > 10 + 100000 * blockSize100k) {
                throw new PcuException("bzip2 块的 origPtr 超出范围：" + origPtr);
            }

            // 映射表分两段：先读完 16 个 inUse16 标志，再按标志读各段。
            // 这两段不能合成一个循环（"读一位标志、立刻读该段"）：格式上所有标志在前，
            // 只有标签全满（nInUse==256）时两种读法才碰巧一致，其余情况会整块错位。
            boolean[] inUse16 = new boolean[16];
            for (int i = 0; i < 16; i++) {
                inUse16[i] = in.readBits(1) == 1;
            }
            boolean[] inUse = new boolean[256];
            for (int i = 0; i < 16; i++) {
                if (inUse16[i]) {
                    for (int j = 0; j < 16; j++) {
                        if (in.readBits(1) == 1) {
                            inUse[i * 16 + j] = true;
                        }
                    }
                }
            }
            int[] seqToUnseq = new int[256];
            int nInUse = 0;
            for (int i = 0; i < 256; i++) {
                if (inUse[i]) {
                    seqToUnseq[nInUse++] = i;
                }
            }
            if (nInUse == 0) {
                throw new PcuException("bzip2 块的映射表为空");
            }
            int alphaSize = nInUse + 2;

            int nGroups = (int) in.readBits(3);
            if (nGroups < 2 || nGroups > N_GROUPS) {
                throw new PcuException("bzip2 块的 Huffman 表数量非法：" + nGroups);
            }
            int nSelectors = (int) in.readBits(15);
            if (nSelectors < 1) {
                throw new PcuException("bzip2 块的选择子数量非法：" + nSelectors);
            }
            int storedSelectors = Math.min(nSelectors, MAX_SELECTORS);
            int[] selectorMtf = new int[storedSelectors];
            for (int i = 0; i < nSelectors; i++) {
                int j = 0;
                while (true) {
                    if (in.readBits(1) == 0) {
                        break;
                    }
                    j++;
                    if (j >= nGroups) {
                        throw new PcuException("bzip2 块的选择子 MTF 值越界");
                    }
                }
                if (i < storedSelectors) {
                    selectorMtf[i] = j;
                }
            }

            int[] selector = new int[storedSelectors];
            int[] pos = new int[N_GROUPS];
            for (int i = 0; i < nGroups; i++) {
                pos[i] = i;
            }
            for (int i = 0; i < storedSelectors; i++) {
                int v = selectorMtf[i];
                int tmp = pos[v];
                while (v > 0) {
                    pos[v] = pos[v - 1];
                    v--;
                }
                pos[0] = tmp;
                selector[i] = tmp;
            }

            int[][] len = new int[nGroups][MAX_ALPHA_SIZE];
            for (int t = 0; t < nGroups; t++) {
                int curr = (int) in.readBits(5);
                for (int i = 0; i < alphaSize; i++) {
                    while (true) {
                        if (curr < 1 || curr > 20) {
                            throw new PcuException("bzip2 码长超出 1..20：" + curr);
                        }
                        if (in.readBits(1) == 0) {
                            break;
                        }
                        if (in.readBits(1) == 0) {
                            curr++;
                        } else {
                            curr--;
                        }
                    }
                    len[t][i] = curr;
                }
            }

            int[][] limit = new int[nGroups][MAX_CODE_LEN];
            int[][] base = new int[nGroups][MAX_CODE_LEN];
            int[][] perm = new int[nGroups][MAX_ALPHA_SIZE];
            int[] minLens = new int[nGroups];
            for (int t = 0; t < nGroups; t++) {
                int minLen = 32;
                int maxLen = 0;
                for (int i = 0; i < alphaSize; i++) {
                    if (len[t][i] > maxLen) {
                        maxLen = len[t][i];
                    }
                    if (len[t][i] < minLen) {
                        minLen = len[t][i];
                    }
                }
                hbCreateDecodeTables(limit[t], base[t], perm[t], len[t], minLen, maxLen, alphaSize);
                minLens[t] = minLen;
            }

            int eob = nInUse + 1;
            int nblockMax = 100000 * blockSize100k;
            int[] unzftab = new int[256];
            int[] tt = new int[Math.min(nblockMax, 1 << 14)];
            int nblock = 0;

            int[] yy = new int[nInUse];
            for (int i = 0; i < nInUse; i++) {
                yy[i] = i;
            }

            // state：groupNo / groupPos / gSel / gMinlen，跨调用必须保持。
            int[] state = new int[]{-1, 0, 0, 0};
            int nextSym = mtfValue(in, limit, base, perm, minLens, selector, state);

            while (nextSym != eob) {
                if (nextSym == RUNA || nextSym == RUNB) {
                    int run = -1;
                    int weight = 1;
                    do {
                        if (weight >= 2 * 1024 * 1024) {
                            throw new PcuException("bzip2 的 RLE2 游程长度溢出");
                        }
                        run += (nextSym == RUNA) ? weight : 2 * weight;
                        weight *= 2;
                        nextSym = mtfValue(in, limit, base, perm, minLens, selector, state);
                    } while (nextSym == RUNA || nextSym == RUNB);
                    run++;
                    int uc = seqToUnseq[yy[0]];
                    unzftab[uc] += run;
                    for (int i = 0; i < run; i++) {
                        tt = ensure(tt, nblock, nblockMax);
                        tt[nblock++] = uc;
                    }
                    continue;
                }

                tt = ensure(tt, nblock, nblockMax);
                int nn = nextSym - 1;
                if (nn >= nInUse) {
                    throw new PcuException("bzip2 的 MTF 值越界：" + nn);
                }
                // yy 里存的是 MTF 序号，队首移动用序号；写进 tt 的必须是序号映射回的原始字节，
                // 少了 seqToUnseq 这一步，标签不满 256 时整块都会错（满 256 时映射是恒等才看不出来）。
                int idx = yy[nn];
                System.arraycopy(yy, 0, yy, 1, nn);
                yy[0] = idx;
                int uc = seqToUnseq[idx];
                unzftab[uc]++;
                tt[nblock++] = uc;
                nextSym = mtfValue(in, limit, base, perm, minLens, selector, state);
            }

            if (origPtr < 0 || origPtr >= nblock) {
                throw new PcuException("bzip2 块的 origPtr 与实际符号数不符：" + origPtr);
            }

            int[] cftab = new int[257];
            for (int i = 0; i < 256; i++) {
                if (unzftab[i] < 0 || unzftab[i] > nblock) {
                    throw new PcuException("bzip2 块的字符频次非法");
                }
                cftab[i + 1] = unzftab[i];
            }
            for (int i = 1; i <= 256; i++) {
                cftab[i] += cftab[i - 1];
            }

            // 逆 BWT：低 8 位是字符，高位记录后一个行号。
            for (int i = 0; i < nblock; i++) {
                int uc = tt[i] & 0xFF;
                tt[cftab[uc]] |= (i << 8);
                cftab[uc]++;
            }

            int tPos = tt[origPtr] >>> 8;
            byte[] l = new byte[nblock];
            for (int i = 0; i < nblock; i++) {
                int entry = tt[tPos];
                l[i] = (byte) entry;
                tPos = entry >>> 8;
            }

            // 逆 RLE1：4 个相同字节后跟一个计数。
            int crc = CRC_INIT;
            int i = 0;
            while (i < nblock) {
                int ch = l[i] & 0xFF;
                i++;
                int run = 1;
                if (i < nblock && (l[i] & 0xFF) == ch) {
                    i++;
                    run = 2;
                    if (i < nblock && (l[i] & 0xFF) == ch) {
                        i++;
                        run = 3;
                        if (i < nblock && (l[i] & 0xFF) == ch) {
                            i++;
                            if (i < nblock) {
                                run = (l[i] & 0xFF) + 4;
                                i++;
                            } else {
                                run = 4;
                            }
                        }
                    }
                }
                for (int k = 0; k < run; k++) {
                    out.append(ch);
                    crc = updateCrc(crc, ch);
                }
            }
            if (crcFinal(crc) != storedBlockCrc) {
                throw new PcuException("bzip2 块 CRC 校验失败");
            }
            combinedCrc = ((combinedCrc << 1) | (combinedCrc >>> 31)) & 0xFFFFFFFFL;
            combinedCrc ^= storedBlockCrc;
        }

        private static int[] ensure(int[] tt, int nblock, int nblockMax) {
            if (nblock < nblockMax && nblock != tt.length) {
                return tt;
            }
            if (nblock >= nblockMax) {
                throw new PcuException("bzip2 块解出的符号数超出块上限");
            }
            return Arrays.copyOf(tt, Math.min(nblockMax, tt.length * 2));
        }
    }

    /** 读一个 MTF 值；{@code state} = { groupNo, groupPos, gSel, gMinlen }。 */
    private static int mtfValue(BitReader in, int[][] limit, int[][] base, int[][] perm,
                                int[] minLens, int[] selector, int[] state) {
        if (state[1] == 0) {
            state[0]++;
            if (state[0] >= selector.length) {
                throw new PcuException("bzip2 选择子用尽，块数据不完整");
            }
            state[1] = G_SIZE;
            state[2] = selector[state[0]];
            state[3] = minLens[state[2]];
        }
        state[1]--;
        int zn = state[3];
        int zvec = (int) in.readBits(zn);
        while (true) {
            if (zn > 20) {
                throw new PcuException("bzip2 Huffman 码字长度超出 20 位");
            }
            if (zvec <= limit[state[2]][zn]) {
                break;
            }
            zn++;
            zvec = (zvec << 1) | (int) in.readBits(1);
        }
        int idx = zvec - base[state[2]][zn];
        if (idx < 0 || idx >= MAX_ALPHA_SIZE) {
            throw new PcuException("bzip2 Huffman 解码越界");
        }
        return perm[state[2]][idx];
    }

    // ------------------------------------------------------------ 位读写

    /** MSB 优先的位输出，对齐 libbz2 的 bsBuff/bsLive。 */
    private static final class BitWriter {

        private byte[] buf = new byte[1024];
        private int len;
        private long acc;
        private int nbits;

        void writeBits(int n, long value) {
            long mask = (1L << n) - 1;
            acc = (acc << n) | (value & mask);
            nbits += n;
            while (nbits >= 8) {
                nbits -= 8;
                if (len == buf.length) {
                    buf = Arrays.copyOf(buf, buf.length * 2);
                }
                buf[len++] = (byte) (acc >>> nbits);
            }
            acc = nbits == 0 ? 0 : (acc & ((1L << nbits) - 1));
        }

        byte[] finish() {
            if (nbits > 0) {
                if (len == buf.length) {
                    buf = Arrays.copyOf(buf, buf.length + 1);
                }
                buf[len++] = (byte) (acc << (8 - nbits));
                nbits = 0;
                acc = 0;
            }
            return Arrays.copyOf(buf, len);
        }
    }

    /** MSB 优先的位输入。数据不足一律报错，绝不补零。 */
    private static final class BitReader {

        private final byte[] data;
        private int pos;
        private long acc;
        private int nbits;

        BitReader(byte[] data) {
            this.data = data;
        }

        long readBits(int n) {
            while (nbits < n) {
                if (pos >= data.length) {
                    throw new PcuException("bzip2 流数据不足，需要 " + n + " 位");
                }
                acc = (acc << 8) | (data[pos++] & 0xFF);
                nbits += 8;
            }
            nbits -= n;
            long v = (acc >>> nbits) & ((1L << n) - 1);
            acc = nbits == 0 ? 0 : (acc & ((1L << nbits) - 1));
            return v;
        }
    }

    /** 解压输出的可增长缓冲，避免逐次重新分配整块；同时负责卡住输出上限。 */
    private static final class Growable {

        /** 数组长度到不了 Integer.MAX_VALUE；上限先夹到这里，后面 buf.length * 2 才不会溢出成负数。 */
        private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

        private final long maxOut;
        private byte[] buf;
        private int len;

        Growable(long maxOut) {
            this.maxOut = Math.min(maxOut, MAX_ARRAY_SIZE);
            this.buf = new byte[(int) Math.min(4096L, Math.max(1L, this.maxOut))];
        }

        void append(int b) {
            if (len >= maxOut) {
                throw new PcuException("bzip2 解压输出超过上限 " + maxOut
                        + " 字节，疑似压缩炸弹或补丁被篡改");
            }
            if (len == buf.length) {
                buf = Arrays.copyOf(buf, (int) Math.min(buf.length * 2L, maxOut));
            }
            buf[len++] = (byte) b;
        }

        byte[] toArray() {
            return Arrays.copyOf(buf, len);
        }
    }
}