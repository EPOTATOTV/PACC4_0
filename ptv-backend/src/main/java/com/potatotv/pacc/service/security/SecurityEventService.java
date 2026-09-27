package com.potatotv.pacc.service.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.SecurityEvent;
import com.potatotv.pacc.repository.SecurityEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v5.4 §3.6 客户端安全事件服务（哈希链审计日志）。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><b>不信任客户端</b>：事件类型/等级入库前归一，发生时间被夹进 {@code [now-7d, now+5min]}，
 *       聚合与过滤一律以服务端 {@code received_at} 为准，客户端改时间无法逃逸。</li>
 *   <li><b>防篡改</b>：每行摘要内嵌上一行摘要（{@code prev_hash}），{@link #verifyChain(int)}
 *       重算即可发现被删/被改的行。</li>
 *   <li><b>链位分配</b>：本部署只有单个后端实例，因此用进程内 {@code synchronized} 锁串行化
 *       「读链尾 → 分配 seq」即可保证单调递增（见 {@link #nextChainSlice()}）。唯一索引
 *       {@code uk_security_event_seq} 是安全网：一旦锁失效导致重复 seq，插入会直接失败而不是静默分叉。</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SecurityEventService {

    /** 单次上报最多接受的事件数。 */
    private static final int MAX_EVENTS_PER_CALL = 200;
    /** detail 落库上限。 */
    private static final int MAX_DETAIL = 512;
    /** evidence 落库上限。 */
    private static final int MAX_EVIDENCE = 4096;
    /** 客户端发生时间最多回溯。 */
    private static final ChronoUnit BACK_UNIT = ChronoUnit.DAYS;
    private static final long MAX_BACKDATE_DAYS = 7L;
    /** 允许的客户端时钟超前量。 */
    private static final ChronoUnit FUTURE_UNIT = ChronoUnit.MINUTES;
    private static final long MAX_FUTURE_MINUTES = 5L;
    /** 链校验最多回看的行数（避免一次查询把链整个拉进内存）。 */
    private static final int CHAIN_SCAN_MAX = 2000;
    /** 概览里 recent 的条数。 */
    private static final int RECENT_LIMIT = 20;

    private final SecurityEventRepository repository;
    private final ObjectMapper objectMapper;

    /** 客户端上报的单条事件。 */
    public record EventInput(String type, String level, String detail, String evidence, Instant occurredAt) {
    }

    /** 链位分配结果：下一个 seq 与其前驱摘要。 */
    private record ChainSlice(long seq, String prevHash) {
    }

    /** 链校验结果：{@code brokenAtSeq} 为断链行 seq（ok 时为 0）。 */
    public record ChainVerification(boolean ok, long checked, long brokenAtSeq, String detail) {
    }

    /**
     * 追加一批安全事件。
     *
     * @return 实际入库条数（类型为空的条目被跳过）
     */
    public int record(String pteid, String platform, String clientVer, List<EventInput> events) {
        if (events == null || events.isEmpty()) return 0;
        Instant now = Instant.now();
        Instant oldest = now.minus(MAX_BACKDATE_DAYS, BACK_UNIT);
        Instant newest = now.plus(MAX_FUTURE_MINUTES, FUTURE_UNIT);
        String pid = nz(pteid);
        String plat = nz(platform);
        String ver = nz(clientVer);

        int limit = Math.min(events.size(), MAX_EVENTS_PER_CALL);
        int accepted = 0;
        for (int i = 0; i < limit; i++) {
            EventInput in = events.get(i);
            if (in == null || in.type() == null || in.type().isBlank()) continue;

            String type = SecurityEvent.parseType(in.type()).name();
            String level = SecurityEvent.parseLevel(in.level()).name();
            Instant occurredAt = clampInstant(in.occurredAt(), oldest, newest, now);
            String detail = cap(nz(in.detail()), MAX_DETAIL);
            String evidence = cap(in.evidence(), MAX_EVIDENCE);

            ChainSlice slice = nextChainSlice();
            String hash = computeHash(slice.seq(), pid, type, level, occurredAt, detail, slice.prevHash());
            SecurityEvent row = SecurityEvent.builder()
                    .id(UUID.randomUUID().toString())
                    .seq(slice.seq())
                    .pteid(pid)
                    .platform(plat)
                    .clientVer(ver)
                    .eventType(type)
                    .level(level)
                    .detail(detail)
                    .evidence(evidence == null || evidence.isBlank() ? null : evidence)
                    .occurredAt(occurredAt)
                    .receivedAt(now)
                    .prevHash(slice.prevHash())
                    .hash(hash)
                    .build();
            try {
                repository.save(row);
                accepted++;
            } catch (DataIntegrityViolationException e) {
                // 链位冲突必须显式失败：宁可丢这一批，也不能让链静默分叉
                log.error("安全事件哈希链追加失败（seq 冲突，链可能已分叉）seq={} type={}", slice.seq(), type, e);
                throw e;
            }
        }
        if (accepted < events.size()) {
            log.debug("安全事件上报裁剪：收到={} 入库={}", events.size(), accepted);
        }
        return accepted;
    }

    /**
     * 读取链尾并分配下一个 seq。
     *
     * <p>{@code synchronized} 串行化「读链尾 → 用其 hash 作为下一行 prevHash」这一步；
     * 本部署为单后端实例，进程内锁足够。若将来多实例，需换成数据库序列 + 唯一索引兜底。</p>
     */
    private synchronized ChainSlice nextChainSlice() {
        SecurityEvent last = repository.findFirstByOrderBySeqDesc().orElse(null);
        if (last == null) {
            return new ChainSlice(1L, "");
        }
        return new ChainSlice(last.getSeq() + 1L, trim(last.getHash()));
    }

    /**
     * 校验最近的 {@code limit} 行：逐行重算摘要，确认 prev_hash 链接与 seq 连续。
     * 窗口内最老一行没有前驱可比，只复算其自身摘要。
     */
    public ChainVerification verifyChain(int limit) {
        int n = clamp(limit, 1, CHAIN_SCAN_MAX);
        List<SecurityEvent> desc = repository.findAll(
                PageRequest.of(0, n, Sort.by(Sort.Direction.DESC, "seq"))).getContent();
        if (desc.isEmpty()) {
            return new ChainVerification(true, 0L, 0L, "链为空");
        }
        List<SecurityEvent> rows = new ArrayList<>(desc);
        Collections.reverse(rows); // 转为 seq 升序

        SecurityEvent prev = null;
        for (SecurityEvent e : rows) {
            // CHAR(64) 在部分库（如 H2）会把短值右侧补空格，读取时统一 trim，避免把补位误判成断链
            String recomputed = computeHash(e.getSeq(), nz(e.getPteid()), e.getEventType(), e.getLevel(),
                    e.getOccurredAt(), nz(e.getDetail()), trim(e.getPrevHash()));
            if (!recomputed.equals(trim(e.getHash()))) {
                return new ChainVerification(false, rows.size(), e.getSeq(), "行摘要复算不匹配");
            }
            if (prev != null) {
                if (!trim(e.getPrevHash()).equals(trim(prev.getHash()))) {
                    return new ChainVerification(false, rows.size(), e.getSeq(), "prev_hash 未链接上一行");
                }
                if (e.getSeq() != prev.getSeq() + 1L) {
                    return new ChainVerification(false, rows.size(), e.getSeq(), "seq 不连续");
                }
            }
            prev = e;
        }
        return new ChainVerification(true, rows.size(), 0L, "");
    }

    /** 概览卡片：窗口内计数 + 分布 + 链状态 + 最近事件。 */
    public Map<String, Object> overview(int hours) {
        int h = clamp(hours, 1, 720);
        Instant from = Instant.now().minus(h, ChronoUnit.HOURS);
        long total = repository.countByReceivedAtAfter(from);

        List<Map<String, Object>> byType = new ArrayList<>();
        for (Object[] row : repository.countByTypeSince(from)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", row[0] == null ? "" : row[0].toString());
            m.put("count", row[1] == null ? 0L : ((Number) row[1]).longValue());
            byType.add(m);
        }

        long critical = 0L;
        long high = 0L;
        List<Map<String, Object>> byLevel = new ArrayList<>();
        for (Object[] row : repository.countByLevelSince(from)) {
            String lvl = row[0] == null ? "" : row[0].toString();
            long cnt = row[1] == null ? 0L : ((Number) row[1]).longValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", lvl);
            m.put("count", cnt);
            byLevel.add(m);
            if (SecurityEvent.Level.CRITICAL.name().equals(lvl)) critical = cnt;
            if (SecurityEvent.Level.HIGH.name().equals(lvl)) high = cnt;
        }

        ChainVerification cv = verifyChain(500);

        List<Map<String, Object>> recent = new ArrayList<>();
        for (SecurityEvent e : repository.findTop200ByOrderBySeqDesc()) {
            if (recent.size() >= RECENT_LIMIT) break;
            recent.add(viewOf(e));
        }

        // 卡片指标嵌在 cards 下，与 APM 概览（/api/admin/apm/overview）保持同一形状，
        // 前端一套渲染逻辑即可覆盖两个页面。
        Map<String, Object> cards = new LinkedHashMap<>();
        cards.put("total", total);
        cards.put("critical", critical);
        cards.put("high", high);
        cards.put("by_type", byType);
        cards.put("by_level", byLevel);
        cards.put("chain_ok", cv.ok());
        cards.put("chain_checked", cv.checked());
        cards.put("chain_broken_at", cv.brokenAtSeq());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hours", h);
        out.put("cards", cards);
        out.put("recent", recent);
        return out;
    }

    /**
     * 列表查询。ptEID / 类型 / 等级三个过滤条件里取一个作为数据库主过滤，其余在内存里精筛，
     * 这样无需为多条件组合再扩仓储方法；结果上限 {@code [1,200]}。
     */
    public Map<String, Object> list(String level, String type, String pteid, int limit) {
        int n = clamp(limit, 1, 200);
        String lvlFilter = blank(level) ? null : SecurityEvent.parseLevel(level).name();
        String typeFilter = blank(type) ? null : SecurityEvent.parseType(type).name();
        String pteidFilter = blank(pteid) ? null : pteid.trim();

        List<SecurityEvent> rows;
        if (pteidFilter != null) {
            rows = repository.findTop200ByPteidOrderBySeqDesc(pteidFilter);
        } else if (typeFilter != null) {
            rows = repository.findTop200ByEventTypeOrderBySeqDesc(typeFilter);
        } else if (lvlFilter != null) {
            rows = repository.findTop200ByLevelOrderBySeqDesc(lvlFilter);
        } else {
            rows = repository.findTop200ByOrderBySeqDesc();
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (SecurityEvent e : rows) {
            if (lvlFilter != null && !lvlFilter.equals(e.getLevel())) continue;
            if (typeFilter != null && !typeFilter.equals(e.getEventType())) continue;
            if (pteidFilter != null && !pteidFilter.equals(nz(e.getPteid()))) continue;
            if (items.size() >= n) break;
            items.add(viewOf(e));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", items.size());
        return out;
    }

    /** 单行 → 控制层视图（概览 recent 与列表共用同一形状）。 */
    public Map<String, Object> viewOf(SecurityEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("pteid", nz(e.getPteid()));
        m.put("event_type", e.getEventType());
        m.put("level", e.getLevel());
        m.put("client_version", nz(e.getClientVer()));
        m.put("platform", nz(e.getPlatform()));
        m.put("detail", nz(e.getDetail()));
        m.put("evidence", parseEvidence(e.getEvidence()));
        m.put("occurred_at", e.getOccurredAt() == null ? "" : e.getOccurredAt().toString());
        m.put("received_at", e.getReceivedAt() == null ? "" : e.getReceivedAt().toString());
        m.put("seq", e.getSeq());
        m.put("hash", trim(e.getHash()));
        m.put("prev_hash", trim(e.getPrevHash()));
        return m;
    }

    /** evidence 是 JSON 就返回解析后的对象，否则原样返回字符串。 */
    private Object parseEvidence(String evidence) {
        if (evidence == null || evidence.isBlank()) return "";
        try {
            JsonNode node = objectMapper.readTree(evidence);
            return node == null ? evidence : node;
        } catch (Exception e) {
            return evidence;
        }
    }

    /** 行摘要：与 {@code SecurityEventService} 的写入侧同一算法，重算即可校验。 */
    private static String computeHash(long seq, String pteid, String eventType, String level,
                                      Instant occurredAt, String detail, String prevHash) {
        String canonical = seq + "|" + nz(pteid) + "|" + nz(eventType) + "|" + nz(level) + "|"
                + (occurredAt == null ? 0L : occurredAt.toEpochMilli()) + "|" + nz(detail) + "|" + nz(prevHash);
        return sha256Hex(canonical);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static Instant clampInstant(Instant at, Instant oldest, Instant newest, Instant def) {
        if (at == null) return def;
        if (at.isBefore(oldest)) return oldest;
        if (at.isAfter(newest)) return newest;
        return at;
    }

    private static String cap(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}