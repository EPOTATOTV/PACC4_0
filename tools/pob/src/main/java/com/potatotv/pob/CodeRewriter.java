package com.potatotv.pob;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Code 属性的可变长重写。
 *
 * <p>基线的取巧点是「不碰 Code 字节码」，所以偏移类的属性全都不用管。字符串加密要把
 * {@code ldc String}（2~3 字节）换成 {@code ldc_w int + invokestatic}（6 字节），后面所有偏移
 * 都会动，于是必须把偏移相关的结构全部重算一遍。</p>
 *
 * <h2>能力边界（重要，改动前先读）</h2>
 * <p>本类只支持<b>「替换前后净栈变化为 0、且自身不含跳转」</b>的指令替换。这样替换点一定落在
 * 原有的基本块内部，不会产生新的跳转目标，也就不需要生成新的 StackMapTable frame——生成 frame
 * 需要完整的类型数据流分析，工作量与风险都远超本次范围。</p>
 * <p>因此 <b>不支持</b>把短跳转拓宽为 goto_w、也不支持产生新分支的插入。若某方法里出现越界
 * 分支或无法重算的偏移类属性，宁可抛异常让调用方跳过该方法，也不产出可能 VerifyError 的字节码。</p>
 * <p>仍会重新编码的偏移相关结构：所有分支/switch 的相对偏移、tableswitch/lookupswitch 的对齐
 * padding、异常表的 start/end/handler、StackMapTable 的 frame 偏移（含 delta 超过 63 时提升为
 * 扩展形态，以及 Uninitialized 内嵌偏移的重映射）。frame 的<b>种类与类型信息保持不变</b>。</p>
 */
final class CodeRewriter {

    /** 会带偏移、但本类不打算处理的 Code 子属性：出现就说明重写不安全。 */
    private static final List<String> UNSUPPORTED_CODE_ATTRS = List.of(
            "RuntimeVisibleTypeAnnotations", "RuntimeInvisibleTypeAnnotations", "StackMap");

    private CodeRewriter() {
    }

    static byte[] rewrite(byte[] info, Map<Integer, byte[]> edits, java.util.function.IntFunction<String> utf8) {
        return rewrite(info, edits, 0, utf8);
    }

    /**
     * 重写一段 Code 属性。
     *
     * @param info    原始 Code 属性内容（与 {@link ClassFile} 里存放的形式一致）
     * @param edits   以「原指令起始偏移」为键的替换字节；替换串不得含跳转/switch/wide
     * @param extraStack 需要为 max_stack 预留的额外深度（替换串的瞬时净栈增量；字符串替换为 0）
     * @param utf8   常量池 UTF-8 取值器，用于识别子属性名
     * @return 重写后的 Code 属性内容
     * @throws UnsupportedOperationException 命中能力边界外的情况
     */
    static byte[] rewrite(byte[] info, Map<Integer, byte[]> edits, int extraStack,
                          java.util.function.IntFunction<String> utf8) {
        Cursor c = new Cursor(info);
        int maxStack = c.u2() + extraStack;
        int maxLocals = c.u2();
        int codeLength = (int) c.u4();
        byte[] code = c.bytes(codeLength);
        int exceptionCount = c.u2();
        byte[] exceptions = c.bytes(exceptionCount * 8);
        List<SubAttr> subAttrs = readSubAttrs(c, utf8);

        List<Bytecode.Insn> insns = Bytecode.scan(code);
        validateEdits(edits, insns, code.length);

        // 第一遍：顺序计算每条指令的新起点。switch 的 padding 依赖自身新位置，所以只能边走边算。
        int[] newOffset = new int[code.length + 1];
        java.util.Arrays.fill(newOffset, -1);
        int cursor = 0;
        for (Bytecode.Insn insn : insns) {
            newOffset[insn.pos] = cursor;
            byte[] repl = edits.get(insn.pos);
            if (repl != null) {
                cursor += repl.length;
            } else if (Bytecode.isSwitch(insn.opcode)) {
                cursor += switchLength(code, insn.pos, cursor);
            } else {
                cursor += insn.length;
            }
        }
        newOffset[code.length] = cursor;
        int newLength = cursor;
        if (newLength > 0xFFFF) {
            throw new UnsupportedOperationException("重写后方法体超过 65535 字节");
        }

        // 第二遍：按新位置发射字节，并修正所有相对偏移。
        byte[] out = new byte[newLength];
        for (Bytecode.Insn insn : insns) {
            int np = newOffset[insn.pos];
            byte[] repl = edits.get(insn.pos);
            if (repl != null) {
                System.arraycopy(repl, 0, out, np, repl.length);
            } else if (Bytecode.isShortBranch(insn.opcode)) {
                int target = remapTarget(newOffset, code.length, insn.pos + Bytecode.readShort(code, insn.pos + 1));
                int delta = target - np;
                if (delta < Short.MIN_VALUE || delta > Short.MAX_VALUE) {
                    throw new UnsupportedOperationException("分支偏移超出短跳转范围（不做拓宽）");
                }
                out[np] = code[insn.pos];
                Bytecode.writeShort(out, np + 1, delta);
            } else if (Bytecode.isWideBranch(insn.opcode)) {
                int target = remapTarget(newOffset, code.length,
                        insn.pos + Bytecode.readInt(code, insn.pos + 1));
                out[np] = code[insn.pos];
                Bytecode.writeInt(out, np + 1, target - np);
            } else if (insn.opcode == Bytecode.TABLESWITCH) {
                emitTableSwitch(code, insn.pos, np, newOffset, out);
            } else if (insn.opcode == Bytecode.LOOKUPSWITCH) {
                emitLookupSwitch(code, insn.pos, np, newOffset, out);
            } else {
                System.arraycopy(code, insn.pos, out, np, insn.length);
            }
        }

        byte[] remappedExceptions = remapExceptionTable(exceptions, newOffset, code.length);

        ByteArrayOutputStream result = new ByteArrayOutputStream(info.length + 32);
        writeU2(result, maxStack);
        writeU2(result, maxLocals);
        writeU4(result, out.length);
        result.write(out, 0, out.length);
        writeU2(result, exceptionCount);
        result.write(remappedExceptions, 0, remappedExceptions.length);
        writeU2(result, subAttrs.size());
        for (SubAttr a : subAttrs) {
            byte[] body = a.body;
            if ("StackMapTable".equals(utf8.apply(a.nameIndex))) {
                body = StackMapTable.remap(body, newOffset, code.length);
            }
            writeU2(result, a.nameIndex);
            writeU4(result, body.length);
            result.write(body, 0, body.length);
        }
        return result.toByteArray();
    }

    private static final class SubAttr {
        final int nameIndex;
        final byte[] body;

        SubAttr(int nameIndex, byte[] body) {
            this.nameIndex = nameIndex;
            this.body = body;
        }
    }

    private static List<SubAttr> readSubAttrs(Cursor c, java.util.function.IntFunction<String> utf8) {
        int count = c.u2();
        List<SubAttr> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int nameIndex = c.u2();
            int length = (int) c.u4();
            byte[] body = c.bytes(length);
            if (UNSUPPORTED_CODE_ATTRS.contains(utf8.apply(nameIndex))) {
                throw new UnsupportedOperationException("方法体含不支持的偏移类属性：" + utf8.apply(nameIndex));
            }
            out.add(new SubAttr(nameIndex, body));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    private static void validateEdits(Map<Integer, byte[]> edits, List<Bytecode.Insn> insns, int codeLength) {
        Map<Integer, Bytecode.Insn> starts = new HashMap<>();
        for (Bytecode.Insn insn : insns) {
            starts.put(insn.pos, insn);
        }
        for (Map.Entry<Integer, byte[]> e : edits.entrySet()) {
            Bytecode.Insn at = starts.get(e.getKey());
            if (at == null) {
                throw new IllegalArgumentException("替换点不是指令边界：" + e.getKey());
            }
            byte[] repl = e.getValue();
            if (repl == null || repl.length == 0) {
                throw new IllegalArgumentException("替换串为空");
            }
            for (Bytecode.Insn inner : Bytecode.scan(repl)) {
                if (Bytecode.isShortBranch(inner.opcode) || Bytecode.isWideBranch(inner.opcode)
                        || Bytecode.isSwitch(inner.opcode)) {
                    throw new UnsupportedOperationException("替换串自身不得包含跳转（会产生新目标）");
                }
            }
        }
        if (codeLength < 0) {
            throw new IllegalArgumentException("code_length 非法");
        }
    }

    private static int remapTarget(int[] newOffset, int codeLength, int oldTarget) {
        if (oldTarget < 0 || oldTarget > codeLength || newOffset[oldTarget] < 0) {
            throw new UnsupportedOperationException("分支目标不是指令边界：" + oldTarget);
        }
        return newOffset[oldTarget];
    }

    // ------------------------------------------------------------------
    // switch
    // ------------------------------------------------------------------

    private static int switchLength(byte[] code, int pos, int newPos) {
        int pad = (4 - ((newPos + 1) & 3)) & 3;
        int data = Bytecode.align4(pos + 1);
        if ((code[pos] & 0xFF) == Bytecode.TABLESWITCH) {
            int low = Bytecode.readInt(code, data + 4);
            int high = Bytecode.readInt(code, data + 8);
            return 1 + pad + 12 + 4 * (high - low + 1);
        }
        int pairs = Bytecode.readInt(code, data + 4);
        return 1 + pad + 8 + 8 * pairs;
    }

    private static void emitTableSwitch(byte[] code, int pos, int np, int[] newOffset, byte[] out) {
        int data = Bytecode.align4(pos + 1);
        int def = Bytecode.readInt(code, data);
        int low = Bytecode.readInt(code, data + 4);
        int high = Bytecode.readInt(code, data + 8);
        int pad = (4 - ((np + 1) & 3)) & 3;
        out[np] = (byte) Bytecode.TABLESWITCH;
        int p = np + 1 + pad;
        Bytecode.writeInt(out, p, remapTarget(newOffset, code.length, pos + def) - np);
        Bytecode.writeInt(out, p + 4, low);
        Bytecode.writeInt(out, p + 8, high);
        int entry = data + 12;
        for (int i = 0; i < high - low + 1; i++) {
            int target = pos + Bytecode.readInt(code, entry + i * 4);
            Bytecode.writeInt(out, p + 12 + i * 4, remapTarget(newOffset, code.length, target) - np);
        }
    }

    private static void emitLookupSwitch(byte[] code, int pos, int np, int[] newOffset, byte[] out) {
        int data = Bytecode.align4(pos + 1);
        int def = Bytecode.readInt(code, data);
        int pairs = Bytecode.readInt(code, data + 4);
        int pad = (4 - ((np + 1) & 3)) & 3;
        out[np] = (byte) Bytecode.LOOKUPSWITCH;
        int p = np + 1 + pad;
        Bytecode.writeInt(out, p, remapTarget(newOffset, code.length, pos + def) - np);
        Bytecode.writeInt(out, p + 4, pairs);
        int entry = data + 8;
        for (int i = 0; i < pairs; i++) {
            int match = Bytecode.readInt(code, entry + i * 8);
            int target = pos + Bytecode.readInt(code, entry + i * 8 + 4);
            Bytecode.writeInt(out, p + 8 + i * 8, match);
            Bytecode.writeInt(out, p + 12 + i * 8, remapTarget(newOffset, code.length, target) - np);
        }
    }

    // ------------------------------------------------------------------
    // 异常表
    // ------------------------------------------------------------------

    private static byte[] remapExceptionTable(byte[] table, int[] newOffset, int codeLength) {
        byte[] out = table.clone();
        for (int i = 0; i + 8 <= table.length; i += 8) {
            int start = Bytecode.readShort(table, i) & 0xFFFF;
            int end = Bytecode.readShort(table, i + 2) & 0xFFFF;
            int handler = Bytecode.readShort(table, i + 4) & 0xFFFF;
            Bytecode.writeShort(out, i, remapTarget(newOffset, codeLength, start));
            Bytecode.writeShort(out, i + 2, remapTarget(newOffset, codeLength, end));
            Bytecode.writeShort(out, i + 4, remapTarget(newOffset, codeLength, handler));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // StackMapTable
    // ------------------------------------------------------------------

    /**
     * StackMapTable 的重编码。
     *
     * <p>只改 frame 的绝对偏移，frame 的种类与类型信息原样保留；当新 delta 超过编码能表达的
     * 范围（same_frame / same_locals_1_stack_item 的 delta 上限是 63）时提升为扩展形态。
     * Uninitialized(tag 8) 内嵌的「new 指令偏移」也一并重映射。</p>
     */
    private static final class StackMapTable {

        static byte[] remap(byte[] info, int[] newOffset, int codeLength) {
            Cursor c = new Cursor(info);
            int count = c.u2();
            List<Frame> frames = new ArrayList<>();
            int prev = -1;
            for (int i = 0; i < count; i++) {
                frames.add(readFrame(c, prev));
                prev = frames.get(frames.size() - 1).absolute;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(info.length + 16);
            writeU2(out, frames.size());
            int prevOut = -1;
            for (Frame f : frames) {
                prevOut = writeFrame(out, f, prevOut, newOffset, codeLength);
            }
            return out.toByteArray();
        }

        /** kind：0 same、1 same_locals_1、2 chop、3 append、4 full；同时保留下标信息。 */
        private static final class Frame {
            int kind;
            int absolute;
            int chopK;
            List<int[]> locals = new ArrayList<>();   // 每项是 {tag, index}；tag 7/8 才有 index
            List<int[]> stack = new ArrayList<>();
        }

        private static Frame readFrame(Cursor c, int prev) {
            Frame f = new Frame();
            int type = c.u1();
            if (type <= 63) {
                f.kind = 0;
                f.absolute = prev + 1 + type;
            } else if (type <= 127) {
                f.kind = 1;
                f.absolute = prev + 1 + (type - 64);
                f.stack.add(readItem(c));
            } else if (type == 247) {
                f.kind = 1;
                f.absolute = prev + 1 + c.u2();
                f.stack.add(readItem(c));
            } else if (type >= 248 && type <= 250) {
                f.kind = 2;
                f.chopK = 251 - type;
                f.absolute = prev + 1 + c.u2();
            } else if (type == 251) {
                f.kind = 0;
                f.absolute = prev + 1 + c.u2();
            } else if (type >= 252 && type <= 254) {
                f.kind = 3;
                f.absolute = prev + 1 + c.u2();
                int n = type - 251;
                for (int i = 0; i < n; i++) {
                    f.locals.add(readItem(c));
                }
            } else if (type == 255) {
                f.kind = 4;
                f.absolute = prev + 1 + c.u2();
                int nl = c.u2();
                for (int i = 0; i < nl; i++) {
                    f.locals.add(readItem(c));
                }
                int ns = c.u2();
                for (int i = 0; i < ns; i++) {
                    f.stack.add(readItem(c));
                }
            } else {
                throw new UnsupportedOperationException("非法 StackMapTable frame 类型：" + type);
            }
            return f;
        }

        private static int[] readItem(Cursor c) {
            int tag = c.u1();
            if (tag == 7 || tag == 8) {
                return new int[]{tag, c.u2()};
            }
            return new int[]{tag, 0};
        }

        /** 把 frame 的原始偏移映射到新布局后写出，返回该 frame 的新绝对偏移（供下一个算 delta）。 */
        private static int writeFrame(ByteArrayOutputStream out, Frame f, int prev, int[] newOffset, int codeLength) {
            int absolute = remapTarget(newOffset, codeLength, f.absolute);
            int delta = absolute - prev - 1;
            switch (f.kind) {
                case 0 -> {
                    if (delta <= 63) {
                        out.write(delta);
                    } else {
                        out.write(251);
                        writeU2(out, delta);
                    }
                }
                case 1 -> {
                    if (delta <= 63) {
                        out.write(64 + delta);
                    } else {
                        out.write(247);
                        writeU2(out, delta);
                    }
                    writeItem(out, f.stack.get(0), newOffset, codeLength);
                }
                case 2 -> {
                    out.write(248 + (3 - f.chopK));
                    writeU2(out, delta);
                }
                case 3 -> {
                    out.write(252 + (f.locals.size() - 1));
                    writeU2(out, delta);
                    for (int[] item : f.locals) {
                        writeItem(out, item, newOffset, codeLength);
                    }
                }
                default -> {
                    out.write(255);
                    writeU2(out, delta);
                    writeU2(out, f.locals.size());
                    for (int[] item : f.locals) {
                        writeItem(out, item, newOffset, codeLength);
                    }
                    writeU2(out, f.stack.size());
                    for (int[] item : f.stack) {
                        writeItem(out, item, newOffset, codeLength);
                    }
                }
            }
            return absolute;
        }

        private static void writeItem(ByteArrayOutputStream out, int[] item, int[] newOffset, int codeLength) {
            out.write(item[0]);
            if (item[0] == 7) {
                writeU2(out, item[1]);
            } else if (item[0] == 8) {
                writeU2(out, remapTarget(newOffset, codeLength, item[1]));
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static void writeU2(ByteArrayOutputStream out, int v) {
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeU4(ByteArrayOutputStream out, long v) {
        out.write((int) ((v >>> 24) & 0xFF));
        out.write((int) ((v >>> 16) & 0xFF));
        out.write((int) ((v >>> 8) & 0xFF));
        out.write((int) (v & 0xFF));
    }

    /** 只读游标，越界即抛。 */
    private static final class Cursor {
        private final byte[] data;
        private int pos;

        Cursor(byte[] data) {
            this.data = data;
        }

        int u1() {
            check(1);
            return data[pos++] & 0xFF;
        }

        int u2() {
            check(2);
            int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;
            return v;
        }

        long u4() {
            check(4);
            long v = ((long) (data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                    | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            pos += 4;
            return v;
        }

        byte[] bytes(int length) {
            check(length);
            byte[] out = java.util.Arrays.copyOfRange(data, pos, pos + length);
            pos += length;
            return out;
        }

        private void check(int length) {
            if (length < 0 || pos + length > data.length) {
                throw new IllegalArgumentException("Code 属性被截断");
            }
        }
    }
}