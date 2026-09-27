package com.potatotv.pacc.service.detection.df.federated;

import com.potatotv.pacc.domain.FederatedModel;
import com.potatotv.pacc.domain.FederatedRound;
import com.potatotv.pacc.domain.FederatedUpdate;
import com.potatotv.pacc.domain.ModelVersion;
import com.potatotv.pacc.repository.FederatedModelRepository;
import com.potatotv.pacc.repository.FederatedRoundRepository;
import com.potatotv.pacc.repository.FederatedUpdateRepository;
import com.potatotv.pacc.repository.ModelVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * DF §4.1.2 联邦学习云端编排：开轮 → 收梯度 → 校验 → FedAvg 聚合 → 产出全局模型 → 接入既有灰度机制。
 *
 * <pre>
 *   玩家A(梯度) ─┐
 *   玩家B(梯度) ─┼→ 云端聚合(FedAvg) → 新模型 → 灰度下发（复用 v5.2 t_model_version 状态机）
 *   玩家C(梯度) ─┘
 * </pre>
 *
 * <p>轮次关闭的两条路径（先到者生效）：</p>
 * <ol>
 *   <li>{@link #submitUpdate} 中通过校验的客户端数达到 {@code targetClients}，立即自动关闭并聚合；</li>
 *   <li>到达 {@code deadlineAt}，由 {@link #closeExpiredRounds()} 定时关闭（无聚合则留痕不产模型）。</li>
 * </ol>
 *
 * <p>隐私防护：只接收与持久化梯度（模型增量），绝不接收或落库任何逐事件原始数据；每条更新先过
 * {@link FedAvgAggregator#validate}（形状 / 有限性 / 范数上限），再查重（每客户端每轮一条），
 * 未通过者落表 {@code accepted=0} 与拒绝原因供审计。</p>
 *
 * <p>模型产出：聚合结果以固定步长作用于上一全局模型得到新参数，写入 {@link FederatedModel}，
 * 同时在 {@link ModelVersion} 登记一行类型为 {@code FEDERATED} 的灰度版本——灰度放量、全量上线、
 * 一键回退全部沿用 v5.2 §6.1 既有端点，无需新增状态机。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FederatedLearningService {

    /** 联邦模型的模型类型（登记进 t_model_version，供灰度机制复用）。 */
    public static final String MODEL_TYPE_FEDERATED = "FEDERATED";

    private static final int MAX_HISTORY = 200;

    private final FederatedRoundRepository rounds;
    private final FederatedUpdateRepository updates;
    private final FederatedModelRepository models;
    private final ModelVersionRepository modelVersions;

    /** 默认期望客户端数（达到即关闭聚合）。 */
    @Value("${pacc.df.federated.default-target-clients:3}")
    private int defaultTargetClients;
    /** 默认最少客户端数（不足则关闭但不产模型）。 */
    @Value("${pacc.df.federated.default-min-clients:2}")
    private int defaultMinClients;
    /** 默认截止时间（秒）。 */
    @Value("${pacc.df.federated.default-deadline-seconds:300}")
    private int defaultDeadlineSeconds;
    /** 默认全局模型更新步长。 */
    @Value("${pacc.df.federated.learning-rate:0.1}")
    private double defaultLearningRate;
    /** 单客户端梯度范数上限（离群拒绝线）。 */
    @Value("${pacc.df.federated.max-gradient-norm:50.0}")
    private double maxGradientNorm;
    /** 梯度维度上限（防超大载荷）。 */
    @Value("${pacc.df.federated.max-gradient-dim:4096}")
    private int maxGradientDim;
    /** 联邦模型登记进 t_model_version 时的初始灰度放量百分比。 */
    @Value("${pacc.df.federated.gray-percent:10}")
    private int grayPercent;

    // ------------------------------ 开轮 ------------------------------

    /**
     * 开启一轮联邦训练。若已有开放轮次则直接返回该轮次（不重复开轮）。
     *
     * @param targetClients   期望客户端数；null 用默认
     * @param minClients      最少客户端数；null 用默认
     * @param deadlineSeconds 截止秒数；null 用默认
     * @param learningRate    步长；null 用默认
     * @param operator        触发者（前端展示用）
     */
    @Transactional
    public synchronized Map<String, Object> openRound(Integer targetClients, Integer minClients,
                                                      Integer deadlineSeconds, Double learningRate,
                                                      String operator) {
        FederatedRound existing = rounds.findFirstByStatusOrderByOpenedAtDesc(FederatedRound.STATUS_OPEN)
                .orElse(null);
        if (existing != null) {
            Map<String, Object> out = roundView(existing);
            out.put("reused", true);
            return out;
        }

        int target = targetClients == null ? defaultTargetClients : Math.max(1, targetClients);
        int min = minClients == null ? defaultMinClients : Math.max(1, minClients);
        if (min > target) {
            min = target;
        }
        int deadline = deadlineSeconds == null ? defaultDeadlineSeconds : Math.max(1, deadlineSeconds);
        double lr = learningRate == null ? defaultLearningRate : learningRate;
        if (lr <= 0) {
            throw new IllegalArgumentException("学习率必须为正：" + lr);
        }

        Instant now = Instant.now();
        FederatedRound round = FederatedRound.builder()
                .id(newId())
                .status(FederatedRound.STATUS_OPEN)
                .targetClients(target)
                .minClients(min)
                .featureDim(0)
                .learningRate(lr)
                .openedAt(now)
                .deadlineAt(now.plusSeconds(deadline))
                .build();
        rounds.save(round);
        log.info("联邦轮次开启 id={} targetClients={} minClients={} deadlineSeconds={} lr={} operator={}",
                round.getId(), target, min, deadline, lr, operator);

        Map<String, Object> out = roundView(round);
        out.put("reused", false);
        return out;
    }

    // ------------------------------ 收梯度 ------------------------------

    /**
     * 接收一个客户端的梯度（模型增量）并按隐私规则校验、落库。
     *
     * @param roundId     轮次 id；为空时取当前开放轮次
     * @param clientId    客户端标识
     * @param gradient    梯度向量
     * @param sampleCount 样本数（FedAvg 权重）
     * @param loss        客户端本轮损失；可为 null
     * @return {@code accepted} 是否计入聚合、{@code reason} 拒绝原因（accepted=false 时非空）、
     *         轮次进度与（自动关闭时的）聚合结果
     */
    @Transactional
    public synchronized Map<String, Object> submitUpdate(String roundId, String clientId,
                                                         List<Double> gradient, Integer sampleCount,
                                                         Double loss) {
        FederatedRound round = (roundId == null || roundId.isBlank())
                ? rounds.findFirstByStatusOrderByOpenedAtDesc(FederatedRound.STATUS_OPEN)
                        .orElseThrow(() -> new NoSuchElementException("当前没有开放中的联邦轮次"))
                : rounds.findById(roundId).orElseThrow(() -> new NoSuchElementException("联邦轮次不存在：" + roundId));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("round_id", round.getId());

        String reason = rejectionReason(round, clientId, gradient, sampleCount);
        if (reason != null) {
            round.setUpdatesReceived(round.getUpdatesReceived() + 1);
            rounds.save(round);
            // 重复上报由唯一键兜底，故只计数不落表；其余拒绝落表留痕（accepted=false + 原因）
            if (!DUPLICATE_REASON.equals(reason)) {
                updates.save(FederatedUpdate.builder()
                        .roundId(round.getId())
                        .clientId(clientId == null ? "" : clientId)
                        .sampleCount(sampleCount == null ? 0 : sampleCount)
                        .featureDim(gradient == null ? 0 : gradient.size())
                        .gradientJson(joinOrEmpty(gradient))
                        .gradientHash(gradient == null ? "" : GradientCodec.sha256Hex(joinOrEmpty(gradient)))
                        .clientLoss(loss)
                        .accepted(false)
                        .rejectReason(reason)
                        .createdAt(Instant.now())
                        .build());
            }
            log.info("联邦更新被拒 round={} client={} reason={}", round.getId(), clientId, reason);
            out.put("accepted", false);
            out.put("reason", reason);
            out.put("updates_accepted", round.getUpdatesAccepted());
            out.put("closed", false);
            return out;
        }

        double[] vector = toVector(gradient);
        int dim = vector.length;
        if (round.getFeatureDim() == 0) {
            round.setFeatureDim(dim);
        }
        round.setUpdatesReceived(round.getUpdatesReceived() + 1);
        round.setUpdatesAccepted(round.getUpdatesAccepted() + 1);
        round.setTotalSamples(round.getTotalSamples() + sampleCount);
        if (loss != null && Double.isFinite(loss) && round.getUpdatesAccepted() > 1) {
            // 在线加权平均：avg ← (avg·(n-1) + loss) / n，权重为「已接受的更新数」，用于趋势展示
            round.setAvgLoss((round.getAvgLoss() * (round.getUpdatesAccepted() - 1) + loss) / round.getUpdatesAccepted());
        } else if (loss != null && Double.isFinite(loss)) {
            round.setAvgLoss(loss);
        }
        updates.save(FederatedUpdate.builder()
                .roundId(round.getId())
                .clientId(clientId)
                .sampleCount(sampleCount)
                .featureDim(dim)
                .gradientNorm(GradientCodec.l2Norm(vector))
                .gradientHash(GradientCodec.sha256Hex(vector))
                .gradientJson(GradientCodec.encode(vector))
                .clientLoss(loss)
                .accepted(true)
                .createdAt(Instant.now())
                .build());
        rounds.save(round);
        log.info("联邦更新已受理 round={} client={} samples={} dim={} 进度={}/{}",
                round.getId(), clientId, sampleCount, dim, round.getUpdatesAccepted(), round.getTargetClients());

        out.put("accepted", true);
        out.put("reason", "");
        out.put("updates_accepted", round.getUpdatesAccepted());
        out.put("total_samples", round.getTotalSamples());

        if (round.getUpdatesAccepted() >= round.getTargetClients()) {
            // 达到期望客户端数：立即关闭并聚合
            out.put("closed", true);
            out.put("aggregation", aggregateAndClose(round));
        } else {
            out.put("closed", false);
        }
        return out;
    }

    // ------------------------------ 关轮 + 聚合 ------------------------------

    /**
     * 显式关闭轮次并执行 FedAvg 聚合。
     *
     * @throws NoSuchElementException 轮次不存在
     * @throws IllegalStateException  轮次已关闭
     */
    @Transactional
    public synchronized Map<String, Object> closeRound(String roundId, String operator) {
        FederatedRound round = rounds.findById(roundId)
                .orElseThrow(() -> new NoSuchElementException("联邦轮次不存在：" + roundId));
        if (!FederatedRound.STATUS_OPEN.equals(round.getStatus())) {
            throw new IllegalStateException("轮次 " + roundId + " 已关闭");
        }
        log.info("联邦轮次手动关闭 id={} operator={}", roundId, operator);
        return aggregateAndClose(round);
    }

    /** 定时关闭已过截止时间的开放轮次（无足够客户端时留痕不产模型）。 */
    @Scheduled(fixedDelay = 60_000L)
    @Transactional
    public void closeExpiredRounds() {
        List<FederatedRound> expired =
                rounds.findByStatusAndDeadlineAtBefore(FederatedRound.STATUS_OPEN, Instant.now());
        for (FederatedRound round : expired) {
            try {
                Map<String, Object> report = aggregateAndClose(round);
                log.info("联邦轮次到期自动关闭 id={} accepted={} model={}",
                        round.getId(), round.getUpdatesAccepted(), report.get("model"));
            } catch (RuntimeException e) {
                log.warn("联邦轮次到期关闭失败 id={} err={}", round.getId(), e.getMessage());
            }
        }
    }

    /**
     * 执行 FedAvg 并落库：通过校验的客户端不足 {@code minClients} 时只关闭不产模型。
     */
    private Map<String, Object> aggregateAndClose(FederatedRound round) {
        List<FederatedUpdate> accepted = updates.findByRoundIdAndAcceptedTrueOrderByCreatedAtAsc(round.getId());
        round.setStatus(FederatedRound.STATUS_CLOSED);
        round.setClosedAt(Instant.now());
        // 收敛趋势对照：上一已关闭轮次的平均损失
        round.setPrevAvgLoss(rounds
                .findFirstByStatusAndClosedAtIsNotNullOrderByClosedAtDesc(FederatedRound.STATUS_CLOSED)
                .filter(prev -> !prev.getId().equals(round.getId()))
                .map(FederatedRound::getAvgLoss)
                .orElse(0.0));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("round_id", round.getId());

        if (accepted.size() < round.getMinClients()) {
            rounds.save(round);
            String reason = "通过校验的客户端 " + accepted.size() + " 少于最少要求 " + round.getMinClients()
                    + "，本轮不产出模型";
            log.info("联邦轮次关闭但未聚合：{}", reason);
            report.put("aggregated", false);
            report.put("reason", reason);
            report.put("model", null);
            report.put("round", roundView(round));
            return report;
        }

        List<ClientGradient> clientGradients = new ArrayList<>(accepted.size());
        for (FederatedUpdate u : accepted) {
            clientGradients.add(new ClientGradient(u.getClientId(), GradientCodec.decode(u.getGradientJson()),
                    u.getSampleCount(), u.getClientLoss()));
        }
        FedAvgAggregator.Aggregate aggregate = FedAvgAggregator.aggregate(clientGradients);
        double[] previous = models.findFirstByOrderByCreatedAtDesc()
                .map(m -> GradientCodec.decode(m.getWeightsJson()))
                .orElse(new double[aggregate.gradient().length]);
        double[] global = FedAvgAggregator.applyGradient(previous, aggregate.gradient(), round.getLearningRate());
        String sha = GradientCodec.sha256Hex(global);

        FederatedModel model = FederatedModel.builder()
                .id(newId())
                .roundId(round.getId())
                .version(nextVersion())
                .featureDim(global.length)
                .totalSamples(aggregate.totalSamples())
                .avgLoss(aggregate.avgLoss())
                .weightsSha256(sha)
                .weightsJson(GradientCodec.encode(global))
                .createdAt(Instant.now())
                .build();
        model.setModelVersionId(registerVersion(model).getId());
        models.save(model);

        round.setAvgLoss(aggregate.avgLoss());
        round.setAggregatedSha256(sha);
        round.setModelId(model.getId());
        rounds.save(round);

        log.info("联邦聚合完成 round={} clients={} samples={} avgLoss={} version={} sha256={}",
                round.getId(), aggregate.clients(), aggregate.totalSamples(), aggregate.avgLoss(),
                model.getVersion(), sha);
        report.put("aggregated", true);
        report.put("clients", aggregate.clients());
        report.put("total_samples", aggregate.totalSamples());
        report.put("avg_loss", round(aggregate.avgLoss()));
        report.put("loss_delta", round(round.getPrevAvgLoss() - aggregate.avgLoss()));
        report.put("model", modelView(model));
        report.put("round", roundView(round));
        return report;
    }

    /**
     * 把联邦模型登记进既有 {@link ModelVersion}（类型 {@code FEDERATED}，状态 gray）。
     *
     * <p>联邦聚合模型没有监督学习的留出集指标，故 accuracy / fpr / recall 一律为 0 而不伪造数值；
     * 其质量由轮次的 {@code avgLoss} 收敛曲线刻画。{@code fileUrl} 留空——该模型是服务端全局参数，
     * 端侧不下发该产物，因此不会出现在玩家可下载清单里。</p>
     */
    private ModelVersion registerVersion(FederatedModel model) {
        ModelVersion row = ModelVersion.builder()
                .id(newId())
                .modelType(MODEL_TYPE_FEDERATED)
                .version(model.getVersion())
                .fileUrl("")
                .sha256(model.getWeightsSha256())
                .signature("")
                .accuracy(0.0)
                .falsePositiveRate(0.0)
                .recall(0.0)
                .trainingSamples((int) Math.min(Integer.MAX_VALUE, model.getTotalSamples()))
                .status(ModelVersion.STATUS_GRAY)
                .grayPercent(Math.max(0, Math.min(100, grayPercent)))
                .createdAt(Instant.now())
                .publishedAt(Instant.now())
                .build();
        return modelVersions.save(row);
    }

    // ------------------------------ 查询 ------------------------------

    /** 轮次历史（按开启时间倒序），含每轮损失与收敛差值——A24 的证据面。 */
    public List<Map<String, Object>> roundHistory(int limit) {
        int max = limit <= 0 ? MAX_HISTORY : Math.min(limit, MAX_HISTORY);
        List<FederatedRound> all = rounds.findAllByOrderByOpenedAtDesc();
        List<Map<String, Object>> out = new ArrayList<>();
        for (FederatedRound round : all) {
            if (out.size() >= max) {
                break;
            }
            Map<String, Object> view = roundView(round);
            view.put("loss_delta", round(round.getPrevAvgLoss() - round.getAvgLoss()));
            out.add(view);
        }
        return out;
    }

    /** 当前聚合模型（最近一轮产出）；无历史返回 null。 */
    public Map<String, Object> latestModel() {
        return models.findFirstByOrderByCreatedAtDesc().map(FederatedLearningService::modelView).orElse(null);
    }

    /**
     * 当前聚合模型 + 权重向量——端侧下发专用。
     *
     * <p>权重单独走这个方法而不是塞进 {@link #modelView}：{@link #modelHistory()} 会对每条历史调用
     * modelView，若把权重并进去，历史接口的响应体会随模型数量线性膨胀。</p>
     *
     * @return 模型视图（含 {@code weights} 逗号分隔浮点串）；无历史返回 null
     */
    public Map<String, Object> latestModelWithWeights() {
        return models.findFirstByOrderByCreatedAtDesc()
                .map(m -> {
                    Map<String, Object> view = modelView(m);
                    view.put("weights", m.getWeightsJson() == null ? "" : m.getWeightsJson());
                    return view;
                })
                .orElse(null);
    }

    /** 聚合模型历史（按产出时间倒序）。 */
    public List<Map<String, Object>> modelHistory() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FederatedModel model : models.findAllByOrderByCreatedAtDesc()) {
            out.add(modelView(model));
        }
        return out;
    }

    // ------------------------------ 内部工具 ------------------------------

    /** 重复上报：由唯一键 {@code (round_id, client_id)} 兜底，只计数不落表（避免唯一键冲突）。 */
    private static final String DUPLICATE_REASON = "该客户端本轮已上报过更新（每客户端每轮至多一条）";

    /** 逐条隐私/形状校验；通过返回 null，否则返回拒绝原因。 */
    private String rejectionReason(FederatedRound round, String clientId, List<Double> gradient,
                                   Integer sampleCount) {
        if (!round.acceptsUpdates(Instant.now())) {
            return FederatedRound.STATUS_OPEN.equals(round.getStatus())
                    ? "轮次已过截止时间，不再接收更新"
                    : "轮次已关闭，不再接收更新";
        }
        if (clientId == null || clientId.isBlank()) {
            return "客户端标识为空";
        }
        if (gradient == null || gradient.isEmpty()) {
            return "梯度为空";
        }
        if (updates.existsByRoundIdAndClientId(round.getId(), clientId)) {
            return DUPLICATE_REASON;
        }
        if (sampleCount == null || sampleCount <= 0) {
            return "样本数必须为正";
        }
        double[] vector = toVector(gradient);
        if (vector.length != gradient.size()) {
            return "梯度含非法分量（无法解析为非数值）";
        }
        FedAvgAggregator.Validation v = FedAvgAggregator.validate(
                vector, round.getFeatureDim(), maxGradientDim, maxGradientNorm);
        return v.valid() ? null : v.reason();
    }

    /** Double 列表 → 数组；非法分量返回长度不一致的数组以触发上层拒绝。 */
    private static double[] toVector(List<Double> gradient) {
        if (gradient == null) {
            return new double[0];
        }
        double[] out = new double[gradient.size()];
        int valid = 0;
        for (int i = 0; i < gradient.size(); i++) {
            Double v = gradient.get(i);
            if (v == null) {
                continue;
            }
            out[i] = v;
            valid++;
        }
        return valid == gradient.size() ? out : new double[0];
    }

    private static String joinOrEmpty(List<Double> gradient) {
        if (gradient == null || gradient.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gradient.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Double v = gradient.get(i);
            sb.append(v == null ? "0" : Double.toString(v));
        }
        return sb.toString();
    }

    /** 下一个联邦模型版本号：最近一行版本号 +1。 */
    private String nextVersion() {
        return models.findFirstByOrderByCreatedAtDesc()
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

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** 轮次视图。 */
    private static Map<String, Object> roundView(FederatedRound r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("status", r.getStatus());
        m.put("target_clients", r.getTargetClients());
        m.put("min_clients", r.getMinClients());
        m.put("feature_dim", r.getFeatureDim());
        m.put("learning_rate", r.getLearningRate());
        m.put("updates_received", r.getUpdatesReceived());
        m.put("updates_accepted", r.getUpdatesAccepted());
        m.put("total_samples", r.getTotalSamples());
        m.put("avg_loss", round(r.getAvgLoss()));
        m.put("prev_avg_loss", round(r.getPrevAvgLoss()));
        m.put("aggregated_sha256", r.getAggregatedSha256());
        m.put("model_id", r.getModelId() == null ? "" : r.getModelId());
        m.put("opened_at", r.getOpenedAt() == null ? "" : r.getOpenedAt().toString());
        m.put("deadline_at", r.getDeadlineAt() == null ? "" : r.getDeadlineAt().toString());
        m.put("closed_at", r.getClosedAt() == null ? "" : r.getClosedAt().toString());
        return m;
    }

    /** 聚合模型视图。 */
    private static Map<String, Object> modelView(FederatedModel model) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", model.getId());
        m.put("round_id", model.getRoundId());
        m.put("version", model.getVersion());
        m.put("feature_dim", model.getFeatureDim());
        m.put("total_samples", model.getTotalSamples());
        m.put("avg_loss", round(model.getAvgLoss()));
        m.put("weights_sha256", model.getWeightsSha256());
        m.put("model_version_id", model.getModelVersionId() == null ? "" : model.getModelVersionId());
        m.put("created_at", model.getCreatedAt() == null ? "" : model.getCreatedAt().toString());
        return m;
    }
}