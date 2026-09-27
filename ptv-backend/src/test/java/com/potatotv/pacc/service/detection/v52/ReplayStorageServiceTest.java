package com.potatotv.pacc.service.detection.v52;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.domain.ReplayRecording;
import com.potatotv.pacc.repository.ReplayRecordingRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * v5.2 §7.3 回放存储测试：密文落盘 + 元数据登记、授权解密、参数与体积校验、到期清理。
 */
class ReplayStorageServiceTest {

    @TempDir
    Path replayDir;

    private ReplayRecordingRepository recordings;
    private ReplayStorageService service;
    private final List<ReplayRecording> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        recordings = mock(ReplayRecordingRepository.class);
        when(recordings.save(any(ReplayRecording.class))).thenAnswer(inv -> {
            ReplayRecording row = inv.getArgument(0);
            saved.add(row);
            return row;
        });
        service = new ReplayStorageService(recordings, replayDir.toString());
    }

    @Test
    void storesCipherAndDecryptsOnDemand() throws Exception {
        byte[] plain = "AVI-PLAINTEXT-VIDEO-BYTES".getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[32];
        byte[] iv = new byte[12];
        java.util.Arrays.fill(key, (byte) 7);
        java.util.Arrays.fill(iv, (byte) 9);
        byte[] cipher = encrypt(plain, key, iv);

        Map<String, Object> stored = service.store("PT1", "alert_1", cipher,
                Base64.getEncoder().encodeToString(key), Base64.getEncoder().encodeToString(iv),
                "150:960:540:5:90000:1048576", "ab".repeat(32));

        String id = String.valueOf(stored.get("id"));
        assertFalse(id.isBlank());
        ReplayRecording row = saved.get(0);
        assertEquals("PT1", row.getPteid());
        assertEquals("alert_1", row.getAlertId());
        assertEquals(150, row.getFrames());
        assertEquals(960, row.getWidth());
        assertEquals(540, row.getHeight());
        assertEquals(5, row.getFps());
        assertEquals(90000L, row.getDurationMillis());
        assertEquals(1048576L, row.getPlainSize());
        assertEquals(cipher.length, row.getSizeBytes());
        assertTrue(Files.isRegularFile(replayDir.resolve(row.getStoragePath())), "密文应落盘");
        assertFalse(java.util.Arrays.equals(cipher, decrypt(Files.readAllBytes(
                replayDir.resolve(row.getStoragePath())), key, iv)),
                "磁盘上必须是密文");

        when(recordings.findById(id)).thenReturn(java.util.Optional.of(row));
        assertArrayEquals(plain, service.open(id), "授权下载应得到明文");
    }

    @Test
    void rejectsOversizedOrMalformedUploads() {
        byte[] key32 = new byte[32];
        byte[] iv12 = new byte[12];
        String key = Base64.getEncoder().encodeToString(key32);
        String ivB64 = Base64.getEncoder().encodeToString(iv12);

        assertThrows(IllegalArgumentException.class,
                () -> service.store("PT1", "a", new byte[0], key, ivB64, "1:1:1:5:1:1", ""));
        assertThrows(IllegalArgumentException.class,
                () -> service.store("PT1", "a", new byte[]{1}, key,
                        Base64.getEncoder().encodeToString(new byte[16]), "1:1:1:5:1:1", ""));
        assertThrows(IllegalArgumentException.class,
                () -> service.store("PT1", "a", new byte[]{1},
                        Base64.getEncoder().encodeToString(new byte[16]), ivB64, "1:1:1:5:1:1", ""));
        assertThrows(IllegalArgumentException.class,
                () -> service.store("", "a", new byte[]{1}, key, ivB64, "1:1:1:5:1:1", ""));
        assertThrows(IllegalArgumentException.class,
                () -> service.store("PT1", "a", new byte[]{(byte) 1}, "not-base64", ivB64, "1:1:1:5:1:1", ""));
    }

    @Test
    void listHidesKeysAndReturnsMetadataOnly() throws Exception {
        byte[] key = new byte[32];
        byte[] iv = new byte[12];
        byte[] cipher = encrypt("x".getBytes(StandardCharsets.UTF_8), key, iv);
        service.store("PT1", "alert_1", cipher, Base64.getEncoder().encodeToString(key),
                Base64.getEncoder().encodeToString(iv), "12:640:360:5:2400:2048", "cd".repeat(32));
        when(recordings.findTop50ByPteidOrderByCreatedAtDesc("PT1")).thenReturn(saved);

        List<Map<String, Object>> list = service.list("PT1", 10);

        assertEquals(1, list.size());
        assertFalse(list.get(0).containsKey("enc_key"));
        assertFalse(list.get(0).containsKey("storage_path"));
        assertTrue(list.get(0).containsKey("expires_at"));
        assertEquals(12, list.get(0).get("frames"));
    }

    @Test
    void expiredRecordingsAreDeletedWithTheirFiles() throws Exception {
        byte[] key = new byte[32];
        byte[] iv = new byte[12];
        service.store("PT1", "alert_1", encrypt("y".getBytes(StandardCharsets.UTF_8), key, iv),
                Base64.getEncoder().encodeToString(key), Base64.getEncoder().encodeToString(iv),
                "5:64:64:5:1000:2048", "");
        ReplayRecording row = saved.get(0);
        Path file = replayDir.resolve(row.getStoragePath());
        assertTrue(Files.exists(file));
        Instant expired = row.getExpiresAt();

        assertEquals(0, service.purge(expired.minusSeconds(60)), "未到期不清理");
        when(recordings.findByExpiresAtBefore(any(Instant.class))).thenReturn(List.of(row));

        assertEquals(1, service.purge(expired.plusSeconds(60)));
        assertFalse(Files.exists(file), "过期录像的密文文件必须删除");
        assertEquals(ReplayStorageService.RETENTION_DAYS, 30, "保留期与文档一致");
    }

    @Test
    void missingRecordingOrFileYieldsNotFound() {
        assertThrows(NoSuchElementException.class, () -> service.open("nope"));

        ReplayRecording ghost = ReplayRecording.builder()
                .id("ghost").pteid("PT1").storagePath("ghost.avi.enc")
                .encKey(Base64.getEncoder().encodeToString(new byte[32]))
                .encIv(Base64.getEncoder().encodeToString(new byte[12]))
                .build();
        when(recordings.findById("ghost")).thenReturn(java.util.Optional.of(ghost));

        assertThrows(NoSuchElementException.class, () -> service.open("ghost"));
    }

    // ------------------------------ 测试数据 ------------------------------

    private static byte[] encrypt(byte[] plain, byte[] key, byte[] iv) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return c.doFinal(plain);
    }

    private static byte[] decrypt(byte[] cipher, byte[] key, byte[] iv) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return c.doFinal(cipher);
    }
}