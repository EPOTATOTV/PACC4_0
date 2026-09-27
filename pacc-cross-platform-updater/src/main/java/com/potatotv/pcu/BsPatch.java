package com.potatotv.pcu;

import java.util.Arrays;

/**
 * bsdiff 应用端：把 {@code BSDIFF40} 补丁打到旧文件上，还原出新文件。
 *
 * <p>客户端只做应用，不做生成。补丁可能来自服务端的其它语言实现（例如 python 的
 * {@code bz2}），因此这里只按格式解析，不假设生成端是谁：魔数、三个长度、三个 bzip2
 * 子流都当外部输入校验。</p>
 *
 * <p>校验一律从严：魔数不对、长度是负数或超出实际可用数据、CTRL 里的三元组不够、
 * 复制区间越过旧文件、插入区间越过 EXTRA 块，全部抛 {@link PcuException}。
 * 不做静默截断，也不给缺失的字节补零——补丁被截断或篡改时，宁可失败也不能产出一个
 * 「看起来能装但内容是错的」制品，那会把问题推迟到运行期才炸。</p>
 */
public final class BsPatch {

    private static final byte[] MAGIC = {'B', 'S', 'D', 'I', 'F', 'F', '4', '0'};
    private static final int HEADER_SIZE = 32;
    private static final int CTRL_TUPLE_SIZE = 24;

    private BsPatch() {
    }

    /**
     * 用补丁把旧文件还原成新文件。
     *
     * @param oldData   当前版本的完整文件字节
     * @param patchData BSDIFF40 补丁字节
     */
    public static byte[] patch(byte[] oldData, byte[] patchData) {
        if (oldData == null || patchData == null) {
            throw new PcuException("bsdiff 应用的输入不能为 null");
        }
        if (patchData.length < HEADER_SIZE) {
            throw new PcuException("差分补丁长度不足，无法容纳 BSDIFF40 头");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (patchData[i] != MAGIC[i]) {
                throw new PcuException("差分补丁魔数不是 BSDIFF40");
            }
        }

        long ctrlLen = BsDiff.readOffset(patchData, 8);
        long diffLen = BsDiff.readOffset(patchData, 16);
        long newSize = BsDiff.readOffset(patchData, 24);
        if (ctrlLen < 0) {
            throw new PcuException("差分补丁的 CTRL 块长度为负：" + ctrlLen);
        }
        if (diffLen < 0) {
            throw new PcuException("差分补丁的 DIFF 块长度为负：" + diffLen);
        }
        if (newSize < 0) {
            throw new PcuException("差分补丁声明的新文件长度为负：" + newSize);
        }
        if (newSize > Integer.MAX_VALUE - 8L) {
            throw new PcuException("差分补丁声明的新文件过大：" + newSize);
        }
        // 用 long 比较，避免 ctrlLen + diffLen 在 int 上回绕成负数绕过检查。
        long payload = ctrlLen + diffLen;
        if (payload > patchData.length - HEADER_SIZE || payload < 0) {
            throw new PcuException("差分补丁声明的 CTRL/DIFF 块长度超出实际数据"
                    + "（ctrl=" + ctrlLen + " diff=" + diffLen
                    + " 可用=" + (patchData.length - HEADER_SIZE) + "）");
        }

        int ctrlOff = HEADER_SIZE;
        int diffOff = (int) (HEADER_SIZE + ctrlLen);
        int extraOff = (int) (HEADER_SIZE + ctrlLen + diffLen);

        // 三个块各自带输出上限，别让「解压出来到底多大」由补丁自己说了算：
        // DIFF 块只提供 copyLen 字节、EXTRA 块只提供 insertLen 字节，而每次循环都会把
        // copyLen + insertLen 记进 newPos 且 newPos 不超过 newSize，所以这两个块的实际
        // 长度必然不超过 newSize。超过就说明补丁是构造出来的，直接失败，不要先解压再判断。
        long blockLimit = Math.min(newSize, BZip2.MAX_DECOMPRESS_BYTES);
        byte[] ctrl = BZip2.decompress(Arrays.copyOfRange(patchData, ctrlOff, diffOff));
        byte[] diff = BZip2.decompress(Arrays.copyOfRange(patchData, diffOff, extraOff), blockLimit);
        byte[] extra = BZip2.decompress(Arrays.copyOfRange(patchData, extraOff, patchData.length), blockLimit);

        // 分配必须先于「声明的 newSize」被证伪，而不是先分配再校验：一个 32 字节的伪造补丁
        // 把 newSize 写成 2GB，就能让端侧直接 OOM（Error 不受 catch(RuntimeException) 约束）。
        // 可证伪的依据：CTRL 里每个三元组的 copyLen + insertLen 之和恰好等于 newSize（循环
        // 退出条件就是 newPos == newSize），而 copyLen 只从 DIFF 取字节、insertLen 只从 EXTRA
        // 取字节，所以 newSize 不可能超过这两个解压块的长度之和。
        if (newSize > (long) diff.length + extra.length) {
            throw new PcuException("差分补丁声明的新文件长度超出 DIFF/EXTRA 块可用数据"
                    + "（new=" + newSize + " diff=" + diff.length + " extra=" + extra.length + "）");
        }
        byte[] out = new byte[(int) newSize];
        long oldPos = 0;
        int newPos = 0;
        int ctrlPos = 0;
        int diffPos = 0;
        int extraPos = 0;

        while (newPos < newSize) {
            // 三元组必须完整；这条检查同时也是死循环的出口
            if (ctrlPos + CTRL_TUPLE_SIZE > ctrl.length) {
                throw new PcuException("差分补丁的 CTRL 块不完整，缺少三元组");
            }
            long copyLen = BsDiff.readOffset(ctrl, ctrlPos);
            long insertLen = BsDiff.readOffset(ctrl, ctrlPos + 8);
            long seekLen = BsDiff.readOffset(ctrl, ctrlPos + 16);
            ctrlPos += CTRL_TUPLE_SIZE;
            if (copyLen < 0) {
                throw new PcuException("差分补丁的复制长度为负：" + copyLen);
            }
            if (insertLen < 0) {
                throw new PcuException("差分补丁的插入长度为负：" + insertLen);
            }
            if (newPos + copyLen > newSize) {
                throw new PcuException("差分补丁的复制长度超出新文件长度");
            }
            if (copyLen > diff.length - diffPos) {
                throw new PcuException("差分补丁的 DIFF 块数据不足");
            }
            if (copyLen > 0) {
                if (oldPos < 0 || oldPos + copyLen > oldData.length) {
                    throw new PcuException("差分补丁的复制区间超出旧文件范围");
                }
                for (int i = 0; i < copyLen; i++) {
                    out[newPos + i] = (byte) (diff[diffPos + i] + oldData[(int) (oldPos + i)]);
                }
            }
            newPos += (int) copyLen;
            oldPos += copyLen;
            diffPos += (int) copyLen;

            if (newPos + insertLen > newSize) {
                throw new PcuException("差分补丁的插入长度超出新文件长度");
            }
            if (insertLen > extra.length - extraPos) {
                throw new PcuException("差分补丁的 EXTRA 块数据不足");
            }
            if (insertLen > 0) {
                System.arraycopy(extra, extraPos, out, newPos, (int) insertLen);
            }
            newPos += (int) insertLen;
            extraPos += (int) insertLen;

            // seek 可以为负：补丁就是靠它回到旧文件里更早的位置再复制
            oldPos += seekLen;
        }
        return out;
    }
}