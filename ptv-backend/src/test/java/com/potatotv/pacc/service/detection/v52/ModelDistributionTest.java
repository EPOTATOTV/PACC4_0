package com.potatotv.pacc.service.detection.v52;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.domain.ModelVersion;
import com.potatotv.pacc.repository.ModelVersionRepository;
import com.potatotv.pacc.repository.ZeroDayFindingRepository;
import com.potatotv.pacc.service.NotificationService;
import com.potatotv.pacc.util.ml.PaccModelFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * v5.2 §2.1.3 模型下发：灰度分桶确定性、按玩家挑版本、产物读取的目录穿越与摘要校验。
 */
class ModelDistributionTest {

    private static final String TYPE = ModelVersion.TYPE_XGBOOST;

    @TempDir
    Path modelDir;

    private ModelVersionRepository versions;
    private ModelTrainingService service;

    @BeforeEach
    void setUp() {
        versions = mock(ModelVersionRepository.class);
        service = new ModelTrainingService(mock(ZeroDayFindingRepository.class), versions,
                mock(NotificationService.class), modelDir.toString(), "");
    }

    // ------------------------------ 灰度分桶 ------------------------------

    @Test
    void grayBucketingIsDeterministicAndRatioBounded() {
        ModelVersion gray = grayRow("1", 10);
        int hits = 0;
        for (int i = 0; i < 1000; i++) {
            String pteid = "PT" + i;
            boolean first = ModelTrainingService.grayHit(pteid, gray);
            assertEquals(first, ModelTrainingService.grayHit(pteid, gray), "同一玩家同一版本必须稳定命中");
            if (first) hits++;
        }
        assertTrue(hits > 60 && hits < 140, "10% 放量下 1000 个玩家的命中数应接近 100，实际 " + hits);
    }

    @Test
    void zeroPercentNeverHitsAndFullPercentAlwaysHits() {
        assertFalse(ModelTrainingService.grayHit("PT0001", grayRow("1", 0)));
        assertTrue(ModelTrainingService.grayHit("PT0001", grayRow("1", 100)));
        assertFalse(ModelTrainingService.grayHit("", grayRow("1", 100)), "空 PTEID 不下发灰度版本");
        assertFalse(ModelTrainingService.grayHit(null, grayRow("1", 100)));
    }

    @Test
    void bucketDependsOnVersionNotOnlyPlayer() {
        ModelVersion v1 = grayRow("1", 50);
        ModelVersion v2 = grayRow("2", 50);
        boolean differs = false;
        for (int i = 0; i < 50 && !differs; i++) {
            String pteid = "PT" + i;
            differs = ModelTrainingService.grayHit(pteid, v1) != ModelTrainingService.grayHit(pteid, v2);
        }
        assertTrue(differs, "不同版本应使用独立分桶（同批玩家的命中集合不应完全一致）");
    }

    // ------------------------------ 版本挑选 ------------------------------

    @Test
    void grayVersionWinsWhenPlayerHitsTheBucket() {
        stub(TYPE, ModelVersion.STATUS_GRAY, grayRow("4", 100));
        stub(TYPE, ModelVersion.STATUS_ACTIVE, row("3", ModelVersion.STATUS_ACTIVE, 100));

        List<ModelVersion> out = service.releasesFor("PT0001");

        assertEquals(1, out.size());
        assertEquals("4", out.get(0).getVersion(), "命中灰度时应下发灰度版本");
    }

    @Test
    void activeVersionServedWhenGrayMisses() {
        stub(TYPE, ModelVersion.STATUS_GRAY, grayRow("4", 0));
        stub(TYPE, ModelVersion.STATUS_ACTIVE, row("3", ModelVersion.STATUS_ACTIVE, 100));

        List<ModelVersion> out = service.releasesFor("PT0001");

        assertEquals(1, out.size());
        assertEquals("3", out.get(0).getVersion(), "未命中灰度时应回落到全量版本");
    }

    @Test
    void nothingServedWithoutActiveOrGrayVersion() {
        assertTrue(service.releasesFor("PT0001").isEmpty());
    }

    // ------------------------------ 产物读取 ------------------------------

    @Test
    void readArtifactReturnsBytesWhenDigestMatches() throws Exception {
        byte[] bytes = "pacc-model-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(modelDir.resolve("xgboost-v1.paccm"), bytes);
        ModelVersion row = row("1", ModelVersion.STATUS_ACTIVE, 100);
        row.setFileUrl("xgboost-v1.paccm");
        row.setSha256(PaccModelFormat.sha256Hex(bytes));

        assertArrayEquals(bytes, service.readArtifact(row));
    }

    @Test
    void readArtifactRejectsDigestMismatch() throws Exception {
        Files.write(modelDir.resolve("xgboost-v1.paccm"), new byte[]{1, 2, 3});
        ModelVersion row = row("1", ModelVersion.STATUS_ACTIVE, 100);
        row.setFileUrl("xgboost-v1.paccm");
        row.setSha256("00".repeat(32));

        assertThrows(IllegalStateException.class, () -> service.readArtifact(row));
    }

    @Test
    void readArtifactRejectsTraversalAndMissingFile() {
        ModelVersion traversal = row("1", ModelVersion.STATUS_ACTIVE, 100);
        traversal.setFileUrl("../secret.paccm");
        assertThrows(NoSuchElementException.class, () -> service.readArtifact(traversal));

        ModelVersion missing = row("1", ModelVersion.STATUS_ACTIVE, 100);
        missing.setFileUrl("not-there.paccm");
        assertThrows(NoSuchElementException.class, () -> service.readArtifact(missing));

        ModelVersion empty = row("1", ModelVersion.STATUS_ACTIVE, 100);
        empty.setFileUrl("");
        assertThrows(NoSuchElementException.class, () -> service.readArtifact(empty));
    }

    // ------------------------------ 测试数据 ------------------------------

    private void stub(String modelType, String status, ModelVersion row) {
        when(versions.findFirstByModelTypeAndStatusOrderByCreatedAtDesc(modelType, status))
                .thenReturn(Optional.of(row));
    }

    private static ModelVersion grayRow(String version, int percent) {
        return row(version, ModelVersion.STATUS_GRAY, percent);
    }

    private static ModelVersion row(String version, String status, int grayPercent) {
        return ModelVersion.builder()
                .id(status + "-" + version)
                .modelType(TYPE)
                .version(version)
                .status(status)
                .grayPercent(grayPercent)
                .build();
    }
}