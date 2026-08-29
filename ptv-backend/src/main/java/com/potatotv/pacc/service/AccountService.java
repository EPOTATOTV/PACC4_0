package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.domain.DetectionEvent;
import com.potatotv.pacc.repository.AccountRepository;
import com.potatotv.pacc.repository.DetectionEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * 反作弊账号服务：注册 / 登录 / 设备绑定 / 信誉。
 * 账号体系与游戏账号完全解耦。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null") // Spring 存储层返回值的 null 分析误报
public class AccountService {

    private final AccountRepository accountRepository;
    private final DetectionEventRepository detectionEventRepository;
    private final PteidGenerator pteidGenerator;
    private final TokenService tokenService;

    /** 按 PTEID 查询账号，不存在返回 null（用于评分组件容错）。 */
    public Account findByPteidOrNull(String pteid) {
        return accountRepository.findById(pteid).orElse(null);
    }

    /** 持久化一条玩家端上报的检测事件。 */
    @Transactional
    public void saveEvent(DetectionEvent event) {
        detectionEventRepository.save(event);
    }

    @Transactional
    public Account register(String email, String phone, String rawPassword, String deviceFingerprint) {
        if (accountRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("该邮箱已注册");
        }
        if (rawPassword == null || rawPassword.length() < 8 || rawPassword.length() > 32
                || !rawPassword.matches(".*[A-Z].*") || !rawPassword.matches(".*[a-z].*")
                || !rawPassword.matches(".*[0-9].*")) {
            throw new IllegalArgumentException("密码需为 8-32 位，包含大小写字母与数字");
        }
        String pteid = pteidGenerator.generate();
        Account account = Account.builder()
                .pteid(pteid)
                .email(email)
                .phone(phone)
                .passwordHash(hashPassword(rawPassword))
                .deviceFingerprint(hashDevice(deviceFingerprint))
                .registeredAt(Instant.now())
                .build();
        return accountRepository.save(account);
    }

    @Transactional
    public TokenService.Token login(String pteidOrEmail, String rawPassword, String deviceFingerprint, boolean remember) {
        Account account = accountRepository.findById(pteidOrEmail)
                .or(() -> {
                    String v = pteidOrEmail;
                    return v.contains("@") ? accountRepository.findByEmail(v) : accountRepository.findByPhone(v);
                })
                .orElseThrow(() -> new IllegalArgumentException("账号不存在或密码错误"));

        // 连续 5 次错误锁定 30 分钟（锁定到期自动解除）
        Instant now = Instant.now();
        Instant lockedUntil = account.getLockedUntil();
        if (lockedUntil != null && lockedUntil.isAfter(now)) {
            long mins = Math.max(1, java.time.Duration.between(now, lockedUntil).toMinutes());
            throw new IllegalStateException("账号已锁定，请 " + mins + " 分钟后重试或找回密码");
        }
        if (account.getFailedLogins() >= 5) {
            // 锁定已到期：解除并重置计数
            account.setFailedLogins(0);
            account.setLockedUntil(null);
        }
        if (!verify(account.getPasswordHash(), rawPassword)) {
            account.setFailedLogins(account.getFailedLogins() + 1);
            if (account.getFailedLogins() >= 5) {
                account.setLockedUntil(now.plusSeconds(30 * 60L));
            }
            accountRepository.save(account);
            throw new IllegalArgumentException("账号不存在或密码错误");
        }
        account.setFailedLogins(0);
        account.setLockedUntil(null);
        accountRepository.save(account);
        if (deviceFingerprint != null && !deviceFingerprint.isEmpty()) {
            account.setDeviceFingerprint(hashDevice(deviceFingerprint));
            accountRepository.save(account);
        }
        return tokenService.createToken(account.getPteid(), remember);
    }

    private String hashDevice(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------- Argon2id ----------------
    private String hashPassword(String password) {
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        byte[] out = new byte[32];
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(3)
                .withMemoryAsKB(65536)
                .withParallelism(1)
                .withSalt(salt)
                .build();
        var gen = new Argon2BytesGenerator();
        gen.init(params);
        gen.generateBytes(password.getBytes(StandardCharsets.UTF_8), out);
        return java.util.Base64.getEncoder().encodeToString(salt) + ":" + java.util.Base64.getEncoder().encodeToString(out);
    }

    private boolean verify(String stored, String password) {
        String[] parts = stored.split(":");
        if (parts.length != 2) return false;
        byte[] salt = java.util.Base64.getDecoder().decode(parts[0]);
        byte[] expected = java.util.Base64.getDecoder().decode(parts[1]);
        byte[] out = new byte[expected.length];
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(3)
                .withMemoryAsKB(65536)
                .withParallelism(1)
                .withSalt(salt)
                .build();
        var gen = new Argon2BytesGenerator();
        gen.init(params);
        gen.generateBytes(password.getBytes(StandardCharsets.UTF_8), out);
        return org.bouncycastle.util.Arrays.constantTimeAreEqual(expected, out);
    }
}