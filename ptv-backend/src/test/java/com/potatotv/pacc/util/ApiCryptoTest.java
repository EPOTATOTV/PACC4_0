package com.potatotv.pacc.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiCryptoTest {

    private static final String MASTER = "unit-test-master-secret";

    @Test
    void roundTrip() {
        String cipher = ApiCrypto.encrypt("my-api-secret-value", MASTER);
        assertEquals("my-api-secret-value", ApiCrypto.decrypt(cipher, MASTER));
    }

    @Test
    void cipherIsNotPlaintextAndRandomIv() {
        String a = ApiCrypto.encrypt("secret", MASTER);
        String b = ApiCrypto.encrypt("secret", MASTER);
        assertNotEquals("secret", a);
        assertNotEquals(a, b, "相同明文因随机 IV 不应得到相同密文");
    }

    @Test
    void tamperedCiphertextFails() {
        String cipher = ApiCrypto.encrypt("secret", MASTER);
        String tampered = cipher.substring(0, cipher.length() - 4) + "abcd";
        assertThrows(IllegalStateException.class, () -> ApiCrypto.decrypt(tampered, MASTER));
    }

    @Test
    void wrongMasterFails() {
        String cipher = ApiCrypto.encrypt("secret", MASTER);
        assertThrows(IllegalStateException.class, () -> ApiCrypto.decrypt(cipher, "other-master"));
    }
}