package com.potatotv.prl.check;

import com.potatotv.prl.ast.LetDecl;
import com.potatotv.prl.ast.RuleFile;
import com.potatotv.prl.parser.Parser;
import com.potatotv.prl.types.HostTypeRegistry;
import com.potatotv.prl.types.PrlSignatures;
import com.potatotv.prl.types.PrlType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型检查器测试，覆盖验收项 R03「类型不匹配编译期报错」与 R04「空安全」。
 *
 * <p>类型表按 AST 节点身份索引，因此断言表达式类型时必须用「同一棵被检查过的树」，
 * 不能重新解析一遍源码，否则查到的是另一批节点。</p>
 */
class TypeCheckerTest {

    private static final Path EXAMPLES = Path.of("examples");

    private static RuleFile parse(String source) {
        return Parser.parseSource(source);
    }

    private static TypeCheckResult check(RuleFile file) {
        return new TypeChecker().check(file);
    }

    private static TypeCheckResult checkSource(String source) {
        return check(parse(source));
    }

    private static List<String> errorsOf(String source) {
        return checkSource(source).errors().stream().map(Diagnostic::message).toList();
    }

    private static String joinedErrors(String source) {
        return String.join(" | ", errorsOf(source));
    }

    /** 取规则 then 块里第 index 条 let 声明的初始化表达式类型。 */
    private static PrlType initializerType(RuleFile file, TypeCheckResult result, int index) {
        LetDecl let = (LetDecl) file.rules().get(0).then().get(index);
        return result.typeOf(let.initializer()).orElseThrow();
    }

    @Test
    void 全部示例规则通过类型检查() throws IOException {
        for (String name : List.of("kill_aura", "speed_hack", "fly", "autoclicker", "xray")) {
            RuleFile file = parse(Files.readString(EXAMPLES.resolve(name + ".prl")));
            TypeCheckResult result = check(file);
            assertTrue(result.ok(), name + ".prl 类型检查未通过: " + result);
            assertTrue(result.warnings().isEmpty(), name + ".prl 不应产生警告: " + result.warnings());
        }
    }

    @Test
    void 显式类型标注不匹配时报错() {
        List<String> errors = errorsOf("""
                rule "r" {
                    then:
                        let bad: int = "hello"
                }
                """);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("类型不匹配"), errors.get(0));
    }

    @Test
    void int与float不隐式混用() {
        String message = joinedErrors("""
                rule "r" {
                    then:
                        let x = 1 + 1.0
                }
                """);
        assertTrue(message.contains("不支持 int 与 float"), message);
    }

    @Test
    void 未使用变量只给警告() {
        TypeCheckResult result = checkSource("""
                rule "r" {
                    then:
                        let unused = 1
                }
                """);
        assertTrue(result.ok());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).message().contains("unused"));
    }

    @Test
    void 白名单外的函数编译期拒绝() {
        String message = joinedErrors("""
                rule "r" {
                    then:
                        process(1)
                }
                """);
        assertTrue(message.contains("未知函数"), message);
    }

    @Test
    void 未注册的宿主类型报错() {
        String message = joinedErrors("""
                rule "r" {
                    input {
                        ghost: NotRegistered
                    }
                }
                """);
        assertTrue(message.contains("未注册的类型"), message);
    }

    @Test
    void 宿主未提供函数时按白名单拒绝() {
        RuleFile file = parse("""
                rule "r" {
                    then:
                        let t = to_string(1)
                }
                """);
        TypeCheckResult result = new TypeChecker(HostTypeRegistry.standard(), PrlSignatures.standard(),
                Set.of("log", "record_evidence")).check(file);
        assertEquals(1, result.errors().size(), result.toString());
        assertTrue(result.errors().get(0).message().contains("宿主未提供函数"), result.toString());
    }

    @Test
    void lambda参数类型来自被调函数签名() {
        String source = """
                rule "r" {
                    input {
                        attack_events: list[AttackEvent]
                    }
                    then:
                        let damages = map(attack_events, e -> e.damage)
                }
                """;
        RuleFile file = parse(source);
        TypeCheckResult result = check(file);
        assertTrue(result.ok(), result.toString());
        assertEquals(PrlType.list(PrlType.FLOAT), initializerType(file, result, 0));
    }

    @Test
    void 管道与链式调用等价() {
        String source = """
                rule "r" {
                    input {
                        attack_events: list[AttackEvent]
                    }
                    then:
                        let result = attack_events
                            |> filter(e -> e.reach > 3.0)
                            |> map(e -> e.damage)
                            |> average()
                }
                """;
        RuleFile file = parse(source);
        TypeCheckResult result = check(file);
        assertTrue(result.ok(), result.toString());
        assertEquals(PrlType.FLOAT, initializerType(file, result, 0));
    }

    @Test
    void 重载按实参类型选择() {
        String source = """
                rule "r" {
                    then:
                        let a = abs(-3)
                        let b = abs(-3.5)
                        let c = contains("hello", "ell")
                        let d = contains([1, 2, 3], 2)
                        let e = sum([1.0, 2.0])
                        let f = sum([1, 2])
                }
                """;
        RuleFile file = parse(source);
        TypeCheckResult result = check(file);
        assertTrue(result.ok(), result.toString());
        List<PrlType> types = List.of(
                initializerType(file, result, 0),
                initializerType(file, result, 1),
                initializerType(file, result, 2),
                initializerType(file, result, 3),
                initializerType(file, result, 4),
                initializerType(file, result, 5));
        assertEquals(List.of(PrlType.INT, PrlType.FLOAT, PrlType.BOOL, PrlType.BOOL,
                PrlType.FLOAT, PrlType.INT), types);
    }

    @Test
    void 零参宿主方法可省略括号() {
        TypeCheckResult result = checkSource("""
                rule "r" {
                    input {
                        player: PlayerContext
                    }
                    when: NOT player.is_trusted
                    then:
                        log(level = "info", message = player.name)
                }
                """);
        assertTrue(result.ok(), result.toString());
    }

    @Test
    void 未知宿主成员报错() {
        String message = joinedErrors("""
                rule "r" {
                    input {
                        player: PlayerContext
                    }
                    then:
                        log(level = "info", message = player.nickname)
                }
                """);
        assertTrue(message.contains("没有成员 'nickname'"), message);
    }

    @Test
    void 空值合并让引用型可空处理() {
        String source = """
                rule "r" {
                    input {
                        player: PlayerContext
                    }
                    then:
                        let name = player.name ?? "unknown"
                }
                """;
        RuleFile file = parse(source);
        TypeCheckResult result = check(file);
        assertTrue(result.ok(), result.toString());
        assertEquals(PrlType.STRING, initializerType(file, result, 0));
    }

    @Test
    void 严重级字面量当字符串用() {
        TypeCheckResult result = checkSource("""
                rule "r" {
                    then:
                        let severity = "low"
                        severity = critical
                        record_evidence("severity", severity)
                }
                """);
        assertTrue(result.ok(), result.toString());
    }

    @Test
    void 宿主对象成员只读() {
        String message = joinedErrors("""
                rule "r" {
                    input {
                        player: PlayerContext
                    }
                    then:
                        player.reputation = 100
                }
                """);
        assertTrue(message.contains("不允许修改宿主对象的成员"), message);
    }

    @Test
    void 具名实参写错名字报错() {
        String message = joinedErrors("""
                rule "r" {
                    then:
                        log(level = "warn", text = "hi")
                }
                """);
        assertTrue(message.contains("没有名为 'text' 的形参"), message);
    }

    @Test
    void 缺少实参报错() {
        String message = joinedErrors("""
                rule "r" {
                    then:
                        log(level = "warn")
                }
                """);
        assertTrue(message.contains("缺少实参 'message'"), message);
    }

    @Test
    void when条件必须是bool() {
        String message = joinedErrors("""
                rule "r" {
                    when: 1
                }
                """);
        assertTrue(message.contains("when 条件 需要 bool"), message);
    }

    @Test
    void 未定义变量报错() {
        String message = joinedErrors("""
                rule "r" {
                    when: undefined_var > 1
                }
                """);
        assertTrue(message.contains("未定义的变量 'undefined_var'"), message);
    }

    @Test
    void 诊断带行列位置() {
        TypeCheckResult result = checkSource("""
                rule "r" {
                    then:
                        let bad: int = "hello"
                }
                """);
        Diagnostic diagnostic = result.errors().get(0);
        assertEquals(3, diagnostic.line());
        assertTrue(diagnostic.col() > 0);
        assertTrue(diagnostic.code().startsWith("PRL-"));
        assertFalse(diagnostic.message().isEmpty());
    }

    @Test
    void 表达式类型表按节点记录() {
        RuleFile file = parse("""
                rule "r" {
                    then:
                        let x = 1 + 2
                }
                """);
        TypeCheckResult result = check(file);
        // BinaryExpr + 两个 IntLiteral
        assertEquals(3, result.types().size(), result.types().toString());
        assertEquals(PrlType.INT, initializerType(file, result, 0));
    }
}