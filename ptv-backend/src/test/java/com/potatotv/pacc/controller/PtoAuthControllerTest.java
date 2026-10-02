package com.potatotv.pacc.controller;

import com.potatotv.pacc.config.PtoKeyFactory;
import com.potatotv.pacc.service.TokenService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PTO 对外端点契约：JWKS 公钥分发（RS256 才开放）与刷新令牌换发。
 *
 * <p>钉死 JWK 字段名与整数编码口径（RFC 7518：无符号大端、无前导零），
 * 以及刷新端点的取令牌顺序与失败文案边界。</p>
 */
class PtoAuthControllerTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String KID = "kid-test-1";

    private static KeyPair rsaKeyPair;
    private static String privatePem;
    private static String publicPem;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        rsaKeyPair = generator.generateKeyPair();
        privatePem = pem("PRIVATE KEY", rsaKeyPair.getPrivate().getEncoded());
        publicPem = pem("PUBLIC KEY", rsaKeyPair.getPublic().getEncoded());
    }

    @Test
    void jwksExposedForRs256() {
        PtoKeyFactory keys = rsaFactory();
        PtoAuthController controller = new PtoAuthController(keys, new TokenService(keys));

        ResponseEntity<?> resp = controller.jwks();
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        List<?> published = (List<?>) body.get("keys");
        assertEquals(1, published.size());

        Map<?, ?> jwk = (Map<?, ?>) published.get(0);
        assertEquals("RSA", jwk.get("kty"));
        assertEquals("sig", jwk.get("use"));
        assertEquals("RS256", jwk.get("alg"));
        assertEquals(KID, jwk.get("kid"));

        String n = String.valueOf(jwk.get("n"));
        String e = String.valueOf(jwk.get("e"));
        assertFalse(n.isBlank());
        assertFalse(e.isBlank());
        // JWK 要求无填充；Base64Url 编码不得出现 '='
        assertFalse(n.contains("="));
        assertFalse(e.contains("="));

        // 无符号大端：去掉 BigInteger 正号补出的前导 0，首字节不能是 0
        byte[] modulus = Base64.getUrlDecoder().decode(n);
        assertTrue(modulus[0] != 0);
        RSAPublicKey real = (RSAPublicKey) rsaKeyPair.getPublic();
        assertEquals(real.getModulus(), new BigInteger(1, modulus));
        assertEquals(real.getPublicExponent(), new BigInteger(1, Base64.getUrlDecoder().decode(e)));
    }

    @Test
    void jwksNotAvailableForHs256() {
        PtoKeyFactory keys = hsFactory();
        PtoAuthController controller = new PtoAuthController(keys, new TokenService(keys));
        assertEquals(HttpStatus.NOT_FOUND, controller.jwks().getStatusCode());
    }

    @Test
    void refreshIssuesNewAccessTokenFromBody() {
        PtoKeyFactory keys = rsaFactory();
        TokenService tokenService = new TokenService(keys);
        PtoAuthController controller = new PtoAuthController(keys, tokenService);
        TokenService.TokenPair pair = tokenService.createTokenPair("PT1", "dev-1", false);

        ResponseEntity<?> resp = controller.refresh(null,
                Map.of("refresh_token", pair.refreshToken(), "device_fingerprint", "dev-1"));
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        String access = String.valueOf(((Map<?, ?>) resp.getBody()).get("access_token"));
        assertFalse(access.isBlank());
        // 新访问令牌可被验签，且仍绑定同一设备
        assertEquals("PT1", new TokenService(keys).verifyWithDevice(access, "dev-1"));
    }

    @Test
    void refreshAcceptsCookieToken() {
        PtoKeyFactory keys = hsFactory();
        TokenService tokenService = new TokenService(keys);
        PtoAuthController controller = new PtoAuthController(keys, tokenService);
        TokenService.TokenPair pair = tokenService.createTokenPair("PT2", "dev-9", false);

        // cookie 里的令牌优先于 body；body 里塞一个无效 refresh_token 也不影响
        ResponseEntity<?> resp = controller.refresh(pair.refreshToken(),
                Map.of("refresh_token", "bogus", "device_fingerprint", "dev-9"));
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertFalse(String.valueOf(((Map<?, ?>) resp.getBody()).get("access_token")).isBlank());

        // 设备绑定的刷新令牌缺少指纹时不放行
        assertEquals(HttpStatus.UNAUTHORIZED, controller.refresh(pair.refreshToken(), null).getStatusCode());
    }

    @Test
    void refreshRejectsAccessToken() {
        PtoKeyFactory keys = hsFactory();
        TokenService tokenService = new TokenService(keys);
        PtoAuthController controller = new PtoAuthController(keys, tokenService);
        TokenService.TokenPair pair = tokenService.createTokenPair("PT1", "dev-1", false);

        // 访问令牌的 type=access，不能当刷新令牌用
        ResponseEntity<?> resp = controller.refresh(null, Map.of("refresh_token", pair.accessToken()));
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    void refreshRejectsOtherDevice() {
        PtoKeyFactory keys = hsFactory();
        TokenService tokenService = new TokenService(keys);
        PtoAuthController controller = new PtoAuthController(keys, tokenService);
        TokenService.TokenPair pair = tokenService.createTokenPair("PT1", "dev-1", false);

        ResponseEntity<?> resp = controller.refresh(null,
                Map.of("refresh_token", pair.refreshToken(), "device_fingerprint", "dev-2"));
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    void refreshMissingTokenRejected() {
        PtoKeyFactory keys = hsFactory();
        PtoAuthController controller = new PtoAuthController(keys, new TokenService(keys));
        assertEquals(HttpStatus.BAD_REQUEST, controller.refresh(null, Map.of()).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.refresh(null, null).getStatusCode());
    }

    private static PtoKeyFactory rsaFactory() {
        return new PtoKeyFactory("RS256", SECRET, privatePem, publicPem, KID);
    }

    private static PtoKeyFactory hsFactory() {
        return new PtoKeyFactory("HS256", SECRET, "", "", "");
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }
}
