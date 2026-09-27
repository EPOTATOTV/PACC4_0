package com.potatotv.prl.check;

import com.potatotv.prl.ast.Expression;
import com.potatotv.prl.types.PrlType;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 类型检查结果。
 *
 * <p>除了诊断列表，还把每个表达式推断出的类型留在 {@link #types()} 里：字节码编译器需要据此挑选
 * 指令（{@code +} 在 int/float/string/list 上是四条不同的路径），如果让编译器自己再推一遍，
 * 两处推断规则迟早会漂移。</p>
 *
 * <p>表达式是 record，值相等语义会让 {@code key} 在 {@link java.util.HashMap} 里互相碰撞（同一位置的
 * 节点不会有重复，但依赖这一点太脆弱），所以用 {@link IdentityHashMap}，按 AST 节点身份查。</p>
 */
public final class TypeCheckResult {

    private final Map<Expression, PrlType> types;
    private final Map<Expression, int[]> callArgOrder;
    private final List<Diagnostic> diagnostics;

    TypeCheckResult(Map<Expression, PrlType> types, Map<Expression, int[]> callArgOrder,
                    List<Diagnostic> diagnostics) {
        this.types = types;
        this.callArgOrder = callArgOrder;
        this.diagnostics = List.copyOf(diagnostics);
    }

    static TypeCheckResult empty() {
        return new TypeCheckResult(new IdentityHashMap<>(), new IdentityHashMap<>(), List.of());
    }

    /** 按 AST 节点身份索引的表达式类型表。 */
    public Map<Expression, PrlType> types() {
        return types;
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    public Optional<PrlType> typeOf(Expression expression) {
        return Optional.ofNullable(types.get(expression));
    }

    /**
     * 具名实参的形参落位：{@code order[i]} 是第 {@code i} 个实参对应的形参下标。
     *
     * <p>只记录「顺序与形参表不一致」的调用（比如 {@code emit_alert} 的具名实参），位置实参的调用返回
     * {@code null}，让字节码编译器按原顺序直传。重载解析的结果只有类型检查器手里有，编译器自己再猜
     * 一次迟早会和新签名对不上。</p>
     */
    public int[] argumentOrder(Expression.CallExpr call) {
        return callArgOrder.get(call);
    }

    public List<Diagnostic> errors() {
        List<Diagnostic> errors = new ArrayList<>();
        for (Diagnostic d : diagnostics) {
            if (d.isError()) {
                errors.add(d);
            }
        }
        return errors;
    }

    public List<Diagnostic> warnings() {
        List<Diagnostic> warnings = new ArrayList<>();
        for (Diagnostic d : diagnostics) {
            if (!d.isError()) {
                warnings.add(d);
            }
        }
        return warnings;
    }

    /** 没有 ERROR 级诊断才算通过，编译才能继续。 */
    public boolean ok() {
        for (Diagnostic d : diagnostics) {
            if (d.isError()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        if (diagnostics.isEmpty()) {
            return "类型检查通过";
        }
        StringBuilder sb = new StringBuilder();
        for (Diagnostic d : diagnostics) {
            sb.append(d).append('\n');
        }
        return sb.toString();
    }
}