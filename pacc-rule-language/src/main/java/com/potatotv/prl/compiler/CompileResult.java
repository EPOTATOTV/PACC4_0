package com.potatotv.prl.compiler;

import com.potatotv.prl.PrlException;
import com.potatotv.prl.ast.RuleFile;
import com.potatotv.prl.bytecode.PrlBytecode;
import com.potatotv.prl.check.Diagnostic;
import com.potatotv.prl.check.TypeCheckResult;

import java.util.List;

/**
 * 一次编译的完整结果（设计文档 §2.12.2 的编译步骤，以及管理端编辑器的实时检查接口 §2.15.1）。
 *
 * <p>编译失败不抛异常，而是把诊断装在这里返回。原因和 {@code TypeChecker} 一样：编辑器要一次性
 * 展示所有错误，热加载要拿错误信息去告警，只有 {@code PrlCompiler.compile} 那种「只要产物」的
 * 调用方才需要抛。</p>
 *
 * @param file        解析出的 AST，语法阶段就失败时为 {@code null}
 * @param bytecode    编译产物，失败时为 {@code null}
 * @param types       类型检查的完整结果（每个表达式的推断类型），语法阶段失败时为 {@code null}；
 *                    静态分析要用它判断哪些表达式可能为 null（§2.14.1）
 * @param diagnostics 类型检查的完整诊断（含警告），失败时至少含一条错误
 */
public record CompileResult(RuleFile file, PrlBytecode bytecode, TypeCheckResult types,
                            List<Diagnostic> diagnostics) {

    public CompileResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    static CompileResult failed(RuleFile file, List<Diagnostic> diagnostics) {
        return new CompileResult(file, null, null, diagnostics);
    }

    public boolean ok() {
        return bytecode != null;
    }

    public List<Diagnostic> errors() {
        return diagnostics.stream().filter(Diagnostic::isError).toList();
    }

    public List<Diagnostic> warnings() {
        return diagnostics.stream().filter(diagnostic -> !diagnostic.isError()).toList();
    }

    /** 把第一条错误包成异常；编译成功时调用会抛 {@link IllegalStateException}。 */
    PrlException toException() {
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.isError()) {
                return new PrlException(diagnostic.message(), diagnostic.line(), diagnostic.col());
            }
        }
        throw new IllegalStateException("编译没有失败，不该取异常");
    }

    /** 全部诊断拼成一段人话，用于告警与日志。 */
    public String describe() {
        if (diagnostics.isEmpty()) {
            return "编译通过";
        }
        StringBuilder sb = new StringBuilder();
        for (Diagnostic diagnostic : diagnostics) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(diagnostic);
        }
        return sb.toString();
    }
}