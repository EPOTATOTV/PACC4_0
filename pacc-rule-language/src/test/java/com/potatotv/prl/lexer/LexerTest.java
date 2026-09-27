package com.potatotv.prl.lexer;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 词法分析器测试，覆盖验收项 R01「100% Token 类型覆盖」。
 *
 * <p>注意语料里避开了 {@code s}/{@code m}/{@code h} 这类单字母变量名：它们是单位关键字，
 * 在 PRL 里本身就是关键字（文档 §2.2.2），拿来做变量名会得到单位 token。</p>
 */
class LexerTest {

    /**
     * 词法器永远不会产出的 token 类型：
     * <ul>
     *   <li>{@code ERROR}：非法输入直接抛 {@link LexException}，不产出错误 token，避免错误被静默忽略；</li>
     *   <li>{@code BOOL_LITERAL}/{@code NULL_LITERAL}：文档 §2.5.1 枚举里同时列了字面量组与关键字组，
     *       实际产出的是关键字组 {@code TRUE}/{@code FALSE}/{@code NULL}，这两个保留以对齐枚举。</li>
     * </ul>
     */
    private static final Set<TokenType> NEVER_EMITTED = EnumSet.of(
            TokenType.ERROR, TokenType.BOOL_LITERAL, TokenType.NULL_LITERAL);

    private static List<Token> lex(String source) {
        return new Lexer(source).tokenize();
    }

    @Test
    void 覆盖全部token类型() {
        String corpus = """
                rule "r" {
                    version: "1.0.0"
                    author: "a"
                    severity: high
                    category: "c"
                    cooldown: 300s
                    enabled: true
                    description: "d"
                    input {
                        v01: int
                        v02: float
                        v03: bool
                        v04: string
                        v05: list[int]
                        v06: map[string, int]
                        v07: set[string]
                        v08: tuple[int, string]
                    }
                    let v10 = 42
                    let v11 = 3.14
                    let v12 = 1_000_000
                    let v13 = 0xFF
                    let v14 = 0b1010
                    let v15 = "s"
                    let v16 = 's'
                    let v17 = "line1\\nline2"
                    let v18 = f"p: {v04}"
                    let v19 = true
                    let v20 = false
                    let v21 = null
                    let v22 = [1, 2, 3]
                    let v23 = {"k": 1}
                    let v24 = {1, 2}
                    let v25 = (1, "x")
                    let v26 = 5 per_second
                    let v27 = 5 per_minute
                    let v28 = 10 ms
                    let v29 = 10 s
                    let v30 = 10 m
                    let v31 = 10 h
                    let v32 = 10 KB
                    let v33 = 10 MB
                    let v34 = 10 GB
                    let v35 = 300s
                    let v36 = low
                    let v37 = medium
                    let v38 = critical
                    let v39 = avoided - 1 * 2 / 3
                    let v40 = v04.id
                    when: NOT (v10 == 1 OR v11 != 2) AND v10 >= 1 AND v10 <= 2 AND v10 > 1 AND v10 < 2
                        AND v10 in v22 AND v10 not in v22 AND 1 .. 10
                    then:
                        let acc = 0
                        acc += 1
                        acc -= 1
                        acc *= 2
                        acc /= 2
                        ;
                        if v10 == 1:
                            return null
                        else if v10 == 2:
                            log(level = "warn", message = "m")
                        else:
                            emit_alert(type = "T", confidence = 1.0, evidence = v23)
                        end
                        for item in v22:
                            acc = acc + 1
                        end
                        while acc < 10:
                            acc = acc + 1
                        end
                        v22 |> filter(x -> x > 0) |> map(x -> x)
                        v10 ?? 0
                        v10 ^ 2
                        v10 % 2
                        record_evidence("k", v21)
                        trigger_redscreen(reason = "r")
                        ban_feature(feature = "f", duration = 60s)
                    # a comment line
                }
                """;

        EnumSet<TokenType> seen = EnumSet.noneOf(TokenType.class);
        for (Token t : lex(corpus)) {
            seen.add(t.type());
        }

        EnumSet<TokenType> missing = EnumSet.allOf(TokenType.class);
        missing.removeAll(seen);
        missing.removeAll(NEVER_EMITTED);
        assertTrue(missing.isEmpty(), "以下 token 类型未被语料覆盖: " + missing);
    }

    @Test
    void 换行与注释也会产出token() {
        List<Token> tokens = lex("# c\n1");
        assertEquals(TokenType.COMMENT, tokens.get(0).type());
        assertEquals("# c", tokens.get(0).value());
        assertEquals(TokenType.NEWLINE, tokens.get(1).type());
        assertEquals(TokenType.INT_LITERAL, tokens.get(2).type());
        assertEquals(TokenType.EOF, tokens.get(tokens.size() - 1).type());
    }

    @Test
    void not与in相邻时合并为单一运算符() {
        List<Token> tokens = lex("a not in b");
        assertEquals(List.of(TokenType.IDENTIFIER, TokenType.NOT_IN, TokenType.IDENTIFIER),
                tokens.subList(0, 3).stream().map(Token::type).toList());
        assertEquals("not in", tokens.get(1).value());
    }

    @Test
    void not后跟标识符时不合并() {
        List<Token> tokens = lex("NOT in_list");
        assertEquals(TokenType.NOT, tokens.get(0).type());
        assertEquals(TokenType.IDENTIFIER, tokens.get(1).type());
    }

    @Test
    void 数字字面量与单位后缀分离() {
        List<Token> tokens = lex("300s 10MB 1.5ms 0xFF 0b1010 1_000");
        assertEquals("300", tokens.get(0).value());
        assertEquals("s", tokens.get(0).unit());
        assertEquals("10", tokens.get(1).value());
        assertEquals("mb", tokens.get(1).unit());
        assertEquals(TokenType.FLOAT_LITERAL, tokens.get(2).type());
        assertEquals("ms", tokens.get(2).unit());
        assertEquals("0xFF", tokens.get(3).value());
        assertEquals(TokenType.INT_LITERAL, tokens.get(3).type());
        assertEquals("0b1010", tokens.get(4).value());
        assertEquals("1_000", tokens.get(5).value());
    }

    @Test
    void 单位后跟标识符字符时不当作单位() {
        List<Token> tokens = lex("10msec");
        assertEquals("10", tokens.get(0).value());
        assertNull(tokens.get(0).unit());
        assertEquals(TokenType.IDENTIFIER, tokens.get(1).type());
        assertEquals("msec", tokens.get(1).value());
    }

    @Test
    void 字符串转义与fstring() {
        List<Token> tokens = lex("\"a\\nb\\u4e2d\" f\"p: {x.y}\"");
        assertEquals(TokenType.STRING_LITERAL, tokens.get(0).type());
        assertEquals("a\nb中", tokens.get(0).value());
        assertEquals("p: {x.y}", tokens.get(1).value());
    }

    @Test
    void 非法输入抛出词法错误() {
        assertThrows(LexException.class, () -> lex("\"未闭合"));
        assertThrows(LexException.class, () -> lex("1 @ 2"));
        assertThrows(LexException.class, () -> lex("\"a\\qb\""));
        assertThrows(LexException.class, () -> lex("0x"));
    }

    @Test
    void 行列号按1基递增() {
        List<Token> tokens = lex("a\n  b");
        assertEquals(1, tokens.get(0).line());
        assertEquals(1, tokens.get(0).col());
        assertEquals(2, tokens.get(2).line());
        assertEquals(3, tokens.get(2).col());
    }

    @Test
    void 科学计数法与小数点后成员访问() {
        assertEquals(TokenType.FLOAT_LITERAL, lex("1.5e3").get(0).type());
        assertEquals(TokenType.FLOAT_LITERAL, lex("1e3").get(0).type());
        // "1." 后面不是数字，按整数 + 成员访问切分
        List<Token> tokens = lex("1.foo");
        assertEquals(TokenType.INT_LITERAL, tokens.get(0).type());
        assertEquals(TokenType.DOT, tokens.get(1).type());
    }
}