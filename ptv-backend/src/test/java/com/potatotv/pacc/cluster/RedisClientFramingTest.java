package com.potatotv.pacc.cluster;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RedisClient 的 RESP2 命令帧编码单测（不依赖真实 Redis，纯验证协议编码）。 */
class RedisClientFramingTest {

    private static byte[] encode(String... args) throws Exception {
        Method m = RedisClient.class.getDeclaredMethod("encode", String[].class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, (Object) args);
    }

    @Test
    void encodesArrayOfBulkStrings() throws Exception {
        String expected = "*3\r\n$5\r\nZCARD\r\n$3\r\nkey\r\n$1\r\nx\r\n";
        assertEquals(expected, new String(encode("ZCARD", "key", "x"), StandardCharsets.UTF_8));
    }

    @Test
    void multibyteArgsLengthIsByteLen() throws Exception {
        // 中文按 UTF-8 字节数计长，非法字节长度会被 Redis 拒绝
        String zh = "验证";
        byte[] frame = encode("GET", zh);
        String s = new String(frame, StandardCharsets.UTF_8);
        int len = zh.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(s.contains("$" + len + "\r\n" + zh + "\r\n"));
    }
}