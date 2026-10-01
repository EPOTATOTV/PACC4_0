package com.potatotv.pob;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PobRulesTest {

    private static final int PUBLIC = 0x0001;
    private static final int PRIVATE = 0x0002;
    private static final int PROTECTED = 0x0004;

    @TempDir
    Path tmp;

    private PobRules parse(String text) throws IOException {
        Path file = tmp.resolve("rules.pob");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return PobRules.parse(file);
    }

    @Test
    void 三种保留级别() throws IOException {
        PobRules rules = parse("""
                # 注释行与空行都要能跳过

                keep class com.potatotv.paccclient.Json all
                keep class com.potatotv.paccclient.apm.ApmCollector public
                keep class com.potatotv.paccclient.PaccClient
                keep member com.potatotv.paccclient.PaccClient main
                """);
        String json = "com/potatotv/paccclient/Json";
        assertTrue(rules.keepsClass(json));
        assertTrue(rules.keepsMember(json, "escape", PRIVATE));

        String apm = "com/potatotv/paccclient/apm/ApmCollector";
        assertTrue(rules.keepsMember(apm, "record", PUBLIC));
        assertTrue(rules.keepsMember(apm, "record", PROTECTED));
        assertFalse(rules.keepsMember(apm, "internal", PRIVATE));

        String entry = "com/potatotv/paccclient/PaccClient";
        assertTrue(rules.keepsClass(entry));
        assertTrue(rules.keepsMember(entry, "main", PUBLIC));
        assertFalse(rules.keepsMember(entry, "loop", PUBLIC));
    }

    @Test
    void 非法规则直接失败而不是静默忽略() {
        assertThrows(IOException.class, () -> parse("kepp class com.a.B"));
        assertThrows(IOException.class, () -> parse("keep class com.a.B exotic"));
        assertThrows(IOException.class, () -> parse("keep member com.a.B"));
    }
}