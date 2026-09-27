package com.potatotv.prl.parser;

import com.potatotv.prl.PrlException;
import com.potatotv.prl.ast.Expression;
import com.potatotv.prl.ast.LetDecl;
import com.potatotv.prl.ast.Rule;
import com.potatotv.prl.ast.RuleFile;
import com.potatotv.prl.ast.Severity;
import com.potatotv.prl.ast.Statement;
import com.potatotv.prl.ast.UnitKind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 语法分析器测试，覆盖验收项 R02「所有语法结构可解析」。
 */
class ParserTest {

    private static final Path EXAMPLES = Path.of("examples");

    @Test
    void 解析全部示例规则() throws IOException {
        List<String> names = List.of("kill_aura", "speed_hack", "fly", "autoclicker", "xray");
        for (String name : names) {
            String source = Files.readString(EXAMPLES.resolve(name + ".prl"));
            RuleFile file;
            try {
                file = Parser.parseSource(source);
            } catch (PrlException e) {
                fail(name + ".prl 解析失败: " + e.getMessage());
                return;
            }
            assertEquals(1, file.rules().size(), name + " 应只含一条规则");
            assertTrue(file.rules().get(0).name().endsWith("_detection"),
                    name + " 规则名应以 _detection 结尾");
        }
    }

    @Test
    void 解析文档示例规则结构() throws IOException {
        Rule rule = Parser.parseSource(Files.readString(EXAMPLES.resolve("kill_aura.prl"))).rules().get(0);

        assertEquals("kill_aura_detection", rule.name());
        assertEquals("1.2.0", rule.metadata().version());
        assertEquals("PACC Security Team", rule.metadata().author());
        assertEquals(Severity.HIGH, rule.metadata().severity());
        assertEquals("combat", rule.metadata().category());
        assertEquals(300_000L, rule.metadata().cooldownMillis());
        assertTrue(rule.metadata().enabled());

        assertEquals(List.of("player", "attack_events", "system_state"),
                rule.input().fields().stream().map(input -> input.name()).toList());
        assertEquals("PlayerContext", rule.input().fields().get(0).type().name());
        assertEquals("list", rule.input().fields().get(1).type().name());
        assertEquals("AttackEvent", rule.input().fields().get(1).type().args().get(0).name());

        assertEquals(5, rule.lets().size());
        assertEquals("attack_rate", rule.lets().get(0).name());
        assertNotNull(rule.when());
        assertInstanceOf(Expression.BinaryExpr.class, rule.when().condition());

        assertEquals(3, rule.then().size());
        assertInstanceOf(Statement.ExprStmt.class, rule.then().get(0));
        assertInstanceOf(Statement.IfStmt.class, rule.then().get(2));
    }

    @Test
    void 具名实参与映射表字面量() throws IOException {
        Rule rule = Parser.parseSource(Files.readString(EXAMPLES.resolve("kill_aura.prl"))).rules().get(0);
        Statement.ExprStmt call = (Statement.ExprStmt) rule.then().get(0);
        Expression.CallExpr emit = (Expression.CallExpr) call.expression();

        assertEquals("emit_alert", emit.name());
        assertEquals(List.of("type", "confidence", "evidence"),
                emit.args().stream().map(Expression.Arg::name).toList());
        Expression.MapLiteral evidence = (Expression.MapLiteral) emit.args().get(2).value();
        assertEquals(4, evidence.entries().size());
        assertEquals("attack_rate", ((Expression.StringLiteral) evidence.entries().get(0).key()).value());
    }

    @Test
    void 控制流与复合赋值() {
        RuleFile file = Parser.parseSource("""
                rule "r" {
                    then:
                        let count = 0
                        if count > 10:
                            count = 1
                        else if count > 5:
                            count += 2
                        else:
                            count -= 1
                        end
                        for e in [1, 2, 3]:
                            count += e
                        end
                        while count < 100:
                            count *= 2
                        end
                        return count
                }
                """);
        List<Statement> body = file.rules().get(0).then();
        assertInstanceOf(LetDecl.class, body.get(0));

        Statement.IfStmt ifStmt = (Statement.IfStmt) body.get(1);
        assertInstanceOf(Statement.AssignStmt.class, ifStmt.thenBody().get(0));
        Statement.IfStmt elseIf = (Statement.IfStmt) ifStmt.elseBody().get(0);
        assertEquals(Statement.AssignOp.PLUS,
                ((Statement.AssignStmt) elseIf.thenBody().get(0)).op());
        assertEquals(Statement.AssignOp.MINUS,
                ((Statement.AssignStmt) elseIf.elseBody().get(0)).op());

        assertInstanceOf(Statement.ForStmt.class, body.get(2));
        assertInstanceOf(Statement.WhileStmt.class, body.get(3));
        assertInstanceOf(Statement.ReturnStmt.class, body.get(4));
    }

    @Test
    void 管道与lambda() {
        RuleFile file = Parser.parseSource("""
                rule "r" {
                    then:
                        let r = attack_events
                            |> filter(e -> e.damage > 50)
                            |> map(e -> e.damage)
                            |> average()
                        let summed = map(events, (a, b) -> a + b)
                }
                """);
        LetDecl pipe = (LetDecl) file.rules().get(0).then().get(0);
        Expression.PipeExpr outer = (Expression.PipeExpr) pipe.initializer();
        Expression.CallExpr average = (Expression.CallExpr) outer.right();
        assertEquals("average", average.name());
        Expression.PipeExpr inner = (Expression.PipeExpr) outer.left();
        Expression.CallExpr map = (Expression.CallExpr) inner.right();
        assertEquals("map", map.name());
        Expression.LambdaExpr lambda = (Expression.LambdaExpr) map.args().get(0).value();
        assertEquals(List.of("e"), lambda.params());

        LetDecl tupleLambda = (LetDecl) file.rules().get(0).then().get(1);
        Expression.CallExpr reduceCall = (Expression.CallExpr) tupleLambda.initializer();
        assertEquals(List.of("a", "b"), ((Expression.LambdaExpr) reduceCall.args().get(1).value()).params());
    }

    @Test
    void 字面量集合与区间() {
        RuleFile file = Parser.parseSource("""
                rule "r" {
                    then:
                        let a = [1, 2, 3]
                        let b = {"k": 1, "j": 2}
                        let c = {1, 2, 3}
                        let d = (1, "x", true)
                        let e = 1..10
                        let f = 3.14
                        let g = 0xFF
                        let sev = critical
                        let i = 1 per_minute
                        let j = 10MB
                        let k = if a[0] > 1: "big" else: "small" end
                        let msg = f"n={a[0]}"
                }
                """);
        List<Statement> body = file.rules().get(0).then();
        assertInstanceOf(Expression.ListLiteral.class, ((LetDecl) body.get(0)).initializer());
        assertInstanceOf(Expression.MapLiteral.class, ((LetDecl) body.get(1)).initializer());
        assertInstanceOf(Expression.SetLiteral.class, ((LetDecl) body.get(2)).initializer());
        assertInstanceOf(Expression.TupleLiteral.class, ((LetDecl) body.get(3)).initializer());
        assertInstanceOf(Expression.RangeExpr.class, ((LetDecl) body.get(4)).initializer());

        Expression.UnitLiteral rate = (Expression.UnitLiteral) ((LetDecl) body.get(8)).initializer();
        assertEquals(UnitKind.RATE, rate.kind());
        assertEquals(1.0 / 60.0, rate.perSecond(), 1e-12);

        Expression.UnitLiteral size = (Expression.UnitLiteral) ((LetDecl) body.get(9)).initializer();
        assertEquals(10L * 1024 * 1024, size.bytes());

        assertInstanceOf(Expression.IfExpr.class, ((LetDecl) body.get(10)).initializer());
        Expression.InterpolatedString interp = (Expression.InterpolatedString)
                ((LetDecl) body.get(11)).initializer();
        assertEquals(2, interp.parts().size());
    }

    @Test
    void 空规则体与缺省元数据() {
        Rule rule = Parser.parseSource("rule \"empty\" { }").rules().get(0);
        assertEquals("empty", rule.name());
        assertEquals(Severity.LOW, rule.metadata().severity());
        assertTrue(rule.metadata().enabled());
        assertEquals(0L, rule.metadata().cooldownMillis());
        assertTrue(rule.then().isEmpty());
        assertTrue(rule.lets().isEmpty());
    }

    @Test
    void 语法错误带行列位置() {
        ParseException missingBrace = assertThrows(ParseException.class,
                () -> Parser.parseSource("rule \"r\" {\n  let a = 1\n"));
        assertEquals(3, missingBrace.line());

        ParseException badBody = assertThrows(ParseException.class,
                () -> Parser.parseSource("rule \"r\" {\n  let a = \n}"));
        assertEquals(3, badBody.line());
        assertTrue(badBody.getMessage().contains("语法错误"));
    }

    @Test
    void 运算符优先级符合文档() {
        // AND 比 OR 紧、比较比 AND 紧、乘比加紧
        RuleFile file = Parser.parseSource("""
                rule "r" {
                    when: true OR true AND 1 + 2 * 3 > 4
                }
                """);
        Expression.BinaryExpr or = (Expression.BinaryExpr) file.rules().get(0).when().condition();
        assertEquals("OR", or.op());
        Expression.BinaryExpr and = (Expression.BinaryExpr) or.right();
        assertEquals("AND", and.op());
        Expression.BinaryExpr gt = (Expression.BinaryExpr) and.right();
        assertEquals(">", gt.op());
        Expression.BinaryExpr add = (Expression.BinaryExpr) gt.left();
        assertEquals("+", add.op());
        assertEquals("*", ((Expression.BinaryExpr) add.right()).op());
    }
}
