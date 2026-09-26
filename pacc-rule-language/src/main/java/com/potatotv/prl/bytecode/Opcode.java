package com.potatotv.prl.bytecode;

/**
 * 字节码操作码与编码规则（设计文档 §2.8.2）。
 *
 * <p>操作数一律按 §2.8.2 规定的宽度编码：寄存器 1 字节、常量池（字符串表）索引 2 字节、
 * 跳转偏移 4 字节、小整数内联。文档 §2.8.3 的例子把常量池索引写成了 1 字节（{@code 12 01 02}），
 * 那是为了示意压缩了宽度，这里按 §2.8.2 的表格取 2 字节，读写的都是同一份代码，不会错位。</p>
 *
 * <p>文档给出的 0x10 CONST / 0x11 STORE / 0x12 LOAD / 0x20 MUL / 0x21 ADD / 0x30 CALL 全部保留原值，
 * 其余按同一套命名补齐；分段留给后续扩展（0x1x 常量与搬运、0x2x 算术、0x3x 调用、0x4x 比较逻辑、
 * 0x5x 跳转、0x6x 集合与函数值、0x7x 收尾）。</p>
 */
public final class Opcode {

    private Opcode() {
    }

    // ---- 常量与搬运 ----
    public static final int CONST = 0x10;
    public static final int STORE = 0x11;
    public static final int LOAD = 0x12;
    public static final int CONST_INT = 0x13;
    public static final int CONST_FLOAT = 0x14;
    public static final int CONST_TRUE = 0x15;
    public static final int CONST_FALSE = 0x16;
    public static final int CONST_NULL = 0x17;
    public static final int MOVE = 0x18;

    /**
     * 源码行号标记（§2.15.1 的断点要用）。{@code 0x0F} 空在常量段前面，执行时只更新当前行号、
     * 不碰寄存器。
     */
    public static final int LINE = 0x0F;

    // ---- 算术 ----
    public static final int MUL = 0x20;
    public static final int ADD = 0x21;
    public static final int SUB = 0x22;
    public static final int DIV = 0x23;
    public static final int MOD = 0x24;
    public static final int POW = 0x25;
    public static final int NEG = 0x26;

    // ---- 调用 ----
    public static final int CALL = 0x30;
    public static final int CALL_VALUE = 0x31;
    public static final int METHOD_CALL = 0x32;
    public static final int MEMBER_GET = 0x33;

    // ---- 比较与逻辑 ----
    public static final int GT = 0x40;
    public static final int LT = 0x41;
    public static final int GTE = 0x42;
    public static final int LTE = 0x43;
    public static final int EQ = 0x44;
    public static final int NEQ = 0x45;
    public static final int IN = 0x46;
    public static final int NOT_IN = 0x47;
    public static final int AND = 0x48;
    public static final int OR = 0x49;
    public static final int NOT = 0x4B;

    // ---- 跳转 ----
    public static final int JUMP = 0x50;
    public static final int JUMP_IF_FALSE = 0x51;
    public static final int JUMP_IF_TRUE = 0x52;
    public static final int JUMP_IF_NULL = 0x53;

    // ---- 集合与函数值 ----
    public static final int LIST_NEW = 0x60;
    public static final int SET_NEW = 0x61;
    public static final int MAP_NEW = 0x62;
    public static final int TUPLE_NEW = 0x63;
    public static final int INDEX_GET = 0x64;
    public static final int INDEX_SET = 0x65;
    public static final int RANGE = 0x66;
    public static final int GET_ITER = 0x67;
    public static final int ITER_NEXT = 0x68;
    public static final int CLOSURE = 0x69;
    public static final int STRING_INTERP = 0x6A;
    public static final int TO_STRING = 0x6B;

    // ---- 收尾 ----
    public static final int RETURN_VOID = 0x7E;
    public static final int RETURN = 0x7F;

    private static final String[] MNEMONICS = new String[0x80];

    static {
        MNEMONICS[CONST] = "CONST";
        MNEMONICS[STORE] = "STORE";
        MNEMONICS[LOAD] = "LOAD";
        MNEMONICS[CONST_INT] = "CONST_INT";
        MNEMONICS[CONST_FLOAT] = "CONST_FLOAT";
        MNEMONICS[CONST_TRUE] = "CONST_TRUE";
        MNEMONICS[CONST_FALSE] = "CONST_FALSE";
        MNEMONICS[CONST_NULL] = "CONST_NULL";
        MNEMONICS[MOVE] = "MOVE";
        MNEMONICS[LINE] = "LINE";
        MNEMONICS[MUL] = "MUL";
        MNEMONICS[ADD] = "ADD";
        MNEMONICS[SUB] = "SUB";
        MNEMONICS[DIV] = "DIV";
        MNEMONICS[MOD] = "MOD";
        MNEMONICS[POW] = "POW";
        MNEMONICS[NEG] = "NEG";
        MNEMONICS[CALL] = "CALL";
        MNEMONICS[CALL_VALUE] = "CALL_VALUE";
        MNEMONICS[METHOD_CALL] = "METHOD_CALL";
        MNEMONICS[MEMBER_GET] = "MEMBER_GET";
        MNEMONICS[GT] = "GT";
        MNEMONICS[LT] = "LT";
        MNEMONICS[GTE] = "GTE";
        MNEMONICS[LTE] = "LTE";
        MNEMONICS[EQ] = "EQ";
        MNEMONICS[NEQ] = "NEQ";
        MNEMONICS[IN] = "IN";
        MNEMONICS[NOT_IN] = "NOT_IN";
        MNEMONICS[AND] = "AND";
        MNEMONICS[OR] = "OR";
        MNEMONICS[NOT] = "NOT";
        MNEMONICS[JUMP] = "JUMP";
        MNEMONICS[JUMP_IF_FALSE] = "JUMP_IF_FALSE";
        MNEMONICS[JUMP_IF_TRUE] = "JUMP_IF_TRUE";
        MNEMONICS[JUMP_IF_NULL] = "JUMP_IF_NULL";
        MNEMONICS[LIST_NEW] = "LIST_NEW";
        MNEMONICS[SET_NEW] = "SET_NEW";
        MNEMONICS[MAP_NEW] = "MAP_NEW";
        MNEMONICS[TUPLE_NEW] = "TUPLE_NEW";
        MNEMONICS[INDEX_GET] = "INDEX_GET";
        MNEMONICS[INDEX_SET] = "INDEX_SET";
        MNEMONICS[RANGE] = "RANGE";
        MNEMONICS[GET_ITER] = "GET_ITER";
        MNEMONICS[ITER_NEXT] = "ITER_NEXT";
        MNEMONICS[CLOSURE] = "CLOSURE";
        MNEMONICS[STRING_INTERP] = "STRING_INTERP";
        MNEMONICS[TO_STRING] = "TO_STRING";
        MNEMONICS[RETURN_VOID] = "RETURN_VOID";
        MNEMONICS[RETURN] = "RETURN";
    }

    /** 反汇编用的助记符；未知操作码返回 {@code ?0xNN}。 */
    public static String mnemonic(int opcode) {
        if (opcode >= 0 && opcode < MNEMONICS.length && MNEMONICS[opcode] != null) {
            return MNEMONICS[opcode];
        }
        return String.format("?0x%02X", opcode);
    }
}