package com.potatotv.paccclient.store;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class KeyDeriverTest {

    @Test
    void deterministic() {
        byte[] salt = new byte[16];
        byte[] k1 = KeyDeriver.derive("pw", salt).getEncoded();
        byte[] k2 = KeyDeriver.derive("pw", salt).getEncoded();
        assertArrayEquals(k1, k2);
    }

    @Test
    void differentSaltDiffers() {
        byte[] s1 = new byte[16];
        byte[] s2 = new byte[16];
        s2[0] = 1;
        assertFalse(Arrays.equals(KeyDeriver.derive("pw", s1).getEncoded(),
                KeyDeriver.derive("pw", s2).getEncoded()));
    }

    @Test
    void keyLengthIs32Bytes() {
        byte[] k = KeyDeriver.derive("pw", new byte[16]).getEncoded();
        assertFalse(k.length != 32, "AES-256 密钥应为 32 字节");
    }
}