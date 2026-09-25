package com.potatotv.pacc.service.detection.v52;

import com.potatotv.pacc.domain.ReplayRecording;
import com.potatotv.pacc.repository.ReplayRecordingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * v5.2 §7.3 查端回放存储：密文落盘 + 元数据入库 + 到期清理 + 授权解密。
 *
 * <p>与客户端约定：正文是 AES-256-GCM 密文（MJPEG-AVI 明文），密钥与 IV 走请求头 Base64，
 * 服务端不解密、只保存；只有管理员下载时（{@link #open(String)}）才现场解密并返回 AVI。
 * 这样「磁盘上永远没有明文录像」，同时保留可查看能力。</p>
 *
 * <p>限额与清理：单条密文上限 {@value #MAX_BYTES}MB（客户端侧已按 50MB 明文裁剪，留出余量），
 * 超限直接拒绝；保留 {@value #RETENTION_DAYS} 天，每日 03:40 清理过期文件与登记行。</p>
 */
@Slf4j
@Service
public class ReplayStorageService {

    /** 单条密文上限（字节）。 */
    static final long MAX_BYTES = 64L * 1024 * 1024;
    /** 保留天数（文档 §10.3：30 天后自动删除）。 */
    public static final int RETENTION_DAYS = 30;
    /** 元数据里 videos 的文件扩展名。 */
    private static final String SUFFIX = ".avi.enc";

    private final ReplayRecordingRepository recordings;
    private final String storeDir;

    public ReplayStorageService(ReplayRecordingRepository recordings,
                                @Value("${pacc.replay.store-dir:${PACC_REPLAY_DIR:${PACC_DATA_DIR:data}/replays}}")
                                String storeDir) {
        this.recordings = recordings;
        this.storeDir = storeDir;
    }

    /**
     * 保存一条录像。
     *
     * @param pteid   上传玩家（由会话属性提供，不信客户端自述）
     * @param alertId 关联红屏告警 id
     * @param cipher  密文（AES-256-GCM，含认证标签）
     * @param keyB64  解密密钥（Base64，32 字节）
     * @param ivB64   GCM IV（Base64，12 字节）
     * @param meta    {@code frames:width:height:fps:durationMs}
     * @param sha256  明文摘要（客户端计算，供下载时复核）
     * @throws IllegalArgumentException 参数不合法或超限
     */
    @Transactional
    public Map<String, Object> store(String pteid, String alertId, byte[] cipher,
                                     String keyB64, String ivB64, String meta, String sha256) {
        if (pteid == null || pteid.isBlank()) throw new IllegalArgumentException("pteid 不能为空");
        if (cipher == null || cipher.length == 0) throw new IllegalArgumentException("录像内容为空");
        if (cipher.length > MAX_BYTES) {
            throw new IllegalArgumentException("录像超过上限 " + (MAX_BYTES / 1024 / 1024) + "MB");
        }
        byte[] key = decode(keyB64, "密钥");
        byte[] iv = decode(ivB64, "IV");
        if (key.length != 32) throw new IllegalArgumentException("密钥必须是 32 字节 AES-256");
        if (iv.length != 12) throw new IllegalArgumentException("IV 必须是 12 字节");
        int[] m = parseMeta(meta);

        String id = UUID.randomUUID().toString().replace("-", "");
        String fileName = id + SUFFIX;
        writeCipher(fileName, cipher);

        ReplayRecording row = ReplayRecording.builder()
                .id(id)
                .alertId(nullToEmpty(alertId))
                .pteid(pteid)
                .storagePath(fileName)
                .frames(m[0])
                .width(m[1])
                .height(m[2])
                .fps(m[3])
                .durationMillis(m[4])
                .sizeBytes(cipher.length)
                .plainSize(m[5])
                .sha256(nullToEmpty(sha256).toLowerCase(Locale.ROOT))
                .encKey(keyB64.trim())
                .encIv(ivB64.trim())
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plus(RETENTION_DAYS, ChronoUnit.DAYS))
                .build();
        recordings.save(row);
        log.info("查端回放入库 id={} pteid={} alert={} 帧={} 密文={}B", id, pteid, row.getAlertId(),
                row.getFrames(), row.getSizeBytes());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("expires_at", row.getExpiresAt().toString());
        return out;
    }

    /** 元数据列表（不含密钥与内容）。 */
    public List<Map<String, Object>> list(String pteid, int limit) {
        List<ReplayRecording> rows = (pteid == null || pteid.isBlank())
                ? recordings.findTop50ByOrderByCreatedAtDesc()
                : recordings.findTop50ByPteidOrderByCreatedAtDesc(pteid);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (ReplayRecording row : rows) {
            if (out.size() >= Math.max(1, limit)) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.getId());
            m.put("alert_id", row.getAlertId());
            m.put("pteid", row.getPteid());
            m.put("frames", row.getFrames());
            m.put("width", row.getWidth());
            m.put("height", row.getHeight());
            m.put("fps", row.getFps());
            m.put("duration_ms", row.getDurationMillis());
            m.put("size_bytes", row.getSizeBytes());
            m.put("sha256", row.getSha256());
            m.put("created_at", row.getCreatedAt().toString());
            m.put("expires_at", row.getExpiresAt().toString());
            out.add(m);
        }
        return out;
    }

    /**
     * 解密并返回录像明文（管理员下载用）。
     *
     * @throws NoSuchElementException 记录或文件不存在
     * @throws IllegalStateException  文件被改动导致解密失败
     */
    public byte[] open(String id) {
        ReplayRecording row = recordings.findById(id)
                .orElseThrow(() -> new NoSuchElementException("录像不存在"));
        Path file = resolve(row.getStoragePath());
        if (!Files.isRegularFile(file)) throw new NoSuchElementException("录像文件已不存在");
        try {
            byte[] cipher = Files.readAllBytes(file);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(Base64.getDecoder().decode(row.getEncKey()), "AES"),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(row.getEncIv())));
            return c.doFinal(cipher);
        } catch (Exception e) {
            throw new IllegalStateException("录像解密失败（文件可能已损坏）: " + e.getMessage(), e);
        }
    }

    /** 到期清理：每日 03:40 删除超过保留期的密文与登记行。 */
    @Scheduled(cron = "0 40 3 * * ?")
    public void purgeExpired() {
        try {
            int deleted = purge(Instant.now());
            if (deleted > 0) log.info("查端回放过期清理完成 deleted={}", deleted);
        } catch (Exception e) {
            log.warn("查端回放过期清理失败 err={}", e.getMessage());
        }
    }

    /** 删除到期录像，返回删除条数（供定时任务与测试共用）。 */
    @Transactional
    public int purge(Instant now) {
        List<ReplayRecording> expired = recordings.findByExpiresAtBefore(now);
        for (ReplayRecording row : expired) {
            try {
                Files.deleteIfExists(resolve(row.getStoragePath()));
            } catch (IOException e) {
                log.warn("录像文件删除失败 id={} err={}", row.getId(), e.getMessage());
            }
        }
        recordings.deleteAll(expired);
        return expired.size();
    }

    // ------------------------------ 内部工具 ------------------------------

    private void writeCipher(String fileName, byte[] cipher) {
        try {
            Path dir = Paths.get(storeDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Files.write(dir.resolve(fileName), cipher,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new IllegalStateException("录像落盘失败：" + e.getMessage(), e);
        }
    }

    /** 存储路径解析：文件名由服务端生成（UUID + 固定后缀），仍做一次目录逃逸校验。 */
    private Path resolve(String fileName) {
        Path dir = Paths.get(storeDir).toAbsolutePath().normalize();
        Path file = dir.resolve(fileName == null ? "" : fileName).normalize();
        if (!file.startsWith(dir)) throw new NoSuchElementException("录像路径非法");
        return file;
    }

    private static byte[] decode(String base64, String what) {
        if (base64 == null || base64.isBlank()) throw new IllegalArgumentException(what + "缺失");
        try {
            return Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(what + "不是合法 Base64");
        }
    }

    /** 解析 {@code frames:width:height:fps:durationMs[:plainSize]}，缺省段按 0 处理。 */
    private static int[] parseMeta(String meta) {
        int[] out = new int[6];
        if (meta == null || meta.isBlank()) return out;
        String[] parts = meta.split(":");
        for (int i = 0; i < Math.min(parts.length, out.length); i++) {
            try {
                long v = Long.parseLong(parts[i].trim());
                out[i] = (int) Math.max(0, Math.min(Integer.MAX_VALUE, v));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}