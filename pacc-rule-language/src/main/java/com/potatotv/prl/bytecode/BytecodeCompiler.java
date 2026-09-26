package com.potatotv.prl.bytecode;

import com.potatotv.prl.PrlException;
import com.potatotv.prl.ast.Rule;
import com.potatotv.prl.ast.RuleFile;
import com.potatotv.prl.ast.RuleMetadata;
import com.potatotv.prl.ir.IrFunction;
import com.potatotv.prl.ir.IrInstr;
import com.potatotv.prl.ir.IrOp;
import com.potatotv.prl.ir.IrProgram;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IR 翻译成字节码（设计文档 §2.8）。
 *
 * <p>翻译是逐条对应的，没有寄存器分配这一步要解：IR 的临时值编号直接就是寄存器号，
 * 而变量本来就走名字槽位（{@code LOAD}/{@code STORE}）。这样 §2.9.2 里说的「寄存器分配」
 * 实际上在 IR 生成阶段就做完了 —— 临时值编号按语句重置，同时活跃的临时值数量就是寄存器需求。</p>
 *
 * <p>跳转偏移一律相对「偏移字段之后的位置」，也就是 VM 顺序读完操作数后的 {@code ip}，
 * 所以执行跳转时只要 {@code ip += offset}，不需要回退重算指令起始地址。</p>
 */
public final class BytecodeCompiler {

    /** §2.8.2：寄存器 1 字节，所以最多 256 个。 */
    public static final int MAX_REGISTERS = 256;

    private BytecodeCompiler() {
    }

    public static PrlBytecode compile(RuleFile file, IrProgram program) {
        List<IrFunction> irFunctions = program.functions();
        if (irFunctions.size() > 0xFFFF) {
            throw new PrlException("函数数量超过字节码格式上限 65535");
        }

        Map<String, Integer> functionIndex = new LinkedHashMap<>();
        for (int i = 0; i < irFunctions.size(); i++) {
            functionIndex.put(irFunctions.get(i).name(), i);
        }
        Map<String, Integer> strings = new LinkedHashMap<>();

        // 函数名、形参名、捕获名与规则名必须预先登记进字符串表：PrlcFormat 写代码段时按字符串表
        // 下标去查它们，而它们从不作为指令操作数出现，光靠 Builder.constant 的惰性登记永远补不上，
        // 写 .prlc 时会直接抛「字符串表里缺少 'rule_xxx'」。
        for (IrFunction irFunction : irFunctions) {
            intern(strings, irFunction.name());
            for (String param : irFunction.params()) {
                intern(strings, param);
            }
            for (String capture : irFunction.captures()) {
                intern(strings, capture);
            }
        }
        for (Rule rule : file.rules()) {
            intern(strings, rule.name());
        }

        List<BytecodeFunction> functions = new ArrayList<>(irFunctions.size());
        for (IrFunction irFunction : irFunctions) {
            functions.add(translate(irFunction, strings));
        }

        List<RuleEntry> rules = new ArrayList<>();
        for (Rule rule : file.rules()) {
            Integer index = functionIndex.get("rule_" + rule.name());
            if (index == null) {
                throw new PrlException("规则 '" + rule.name() + "' 没有对应的入口函数", rule.line(), rule.col());
            }
            rules.add(new RuleEntry(rule.name(), index, metadataOf(rule)));
        }

        return new PrlBytecode(functions, rules, new ArrayList<>(strings.keySet()));
    }

    // ------------------------------------------------------------------ 单函数

    private static BytecodeFunction translate(IrFunction function, Map<String, Integer> strings) {
        if (function.tempCount() > MAX_REGISTERS) {
            throw new PrlException("函数 " + function.name() + " 需要 " + function.tempCount()
                    + " 个寄存器，超过字节码上限 " + MAX_REGISTERS);
        }
        Builder builder = new Builder(strings);
        for (IrInstr instr : function.code()) {
            builder.emit(instr);
        }
        byte[] code = builder.finish();
        return new BytecodeFunction(function.name(), function.params(), function.captures(), code,
                Math.max(function.tempCount(), 1), function.entry());
    }

    private static Map<String, Object> metadataOf(Rule rule) {
        RuleMetadata metadata = rule.metadata();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", rule.name());
        if (metadata.version() != null) {
            map.put("version", metadata.version());
        }
        if (metadata.author() != null) {
            map.put("author", metadata.author());
        }
        if (metadata.description() != null) {
            map.put("description", metadata.description());
        }
        if (metadata.severity() != null) {
            map.put("severity", metadata.severity().name().toLowerCase(java.util.Locale.ROOT));
        }
        if (metadata.category() != null) {
            map.put("category", metadata.category());
        }
        map.put("cooldown_ms", metadata.cooldownMillis());
        map.put("enabled", metadata.enabled());
        return map;
    }

    /** 字符串表下标；已存在就返回，否则就地登记。全局唯一入口，避免登记逻辑两处漂移。 */
    private static int intern(Map<String, Integer> strings, String text) {
        Integer index = strings.get(text);
        if (index != null) {
            return index;
        }
        if (strings.size() > 0xFFFF) {
            throw new PrlException("字符串表超过字节码格式上限 65535 条");
        }
        int created = strings.size();
        strings.put(text, created);
        return created;
    }

    // ------------------------------------------------------------------ 编码

    private static final class Builder {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final Map<String, Integer> labels = new LinkedHashMap<>();
        private final List<Patch> patches = new ArrayList<>();
        private final Map<String, Integer> strings;

        Builder(Map<String, Integer> strings) {
            this.strings = strings;
        }

        byte[] finish() {
            byte[] code = out.toByteArray();
            for (Patch patch : patches) {
                Integer target = labels.get(patch.label());
                if (target == null) {
                    throw new PrlException("字节码里引用了不存在的标签 '" + patch.label() + "'");
                }
                int offset = target - patch.base();
                code[patch.position()] = (byte) (offset >>> 24);
                code[patch.position() + 1] = (byte) (offset >>> 16);
                code[patch.position() + 2] = (byte) (offset >>> 8);
                code[patch.position() + 3] = (byte) offset;
            }
            return code;
        }

        void emit(IrInstr instr) {
            if (instr.op() == IrOp.LABEL) {
                labels.put(instr.name(), out.size());
                return;
            }
            switch (instr.op()) {
                case CONST -> emitConst(instr);
                case LOAD -> {
                    u8(Opcode.LOAD);
                    reg(instr.dst());
                    shortOperand(constant(instr.name()));
                }
                case STORE -> {
                    u8(Opcode.STORE);
                    shortOperand(constant(instr.name()));
                    reg(instr.a());
                }
                case MOVE -> reg2(Opcode.MOVE, instr.dst(), instr.a());
                case NEG -> reg2(Opcode.NEG, instr.dst(), instr.a());
                case NOT -> reg2(Opcode.NOT, instr.dst(), instr.a());
                case TO_STRING -> reg2(Opcode.TO_STRING, instr.dst(), instr.a());
                case ADD -> reg3(Opcode.ADD, instr.dst(), instr.a(), instr.b());
                case SUB -> reg3(Opcode.SUB, instr.dst(), instr.a(), instr.b());
                case MUL -> reg3(Opcode.MUL, instr.dst(), instr.a(), instr.b());
                case DIV -> reg3(Opcode.DIV, instr.dst(), instr.a(), instr.b());
                case MOD -> reg3(Opcode.MOD, instr.dst(), instr.a(), instr.b());
                case POW -> reg3(Opcode.POW, instr.dst(), instr.a(), instr.b());
                case GT -> reg3(Opcode.GT, instr.dst(), instr.a(), instr.b());
                case LT -> reg3(Opcode.LT, instr.dst(), instr.a(), instr.b());
                case GTE -> reg3(Opcode.GTE, instr.dst(), instr.a(), instr.b());
                case LTE -> reg3(Opcode.LTE, instr.dst(), instr.a(), instr.b());
                case EQ -> reg3(Opcode.EQ, instr.dst(), instr.a(), instr.b());
                case NEQ -> reg3(Opcode.NEQ, instr.dst(), instr.a(), instr.b());
                case IN -> reg3(Opcode.IN, instr.dst(), instr.a(), instr.b());
                case NOT_IN -> reg3(Opcode.NOT_IN, instr.dst(), instr.a(), instr.b());
                case AND -> reg3(Opcode.AND, instr.dst(), instr.a(), instr.b());
                case OR -> reg3(Opcode.OR, instr.dst(), instr.a(), instr.b());
                case RANGE -> reg3(Opcode.RANGE, instr.dst(), instr.a(), instr.b());
                case INDEX_GET -> reg3(Opcode.INDEX_GET, instr.dst(), instr.a(), instr.b());
                case INDEX_SET -> {
                    u8(Opcode.INDEX_SET);
                    reg(instr.a());
                    reg(instr.b());
                    reg(instr.extra()[0]);
                }
                case CALL -> emitCall(instr);
                case CALL_VALUE -> emitCallValue(instr);
                case METHOD_CALL -> emitMethodCall(instr);
                case MEMBER_GET -> {
                    u8(Opcode.MEMBER_GET);
                    reg(instr.dst());
                    reg(instr.a());
                    shortOperand(constant(instr.name()));
                }
                case LIST_NEW -> emitCollection(Opcode.LIST_NEW, instr);
                case SET_NEW -> emitCollection(Opcode.SET_NEW, instr);
                case TUPLE_NEW -> emitCollection(Opcode.TUPLE_NEW, instr);
                case MAP_NEW -> {
                    u8(Opcode.MAP_NEW);
                    reg(instr.dst());
                    u8(instr.extra().length / 2);
                    for (int register : instr.extra()) {
                        reg(register);
                    }
                }
                case GET_ITER -> reg2(Opcode.GET_ITER, instr.dst(), instr.a());
                case ITER_NEXT -> {
                    u8(Opcode.ITER_NEXT);
                    reg(instr.dst());
                    reg(instr.a());
                    patch(instr.name());
                }
                case CLOSURE -> emitClosure(instr);
                case STRING_INTERP -> emitCollection(Opcode.STRING_INTERP, instr);
                case JUMP -> {
                    u8(Opcode.JUMP);
                    patch(instr.name());
                }
                case JUMP_IF_FALSE -> jumpIf(Opcode.JUMP_IF_FALSE, instr);
                case JUMP_IF_TRUE -> jumpIf(Opcode.JUMP_IF_TRUE, instr);
                case JUMP_IF_NULL -> jumpIf(Opcode.JUMP_IF_NULL, instr);
                case RETURN -> emitReturn(instr);
                case LINE -> {
                    u8(Opcode.LINE);
                    int32(instr.a());
                }
                default -> throw new PrlException("没有对应的字节码指令：" + instr.op());
            }
        }

        private void emitReturn(IrInstr instr) {
            if (instr.a() == IrInstr.NONE) {
                u8(Opcode.RETURN_VOID);
                return;
            }
            u8(Opcode.RETURN);
            reg(instr.a());
        }

        private void emitConst(IrInstr instr) {
            Object value = instr.literal();
            if (value instanceof String text) {
                u8(Opcode.CONST);
                reg(instr.dst());
                shortOperand(constant(text));
            } else if (value instanceof Long || value instanceof Integer) {
                long number = ((Number) value).longValue();
                if (number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
                    u8(Opcode.CONST_INT);
                    reg(instr.dst());
                    int32((int) number);
                } else {
                    // 超出 int32 的整数靠 double 常量承载：PRL 的 int 是 64 位，但超过 2^53 的
                    // 整数值本来也无法用 double 精确表示，规则里不会出现。
                    u8(Opcode.CONST_FLOAT);
                    reg(instr.dst());
                    float64(number);
                }
            } else if (value instanceof Double number) {
                u8(Opcode.CONST_FLOAT);
                reg(instr.dst());
                float64(number);
            } else if (value instanceof Boolean bool) {
                u8(bool ? Opcode.CONST_TRUE : Opcode.CONST_FALSE);
                reg(instr.dst());
            } else if (value == null) {
                u8(Opcode.CONST_NULL);
                reg(instr.dst());
            } else {
                throw new PrlException("不支持的常量类型 " + value.getClass().getSimpleName());
            }
        }

        private void emitCall(IrInstr instr) {
            u8(Opcode.CALL);
            shortOperand(constant(instr.name()));
            u8(instr.extra().length);
            for (int register : instr.extra()) {
                reg(register);
            }
            reg(instr.dst());
        }

        private void emitCallValue(IrInstr instr) {
            u8(Opcode.CALL_VALUE);
            reg(instr.a());
            u8(instr.extra().length);
            for (int register : instr.extra()) {
                reg(register);
            }
            reg(instr.dst());
        }

        private void emitMethodCall(IrInstr instr) {
            u8(Opcode.METHOD_CALL);
            reg(instr.a());
            shortOperand(constant(instr.name()));
            u8(instr.extra().length);
            for (int register : instr.extra()) {
                reg(register);
            }
            reg(instr.dst());
        }

        private void emitCollection(int opcode, IrInstr instr) {
            u8(opcode);
            reg(instr.dst());
            u8(instr.extra().length);
            for (int register : instr.extra()) {
                reg(register);
            }
        }

        private void emitClosure(IrInstr instr) {
            u8(Opcode.CLOSURE);
            reg(instr.dst());
            shortOperand(constant(instr.name()));
            u8(instr.extra().length);
            for (int register : instr.extra()) {
                reg(register);
            }
        }

        private void jumpIf(int opcode, IrInstr instr) {
            u8(opcode);
            reg(instr.a());
            patch(instr.name());
        }

        private void reg2(int opcode, int dst, int a) {
            u8(opcode);
            reg(dst);
            reg(a);
        }

        private void reg3(int opcode, int dst, int a, int b) {
            u8(opcode);
            reg(dst);
            reg(a);
            reg(b);
        }

        private void reg(int register) {
            if (register < 0 || register >= MAX_REGISTERS) {
                throw new PrlException("寄存器编号越界：" + register);
            }
            out.write(register);
        }

        /** 单字节操作数。方法名不能叫 {@code byte}（Java 关键字），所以用 u8。 */
        private void u8(int value) {
            out.write(value & 0xFF);
        }

        private void shortOperand(int value) {
            out.write((value >>> 8) & 0xFF);
            out.write(value & 0xFF);
        }

        private void int32(int value) {
            out.write((value >>> 24) & 0xFF);
            out.write((value >>> 16) & 0xFF);
            out.write((value >>> 8) & 0xFF);
            out.write(value & 0xFF);
        }

        private void float64(double value) {
            long bits = Double.doubleToLongBits(value);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) (bits >>> shift) & 0xFF);
            }
        }

        /** 记下一个待回填的相对偏移。 */
        private void patch(String label) {
            patches.add(new Patch(out.size(), out.size() + 4, label));
            int32(0);
        }

        /** 字符串表下标；新字符串就地登记。 */
        private int constant(String text) {
            return intern(strings, text);
        }
    }

    /**
     * 待回填的跳转。
     *
     * @param position 偏移字段起始位置
     * @param base     偏移字段之后的位置，目标偏移按它计算
     */
    private record Patch(int position, int base, String label) {
    }

    /** 供 {@code PrlcFormat} 复用：写一段 UTF-8 字符串。 */
    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}