package com.potatotv.pacc.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiSignatureTest {

    @Test
    void canonicalIsDeterministicAndLineSeparated() {
        String c1 = ApiSignature.canonical("POST", "/api/v1/detections", "1700000000000", "abc");
        String c2 = ApiSignature.canonical("POST", "/api/v1/detections", "1700000000000", "abc");
        assertTrue(c1.equals(c2));
        assertTrue(c1.startsWith("POST\n/api/v1/detections\n1700000000000\nabc"));
    }

    @Test
    void signAndVerify() {
        String secret = "k_abcdef";
        String canonical = ApiSignature.canonical("GET", "/api/v1/stats/overview", "1700000000000",
                ApiSignature.bodySha256Hex(""));
        String sig = ApiSignature.hmacHex(secret, canonical);
        assertFalse(sig.isBlank());

        String candidate = ApiSignature.hmacHex(secret, canonical);
        assertTrue(ApiSignature.constantTimeEquals(sig, candidate), "同密钥同内容应通过");

        String forged = ApiSignature.hmacHex("wrong-secret", canonical);
        assertFalse(ApiSignature.constantTimeEquals(sig, forged), "他方密钥不应通过");
    }

    @Test
    void constantTimeEqualsRejectsLengthMismatchAndNull() {
        assertFalse(ApiSignature.constantTimeEquals("abc", "abcd"));
        assertFalse(ApiSignature.constantTimeEquals(null, "abc"));
        assertTrue(ApiSignature.constantTimeEquals("abcdef", "abcdef"));
    }

    @Test
    void bodyShaStableForEmptyAndDistinct() {
        assertTrue(ApiSignature.bodySha256Hex("").equals(ApiSignature.bodySha256Hex("")));
        assertFalse(ApiSignature.bodySha256Hex("").equals(ApiSignature.bodySha256Hex("x")));
    }
}