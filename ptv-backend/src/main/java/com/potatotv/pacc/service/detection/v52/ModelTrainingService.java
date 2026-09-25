package com.potatotv.pacc.service.detection.v52;

import com.potatotv.pacc.domain.ModelVersion;
import com.potatotv.pacc.domain.ZeroDayFinding;
import com.potatotv.pacc.repository.ModelVersionRepository;
import com.potatotv.pacc.repository.ZeroDayFindingRepository;
import com.potatotv.pacc.service.NotificationService;
import com.potatotv.pacc.util.ml.AutoencoderModel;
import com.potatotv.pacc.util.ml.PaccModelFormat;
import com.potatotv.pacc.util.ml.XGBoostModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
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
 * v5.2 §6.1 模型训练流水线：从人工复核结论构建数据集 → 训练 XGBoost 与自编码器 → 留出集评估 →
 * 过门禁则登记并灰度发布，未过则仅留痕（draft）并通知管理员。
 *
 * <p>数据来源为主动学习闭环中已复核的零日发现（{@link com.potatotv.pacc.service.detection.v46.ActiveLearningService}
 * 复核时写入 reviewed_at / confirmed），特征取自其 {@code feature_xxx} 摘要 JSON。</p>
 *
 * <p>每次训练都会产出后端训练侧的 {@code .paccm} 模型字节（与客户端 §2.1 加载的容器一致）：
 * 落盘模型库目录 → 计算 SHA-256 → 用 PTV 私钥签名（未配置密钥则不签名，绝不写伪造签名）。</p>
 *
 * <p>发布门禁（§6.1.2）：{@code falsePositiveRate < 0.03 且 accuracy > 0.90} 才进入灰度，
 * 放量默认 {@value #GRAY_PERCENT}%，由运营再提升为全量；未达门禁则记 draft。</p>
 */
@Slf4j
@Service
public class ModelTrainingService {

    /** 训练窗口：近 7 天的复核结论。 */
    public static final int WINDOW_DAYS = 7;
    /** 最低样本数：不足则跳过训练（不登记任何行）。 */
    public static final int MIN_TRAINING_SAMPLES = 20;
    /** 发布门禁：准确率下限（严格大于）。 */
    public static final double MIN_ACCURACY = 0.90;
    /** 发布门禁：误报率上限（严格小于）。 */
    public static final double MAX_FALSE_POSITIVE_RATE = 0.03;
    /** 达标后自动发布时的灰度放量百分比。 */
    public static final int GRAY_PERCENT = 10;

    /** 留出集比例。 */
    private static final double TEST_RATIO = 0.25;
    /** 留出集下限：过小则评估无意义，退化回全量自评。 */
    private static final int MIN_HOLDOUT = 4;
    /** 切分洗牌种子（固定值保证评估可复现）。 */
    private static final long SHUFFLE_SEED = 20250925L;
    /** 训练随机种子（固定值保证模型字节可复现）。 */
    private static final long MODEL_SEED = 52L;
    /** XGBoost 超参。 */
    private static final int XGB_TREES = 60;
    private static final int XGB_MAX_DEPTH = 4;
    private static final double XGB_LEARNING_RATE = 0.3;
    /** 自编码器超参。 */
    private static final int AE_EPOCHS = 200;
    private static final double AE_LEARNING_RATE = 0.05;
    /** 自编码器异常判定阈值：正常样本重构误差的 μ + kσ。 */
    private static final double AE_SIGMA = 3.0;

    private final ZeroDayFindingRepository findings;
    private final ModelVersionRepository versions;
    private final NotificationService notificationService;
    private final String storeDir;
    private final String signingKey;

    public ModelTrainingService(ZeroDayFindingRepository findings,
                                ModelVersionRepository versions,
                                NotificationService notificationService,
                                @Value("${pacc.model.store-dir:${PACC_DATA_DIR:data}/models}") String storeDir,
                                @Value("${pacc.model.signing-key:${PACC_MODEL_SIGNING_KEY:}}") String signingKey) {
        this.findings = findings;
        this.versions = versions;
        this.notificationService = notificationService;
        this.storeDir = storeDir;
        this.signingKey = signingKey;
    }

    /** 每日 02:00 定时训练。单轮失败不影响后续调度。 */
    @Scheduled(cron = "0 0 2 * * ?")
    public void scheduledTraining() {
        try {
            Map<String, Object> result = trainAndPublish("scheduler");
            log.info("定时模型训练完成 result={}", result);
        } catch (Exception e) {
            log.warn("定时模型训练失败 err={}", e.getMessage());
        }
    }

    /**
     * 执行一轮训练并按门禁发布。
     *
     * @param operator 触发者（"scheduler" 或管理后台账号），写入训练报告与日志
     * @return 训练报告：{@code trained}（是否产出模型）、{@code reason}（跳过原因）、
     *         {@code samples}、{@code feature_dim}、{@code models}（各模型指标与状态）
     */
    @Transactional
    public Map<String, Object> trainAndPublish(String operator) {
        Instant since = Instant.now().minus(WINDOW_DAYS, ChronoUnit.DAYS);
        List<ZeroDayFinding> reviewed = findings.findByStatusAndReviewedAtAfterOrderByReviewedAtDesc(
                ZeroDayFinding.Status.REVIEWED, since);
        List<TrainingDataset.LabeledSample> samples = labeledSamples(reviewed);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operator", operator);
        out.put("window_days", WINDOW_DAYS);
        out.put("samples", samples.size());
        if (samples.size() < MIN_TRAINING_SAMPLES) {
            String reason = "近 " + WINDOW_DAYS + " 天已复核样本 " + samples.size()
                    + " 条，少于最低要求 " + MIN_TRAINING_SAMPLES + " 条，跳过训练";
            log.info("跳过模型训练：{}", reason);
            out.put("trained", false);
            out.put("reason", reason);
            return out;
        }

        TrainingDataset dataset = TrainingDataset.build(samples);
        out.put("feature_dim", dataset.featureDim());
        out.put("positives", dataset.positiveCount());
        out.put("negatives", dataset.negativeCount());
        if (dataset.featureDim() == 0 || dataset.positiveCount() == 0 || dataset.negativeCount() == 0) {
            String reason = "复核样本不足以训练判别模型（判别特征 " + dataset.featureDim()
                    + " 维，作弊样本 " + dataset.positiveCount() + "，误报样本 " + dataset.negativeCount() + "）";
            log.info("跳过模型训练：{}", reason);
            out.put("trained", false);
            out.put("reason", reason);
            return out;
        }

        // 留出集要求：条数够、且训练侧与评估侧都含两类样本；否则退化为全量自评并如实标注
        TrainingDataset.Split split = dataset.split(TEST_RATIO, SHUFFLE_SEED);
        boolean holdout = split.test().size() >= MIN_HOLDOUT
                && hasBothClasses(split.train()) && hasBothClasses(split.test());
        TrainingDataset trainSet = holdout ? split.train() : dataset;
        TrainingDataset evalSet = holdout ? split.test() : dataset;
        out.put("split", holdout ? "holdout" : "resubstitution");
        out.put("train_samples", trainSet.size());
        out.put("eval_samples", evalSet.size());

        List<Map<String, Object>> reports = new ArrayList<>();
        reports.add(trainXgboost(trainSet, evalSet, dataset, holdout));
        reports.add(trainAutoencoder(trainSet, evalSet, dataset, holdout));
        out.put("models", reports);
        out.put("trained", true);
        out.put("published", reports.stream()
                .anyMatch(r -> ModelVersion.STATUS_GRAY.equals(r.get("status"))));
        return out;
    }

    // ------------------------------ 训练与评估 ------------------------------

    /** 训练 XGBoost 判别模型：阈值在训练集上择优，再在评估集上算指标，最后按门禁登记。 */
    private Map<String, Object> trainXgboost(TrainingDataset train, TrainingDataset eval,
                                             TrainingDataset all, boolean holdout) {
        XGBoostModel model = XGBoostModel.train(train.features(), train.labels(),
                XGB_TREES, XGB_MAX_DEPTH, XGB_LEARNING_RATE, MODEL_SEED);
        double[] trainScores = scoreXgboost(model, train.features());
        double threshold = pickThreshold(trainScores, train.labels());
        Metrics metrics = metricsOf(scoreXgboost(model, eval.features()), eval.labels(), threshold);
        return register(ModelVersion.TYPE_XGBOOST, model.toPaccmBytes(all.featureDim()), metrics, all, holdout);
    }

    /** 训练自编码器：只在「误报样本」（人工判定正常）上拟合，阈值取正常样本重构误差的 μ+3σ。 */
    private Map<String, Object> trainAutoencoder(TrainingDataset train, TrainingDataset eval,
                                                 TrainingDataset all, boolean holdout) {
        double[][] normals = filterByLabel(train, 0.0);
        int hidden = Math.max(1, all.featureDim() / 2);
        AutoencoderModel model = AutoencoderModel.fit(normals, hidden, AE_EPOCHS, AE_LEARNING_RATE, MODEL_SEED);

        double mean = 0;
        for (double[] row : normals) {
            mean += model.reconstructionError(row);
        }
        mean /= normals.length;
        double var = 0;
        for (double[] row : normals) {
            var += Math.pow(model.reconstructionError(row) - mean, 2);
        }
        double threshold = mean + AE_SIGMA * Math.sqrt(var / normals.length);

        double[] evalScores = new double[eval.size()];
        for (int i = 0; i < eval.size(); i++) {
            evalScores[i] = model.reconstructionError(eval.features()[i]);
        }
        Metrics metrics = metricsOf(evalScores, eval.labels(), threshold);
        return register(ModelVersion.TYPE_AUTOENCODER, model.toPaccmBytes(), metrics, all, holdout);
    }

    /**
     * 落盘模型、签名、登记版本行：达门禁进 gray，否则留 draft 并通知管理员。
     */
    private Map<String, Object> register(String modelType, byte[] modelBytes, Metrics metrics,
                                         TrainingDataset dataset, boolean holdout) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("model_type", modelType);
        report.put("accuracy", round(metrics.accuracy()));
        report.put("false_positive_rate", round(metrics.falsePositiveRate()));
        report.put("recall", round(metrics.recall()));
        report.put("threshold", round(metrics.threshold()));
        report.put("held_out", holdout);
        report.put("training_samples", dataset.size());

        String sha256 = PaccModelFormat.sha256Hex(modelBytes);
        // 幂等：模型字节与已在灰度/全量中的版本一致时不再重复登记
        ModelVersion same = versions.findFirstBySha256OrderByCreatedAtDesc(sha256).orElse(null);
        if (same != null && (ModelVersion.STATUS_GRAY.equals(same.getStatus())
                || ModelVersion.STATUS_ACTIVE.equals(same.getStatus()))) {
            report.put("status", same.getStatus());
            report.put("skipped", "模型字节与已发布版本 " + same.getId() + " 一致，未重复登记");
            return report;
        }

        ModelVersion row = ModelVersion.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .modelType(modelType)
                .version(nextVersion(modelType))
                .sha256(sha256)
                .signature(sign(modelBytes))
                .accuracy(metrics.accuracy())
                .falsePositiveRate(metrics.falsePositiveRate())
                .recall(metrics.recall())
                .trainingSamples(dataset.size())
                .createdAt(Instant.now())
                .build();
        row.setFileUrl(writeModelFile(modelType, row.getVersion(), modelBytes));

        boolean passed = metrics.accuracy() > MIN_ACCURACY
                && metrics.falsePositiveRate() < MAX_FALSE_POSITIVE_RATE;
        if (passed) {
            release(row, GRAY_PERCENT);
            log.info("模型通过门禁并进入灰度 type={} version={} accuracy={} fpr={} recall={}",
                    modelType, row.getVersion(), metrics.accuracy(), metrics.falsePositiveRate(), metrics.recall());
        } else {
            row.setStatus(ModelVersion.STATUS_DRAFT);
            versions.save(row);
            log.warn("模型未达发布门禁，记入 draft type={} version={} accuracy={} fpr={} recall={}",
                    modelType, row.getVersion(), metrics.accuracy(), metrics.falsePositiveRate(), metrics.recall());
            notifyGateMiss(modelType, row.getVersion(), metrics, dataset.size());
        }
        report.put("status", row.getStatus());
        report.put("version_id", row.getId());
        report.put("version", row.getVersion());
        report.put("sha256", sha256);
        report.put("signed", !row.getSignature().isEmpty());
        return report;
    }

    /** 在候选阈值（0.01 步长）中挑训练集准确率最优者；并列时取误报率更低者。 */
    private static double pickThreshold(double[] scores, double[] labels) {
        double bestThreshold = 0.5;
        double bestAccuracy = -1;
        double bestFpr = 1;
        for (int i = 1; i <= 99; i++) {
            double t = i / 100.0;
            Metrics m = metricsOf(scores, labels, t);
            boolean better = m.accuracy() > bestAccuracy + 1e-12
                    || (Math.abs(m.accuracy() - bestAccuracy) <= 1e-12 && m.falsePositiveRate() < bestFpr);
            if (better) {
                bestAccuracy = m.accuracy();
                bestFpr = m.falsePositiveRate();
                bestThreshold = t;
            }
        }
        return bestThreshold;
    }

    private static double[] scoreXgboost(XGBoostModel model, double[][] x) {
        double[] scores = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            scores[i] = model.predict(x[i]);
        }
        return scores;
    }

    /**
     * 混淆矩阵 → 指标：分数严格大于阈值判为作弊。
     * <ul>
     *   <li>accuracy = (TP + TN) / N；</li>
     *   <li>falsePositiveRate = FP / (FP + TN)（评估集无正常样本时记 1：宁可判未达标）；</li>
     *   <li>recall = TP / (TP + FN)（评估集无作弊样本时记 1：无召回可失）。</li>
     * </ul>
     */
    private static Metrics metricsOf(double[] scores, double[] labels, double threshold) {
        int tp = 0;
        int fp = 0;
        int tn = 0;
        int fn = 0;
        for (int i = 0; i < scores.length; i++) {
            boolean positive = scores[i] > threshold;
            boolean actual = labels[i] >= 0.5;
            if (positive && actual) {
                tp++;
            } else if (positive) {
                fp++;
            } else if (actual) {
                fn++;
            } else {
                tn++;
            }
        }
        int n = scores.length;
        double accuracy = n == 0 ? 0.0 : (double) (tp + tn) / n;
        double fpr = (fp + tn) == 0 ? 1.0 : (double) fp / (fp + tn);
        double recall = (tp + fn) == 0 ? 1.0 : (double) tp / (tp + fn);
        return new Metrics(accuracy, fpr, recall, threshold);
    }

    private static boolean hasBothClasses(TrainingDataset dataset) {
        return dataset.positiveCount() > 0 && dataset.negativeCount() > 0;
    }

    private static double[][] filterByLabel(TrainingDataset dataset, double label) {
        List<double[]> rows = new ArrayList<>();
        double[][] x = dataset.features();
        double[] y = dataset.labels();
        for (int i = 0; i < y.length; i++) {
            if (Double.compare(y[i], label) == 0) {
                rows.add(x[i]);
            }
        }
        return rows.toArray(new double[0][]);
    }

    /** 评估指标（accuracy / 误报率 / 召回率 + 判定阈值）。 */
    private record Metrics(double accuracy, double falsePositiveRate, double recall, double threshold) {
    }

    // ------------------------------ 版本状态机 ------------------------------

    /**
     * 灰度发布：状态置 {@code gray} 并设置放量百分比。
     * 灰度期间不撤下当前 active 版本，便于对比与随时回退。
     *
     * @param modelVersion 待灰度版本
     * @param grayPercent  放量百分比，越界自动裁剪到 [0,100]
     */
    @Transactional
    public ModelVersion release(ModelVersion modelVersion, int grayPercent) {
        if (ModelVersion.STATUS_ACTIVE.equals(modelVersion.getStatus())) {
            throw new IllegalStateException("版本 " + modelVersion.getId() + " 已全量生效，无需灰度");
        }
        modelVersion.setStatus(ModelVersion.STATUS_GRAY);
        modelVersion.setGrayPercent(clampPercent(grayPercent));
        if (modelVersion.getPublishedAt() == null) {
            modelVersion.setPublishedAt(Instant.now());
        }
        versions.save(modelVersion);
        log.info("模型进入灰度 id={} type={} version={} grayPercent={}",
                modelVersion.getId(), modelVersion.getModelType(), modelVersion.getVersion(),
                modelVersion.getGrayPercent());
        return modelVersion;
    }

    /** 灰度发布（按版本 id，供管理端调用）。 */
    @Transactional
    public ModelVersion release(String id, int grayPercent) {
        return release(require(id), grayPercent);
    }

    /** 全量上线：当前 active 让位为 {@code rollback}，本版本置 {@code active} 且放量 100%。 */
    @Transactional
    public ModelVersion promoteToActive(String id) {
        ModelVersion target = require(id);
        if (ModelVersion.STATUS_DRAFT.equals(target.getStatus())) {
            throw new IllegalStateException("版本 " + id + " 未通过发布门禁（draft），不能直接全量上线");
        }
        demoteCurrentActive(target.getModelType(), target.getId());
        target.setStatus(ModelVersion.STATUS_ACTIVE);
        target.setGrayPercent(100);
        if (target.getPublishedAt() == null) {
            target.setPublishedAt(Instant.now());
        }
        versions.save(target);
        log.info("模型全量上线 id={} type={} version={}", id, target.getModelType(), target.getVersion());
        return target;
    }

    /**
     * 一键回退：当前 active 落为 {@code rollback}，历史上最近发布的 {@code rollback} 版本重新生效。
     *
     * @throws IllegalStateException 无生效版本或无历史可回退版本
     */
    @Transactional
    public ModelVersion rollback(String modelType) {
        ModelVersion current = versions.findFirstByModelTypeAndStatusOrderByCreatedAtDesc(
                        modelType, ModelVersion.STATUS_ACTIVE)
                .orElseThrow(() -> new IllegalStateException("模型类型 " + modelType + " 没有生效中的版本"));
        // 先选出回退目标再降级当前版本，避免把刚降级者当成回退目标
        ModelVersion previous = versions.findFirstByModelTypeAndStatusOrderByPublishedAtDesc(
                        modelType, ModelVersion.STATUS_ROLLBACK)
                .filter(p -> !p.getId().equals(current.getId()))
                .orElseThrow(() -> new IllegalStateException("模型类型 " + modelType + " 没有可回退的历史版本"));

        current.setStatus(ModelVersion.STATUS_ROLLBACK);
        current.setGrayPercent(0);
        versions.save(current);
        previous.setStatus(ModelVersion.STATUS_ACTIVE);
        previous.setGrayPercent(100);
        previous.setPublishedAt(Instant.now());
        versions.save(previous);
        log.info("模型回退 type={} from={} to={}", modelType, current.getVersion(), previous.getVersion());
        return previous;
    }

    /** 回退到指定版本：该版本所在模型的其余 active 落为 {@code rollback}，本版本置 {@code active}。 */
    @Transactional
    public ModelVersion rollbackTo(String versionId) {
        ModelVersion target = require(versionId);
        demoteCurrentActive(target.getModelType(), target.getId());
        target.setStatus(ModelVersion.STATUS_ACTIVE);
        target.setGrayPercent(100);
        if (target.getPublishedAt() == null) {
            target.setPublishedAt(Instant.now());
        }
        versions.save(target);
        log.info("模型回退到指定版本 id={} type={} version={}",
                versionId, target.getModelType(), target.getVersion());
        return target;
    }

    // ------------------------------ 查询 API ------------------------------

    /** 版本列表（按登记时间倒序）；modelType 为空时返回全部模型类型。 */
    public List<Map<String, Object>> listVersions(String modelType) {
        List<ModelVersion> rows = (modelType == null || modelType.isBlank())
                ? versions.findAllByOrderByCreatedAtDesc()
                : versions.findByModelTypeOrderByCreatedAtDesc(upper(modelType));
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (ModelVersion row : rows) {
            out.add(toView(row, false));
        }
        return out;
    }

    /** 当前生效（active）版本视图；该模型类型无生效版本时返回 null。 */
    public Map<String, Object> activeVersion(String modelType) {
        if (modelType == null || modelType.isBlank()) {
            throw new IllegalArgumentException("modelType 不能为空");
        }
        return versions.findFirstByModelTypeAndStatusOrderByCreatedAtDesc(
                        upper(modelType), ModelVersion.STATUS_ACTIVE)
                .map(row -> toView(row, true))
                .orElse(null);
    }

    /** 版本详情（含签名）。 */
    public Map<String, Object> versionDetail(String id) {
        return toView(require(id), true);
    }

    // ------------------------------ 内部工具 ------------------------------

    /** 复核发现 → 有监督样本；无明确结论或无特征摘要的行跳过（不可作为训练信号）。 */
    private static List<TrainingDataset.LabeledSample> labeledSamples(List<ZeroDayFinding> reviewed) {
        List<TrainingDataset.LabeledSample> out = new ArrayList<>();
        for (ZeroDayFinding f : reviewed) {
            if (f.getConfirmed() == null || f.getFeaturesJson() == null || f.getFeaturesJson().isBlank()) {
                continue;
            }
            out.add(new TrainingDataset.LabeledSample(f.getFeaturesJson(), f.getConfirmed() ? 1.0 : 0.0));
        }
        return out;
    }

    /** 下一个版本号：同模型类型最近登记行的版本号 +1（版本号单调递增）。 */
    private String nextVersion(String modelType) {
        return versions.findTopByModelTypeOrderByCreatedAtDesc(modelType)
                .map(last -> Long.toString(parseVersion(last.getVersion()) + 1))
                .orElse("1");
    }

    private static long parseVersion(String version) {
        try {
            return Long.parseLong(version);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 降低当前 active 版本（排除 keepId 自身）。 */
    private void demoteCurrentActive(String modelType, String keepId) {
        versions.findFirstByModelTypeAndStatusOrderByCreatedAtDesc(modelType, ModelVersion.STATUS_ACTIVE)
                .filter(current -> !current.getId().equals(keepId))
                .ifPresent(current -> {
                    current.setStatus(ModelVersion.STATUS_ROLLBACK);
                    current.setGrayPercent(0);
                    versions.save(current);
                });
    }

    /** 写出模型字节到模型库目录，返回相对文件名（搬迁数据目录不影响历史登记）。 */
    private String writeModelFile(String modelType, String version, byte[] modelBytes) {
        String fileName = modelType.toLowerCase(Locale.ROOT) + "-v" + version + ".paccm";
        try {
            Path dir = Paths.get(storeDir);
            Files.createDirectories(dir);
            Files.write(dir.resolve(fileName), modelBytes,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new IllegalStateException("模型产物写入失败：" + e.getMessage(), e);
        }
        return fileName;
    }

    /**
     * 模型签名：私钥为 PKCS#8 Base64（PACC_MODEL_SIGNING_KEY），算法 SHA256withRSA。
     * 未配置密钥或签名失败时返回空串并记日志——绝不写入伪造签名。
     */
    private String sign(byte[] modelBytes) {
        if (signingKey == null || signingKey.isBlank()) {
            log.info("模型签名已禁用（未配置 PACC_MODEL_SIGNING_KEY），签名列写空串");
            return "";
        }
        try {
            byte[] pkcs8 = Base64.getDecoder().decode(signingKey.trim());
            PrivateKey privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(modelBytes);
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.warn("模型签名失败，签名列写空串（不伪造签名）err={}", e.getMessage());
            return "";
        }
    }

    /** 门禁未通过：走既有通知服务告知管理员（内容只含指标，不含敏感信息）。 */
    private void notifyGateMiss(String modelType, String version, Metrics metrics, int sampleCount) {
        String content = String.format(Locale.ROOT,
                "候选版本 v%s（%s）未通过发布门禁：accuracy=%.4f（需 > %.2f），false_positive_rate=%.4f（需 < %.2f），"
                        + "recall=%.4f，样本 %d 条。已登记为 draft，未发布。",
                version, modelType, metrics.accuracy(), MIN_ACCURACY,
                metrics.falsePositiveRate(), MAX_FALSE_POSITIVE_RATE, metrics.recall(), sampleCount);
        try {
            notificationService.sendToAll("模型未达发布门禁：" + modelType, content, "SYSTEM", "MODEL_TRAINING", "HIGH");
        } catch (Exception e) {
            log.warn("模型门禁通知发送失败 err={}", e.getMessage());
        }
    }

    private ModelVersion require(String id) {
        return versions.findById(id)
                .orElseThrow(() -> new NoSuchElementException("模型版本不存在：" + id));
    }

    private static Map<String, Object> toView(ModelVersion row, boolean withSignature) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("model_type", row.getModelType());
        m.put("version", row.getVersion());
        m.put("file_url", row.getFileUrl());
        m.put("sha256", row.getSha256());
        m.put("accuracy", row.getAccuracy());
        m.put("false_positive_rate", row.getFalsePositiveRate());
        m.put("recall", row.getRecall());
        m.put("training_samples", row.getTrainingSamples());
        m.put("status", row.getStatus());
        m.put("gray_percent", row.getGrayPercent());
        m.put("created_at", row.getCreatedAt() == null ? "" : row.getCreatedAt().toString());
        m.put("published_at", row.getPublishedAt() == null ? "" : row.getPublishedAt().toString());
        if (withSignature) {
            m.put("signature", row.getSignature());
        }
        return m;
    }

    private static String upper(String value) {
        return value.toUpperCase(Locale.ROOT);
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}