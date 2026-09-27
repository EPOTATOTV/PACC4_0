package com.potatotv.pacc.service.detection.v52;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.domain.ModelVersion;
import com.potatotv.pacc.domain.ZeroDayFinding;
import com.potatotv.pacc.repository.ModelVersionRepository;
import com.potatotv.pacc.repository.ZeroDayFindingRepository;
import com.potatotv.pacc.service.NotificationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * v5.2 §6.1 模型训练流水线确定性单测：门禁未达标的候选不得发布、达标候选按灰度登记、
 * 一键回退切换 active、样本不足时跳过且不登记任何行。
 *
 * <p>用固定 seed 的合成特征与固定洗牌种子，保证同一输入每次得到同一结论（不依赖随机性）。</p>
 */
class ModelTrainingServiceTest {

    private static final String TYPE_XGBOOST = ModelVersion.TYPE_XGBOOST;

    private ZeroDayFindingRepository findings;
    private ModelVersionRepository versions;
    private NotificationService notificationService;

    /** 模型库目录（真实落盘到临时目录，避免污染工作区）。 */
    @TempDir
    Path modelDir;

    private ModelTrainingService service;
    private final List<ModelVersion> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        findings = mock(ZeroDayFindingRepository.class);
        versions = mock(ModelVersionRepository.class);
        notificationService = mock(NotificationService.class);
        // 未配置签名密钥：签名列写空串（绝不伪造签名）
        service = new ModelTrainingService(findings, versions, notificationService, modelDir.toString(), "");
        when(versions.save(any(ModelVersion.class))).thenAnswer(inv -> {
            ModelVersion row = inv.getArgument(0);
            saved.add(row);
            return row;
        });
    }

    // ------------------------------ 训练与门禁 ------------------------------

    @Test
    void candidateBelowGateIsRecordedAsDraftAndAdminIsNotified() {
        stubFindings(noiseFindings(200));

        Map<String, Object> result = service.trainAndPublish("tester");

        assertEquals(Boolean.TRUE, result.get("trained"), "样本充足时应完成训练");
        assertEquals(Boolean.FALSE, result.get("published"), "无模型达标时不应有任何发布");
        for (Map<String, Object> model : modelsOf(result)) {
            assertEquals(ModelVersion.STATUS_DRAFT, model.get("status"),
                    "未达门禁的候选只能是 draft：" + model.get("model_type"));
        }
        assertTrue(!saved.isEmpty(), "未达标的候选仍应留痕");
        assertTrue(saved.stream().noneMatch(v -> !ModelVersion.STATUS_DRAFT.equals(v.getStatus())),
                "未达标的候选不得进入 gray/active");
        verify(notificationService, atLeastOnce())
                .sendToAll(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void candidateMeetingGateIsPublishedToGrayWithConfiguredPercent() throws Exception {
        stubFindings(separableFindings(60));

        Map<String, Object> result = service.trainAndPublish("tester");

        assertEquals(Boolean.TRUE, result.get("published"), "强判别力的数据应至少有一个模型达标");
        Map<String, Object> xgb = reportOf(result, TYPE_XGBOOST);
        assertEquals(ModelVersion.STATUS_GRAY, xgb.get("status"));
        assertTrue((double) xgb.get("accuracy") > ModelTrainingService.MIN_ACCURACY,
                "达标候选的准确率应高于门禁：" + xgb.get("accuracy"));
        assertTrue((double) xgb.get("false_positive_rate") < ModelTrainingService.MAX_FALSE_POSITIVE_RATE,
                "达标候选的误报率应低于门禁：" + xgb.get("false_positive_rate"));
        assertEquals(Boolean.FALSE, xgb.get("signed"), "未配置签名密钥时签名列必须为空");

        ModelVersion gray = saved.stream()
                .filter(v -> TYPE_XGBOOST.equals(v.getModelType()))
                .filter(v -> ModelVersion.STATUS_GRAY.equals(v.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("XGBoost 候选未进入灰度"));
        assertEquals(ModelTrainingService.GRAY_PERCENT, gray.getGrayPercent());
        assertEquals("1", gray.getVersion(), "首个版本号应为 1");
        assertEquals("", gray.getSignature(), "无密钥时不得伪造签名");
        assertTrue(Files.exists(modelDir.resolve("xgboost-v1.paccm")), "模型产物应落盘到模型库目录");
        assertTrue(Files.exists(modelDir.resolve("autoencoder-v1.paccm")), "自编码器产物也应落盘");
    }

    @Test
    void tooFewReviewedSamplesSkipsTrainingAndRegistersNothing() {
        stubFindings(separableFindings(19));

        Map<String, Object> result = service.trainAndPublish("tester");

        assertEquals(Boolean.FALSE, result.get("trained"));
        verify(versions, never()).save(any(ModelVersion.class));
        verify(notificationService, never())
                .sendToAll(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    // ------------------------------ 版本状态机 ------------------------------

    @Test
    void rollbackSwitchesActiveVersionBackToPrevious() {
        ModelVersion current = ModelVersion.builder()
                .id("cur").modelType(TYPE_XGBOOST).version("2")
                .status(ModelVersion.STATUS_ACTIVE).grayPercent(100)
                .publishedAt(Instant.now()).build();
        ModelVersion previous = ModelVersion.builder()
                .id("prev").modelType(TYPE_XGBOOST).version("1")
                .status(ModelVersion.STATUS_ROLLBACK).grayPercent(0)
                .publishedAt(Instant.now().minusSeconds(3600)).build();
        when(versions.findFirstByModelTypeAndStatusOrderByCreatedAtDesc(TYPE_XGBOOST, ModelVersion.STATUS_ACTIVE))
                .thenReturn(Optional.of(current));
        when(versions.findFirstByModelTypeAndStatusOrderByPublishedAtDesc(TYPE_XGBOOST, ModelVersion.STATUS_ROLLBACK))
                .thenReturn(Optional.of(previous));

        ModelVersion restored = service.rollback(TYPE_XGBOOST);

        assertEquals("prev", restored.getId(), "回退应返回上一版本");
        assertEquals(ModelVersion.STATUS_ACTIVE, previous.getStatus());
        assertEquals(100, previous.getGrayPercent());
        assertEquals(ModelVersion.STATUS_ROLLBACK, current.getStatus());
        assertEquals(0, current.getGrayPercent());
        verify(versions).save(current);
        verify(versions).save(previous);
    }

    @Test
    void rollbackToReactivatesChosenVersionAndDemotesCurrentActive() {
        ModelVersion current = ModelVersion.builder()
                .id("cur").modelType(TYPE_XGBOOST).version("3")
                .status(ModelVersion.STATUS_ACTIVE).grayPercent(100).build();
        ModelVersion target = ModelVersion.builder()
                .id("v2").modelType(TYPE_XGBOOST).version("2")
                .status(ModelVersion.STATUS_GRAY).grayPercent(10).build();
        when(versions.findById("v2")).thenReturn(Optional.of(target));
        when(versions.findFirstByModelTypeAndStatusOrderByCreatedAtDesc(TYPE_XGBOOST, ModelVersion.STATUS_ACTIVE))
                .thenReturn(Optional.of(current));

        ModelVersion restored = service.rollbackTo("v2");

        assertEquals("v2", restored.getId());
        assertEquals(ModelVersion.STATUS_ACTIVE, target.getStatus());
        assertEquals(100, target.getGrayPercent());
        assertEquals(ModelVersion.STATUS_ROLLBACK, current.getStatus());
    }

    @Test
    void draftVersionCannotBeActivatedDirectly() {
        ModelVersion draft = ModelVersion.builder()
                .id("d").modelType(TYPE_XGBOOST).version("1").status(ModelVersion.STATUS_DRAFT).build();
        when(versions.findById("d")).thenReturn(Optional.of(draft));

        assertThrows(IllegalStateException.class, () -> service.promoteToActive("d"));
    }

    @Test
    void alreadyActiveVersionCannotBeReleasedToGrayAgain() {
        ModelVersion active = ModelVersion.builder()
                .id("a").modelType(TYPE_XGBOOST).version("1")
                .status(ModelVersion.STATUS_ACTIVE).grayPercent(100).build();

        assertThrows(IllegalStateException.class, () -> service.release(active, 10));
    }

    // ------------------------------ 测试数据 ------------------------------

    private void stubFindings(List<ZeroDayFinding> rows) {
        when(findings.findByStatusAndReviewedAtAfterOrderByReviewedAtDesc(
                eq(ZeroDayFinding.Status.REVIEWED), any(Instant.class))).thenReturn(rows);
    }

    /** 强判别力样本：作弊样本两维显著为正，误报样本两维显著为负，第三维为噪声。 */
    private static List<ZeroDayFinding> separableFindings(int n) {
        List<ZeroDayFinding> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            boolean cheat = i % 2 == 1;
            double sign = cheat ? 1.0 : -1.0;
            out.add(finding("s" + i,
                    2.0 * sign + 0.25 * Math.sin(i * 1.3),
                    1.5 * sign + 0.20 * Math.cos(i * 0.7),
                    0.30 * Math.sin(i * 2.1),
                    cheat));
        }
        return out;
    }

    /** 纯噪声样本：特征与标签无关，任何模型在留出集上都应远低于门禁。 */
    private static List<ZeroDayFinding> noiseFindings(int n) {
        Random rng = new Random(20240925L);
        List<ZeroDayFinding> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(finding("n" + i,
                    rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian(),
                    i % 2 == 1));
        }
        return out;
    }

    /** 构造一条已复核（有明确结论）的零日发现，特征摘要为 {@code feature_xxx} JSON。 */
    private static ZeroDayFinding finding(String id, double attackRate, double moveVar, double sessionLen,
                                          boolean confirmed) {
        String json = String.format(Locale.ROOT,
                "{\"feature_attack_rate\": %.4f, \"feature_move_var\": %.4f, \"feature_session_len\": %.4f}",
                attackRate, moveVar, sessionLen);
        return ZeroDayFinding.builder()
                .id(id)
                .pteid("PT" + id)
                .status(ZeroDayFinding.Status.REVIEWED)
                .confirmed(confirmed)
                .featuresJson(json)
                .reviewedAt(Instant.now())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> modelsOf(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("models");
    }

    private static Map<String, Object> reportOf(Map<String, Object> result, String modelType) {
        return modelsOf(result).stream()
                .filter(m -> modelType.equals(m.get("model_type")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("训练报告缺少模型 " + modelType));
    }
}