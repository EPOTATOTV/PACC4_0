package com.potatotv.pacc.config;

import com.potatotv.pacc.domain.ApiKey;
import com.potatotv.pacc.domain.ApiUsageLog;
import com.potatotv.pacc.repository.ApiKeyRepository;
import com.potatotv.pacc.repository.ApiUsageLogRepository;
import com.potatotv.pacc.service.ApiKeyService;
import com.potatotv.pacc.util.ApiCrypto;
import com.potatotv.pacc.util.ApiSignature;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 开放 API（/api/v1/**）认证：API Key + HMAC-SHA256 签名，时间窗 + nonce 防重放，
 * 可选 IP 白名单，免费/专业/企业分级限流，全量调用审计。
 * <p>认证失败返回 401/403/429 JSON；通过后在 request 注入 apiKey/apiTenantId/apiWrite 属性。</p>
 */
public class ApiV1AuthFilter extends OncePerRequestFilter {

    private static final String HEADER_KEY = "X-PTV-Key";
    private static final String HEADER_TS = "X-PTV-Timestamp";
    private static final String HEADER_SIG = "X-PTV-Signature";
    private static final String HEADER_NONCE = "X-PTV-Nonce";
    /** 时间窗（毫秒），超出视为过期请求。 */
    private static final long WINDOW_MS = 300_000L;

    private final ApiKeyRepository keyRepository;
    private final ApiUsageLogRepository usageLogRepository;
    private final ApiKeyService keyService;
    private final String masterSecret;
    private final Map<String, Long> nonceCache = new ConcurrentHashMap<>();

    public ApiV1AuthFilter(ApiKeyRepository keyRepository, ApiUsageLogRepository usageLogRepository,
                           ApiKeyService keyService, String masterSecret) {
        this.keyRepository = keyRepository;
        this.usageLogRepository = usageLogRepository;
        this.keyService = keyService;
        this.masterSecret = masterSecret;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String uri = request.getRequestURI();
        // 开放数据 API 与开发者自助门户共用同一套 API Key + HMAC 鉴权
        return !(uri.startsWith("/api/v1/") || uri.startsWith("/api/dev/"));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String keyId = request.getHeader(HEADER_KEY);
        String ts = request.getHeader(HEADER_TS);
        String sig = request.getHeader(HEADER_SIG);
        String nonce = request.getHeader(HEADER_NONCE);

        long start = System.currentTimeMillis();
        if (keyId == null || ts == null || sig == null || nonce == null) {
            auditAndReject(request, keyId, "缺请求头", HttpServletResponse.SC_UNAUTHORIZED, start);
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "{\"error\":\"缺少 API 鉴权头\"}");
            return;
        }

        // 1) 密钥存在且启用
        ApiKey key = keyRepository.findFirstByKeyId(keyId).orElse(null);
        if (key == null || !key.isEnabled()) {
            auditAndReject(request, keyId, "密钥无效或已禁用", HttpServletResponse.SC_UNAUTHORIZED, start);
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "{\"error\":\"无效的 API 密钥\"}");
            return;
        }

        // 2) 时间窗新鲜度
        long tsMs;
        try {
            tsMs = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            auditAndReject(request, keyId, "时间戳非法", HttpServletResponse.SC_UNAUTHORIZED, start);
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "{\"error\":\"时间戳非法\"}");
            return;
        }
        if (Math.abs(System.currentTimeMillis() - tsMs) > WINDOW_MS) {
            auditAndReject(request, keyId, "请求过期", HttpServletResponse.SC_UNAUTHORIZED, start);
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "{\"error\":\"请求时间戳过期\"}");
            return;
        }

        // 3) nonce 防重放
        if (!consumeNonce(nonce)) {
            auditAndReject(request, keyId, "重复 nonce", HttpServletResponse.SC_UNAUTHORIZED, start);
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "{\"error\":\"重复请求\"}");
            return;
        }

        // 4) IP 白名单
        String ip = clientIp(request);
        if (!ipAllowed(key, ip)) {
            auditAndReject(request, keyId, "IP 不在白名单", HttpServletResponse.SC_FORBIDDEN, start);
            writeJson(response, HttpServletResponse.SC_FORBIDDEN, "{\"error\":\"调用来源受限\"}");
            return;
        }

        // 5) 签名校验（需缓存 body 供 @RequestBody 解析）
        ContentCachingRequestWrapper wrapped =
                new ContentCachingRequestWrapper(request, 512 * 1024);
        String bodySha = ApiSignature.bodySha256Hex(bodyOf(wrapped));
        String canonical = ApiSignature.canonical(request.getMethod(), request.getRequestURI(), ts, bodySha);
        String secret = ApiCrypto.decrypt(key.getSecretEnc(), masterSecret);
        String candidate = ApiSignature.hmacHex(secret, canonical);
        if (!ApiSignature.constantTimeEquals(sig, candidate)) {
            auditAndReject(request, keyId, "签名不匹配", HttpServletResponse.SC_UNAUTHORIZED, start);
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "{\"error\":\"签名校验失败\"}");
            return;
        }

        // 6) 分级限流（超出 429）
        if (keyService.rateExceeded(key)) {
            auditAndReject(request, keyId, "超限流", 429, start);
            writeJson(response, 429, "{\"error\":\"API 调用超限\"}");
            return;
        }

        // 通过：注入上下文，标记更新时间
        key.setLastUsedAt(java.time.Instant.now());
        keyRepository.save(key);
        request.setAttribute("apiKey", key);
        request.setAttribute("apiTenantId", key.getTenantId());
        request.setAttribute("apiWriteName", key.hasWriteScope());

        try {
            chain.doFilter(wrapped, response);
            recordUsage(request, key.getKeyId(), response.getStatus(), start);
        } finally {
            purgeNonces();
        }
    }

    private boolean consumeNonce(String nonce) {
        if (nonce.isBlank()) return false;
        if (nonceCache.containsKey(nonce)) return false;
        nonceCache.put(nonce, System.currentTimeMillis() + WINDOW_MS);
        return true;
    }

    private void purgeNonces() {
        if (nonceCache.size() < 100_000) return;
        long now = System.currentTimeMillis();
        nonceCache.entrySet().removeIf(e -> e.getValue() < now);
    }

    private static boolean ipAllowed(ApiKey key, String ip) {
        String wl = key.getIpWhitelist();
        if (wl == null || wl.isBlank()) return true;
        Set<String> set = new HashSet<>(Arrays.asList(wl.split(",")));
        return set.contains(ip.trim());
    }

    private static String bodyOf(ContentCachingRequestWrapper w) {
        byte[] bytes = w.getContentAsByteArray();
        return bytes == null || bytes.length == 0 ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    private void auditAndReject(HttpServletRequest request, String keyId, String cause, int status, long start) {
        recordUsage(request, keyId, status, start);
    }

    private void recordUsage(HttpServletRequest request, String keyId, int status, long start) {
        try {
            ApiUsageLog logRow = ApiUsageLog.builder()
                    .id(UUID.randomUUID().toString())
                    .apiKeyId(keyId == null ? "?" : keyId)
                    .tenantId("?")
                    .method(request.getMethod())
                    .path(request.getRequestURI())
                    .ip(clientIp(request))
                    .statusCode(status)
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();
            usageLogRepository.save(logRow);
        } catch (Exception ignored) {
            // 审计失败不阻断主流程
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }

    private static void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        // 未提交时直接写；已提交则忽略
        if (response.isCommitted()) return;
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body);
    }
}