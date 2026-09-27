package com.potatotv.pacc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.SecurityTotp;
import com.potatotv.pacc.repository.SecurityTotpRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 玩家 TOTP 两步验证（2FA）业务服务。
 * <p>包含：TOTP 校验（HmacSHA1，Base32 种子，30s 窗口容差 ±1）、
 * 一次性恢复码生成/消费（SHA-256 摘要存储，明文仅签发时展示一次）、
 * 登录第二步 pending 短时令牌签发与校验（独立派生 key，与主会话 JWT 隔离）。</p>
 * <p>2FA 仅在 {@link SecurityTotp#isEnabled()} 时对登录流程生效，否则零影响。</p>
 */
@Service
public class TotpService {

    private static final Logger log = LoggerFactory.getLogger(TotpService.class);

    private static final String PENDING_ISSUER = "pacc-2fa-pending";
    private static final String ADMIN_PENDING_ISSUER = "pacc-admin-2fa-pending";
    private static final long PENDING_TTL_SECONDS = 300L; // 5 分钟
    private static final int RECOVERY_COUNT = 10;
    private static final String[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".split("");

    private final SecurityTotpRepository totpRepository;
    private final ObjectMapper objectMapper;
    private final SecretKey pendingKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public TotpService(SecurityTotpRepository totpRepository,
                       @Value("${pacc.security.jwt-secret}") String secret) {
        this.totpRepository = totpRepository;
        this.objectMapper = new ObjectMapper();
        // 独立派生 key：与主会话 JWT 私钥区分，避免 pending 令牌被当作有效主会话
        byte[] derived = sha256("2fa-pending:" + secret);
        this.pendingKey = Keys.hmacShaKeyFor(derived);
    }

    /** 查询某 PTEID 的 2FA 启用状态（未注册返回 false）。 */
    public boolean enabled(String pteid) {
        SecurityTotp t = totpRepository.findById(pteid).orElse(null);
        return t != null && t.isEnabled();
    }

    /** 安全中心面板数据：是否启用 + 是否已生成恢复码。 */
    public Map<String, Object> status(String pteid) {
        SecurityTotp t = totpRepository.findById(pteid).orElse(null);
        boolean enabled = t != null && t.isEnabled();
        boolean hasRecovery = t != null && t.getRecoveryCodes() != null && !t.getRecoveryCodes().isBlank();
        return Map.of("enabled", enabled, "recovery_ready", hasRecovery);
    }

    /** 校验 TOTP 验证码（或恢复码），校验成功返回 true。 */
    public boolean validate(String pteid, String code) {
        SecurityTotp t = totpRepository.findById(pteid).orElse(null);
        if (t == null || !t.isEnabled()) {
            return false;
        }
        if (verifyTotpCode(t.getSecret(), code)) {
            return true;
        }
        return consumeRecoveryCode(t, code);
    }

    /** 禁用 2FA，须提供有效 TOTP 或恢复码解锁。 */
    @Transactional
    public boolean disable(String pteid, String code) {
        SecurityTotp t = totpRepository.findById(pteid).orElse(null);
        if (t == null || !t.isEnabled()) {
            return false;
        }
        boolean ok = verifyTotpCode(t.getSecret(), code) || consumeRecoveryCode(t, code);
        if (!ok) {
            return false;
        }
        t.setEnabled(false);
        t.setUpdatedAt(Instant.now());
        totpRepository.save(t);
        return true;
    }

    /** 生成一次性恢复码（仅此刻返回明文），持久化为 SHA-256 摘要。重新生成会作废旧码。 */
    @Transactional
    public List<String> generateRecoveryCodes(String pteid) {
        SecurityTotp t = totpRepository.findById(pteid).orElse(null);
        if (t == null) {
            throw new IllegalStateException("请先获取 TOTP 密钥");
        }
        List<String> plain = new ArrayList<>(RECOVERY_COUNT);
        Set<String> hashes = new LinkedHashSet<>();
        for (int i = 0; i < RECOVERY_COUNT; i++) {
            String code = randomRecoveryCode();
            plain.add(code);
            hashes.add(sha256Hex(t.getPteid() + ":" + code));
        }
        t.setRecoveryCodes(toJson(hashes));
        t.setRecoveryUsed(toJson(Set.of()));
        t.setUpdatedAt(Instant.now());
        totpRepository.save(t);
        return List.copyOf(plain);
    }

    /** 签发登录第二步 pending 令牌（5 分钟有效，夹带 nonce 防重放）。 */
    public String issuePending(String pteid) {
        return issuePending(pteid, PENDING_ISSUER, null);
    }

    /** 校验 pending 令牌并返回 PTEID；非法/越期抛出 SecurityException。 */
    public String parsePending(String pending) {
        return parsePending(pending, PENDING_ISSUER).getSubject();
    }

    // -------------------------------- 管理员 2FA --------------------------------

    /** 管理员第二步 pending 令牌的解析结果。 */
    public record AdminPending(String identity, String role) {}

    /**
     * 签发管理员登录第二步 pending 令牌。
     * <p>用独立 issuer：与玩家 pending 令牌即使落到对方端点也验不过，
     * 避免两条登录链路互相成为对方的旁路。</p>
     * <p>角色随令牌夹带——飞书身份的角色要靠白名单反查，第二步时已无授权码可换，
     * 只能在第一步算好后带过来。令牌有签名与 5 分钟有效期，可信度等同于第一步结果。</p>
     */
    public String issueAdminPending(String identity, String role) {
        return issuePending(identity, ADMIN_PENDING_ISSUER, role);
    }

    /** 校验管理员 pending 令牌；非法/越期抛出 SecurityException。 */
    public AdminPending parseAdminPending(String pending) {
        Claims c = parsePending(pending, ADMIN_PENDING_ISSUER);
        return new AdminPending(c.getSubject(), c.get("arole", String.class));
    }

    private String issuePending(String subject, String issuer, String adminRole) {
        Instant exp = Instant.now().plusSeconds(PENDING_TTL_SECONDS);
        var builder = Jwts.builder()
                .issuer(issuer)
                .subject(subject)
                .id(randomId())
                .expiration(Date.from(exp));
        if (adminRole != null) {
            builder.claim("arole", adminRole);
        }
        return builder.signWith(pendingKey).compact();
    }

    private Claims parsePending(String pending, String issuer) {
        try {
            return Jwts.parser().requireIssuer(issuer).verifyWith(pendingKey).build()
                    .parseSignedClaims(pending).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new SecurityException("两步验证会话已失效，请重新登录");
        }
    }

    /** 是否命中真实 TOTP 验证码（不消费恢复码状态）。用于判定能否建立设备信任。 */
    public boolean isValidTotp(String pteid, String code) {
        SecurityTotp t = totpRepository.findById(pteid).orElse(null);
        if (t == null || !t.isEnabled()) {
            return false;
        }
        return verifyTotpCode(t.getSecret(), code);
    }

    // -------------------------------- 可信设备 --------------------------------

    /** 判断该设备指纹是否为某 PTEID 的有效可信设备（未过期）。指纹只存 SHA-256 摘要。 */
    public boolean isTrustedDevice(String pteid, String deviceFp) {
        if (deviceFp == null || deviceFp.isBlank()) {
            return false;
        }
        SecurityTotp t = totpRepository.findById(pteid).orElse(null);
        Map<String, Long> map = trustedMap(t);
        if (map.isEmpty()) {
            return false;
        }
        Long exp = map.get(sha256Hex("trust:" + deviceFp));
        return exp != null && exp > System.currentTimeMillis();
    }

    /** 记录可信设备（默认 30 天）。指纹以摘要形式存储，避免明文设备标识落库。 */
    @Transactional
    public boolean trustDevice(String pteid, String deviceFp, long days) {
        if (deviceFp == null || deviceFp.isBlank()) {
            return false;
        }
        SecurityTotp t = totpRepository.findById(pteid).orElse(null);
        if (t == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Map<String, Long> map = trustedMap(t);
        long cap = days <= 0 ? 30L : days;
        long exp = now + cap * 24L * 3600L * 1000L;
        map.put(sha256Hex("trust:" + deviceFp), exp);
        // 保留最近 10 台，避免无限膨胀
        if (map.size() > 10) {
            map.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .limit(map.size() - 10)
                    .forEach(e -> map.remove(e.getKey()));
        }
        t.setTrustedDevices(toJson(map));
        t.setUpdatedAt(Instant.now());
        totpRepository.save(t);
        log.info("新增可信设备 days={} pteid={}", cap, pteid);
        return true;
    }

    /** 撤销某可信设备。 */
    @Transactional
    public boolean revokeTrustedDevice(String pteid, String deviceFp) {
        if (deviceFp == null || deviceFp.isBlank()) {
            return false;
        }
        SecurityTotp t = totpRepository.findById(pteid).orElse(null);
        if (t == null) {
            return false;
        }
        Map<String, Long> map = trustedMap(t);
        boolean removed = map.remove(sha256Hex("trust:" + deviceFp)) != null;
        if (removed) {
            t.setTrustedDevices(toJson(map));
            t.setUpdatedAt(Instant.now());
            totpRepository.save(t);
            log.info("撤销可信设备 pteid={}", pteid);
        }
        return removed;
    }

    /** 按存储的可信设备 id（指纹哈希）撤销，供安全中心列表操作使用。 */
    @Transactional
    public boolean revokeTrustedDeviceByHash(String pteid, String deviceHash) {
        if (deviceHash == null || deviceHash.isBlank()) {
            return false;
        }
        SecurityTotp t = totpRepository.findById(pteid).orElse(null);
        if (t == null) {
            return false;
        }
        Map<String, Long> map = trustedMap(t);
        boolean removed = map.remove(deviceHash) != null;
        if (removed) {
            t.setTrustedDevices(toJson(map));
            t.setUpdatedAt(Instant.now());
            totpRepository.save(t);
            log.info("按 id 撤销可信设备 pteid={}", pteid);
        }
        return removed;
    }

    /** 返回某 PTEID 的可信设备数量（供安全中心展示，不含具体指纹）。 */
    public int trustedDeviceCount(String pteid) {
        SecurityTotp t = totpRepository.findById(pteid).orElse(null);
        return trustedMap(t).size();
    }

    /** 可信设备列表（仅暴露指纹前缀 + 过期时间，不落明文指纹）。 */
    public List<Map<String, Object>> trustedDeviceList(String pteid) {
        SecurityTotp t = totpRepository.findById(pteid).orElse(null);
        Map<String, Long> map = trustedMap(t);
        List<Map<String, Object>> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : map.entrySet()) {
            if (e.getValue() <= now) {
                continue;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", e.getKey());
            r.put("prefix", e.getKey().substring(0, Math.min(10, e.getKey().length())));
            r.put("expires_at", Instant.ofEpochMilli(e.getValue()).toString());
            rows.add(r);
        }
        return rows;
    }

    private Map<String, Long> trustedMap(SecurityTotp t) {
        if (t == null || t.getTrustedDevices() == null || t.getTrustedDevices().isBlank()) {
            return new java.util.HashMap<>();
        }
        try {
            return new java.util.HashMap<>(
                    objectMapper.readValue(t.getTrustedDevices(), new TypeReference<Map<String, Long>>() {}));
        } catch (JsonProcessingException e) {
            return new java.util.HashMap<>();
        }
    }

    // -------------------------------- TOTP 原语 --------------------------------

    /** 校验 6 位 TOTP 验证码（±1 窗口）。 */
    private boolean verifyTotpCode(String base32Secret, String code) {
        if (base32Secret == null || code == null || !code.matches("\\d{6}")) {
            return false;
        }
        try {
            byte[] key = base32Decode(base32Secret);
            long epoch = Instant.now().getEpochSecond();
            for (long w = -1; w <= 1; w++) {
                if (hotpCode(key, epoch + w * 30L).equals(code)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private String hotpCode(byte[] key, long time) throws NoSuchAlgorithmException, InvalidKeyException {
        long counter = time / 30L;
        byte[] data = new byte[8];
        for (int i = 7; i >= 0; i--) {
            data[i] = (byte) (counter & 0xff);
            counter >>>= 8;
        }
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA1"));
        byte[] hash = mac.doFinal(data);
        int offset = hash[hash.length - 1] & 0x0f;
        int bin = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        return String.format("%06d", bin % 1_000_000);
    }

    // -------------------------------- 恢复码 --------------------------------

    /** 消费一次性恢复码（命中则标记已用并返回 true）。 */
    private boolean consumeRecoveryCode(SecurityTotp t, String code) {
        if (t == null || code == null || !code.matches("\\w{4}-\\w{4}")) {
            return false;
        }
        Set<String> hashes = fromJsonSet(t.getRecoveryCodes());
        Set<String> used = fromJsonSet(t.getRecoveryUsed());
        String candidate = sha256Hex(t.getPteid() + ":" + code);
        if (!hashes.contains(candidate) || used.contains(candidate)) {
            return false;
        }
        used.add(candidate);
        t.setRecoveryUsed(toJson(used));
        t.setUpdatedAt(Instant.now());
        totpRepository.save(t);
        return true;
    }

    private String randomRecoveryCode() {
        return group() + "-" + group();
    }

    private String group() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            int idx = secureRandom.nextInt(BASE32.length);
            sb.append(BASE32[idx]);
        }
        return sb.toString();
    }

    private String randomId() {
        byte[] b = new byte[16];
        secureRandom.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // -------------------------------- JSON / Hash 工具 --------------------------------

    private String toJson(Object v) {
        try {
            return objectMapper.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("恢复码序列化失败", e);
        }
    }

    private Set<String> fromJsonSet(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashSet<>();
        }
        try {
            return new LinkedHashSet<>(objectMapper.readValue(json, new TypeReference<Set<String>>() {}));
        } catch (JsonProcessingException e) {
            return new LinkedHashSet<>();
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String sha256Hex(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : sha256(s)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // -------------------------------- Base32 --------------------------------

    private static byte[] base32Decode(String s) {
        String clean = s.replace("=", "").toUpperCase();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : clean.toCharArray()) {
            int v = indexOfBase32(c);
            if (v < 0) continue;
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >>> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static int indexOfBase32(char c) {
        for (int i = 0; i < BASE32.length; i++) {
            if (BASE32[i].charAt(0) == c) return i;
        }
        return -1;
    }
}