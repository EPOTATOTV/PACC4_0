package com.potatotv.prl.ir;

import java.util.Arrays;

/**
 * 一条三地址码。
 *
 * <p>形状固定，是为了让字节码编译器能无条件按同一套规则翻译：{@code dst}/{@code a}/{@code b} 是临时值
 * 编号（未使用填 {@link #NONE}），{@code name} 是符号（变量槽名/函数名/方法名/标签名），
 * {@code literal} 放常量值，{@code extra} 放变长操作数。字段比指令多的代价是可读性，收益是
 * 优化器和翻译器各只有一处 {@code switch}，不必为每条指令定义一种子类。</p>
 *
 * @param op   指令
 * @param dst  目标临时值编号，{@link #NONE} 表示不产生结果
 * @param a    第一操作数
 * @param b    第二操作数
 * @param name 符号操作数
 * @param literal 常量操作数
 * @param extra 变长操作数
 */
public record IrInstr(IrOp op,
                      int dst,
                      int a,
                      int b,
                      String name,
                      Object literal,
                      int[] extra) {

    public static final int NONE = -1;

    private static final int[] NO_EXTRA = new int[0];

    public IrInstr {
        extra = extra == null ? NO_EXTRA : extra;
    }

    // ------------------------------------------------------------------ 工厂

    public static IrInstr constant(int dst, Object value) {
        return new IrInstr(IrOp.CONST, dst, NONE, NONE, null, value, null);
    }

    public static IrInstr load(int dst, String slot) {
        return new IrInstr(IrOp.LOAD, dst, NONE, NONE, slot, null, null);
    }

    public static IrInstr store(String slot, int src) {
        return new IrInstr(IrOp.STORE, NONE, src, NONE, slot, null, null);
    }

    public static IrInstr move(int dst, int src) {
        return new IrInstr(IrOp.MOVE, dst, src, NONE, null, null, null);
    }

    public static IrInstr unary(IrOp op, int dst, int a) {
        return new IrInstr(op, dst, a, NONE, null, null, null);
    }

    public static IrInstr binary(IrOp op, int dst, int a, int b) {
        return new IrInstr(op, dst, a, b, null, null, null);
    }

    public static IrInstr call(int dst, String function, int[] args) {
        return new IrInstr(IrOp.CALL, dst, NONE, NONE, function, null, args);
    }

    public static IrInstr callValue(int dst, int function, int[] args) {
        return new IrInstr(IrOp.CALL_VALUE, dst, function, NONE, null, null, args);
    }

    public static IrInstr methodCall(int dst, int receiver, String method, int[] args) {
        return new IrInstr(IrOp.METHOD_CALL, dst, receiver, NONE, method, null, args);
    }

    public static IrInstr memberGet(int dst, int target, String member) {
        return new IrInstr(IrOp.MEMBER_GET, dst, target, NONE, member, null, null);
    }

    public static IrInstr collection(IrOp op, int dst, int[] elements) {
        return new IrInstr(op, dst, NONE, NONE, null, null, elements);
    }

    public static IrInstr indexGet(int dst, int container, int index) {
        return new IrInstr(IrOp.INDEX_GET, dst, container, index, null, null, null);
    }

    public static IrInstr indexSet(int container, int index, int value) {
        return new IrInstr(IrOp.INDEX_SET, NONE, container, index, null, null, new int[]{value});
    }

    public static IrInstr getIter(int dst, int source) {
        return new IrInstr(IrOp.GET_ITER, dst, source, NONE, null, null, null);
    }

    public static IrInstr iterNext(int dst, int cursor, String exitLabel) {
        return new IrInstr(IrOp.ITER_NEXT, dst, cursor, NONE, exitLabel, null, null);
    }

    public static IrInstr closure(int dst, String function, int[] captures) {
        return new IrInstr(IrOp.CLOSURE, dst, NONE, NONE, function, null, captures);
    }

    public static IrInstr stringInterp(int dst, int[] parts) {
        return new IrInstr(IrOp.STRING_INTERP, dst, NONE, NONE, null, null, parts);
    }

    public static IrInstr label(String name) {
        return new IrInstr(IrOp.LABEL, NONE, NONE, NONE, name, null, null);
    }

    /** 行号标记，{@code a} 存源码行号。 */
    public static IrInstr line(int line) {
        return new IrInstr(IrOp.LINE, NONE, line, NONE, null, null, null);
    }

    public static IrInstr jump(String label) {
        return new IrInstr(IrOp.JUMP, NONE, NONE, NONE, label, null, null);
    }

    public static IrInstr jumpIf(IrOp op, int condition, String label) {
        return new IrInstr(op, NONE, condition, NONE, label, null, null);
    }

    public static IrInstr ret(int value) {
        return new IrInstr(IrOp.RETURN, NONE, value, NONE, null, null, null);
    }

    public static IrInstr retVoid() {
        return new IrInstr(IrOp.RETURN, NONE, NONE, NONE, null, null, null);
    }

    // ------------------------------------------------------------------ 查询

    /** 本指令写入的临时值编号，没有则 {@link #NONE}。 */
    public int def() {
        return dst;
    }

    /** 本指令读取的临时值编号，供优化器统计使用。 */
    public int[] uses() {
        return switch (op) {
            case CONST, LOAD, LABEL, JUMP, LINE -> NO_EXTRA;
            case STORE, RETURN, JUMP_IF_FALSE, JUMP_IF_TRUE, JUMP_IF_NULL ->
                    a == NONE ? NO_EXTRA : new int[]{a};
            case CALL, LIST_NEW, SET_NEW, TUPLE_NEW, MAP_NEW, STRING_INTERP, CLOSURE -> extra;
            case CALL_VALUE, METHOD_CALL, INDEX_SET -> concat(a, b, extra);
            default -> b == NONE ? new int[]{a} : new int[]{a, b};
        };
    }

    private static int[] concat(int a, int b, int[] extra) {
        int count = (a == NONE ? 0 : 1) + (b == NONE ? 0 : 1) + extra.length;
        int[] result = new int[count];
        int i = 0;
        if (a != NONE) {
            result[i++] = a;
        }
        if (b != NONE) {
            result[i++] = b;
        }
        System.arraycopy(extra, 0, result, i, extra.length);
        return result;
    }

    /** 跳转类指令的目标标签；非跳转返回 null。 */
    public String target() {
        return switch (op) {
            case JUMP, JUMP_IF_FALSE, JUMP_IF_TRUE, JUMP_IF_NULL, ITER_NEXT -> name;
            default -> null;
        };
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (dst != NONE) {
            sb.append("t").append(dst).append(" = ");
        }
        sb.append(op);
        if (name != null) {
            sb.append(' ').append(name);
        }
        if (literal != null) {
            sb.append(' ').append(literal instanceof String text ? '"' + text + '"' : literal);
        }
        if (a != NONE) {
            sb.append(" t").append(a);
        }
        if (b != NONE) {
            sb.append(", t").append(b);
        }
        if (extra.length > 0) {
            sb.append(' ').append(Arrays.toString(extra));
        }
        return sb.toString();
    }
}