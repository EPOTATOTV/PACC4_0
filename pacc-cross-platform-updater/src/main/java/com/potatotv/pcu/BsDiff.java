package com.potatotv.pcu;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * bsdiff 生成端：把旧文件和新文件算成标准 {@code BSDIFF40} 补丁。
 *
 * <p>服务端用它生成差分包，客户端用 {@link BsPatch} 应用。算法沿用经典 bsdiff：
 * 对旧文件建后缀数组，然后在后缀数组上按 {@code search()} 二分找最长匹配，
 * 用 {@code (x, y, z)} 三元组描述「从旧文件复制 x 字节 / 插入 y 字节 / 旧文件偏移再挪 z」。
 * CTRL、DIFF、EXTRA 三个子流各自做 bzip2 压缩。</p>
 *
 * <p>差分质量：本实现产出的补丁合法、能被子 {@link BsPatch} 和 bsdiff 参考实现应用，
 * 但只做贪心匹配，不追求最优差分率。对 PCU 的目标场景（几十 MB 以内的二进制，
 * 小改动、插入、尾部追加）压缩后的补丁会明显小于全量；对完全不同的内容，
 * 补丁比新文件大也是正常的（多出的是 CTRL 元数据），调用方按体积决定是否发差分包。</p>
 */
public final class BsDiff {

    private static final byte[] MAGIC = {'B', 'S', 'D', 'I', 'F', 'F', '4', '0'};
    private static final int HEADER_SIZE = 32;

    private BsDiff() {
    }

    /** 生成 {@code oldData -> newData} 的 BSDIFF40 补丁。 */
    public static byte[] diff(byte[] oldData, byte[] newData) {
        if (oldData == null || newData == null) {
            throw new PcuException("bsdiff 的输入不能为 null");
        }
        int oldSize = oldData.length;
        int newSize = newData.length;

        ByteArrayOutputStream ctrl = new ByteArrayOutputStream();
        ByteArrayOutputStream diffStream = new ByteArrayOutputStream();
        ByteArrayOutputStream extra = new ByteArrayOutputStream();

        if (oldSize == 0) {
            // 后缀数组在空串上没有意义，直接整段当插入。
            if (newSize > 0) {
                writeOffset(ctrl, 0);
                writeOffset(ctrl, newSize);
                writeOffset(ctrl, 0);
                extra.write(newData, 0, newSize);
            }
        } else {
            scan(oldData, newData, ctrl, diffStream, extra);
        }

        byte[] ctrlBlock = BZip2.compress(ctrl.toByteArray());
        byte[] diffBlock = BZip2.compress(diffStream.toByteArray());
        byte[] extraBlock = BZip2.compress(extra.toByteArray());

        ByteArrayOutputStream out = new ByteArrayOutputStream(
                HEADER_SIZE + ctrlBlock.length + diffBlock.length + extraBlock.length);
        out.write(MAGIC, 0, MAGIC.length);
        writeOffset(out, ctrlBlock.length);
        writeOffset(out, diffBlock.length);
        writeOffset(out, newSize);
        out.write(ctrlBlock, 0, ctrlBlock.length);
        out.write(diffBlock, 0, diffBlock.length);
        out.write(extraBlock, 0, extraBlock.length);
        return out.toByteArray();
    }

    /** 经典 bsdiff 的扫描主循环。 */
    private static void scan(byte[] oldData, byte[] newData, ByteArrayOutputStream ctrl,
                             ByteArrayOutputStream diffStream, ByteArrayOutputStream extra) {
        int oldSize = oldData.length;
        int newSize = newData.length;
        int[] sa = suffixArray(oldData);
        int[] pos = new int[1];

        int scan = 0;
        int len = 0;
        int lastScan = 0;
        int lastPos = 0;
        int lastOffset = 0;

        while (scan < newSize) {
            int oldScore = 0;
            int scsc = scan += len;
            for (; scan < newSize; scan++) {
                len = search(sa, oldData, newData, scan, pos);
                for (; scsc < scan + len; scsc++) {
                    if (scsc + lastOffset < oldSize && oldData[scsc + lastOffset] == newData[scsc]) {
                        oldScore++;
                    }
                }
                if ((len == oldScore && len != 0) || len > oldScore + 8) {
                    break;
                }
                if (scan + lastOffset < oldSize && oldData[scan + lastOffset] == newData[scan]) {
                    oldScore--;
                }
            }
            if (len != oldScore || scan == newSize) {
                // 向前、向后各找一个「最划算」的复制区间，再处理两段的重叠。
                int s = 0;
                int sf = 0;
                int lenF = 0;
                for (int i = 0; lastScan + i < scan && lastPos + i < oldSize; ) {
                    if (oldData[lastPos + i] == newData[lastScan + i]) {
                        s++;
                    }
                    i++;
                    if (s * 2 - i > sf * 2 - lenF) {
                        sf = s;
                        lenF = i;
                    }
                }
                int lenB = 0;
                if (scan < newSize) {
                    s = 0;
                    int sb = 0;
                    for (int i = 1; scan >= lastScan + i && pos[0] >= i; i++) {
                        if (oldData[pos[0] - i] == newData[scan - i]) {
                            s++;
                        }
                        if (s * 2 - i > sb * 2 - lenB) {
                            sb = s;
                            lenB = i;
                        }
                    }
                }
                if (lastScan + lenF > scan - lenB) {
                    int overlap = (lastScan + lenF) - (scan - lenB);
                    s = 0;
                    int ss = 0;
                    int lenS = 0;
                    for (int i = 0; i < overlap; i++) {
                        if (newData[lastScan + lenF - overlap + i]
                                == oldData[lastPos + lenF - overlap + i]) {
                            s++;
                        }
                        if (newData[scan - lenB + i] == oldData[pos[0] - lenB + i]) {
                            s--;
                        }
                        if (s > ss) {
                            ss = s;
                            lenS = i + 1;
                        }
                    }
                    lenF += lenS - overlap;
                    lenB -= lenS;
                }

                int extraLen = (scan - lenB) - (lastScan + lenF);
                int seek = (pos[0] - lenB) - (lastPos + lenF);
                writeOffset(ctrl, lenF);
                writeOffset(ctrl, extraLen);
                writeOffset(ctrl, seek);

                for (int i = 0; i < lenF; i++) {
                    diffStream.write(newData[lastScan + i] - oldData[lastPos + i]);
                }
                // EXTRA 段是「复制区间末尾」到「本轮命中起点」之间的待插入字节，
                // 即 new[lastScan+lenF, scan-lenB)，长度恰好 extraLen；
                // 写成 new[scan-lenB, scan) 会把已由复制段覆盖的尾部重复插入一遍。
                for (int i = 0; i < extraLen; i++) {
                    extra.write(newData[lastScan + lenF + i]);
                }

                lastScan = scan - lenB;
                lastPos = pos[0] - lenB;
                lastOffset = pos[0] - scan;
            }
        }
    }

    /** 在旧文件的某个后缀位置找与 {@code newData[newPos..)} 的最长公共前缀。 */
    private static int matchLen(byte[] oldData, int oldPos, byte[] newData, int newPos) {
        int n = Math.min(oldData.length - oldPos, newData.length - newPos);
        int i = 0;
        while (i < n && oldData[oldPos + i] == newData[newPos + i]) {
            i++;
        }
        return i;
    }

    /** 在后缀数组上二分，返回最长匹配长度，并把命中的旧文件位置写回 {@code pos}。 */
    private static int search(int[] sa, byte[] oldData, byte[] newData, int newPos, int[] pos) {
        int st = 0;
        int en = sa.length - 1;
        while (en - st >= 2) {
            int mid = st + (en - st) / 2;
            if (compare(oldData, sa[mid], newData, newPos) < 0) {
                st = mid;
            } else {
                en = mid;
            }
        }
        int x = matchLen(oldData, sa[st], newData, newPos);
        int y = matchLen(oldData, sa[en], newData, newPos);
        if (x > y) {
            pos[0] = sa[st];
            return x;
        }
        pos[0] = sa[en];
        return y;
    }

    /** 等价于 memcmp(old[oldPos..], new[newPos..])，只比两者都存在的部分。 */
    private static int compare(byte[] oldData, int oldPos, byte[] newData, int newPos) {
        int n = Math.min(oldData.length - oldPos, newData.length - newPos);
        for (int i = 0; i < n; i++) {
            int a = oldData[oldPos + i] & 0xFF;
            int b = newData[newPos + i] & 0xFF;
            if (a != b) {
                return a < b ? -1 : 1;
            }
        }
        return 0;
    }

    /** 倍增 + 基数排序的后缀数组，比较语义带末尾哨兵（短后缀排前面）。 */
    private static int[] suffixArray(byte[] s) {
        int n = s.length;
        int[] rank = new int[n];
        int[] sa = new int[n];
        int[] tmp = new int[n];
        int[] newRank = new int[n];
        int[] cnt = new int[Math.max(n, 256) + 2];
        for (int i = 0; i < n; i++) {
            rank[i] = s[i] & 0xFF;
            sa[i] = i;
        }

        int classes = 256;
        for (int k = 1; k < n; k <<= 1) {
            // 先按第二关键字（越界记为 0，即最小）排，再按第一关键字稳定排。
            Arrays.fill(cnt, 0, classes + 1, 0);
            for (int i = 0; i < n; i++) {
                cnt[secondKey(rank, sa[i], k, n)]++;
            }
            int sum = 0;
            for (int i = 0; i <= classes; i++) {
                int c = cnt[i];
                cnt[i] = sum;
                sum += c;
            }
            for (int i = 0; i < n; i++) {
                int idx = sa[i];
                tmp[cnt[secondKey(rank, idx, k, n)]++] = idx;
            }
            Arrays.fill(cnt, 0, classes, 0);
            for (int i = 0; i < n; i++) {
                cnt[rank[tmp[i]]]++;
            }
            sum = 0;
            for (int i = 0; i < classes; i++) {
                int c = cnt[i];
                cnt[i] = sum;
                sum += c;
            }
            for (int i = 0; i < n; i++) {
                sa[cnt[rank[tmp[i]]]++] = tmp[i];
            }

            int newClasses = 1;
            newRank[sa[0]] = 0;
            for (int i = 1; i < n; i++) {
                int a = sa[i - 1];
                int b = sa[i];
                if (rank[a] != rank[b] || secondKey(rank, a, k, n) != secondKey(rank, b, k, n)) {
                    newClasses++;
                }
                newRank[b] = newClasses - 1;
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

    private static int secondKey(int[] rank, int i, int k, int n) {
        return i + k < n ? rank[i + k] + 1 : 0;
    }

    /** bsdiff 的 off_t：小端量值，最高位当符号位。 */
    static void writeOffset(ByteArrayOutputStream out, long value) {
        byte[] buf = new byte[8];
        long y = value < 0 ? -value : value;
        for (int i = 0; i < 7; i++) {
            buf[i] = (byte) (y & 0xFF);
            y >>= 8;
        }
        buf[7] = (byte) (y & 0xFF);
        if (value < 0) {
            buf[7] |= (byte) 0x80;
        }
        out.write(buf, 0, buf.length);
    }

    /** 读 bsdiff 的 off_t。 */
    static long readOffset(byte[] buf, int off) {
        long magnitude = buf[off + 7] & 0x7FL;
        for (int i = 6; i >= 0; i--) {
            magnitude = (magnitude << 8) | (buf[off + i] & 0xFFL);
        }
        return (buf[off + 7] & 0x80) != 0 ? -magnitude : magnitude;
    }
}