package com.potatotv.paccclient;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void decodesFlatObject() {
        Map<String, Object> m = Json.decodeObject("{\"a\":1,\"b\":\"x\",\"c\":true,\"d\":null,\"e\":2.5}");
        assertEquals(1L, m.get("a"));
        assertEquals("x", m.get("b"));
        assertEquals(Boolean.TRUE, m.get("c"));
        assertEquals(null, m.get("d"));
        assertEquals(2.5, m.get("e"));
    }

    @Test
    void decodesNumbersToLongOrDouble() {
        Map<String, Object> m = Json.decodeObject("{\"i\":42,\"f\":3.14}");
        assertEquals(42L, m.get("i"));
        assertEquals(3.14, m.get("f"));
    }

    @Test
    void decodesNestedArrayAndEscapedString() {
        Map<String, Object> m = Json.decodeObject(
                "{\"changes\":[{\"id\":\"a\",\"name\":\"x\\n\"}],\"s\":\"say \\\"hi\\\"\"}");
        List<?> changes = (List<?>) m.get("changes");
        assertEquals("a", ((Map<?, ?>) changes.get(0)).get("id"));
        assertEquals("x\n", ((Map<?, ?>) changes.get(0)).get("name"));
        assertEquals("say \"hi\"", m.get("s"));
    }

    @Test
    void decodesEmptyContainer() {
        assertTrue(Json.decodeObject("{}").isEmpty());
        assertTrue(((List<?>) Json.decode("[]")).isEmpty());
    }

    @Test
    void decodesWhitespace() {
        assertFalse(Json.decodeObject("{\n  \"k\": true\n}").isEmpty());
    }

    @Test
    void rejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> Json.decodeObject("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> Json.decodeObject("not-json"));
        assertThrows(IllegalArgumentException.class, () -> Json.decodeObject("{\"a\":1}x"));
        assertThrows(IllegalArgumentException.class, () -> Json.decodeObject("[1,2"));
    }
}