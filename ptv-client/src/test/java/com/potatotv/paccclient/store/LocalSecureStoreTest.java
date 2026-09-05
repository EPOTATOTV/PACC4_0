package com.potatotv.paccclient.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalSecureStoreTest {

    @TempDir Path dir;

    @Test
    void roundTripEncryptDecrypt() throws Exception {
        Path f = dir.resolve("a.enc");
        byte[] plain = "hello 世界".getBytes(StandardCharsets.UTF_8);
        LocalSecureStore.save(f, "pwd", plain);
        assertArrayEquals(plain, LocalSecureStore.load(f, "pwd"));
    }

    @Test
    void wrongPasswordFails() throws Exception {
        Path f = dir.resolve("b.enc");
        LocalSecureStore.save(f, "p1", new byte[]{1, 2, 3});
        assertThrows(Exception.class, () -> LocalSecureStore.load(f, "p2"));
    }

    @Test
    void tamperingDetected() throws Exception {
        Path f = dir.resolve("c.enc");
        LocalSecureStore.save(f, "pwd", new byte[]{1, 2, 3});
        byte[] blob = Files.readAllBytes(f);
        blob[blob.length - 1] ^= 0xFF; // 翻转密文末尾字节
        Files.write(f, blob);
        assertThrows(Exception.class, () -> LocalSecureStore.load(f, "pwd"));
    }

    @Test
    void stringsRoundTrip() throws Exception {
        Path f = dir.resolve("d.enc");
        List<String> in = List.of("aaa", "bbb中文", "ccc");
        LocalSecureStore.save(f, "pwd", LocalSecureStore.encodeStrings(in));
        assertEquals(in, LocalSecureStore.decodeStrings(LocalSecureStore.load(f, "pwd")));
    }
}