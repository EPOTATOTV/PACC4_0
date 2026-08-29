package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.domain.DeviceRecord;
import com.potatotv.pacc.domain.DetectionEvent;
import com.potatotv.pacc.domain.Peripheral;
import com.potatotv.pacc.repository.AccountRepository;
import com.potatotv.pacc.repository.DeviceRecordRepository;
import com.potatotv.pacc.repository.DetectionEventRepository;
import com.potatotv.pacc.repository.PeripheralRepository;
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
    private final DeviceRecordRepository deviceRepository;
    private final PeripheralRepository peripheralRepository;
    private final PteidGenerator pteidGenerator;
    private final TokenService tokenService;

    /** 按 PTEID 查询账号，不存在返回 null（用于评分组件容错）。 */
    public Account findByPteidOrNull(String pteid) {
        return accountRepository.findById(pteid).orElse(null);
    }

    /** 按邮箱查询账号，不存在返回 null。 */
    public Account findByEmailOrNull(String email) {
        return email != null && email.contains("@")
                ? accountRepository.findByEmail(email).orElse(null)
                : null;
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
            String hashed = hashDevice(deviceFingerprint);
            account.setDeviceFingerprint(hashed);
            accountRepository.save(account);
            touchDevice(account.getPteid(), hashed);
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

    // ---------------- 登录设备 / 外设 ----------------

    /** 记录本次登录设备并标记为当前使用；同账号其余设备置为非当前。 */
    @Transactional
    public void touchDevice(String pteid, String hashedFp) {
        Instant now = Instant.now();
        String deviceId = hex(sha256(pteid + "|" + hashedFp));
        // 其余设备置为非当前
        deviceRepository.findByPteidOrderByLastLoginAtDesc(pteid).forEach(d -> {
            if (!deviceId.equals(d.getDeviceId())) {
                d.setActive(false);
                deviceRepository.save(d);
            }
        });
        DeviceRecord rec = deviceRepository.findById(deviceId).orElseGet(() ->
                DeviceRecord.builder()
                        .deviceId(deviceId)
                        .pteid(pteid)
                        .deviceFingerprint(hashedFp)
                        .deviceName("绑定设备 · " + shortId(hashedFp))
                        .firstLoginAt(now)
                        .build());
        rec.setLastLoginAt(now);
        rec.setActive(true);
        deviceRepository.save(rec);
    }

    /** 当前账号的全部登录设备（当前使用优先）。 */
    public java.util.List<DeviceRecord> listDevices(String pteid) {
        return deviceRepository.findByPteidOrderByLastLoginAtDesc(pteid);
    }

    /** 上报当前设备使用中的外设；该设备上次上报但本次未上报的外设标记为未使用。 */
    @Transactional
    public void reportPeripherals(String pteid, String hashedFp,
                                  java.util.List<java.util.Map<String, String>> inputs) {
        if (hashedFp == null || hashedFp.isEmpty()) return;
        Instant now = Instant.now();
        // 该设备旧外设全部置为非当前
        peripheralRepository.findByPteidAndDeviceFingerprint(pteid, hashedFp).forEach(p -> {
            p.setConnected(false);
            p.setLastSeenAt(now);
            peripheralRepository.save(p);
        });
        if (inputs == null) return;
        for (java.util.Map<String, String> in : inputs) {
            String kind = in.getOrDefault("kind", "usb");
            String vendor = in.getOrDefault("vendor", "");
            String model = in.getOrDefault("model", "unknown");
            String id = hex(sha256(pteid + "|" + hashedFp + "|" + kind + "|" + vendor + "|" + model));
            Peripheral p = peripheralRepository.findById(id).orElseGet(() ->
                    Peripheral.builder()
                            .peripheralId(id)
                            .pteid(pteid)
                            .deviceFingerprint(hashedFp)
                            .kind(kind)
                            .vendor(vendor)
                            .model(model)
                            .firstSeenAt(now)
                            .build());
            p.setConnected(true);
            p.setLastSeenAt(now);
            peripheralRepository.save(p);
        }
    }

    /** 当前账号的全部外设（使用中优先）。 */
    public java.util.List<Peripheral> listPeripherals(String pteid) {
        return peripheralRepository.findByPteidOrderByConnectedDescLastSeenAtDesc(pteid);
    }

    private static byte[] sha256(String s) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String shortId(String hashedFp) {
        if (hashedFp == null || hashedFp.length() < 8) return "";
        return hashedFp.substring(0, 8).toUpperCase();
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