package com.potatotv.prl.ir;

/**
 * 三地址码指令集（设计文档 §2.7.2）。
 *
 * <p>文档给的表只列了十几条，实现时按需要系统性补齐：集合字面量、成员访问、迭代、闭包在表里没有，
 * 但语法（§2.2.3、§2.2.4）里有，缺了就没法把 AST 落下来。补齐的指令一律沿用同一种编码形状，
 * 不给每种形态发明新的操作数布局。</p>
 *
 * <p>操作数约定（见 {@link IrInstr}）：{@code dst}/{@code a}/{@code b} 都是临时值编号（{@code t0},
 * {@code t1}…），{@code name} 在 {@code LOAD}/{@code STORE} 里是变量槽名、在 {@code CALL} 里是函数名、
 * 在跳转指令里是标签名，{@code literal} 只在 {@code CONST} 里用，{@code extra} 承载变长操作数
 * （调用实参、字面量元素、闭包捕获）。</p>
 */
public enum IrOp {

    // ---- 数据搬运 ----
    /** {@code dst = literal} */
    CONST,
    /** {@code dst = slot[name]} */
    LOAD,
    /** {@code slot[name] = a} */
    STORE,
    /** {@code dst = a} */
    MOVE,

    // ---- 算术 ----
    ADD,
    SUB,
    MUL,
    DIV,
    MOD,
    POW,
    /** {@code dst = -a} */
    NEG,

    // ---- 比较与逻辑 ----
    GT,
    LT,
    GTE,
    LTE,
    EQ,
    NEQ,
    IN,
    NOT_IN,
    /** {@code dst = !a} */
    NOT,
    AND,
    OR,

    // ---- 调用 ----
    /** {@code dst = name(extra...)}，函数名走白名单解析（§2.11.1 L3） */
    CALL,
    /** {@code dst = a(extra...)}，{@code a} 是函数值（lambda） */
    CALL_VALUE,
    /** {@code dst = a.name(extra...)}，宿主对象方法 */
    METHOD_CALL,
    /** {@code dst = a.name}，宿主对象成员 */
    MEMBER_GET,

    // ---- 集合 ----
    /** {@code dst = [extra...]} */
    LIST_NEW,
    SET_NEW,
    TUPLE_NEW,
    /** {@code dst = {extra[0]: extra[1], extra[2]: extra[3], ...}} */
    MAP_NEW,
    /** {@code dst = a[b]}；list 用整数下标，map 用键，string 用下标取单字符 */
    INDEX_GET,
    /** {@code a[b] = c} */
    INDEX_SET,
    /** {@code dst = a..b}（左闭右闭） */
    RANGE,
    /** {@code dst = 迭代游标(a)} */
    GET_ITER,
    /** {@code dst = 游标下一个元素}；取不到时跳到 {@code name} 标签 */
    ITER_NEXT,

    // ---- 函数值 ----
    /** {@code dst = 闭包(name, extra...)}，{@code extra} 是被捕获的值 */
    CLOSURE,

    // ---- 字符串 ----
    /** {@code dst = 拼接(extra...)}，各片段已是字符串 */
    STRING_INTERP,
    /** {@code dst = 字符串化(a)} */
    TO_STRING,

    // ---- 控制流 ----
    /** 跳转目标标记，本身不产生字节码 */
    LABEL,
    /**
     * 源码行号标记（{@code a} 存行号）。执行时是空操作，唯一用途是让调试器能把断点打在源码行上：
     * 字节码本身不带行号，不在流里留标记，调试器就只能按指令下标下断点（§2.15.1）。
     */
    LINE,
    JUMP,
    /** {@code a} 为假则跳转 */
    JUMP_IF_FALSE,
    /** {@code a} 为真则跳转 */
    JUMP_IF_TRUE,
    /** {@code a} 为 null 则跳转，用于 {@code ??} 的短路形态 */
    JUMP_IF_NULL,
    /** {@code return [a]} */
    RETURN
}