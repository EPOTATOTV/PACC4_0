package com.potatotv.pob;

import java.util.ArrayList;
import java.util.List;

/**
 * JVM 指令级的扫描工具：给定一段 code 字节，按指令边界逐条给出 (pos, opcode, length)。
 *
 * <p>只做边界与长度，不做语义解码。可变长重写要靠它把「偏移」从一个字节数组搬到另一个，
 * 所以 tableswitch / lookupswitch 的 4 字节对齐 padding、wide 的两种形态、goto_w/jsr_w 的
 * 4 字节偏移都必须在这里处理干净。</p>
 */
final class Bytecode {

    static final int TABLESWITCH = 0xaa;
    static final int LOOKUPSWITCH = 0xab;
    static final int WIDE = 0xc4;
    static final int GOTO_W = 0xc8;
    static final int JSR_W = 0xc9;

    private static final int ACC_WIDE_IINC = 0x84;

    /** 每个 opcode 去掉操作码后的定长操作数字节数；变长指令（switch/wide）不查此表。 */
    private static final int[] OPERAND = new int[256];

    static {
        // 默认 0：无操作数的指令（nop、常量、算术、数组操作、return、athrow、monitor 等）全部命中
        for (int i = 0x01; i <= 0x0f; i++) {
            OPERAND[i] = 0;
        }
        OPERAND[0x10] = 1; // bipush
        OPERAND[0x11] = 2; // sipush
        OPERAND[0x12] = 1; // ldc
        OPERAND[0x13] = 2; // ldc_w
        OPERAND[0x14] = 2; // ldc2_w
        OPERAND[0x15] = 1; // iload
        OPERAND[0x16] = 1; // lload
        OPERAND[0x17] = 1; // fload
        OPERAND[0x18] = 1; // dload
        OPERAND[0x19] = 1; // aload
        OPERAND[0x36] = 1; // istore
        OPERAND[0x37] = 1; // lstore
        OPERAND[0x38] = 1; // fstore
        OPERAND[0x39] = 1; // dstore
        OPERAND[0x3a] = 1; // astore
        OPERAND[0x84] = 2; // iinc
        for (int i = 0x99; i <= 0xa8; i++) {
            OPERAND[i] = 2; // if<cond> / if_icmp<cond> / if_acmp<cond> / goto / jsr
        }
        OPERAND[0xa9] = 1; // ret
        for (int i = 0xb2; i <= 0xb8; i++) {
            OPERAND[i] = 2; // get/put static/field, invokevirtual/special/static
        }
        OPERAND[0xb9] = 4; // invokeinterface
        OPERAND[0xba] = 4; // invokedynamic
        OPERAND[0xbb] = 2; // new
        OPERAND[0xbc] = 1; // newarray
        OPERAND[0xbd] = 2; // anewarray
        OPERAND[0xc0] = 2; // checkcast
        OPERAND[0xc1] = 2; // instanceof
        OPERAND[0xc5] = 3; // multianewarray
        OPERAND[0xc6] = 2; // ifnull
        OPERAND[0xc7] = 2; // ifnonnull
        OPERAND[0xc8] = 4; // goto_w
        OPERAND[0xc9] = 4; // jsr_w
    }

    static final class Insn {
        final int pos;
        final int opcode;
        final int length;

        Insn(int pos, int opcode, int length) {
            this.pos = pos;
            this.opcode = opcode;
            this.length = length;
        }
    }

    private Bytecode() {
    }

    /** 逐条扫描；只在遇到非法 opcode 或越界时抛，绝不静默跳过字节。 */
    static List<Insn> scan(byte[] code) {
        List<Insn> out = new ArrayList<>();
        int pos = 0;
        while (pos < code.length) {
            int op = code[pos] & 0xFF;
            int length;
            try {
                length = length(code, pos);
            } catch (ArrayIndexOutOfBoundsException e) {
                // switch/wide 要读到指令尾部之后，截断的 code 会在这里越界，统一成明确报错
                throw new IllegalArgumentException("非法字节码：偏移 " + pos + " 处指令越界", e);
            }
            if (length <= 0 || pos + length > code.length) {
                throw new IllegalArgumentException("非法字节码：偏移 " + pos + " 处指令越界");
            }
            out.add(new Insn(pos, op, length));
            pos += length;
        }
        return out;
    }

    static int length(byte[] code, int pos) {
        int op = code[pos] & 0xFF;
        if (op == TABLESWITCH) {
            int aligned = align4(pos + 1);
            int low = readInt(code, aligned + 4);
            int high = readInt(code, aligned + 8);
            return (aligned + 12 + 4 * (high - low + 1)) - pos;
        }
        if (op == LOOKUPSWITCH) {
            int aligned = align4(pos + 1);
            int pairs = readInt(code, aligned + 4);
            return (aligned + 8 + 8 * pairs) - pos;
        }
        if (op == WIDE) {
            return (code[pos + 1] & 0xFF) == ACC_WIDE_IINC ? 6 : 4;
        }
        return 1 + OPERAND[op];
    }

    /** 2 字节相对分支：if 系列 / goto / jsr / ifnull / ifnonnull。 */
    static boolean isShortBranch(int op) {
        return (op >= 0x99 && op <= 0xa8) || op == 0xc6 || op == 0xc7;
    }

    static boolean isWideBranch(int op) {
        return op == GOTO_W || op == JSR_W;
    }

    static boolean isSwitch(int op) {
        return op == TABLESWITCH || op == LOOKUPSWITCH;
    }

    static boolean isReturn(int op) {
        return op >= 0xac && op <= 0xb1;
    }

    static int align4(int value) {
        return (value + 3) & ~3;
    }

    static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    static int readShort(byte[] data, int offset) {
        return (short) (((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF));
    }

    static void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    static void writeShort(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 8);
        data[offset + 1] = (byte) value;
    }

    /** 从 Code 属性内容里只取出 code 字节（max_stack/max_locals 之后的 code_length 与 code[]）。 */
    static byte[] readCode(byte[] codeInfo) {
        java.io.DataInputStream in =
                new java.io.DataInputStream(new java.io.ByteArrayInputStream(codeInfo));
        try {
            in.skipBytes(4);
            int codeLength = in.readInt();
            byte[] code = new byte[codeLength];
            in.readFully(code);
            return code;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Code 属性被截断", e);
        }
    }
}