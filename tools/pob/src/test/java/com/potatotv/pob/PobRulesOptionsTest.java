package com.potatotv.pob;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 强化规则的开关解析。默认值必须保守：所有高风险变换关闭，最小加密长度 4。
 * 非法值一律抛 {@link IOException}，不静默忽略。
 */
class PobRulesOptionsTest {

    @TempDir
    Path tmp;

    private PobRules parse(String text) throws IOException {
        Path file = tmp.resolve("rules.pob");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return PobRules.parse(file);
    }

    @Test
    void 默认全部关闭() throws IOException {
        PobRules rules = parse("keep class com.a.B\n");
        assertFalse(rules.encryptStrings());
        assertEquals(4, rules.encryptStringsMinLength());
        assertTrue(rules.encryptStringsKeep().isEmpty());
        assertFalse(rules.unicodeNames());
        assertFalse(rules.bogusCode());
        assertFalse(rules.integrity());
        assertFalse(rules.flatten());
    }

    @Test
    void 解析全部新开关与enhance() throws IOException {
        PobRules rules = parse("""
                enhance class com.potatotv.paccclient.apm.ApmCollector
                encrypt_strings = true
                encrypt_strings_min_length = 7
                encrypt_strings_keep = "keep-me","and-me"
                unicode_names = true
                bogus_code = true
                integrity = true
                flatten = true
                """);
        assertTrue(rules.encryptStrings());
        assertEquals(7, rules.encryptStringsMinLength());
        assertEquals(Set.of("keep-me", "and-me"), rules.encryptStringsKeep());
        assertTrue(rules.unicodeNames());
        assertTrue(rules.bogusCode());
        assertTrue(rules.integrity());
        assertTrue(rules.flatten());
        assertTrue(rules.enhancesClass("com/potatotv/paccclient/apm/ApmCollector"));
        assertFalse(rules.enhancesClass("com/potatotv/paccclient/Other"));
    }

    @Test
    void 白名单里的井号不被当成注释截断() throws IOException {
        PobRules rules = parse("encrypt_strings_keep = \"a#b\",c\n");
        assertEquals(Set.of("a#b", "c"), rules.encryptStringsKeep());
    }

    @Test
    void 非法开关值直接失败() {
        assertThrows(IOException.class, () -> parse("encrypt_strings = yes\n"));
        assertThrows(IOException.class, () -> parse("unicode_names = 1\n"));
        assertThrows(IOException.class, () -> parse("integrity = maybe\n"));
        assertThrows(IOException.class, () -> parse("encrypt_strings_min_length = many\n"));
        assertThrows(IOException.class, () -> parse("unknown_switch = true\n"));
        assertThrows(IOException.class, () -> parse("enhance com.a.B\n"));
        assertThrows(IOException.class, () -> parse("enhance member com.a.B x\n"));
    }
}