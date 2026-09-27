package com.potatotv.prl.compiler;

import com.potatotv.prl.PrlException;
import com.potatotv.prl.ast.RuleFile;
import com.potatotv.prl.bytecode.BytecodeCompiler;
import com.potatotv.prl.bytecode.PrlBytecode;
import com.potatotv.prl.check.Diagnostic;
import com.potatotv.prl.check.TypeCheckResult;
import com.potatotv.prl.check.TypeChecker;
import com.potatotv.prl.ir.IrGenerator;
import com.potatotv.prl.ir.IrProgram;
import com.potatotv.prl.parser.Parser;
import com.potatotv.prl.sandbox.PrlHostContext;
import com.potatotv.prl.types.PrlSignatures;

import java.util.List;
import java.util.Set;

/**
 * 源码到字节码的门面（设计文档 §2.12.2 里 {@code RuleManager} 依赖的 {@code PrlCompiler}）。
 *
 * <p>把 §2.6/§2.3/§2.7/§2.8 四段流水线串起来：{@code Parser → TypeChecker → IrGenerator →
 * BytecodeCompiler}。串起来的价值不在少写几行，而在<strong>类型检查的结果必须原样交给 IR 生成</strong>：
 * {@code +} 在 int/float/string/list 上是四条不同的指令，IR 依赖 {@link TypeCheckResult#types()} 挑路径，
 * 少传一层就得重推一遍类型。</p>
 *
 * <p>构造时可以带宿主，用来取两样东西：宿主的上下文类型（§2.3.3）与函数白名单（§2.11.1 L3）。
 * 不带宿主就是「脱离 PACC 单独编译」，此时不限制可调用的函数，全部交给标准库与运行期兜底。</p>
 */
public final class PrlCompiler {

    /** 词法/语法错误的诊断码；类型错误的码在 {@code TypeChecker} 里是 {@code PRL-T}。 */
    public static final String SYNTAX_CODE = "PRL-P";

    private final PrlHostContext host;

    public PrlCompiler() {
        this(PrlHostContext.EMPTY);
    }

    public PrlCompiler(PrlHostContext host) {
        this.host = host == null ? PrlHostContext.EMPTY : host;
    }

    /** 编译源码，任何错误都抛 {@link PrlException}。热加载与规则装载走这条。 */
    public PrlBytecode compile(String source) {
        CompileResult result = compileChecked(source);
        if (result.ok()) {
            return result.bytecode();
        }
        throw result.toException();
    }

    /** 编译源码，错误以诊断形式返回。管理端编辑器的实时检查走这条。 */
    public CompileResult compileChecked(String source) {
        RuleFile file;
        try {
            file = Parser.parseSource(source);
        } catch (PrlException e) {
            Diagnostic diagnostic = Diagnostic.error(SYNTAX_CODE, e.getMessage(), e.line(), e.col());
            return CompileResult.failed(null, List.of(diagnostic));
        }
        return compileChecked(file);
    }

    public CompileResult compileChecked(RuleFile file) {
        TypeCheckResult types = checker().check(file);
        if (!types.ok()) {
            // 类型不过关就不到 IR/字节码；但 types 照样带出去，静态分析还能靠它做空指针与复杂度判断。
            return new CompileResult(file, null, types, types.diagnostics());
        }
        IrProgram ir = IrGenerator.generate(file, types);
        return new CompileResult(file, BytecodeCompiler.compile(file, ir), types, types.diagnostics());
    }

    private TypeChecker checker() {
        // 白名单为空表示宿主没接管任何函数，此时不设限；否则表外调用应当在编译期被拒（§2.11.1 L3）。
        Set<String> available = host.getAvailableFunctions();
        Set<String> whitelist = available == null || available.isEmpty() ? null : available;
        return new TypeChecker(host.getTypes(), PrlSignatures.standard(), whitelist);
    }
}