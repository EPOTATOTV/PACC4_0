package com.potatotv.pacc.service.apm;

import com.potatotv.pacc.domain.ApmMetric;
import com.potatotv.pacc.repository.ApmMetricRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v5.4 §2.2 APM 采样入库：把客户端批量上报的指标落成 {@code t_apm_metric} 原始行。
 *
 * <p>入口刻意做得很“窄”，因为原始表是全链路唯一由客户端直接写入的表，一旦放开就会失控：</p>
 * <ul>
 *   <li><b>指标名白名单</b>：只接受 {@link ApmCatalog} 登记过的名称，未知指标计数丢弃、不落库，
 *       否则指标基数（每个客户端都能造一个新名字）会把聚合分组与图表彻底撑爆；</li>
 *   <li><b>单批上限</b> {@value #MAX_BATCH} 条：防止一次请求占满连接与内存；</li>
 *   <li><b>时间戳夹紧</b>：只接受 {@code [now-7d, now+5min]}，窗口外直接丢弃。
 *       客户端时钟错乱会写出“穿越”时间戳，把聚合桶打歪到别的日子；</li>
 *   <li><b>tags 截断</b>：只留前 {@value #MAX_TAGS_LENGTH} 个字符，标签由客户端自由拼装，不设上限就是个 LONGTEXT 炸弹。</li>
 * </ul>
 *
 * <p>落库走一次 {@code saveAll}，不做逐条 save：一批上报是同一个语义单元，批量提交才能把
 * 每条的往返开销压下去。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApmIngestService {

    /** 单批采样上限。 */
    public static final int MAX_BATCH = 2000;
    /** tags 序列化后的长度上限。 */
    public static final int MAX_TAGS_LENGTH = 512;
    /** 允许的最大回溯时间。 */
    static final Duration MAX_BACKDATE = Duration.ofDays(7);
    /** 允许的最大未来偏差（容忍客户端时钟小幅漂移）。 */
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final ApmMetricRepository metrics;

    /** 客户端上报的一条采样。{@code tags} 是已序列化的 JSON 字符串（可为空）。 */
    public record Sample(Instant metricTime, String name, double value, String type, String tags) { }

    /** 入库结果：{@code accepted} 落库条数，{@code rejected} 被护栏丢弃的条数。 */
    public record IngestResult(int accepted, int rejected) { }

    /** 只关心落库条数的调用方。 */
    @Transactional
    public int ingest(String pteid, String clientVersion, String platform, List<Sample> samples) {
        return ingestDetailed(pteid, clientVersion, platform, samples).accepted();
    }

    /**
     * 批量入库（带丢弃计数）。
     *
     * @param pteid         上报玩家（取自会话属性，不信客户端自述），空则整批忽略
     * @param clientVersion 客户端版本，作为聚合维度
     * @param platform      平台标识（WIN/LNX/…），统一转大写
     * @param samples       采样列表，可空
     */
    @Transactional
    public IngestResult ingestDetailed(String pteid, String clientVersion, String platform,
                                       List<Sample> samples) {
        if (pteid == null || pteid.isBlank()) {
            return new IngestResult(0, samples == null ? 0 : samples.size());
        }
        if (samples == null || samples.isEmpty()) return new IngestResult(0, 0);

        Instant now = Instant.now();
        Instant earliest = now.minus(MAX_BACKDATE);
        Instant latest = now.plus(MAX_CLOCK_SKEW);
        String ver = clientVersion == null ? "" : clientVersion.trim();
        String plat = platform == null ? "" : platform.trim().toUpperCase(Locale.ROOT);

        int rejected = Math.max(0, samples.size() - MAX_BATCH);
        if (rejected > 0) {
            log.warn("APM 上报超出单批上限 pteid={} 本批={} 丢弃={}", pteid, samples.size(), rejected);
        }

        int limit = Math.min(samples.size(), MAX_BATCH);
        List<ApmMetric> batch = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Sample s = samples.get(i);
            String name = s == null || s.name() == null ? "" : s.name().trim();
            if (name.isEmpty() || ApmCatalog.byName(name).isEmpty()) {
                rejected++;   // 未登记指标：丢弃但不记明细，避免日志被长尾名字污染
                continue;
            }
            Instant time = s.metricTime();
            if (time == null || time.isBefore(earliest) || time.isAfter(latest)) {
                rejected++;
                continue;
            }
            if (!Double.isFinite(s.value())) {
                rejected++;   // NaN/Inf 无法落 DOUBLE 列，也说明端侧算炸了
                continue;
            }
            batch.add(ApmMetric.builder()
                    .pteid(pteid)
                    .platform(plat)
                    .clientVer(ver)
                    .metricTime(time)
                    .metricName(name)
                    .metricValue(s.value())
                    .metricType(normalizeType(s.type()))
                    .tags(truncateTags(s.tags()))
                    .createdAt(now)
                    .build());
        }
        if (!batch.isEmpty()) metrics.saveAll(batch);
        return new IngestResult(batch.size(), rejected);
    }

    /** 类型归一：识别不了的按 gauge 落库（端侧先上新类型、服务端后加枚举时不应报错）。 */
    private static String normalizeType(String raw) {
        if (raw != null && !raw.isBlank()) {
            String up = raw.trim().toUpperCase(Locale.ROOT);
            for (ApmMetric.MetricType type : ApmMetric.MetricType.values()) {
                if (type.name().equals(up)) return up;
            }
        }
        return ApmMetric.MetricType.GAUGE.name();
    }

    private static String truncateTags(String tags) {
        if (tags == null || tags.isEmpty()) return null;
        return tags.length() <= MAX_TAGS_LENGTH ? tags : tags.substring(0, MAX_TAGS_LENGTH);
    }
}