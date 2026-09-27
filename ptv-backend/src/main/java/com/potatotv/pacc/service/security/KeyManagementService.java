package com.potatotv.pacc.service.security;

import com.potatotv.pacc.domain.KeyAuditLog;
import com.potatotv.pacc.domain.ManagedKey;
import com.potatotv.pacc.repository.KeyAuditLogRepository;
import com.potatotv.pacc.repository.ManagedKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * v5.4 §5 托管密钥管理服务：派生、轮换、吊销、过期与审计链校验。
 *
 * <p>核心不变量：根密钥永不出服务器；子密钥由根密钥经 HKDF-SHA256 派生，<b>派生结果不落库</b>，
 * 库内只存盐与指纹（拖库不可还原）。因此本服务的所有写路径都依赖根密钥——
 * 根密钥缺失时<b>写操作 fail-closed</b>，但只读查询（列表/审计）仍可用，便于运维定位问题。</p>
 *
 * <p>根密钥配置优先级：{@code pacc.security.key-root}；为空时<b>兜底</b>复用
 * {@code pacc.security.jwt-secret}，两者皆空则抛 {@link IllegalStateException}。
 * <b>生产环境必须设置 {@code PACC_SECURITY_KEY_ROOT}</b>，绝不可让密钥派生复用 JWT 密钥
 * （否则 JWT 泄露即等于全部子密钥泄露，且两者生命周期耦合）。</p>
 *
 * <p>审计链为单实例串行分配 {@code seq}（见 {@link #nextChainSlice()}）；{@code t_key_audit_log}
 * 上 {@code seq} 唯一索引是并发/多实例下的数据库级安全网。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeyManagementService {

    /** 审计链创世行的前序哈希（空串，与库内 default 保持一致）。 */
    private static final String GENESIS_PREV = "";
    /** 派生盐长度（字节）。 */
    private static final int SALT_BYTES = 32;
    /** 派生密钥长度（字节）。 */
    private static final int KEY_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ManagedKeyRepository keyRepository;
    private final KeyAuditLogRepository auditRepository;

    /** 专属根密钥（生产必配，如 PACC_SECURITY_KEY_ROOT）。 */
    @Value("${pacc.security.key-root:}")
    private String keyRoot;

    /** JWT 密钥：仅作本地开发兜底，生产不得用于密钥派生。 */
    @Value("${pacc.security.jwt-secret:}")
    private String jwtSecret;

    /** 轮换周期（天），默认 90。 */
    @Value("${pacc.security.key-rotation-days:90}")
    private int rotationDays;

    // ------------------------------ 根密钥 ------------------------------

    /**
     * 专属根密钥是否已配置（即 {@code pacc.security.key-root} 非空）。
     * <p>为 false 时说明处于「兜底/降级」状态：派生仍可能通过 JWT 密钥兜底完成，但不合规；
     * 该标志随列表接口返回，供运维一眼识别。</p>
     */
    public boolean rootConfigured() {
        return keyRoot != null && !keyRoot.isBlank();
    }

    /** 解析实际使用的根密钥字节（专属优先，其次 JWT 兜底，都空则 fail-closed 抛错）。 */
    private byte[] effectiveRoot() {
        String root = keyRoot;
        if (root == null || root.isBlank()) {
            root = jwtSecret;
        }
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("根密钥未配置：请设置 PACC_SECURITY_KEY_ROOT（不得复用 JWT 密钥）");
        }
        return root.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------ 生命周期 ------------------------------

    /**
     * 新建并激活某用途的新版本密钥；同用途旧的 ACTIVE 会被降级为 ROTATED（保留以便历史密文仍可解）。
     *
     * @param purposeRaw 用途（须为 {@link ManagedKey.Purpose} 之一，未知即拒绝）
     * @param operator   操作者
     * @param note       备注
     * @throws IllegalArgumentException 用途未知（控制器映射 400）
     * @throws IllegalStateException    根密钥缺失（控制器映射 409，fail-closed）
     */
    @Transactional
    public ManagedKey create(String purposeRaw, String operator, String note) {
        ManagedKey.Purpose purpose = requirePurpose(purposeRaw);
        int version = nextVersion(purpose);

        byte[] saltBytes = new byte[SALT_BYTES];
        RANDOM.nextBytes(saltBytes);
        String salt = Hkdf.hex(saltBytes);

        String keyId = purpose.name().toLowerCase(Locale.ROOT) + "-v" + version;
        String fingerprint = fingerprintOf(purpose, saltBytes);

        Instant now = Instant.now();
        ManagedKey key = ManagedKey.builder()
                .id(UUID.randomUUID().toString())
                .keyId(keyId)
                .purpose(purpose.name())
                .algorithm("HKDF-SHA256")
                .state(ManagedKey.State.ACTIVE.name())
                .version(version)
                .derivedFrom("ROOT")
                .salt(salt)
                .fingerprint(fingerprint)
                .createdBy(blank(operator))
                .note(blank(note))
                .createdAt(now)
                .activatedAt(now)
                .expiresAt(now.plus(rotationDays, ChronoUnit.DAYS))
                .build();

        // 同用途的旧 ACTIVE 全部降级为 ROTATED，并各留一条 ROTATE 审计
        for (ManagedKey prev : keyRepository.findByPurposeAndState(purpose.name(), ManagedKey.State.ACTIVE.name())) {
            if (prev.getKeyId().equals(keyId)) continue;
            prev.setState(ManagedKey.State.ROTATED.name());
            prev.setRotatedAt(now);
            keyRepository.save(prev);
            appendAudit(prev.getKeyId(), KeyAuditLog.Action.ROTATE,
                    ManagedKey.State.ACTIVE.name(), ManagedKey.State.ROTATED.name(),
                    operator, "新版本 " + keyId + " 上线");
        }

        keyRepository.save(key);
        appendAudit(keyId, KeyAuditLog.Action.CREATE, "", ManagedKey.State.ACTIVE.name(), operator, note);
        log.info("托管密钥创建 key_id={} purpose={} version={} 操作者={}", keyId, purpose, version, blank(operator));
        return key;
    }

    /**
     * 轮换：仅允许对 ACTIVE 密钥发起；旧密钥降级 ROTATED，随后复用 {@link #create} 生成新版本，返回新密钥。
     *
     * @throws NoSuchElementException keyId 不存在（控制器映射 404）
     * @throws IllegalStateException  当前状态非 ACTIVE（控制器映射 409）
     */
    @Transactional
    public ManagedKey rotate(String keyId, String operator, String note) {
        ManagedKey current = keyRepository.findByKeyId(keyId)
                .orElseThrow(() -> new NoSuchElementException("密钥不存在：" + keyId));
        if (!ManagedKey.State.ACTIVE.name().equals(current.getState())) {
            throw new IllegalStateException("仅 ACTIVE 密钥可轮换，当前状态=" + current.getState());
        }
        // create 会把同用途当前 ACTIVE（即 current）降级为 ROTATED 并写 ROTATE 审计，随后落新版本
        ManagedKey rotated = create(current.getPurpose(), operator, note);
        log.info("托管密钥轮换 old={} new={} 操作者={}", keyId, rotated.getKeyId(), blank(operator));
        return rotated;
    }

    /**
     * 吊销：ACTIVE 或 ROTATED → REVOKED（用于泄露/异常场景，之后不可再用）。
     *
     * @throws NoSuchElementException keyId 不存在（控制器映射 404）
     * @throws IllegalStateException  已 REVOKED 或处于 EXPIRED（控制器映射 409）
     */
    @Transactional
    public ManagedKey revoke(String keyId, String operator, String reason) {
        ManagedKey key = keyRepository.findByKeyId(keyId)
                .orElseThrow(() -> new NoSuchElementException("密钥不存在：" + keyId));
        String from = key.getState();
        if (ManagedKey.State.REVOKED.name().equals(from)) {
            throw new IllegalStateException("密钥已吊销：" + keyId);
        }
        if (!ManagedKey.State.ACTIVE.name().equals(from) && !ManagedKey.State.ROTATED.name().equals(from)) {
            throw new IllegalStateException("仅 ACTIVE/ROTATED 密钥可吊销，当前状态=" + from);
        }
        key.setState(ManagedKey.State.REVOKED.name());
        key.setRevokedAt(Instant.now());
        key.setRevokeReason(blank(reason));
        keyRepository.save(key);
        appendAudit(keyId, KeyAuditLog.Action.REVOKE, from, ManagedKey.State.REVOKED.name(), operator, reason);
        log.warn("托管密钥吊销 key_id={} 原因={} 操作者={}", keyId, blank(reason), blank(operator));
        return key;
    }

    /**
     * 到期巡检：把已过 {@code expiresAt} 的 ACTIVE 密钥置为 EXPIRED，并各写一条 EXPIRE 审计。
     * <p>每日 03:25 执行（{@code @EnableScheduling} 由配置类统一开启）。</p>
     */
    @Scheduled(cron = "0 25 3 * * ?")
    @Transactional
    public void expireDueKeys() {
        Instant now = Instant.now();
        List<ManagedKey> due = keyRepository.findByStateAndExpiresAtBefore(ManagedKey.State.ACTIVE.name(), now);
        for (ManagedKey key : due) {
            key.setState(ManagedKey.State.EXPIRED.name());
            keyRepository.save(key);
            appendAudit(key.getKeyId(), KeyAuditLog.Action.EXPIRE,
                    ManagedKey.State.ACTIVE.name(), ManagedKey.State.EXPIRED.name(),
                    "system", "超过轮换周期自动过期");
            log.warn("托管密钥到期 key_id={} expires_at={}", key.getKeyId(), iso(key.getExpiresAt()));
        }
        if (!due.isEmpty()) {
            log.info("托管密钥到期巡检完成，共过期 {} 把", due.size());
        }
    }

    // ------------------------------ 只读视图 ------------------------------

    /**
     * 密钥总览：列表 + 根密钥配置状态 + 轮换周期 + 各状态计数 + 可选用途清单。
     * <p>{@code root_configured=false} 即降级告警，管理端应据此提示运维补齐 {@code PACC_SECURITY_KEY_ROOT}。</p>
     */
    @Transactional(readOnly = true)
    public Map<String, Object> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Long> byState = new LinkedHashMap<>();
        for (ManagedKey.State s : ManagedKey.State.values()) {
            byState.put(s.name(), 0L);
        }
        for (ManagedKey key : keyRepository.findAllByOrderByCreatedAtDesc()) {
            items.add(keyView(key));
            String state = key.getState() == null ? "" : key.getState();
            if (byState.containsKey(state)) {
                byState.merge(state, 1L, Long::sum);
            }
        }
        List<String> purposes = new ArrayList<>();
        for (ManagedKey.Purpose p : ManagedKey.Purpose.values()) {
            purposes.add(p.name());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("root_configured", rootConfigured());
        out.put("rotation_days", rotationDays);
        out.put("by_state", byState);
        out.put("purposes", purposes);
        return out;
    }

    /** 某把密钥的审计明细（最近 200 条，按 seq 倒序）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> audit(String keyId) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (KeyAuditLog row : auditRepository.findTop200ByKeyIdOrderBySeqDesc(keyId)) {
            items.add(auditView(row));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        return out;
    }

    /** 审计总览：链完整性校验（最多校验 500 条）+ 全局最近 200 条审计。 */
    @Transactional(readOnly = true)
    public Map<String, Object> auditOverview() {
        ChainVerification chain = verifyAuditChain(500);
        List<Map<String, Object>> items = new ArrayList<>();
        for (KeyAuditLog row : auditRepository.findTop200ByOrderBySeqDesc()) {
            items.add(auditView(row));
        }
        Map<String, Object> chainView = new LinkedHashMap<>();
        chainView.put("ok", chain.ok());
        chainView.put("checked", chain.checked());
        chainView.put("broken_at_seq", chain.brokenAtSeq());
        chainView.put("detail", chain.detail());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chain", chainView);
        out.put("items", items);
        return out;
    }

    /**
     * 校验审计链完整性：自创世行起按 seq 升序逐条复核「序号连续 + 前序哈希衔接 + 内容哈希重算一致」。
     * <p>任何一环不匹配即返回首个断裂位置。{@code limit} 为校验条数上限（自创世起），
     * 用于限制长链的单次开销；若链长于 limit，尾部尚未复核，需以更大 limit 再次调用。</p>
     */
    @Transactional(readOnly = true)
    public ChainVerification verifyAuditChain(int limit) {
        List<KeyAuditLog> rows = auditRepository.findAllByOrderBySeqAsc();
        long expectedSeq = 1L;
        String expectedPrev = GENESIS_PREV;
        long checked = 0L;
        for (KeyAuditLog row : rows) {
            if (limit > 0 && checked >= limit) {
                break;
            }
            checked++;
            if (row.getSeq() != expectedSeq) {
                return new ChainVerification(false, checked, row.getSeq(),
                        "序号不连续：期望 " + expectedSeq + " 实际 " + row.getSeq());
            }
            String prev = blank(row.getPrevHash());
            if (!expectedPrev.equals(prev)) {
                return new ChainVerification(false, checked, row.getSeq(),
                        "前序哈希断裂：期望 " + expectedPrev + " 实际 " + prev);
            }
            String recomputed = chainHash(row.getSeq(), blank(row.getKeyId()), blank(row.getAction()),
                    blank(row.getFromState()), blank(row.getToState()), blank(row.getOperator()),
                    row.getCreatedAt(), blank(row.getNote()), prev);
            if (!recomputed.equals(blank(row.getHash()))) {
                return new ChainVerification(false, checked, row.getSeq(), "内容哈希不匹配（记录可能被篡改）");
            }
            expectedPrev = blank(row.getHash());
            expectedSeq++;
        }
        return new ChainVerification(true, checked, 0L, checked == 0 ? "审计链为空" : "审计链完整");
    }

    // ------------------------------ 内部：审计链 ------------------------------

    /**
     * 分配下一段链：读取链尾得到下一 {@code seq} 与前序哈希。
     *
     * <p>并发说明：本方法 synchronized 只保证单实例内序号分配串行；多实例/极端并发下仍可能撞号，
     * 此时库内 {@code uk_key_audit_seq} 唯一索引会拒绝重复插入（数据库级安全网），
     * 调用方应视为写入失败并重试。本子系统按单实例部署假设运行。</p>
     */
    private synchronized ChainSlice nextChainSlice() {
        KeyAuditLog last = auditRepository.findFirstByOrderBySeqDesc().orElse(null);
        long seq = last == null ? 1L : last.getSeq() + 1L;
        String prevHash = last == null ? GENESIS_PREV : blank(last.getHash());
        return new ChainSlice(seq, prevHash);
    }

    /** 追加一条审计记录：分配序号、计算内容哈希、落库。 */
    private KeyAuditLog appendAudit(String keyId, KeyAuditLog.Action action, String fromState, String toState,
                                    String operator, String note) {
        ChainSlice slice = nextChainSlice();
        Instant now = Instant.now();
        String hash = chainHash(slice.seq(), blank(keyId), action.name(),
                blank(fromState), blank(toState), blank(operator), now, blank(note), slice.prevHash());
        KeyAuditLog row = KeyAuditLog.builder()
                .id(UUID.randomUUID().toString())
                .seq(slice.seq())
                .keyId(blank(keyId))
                .action(action.name())
                .fromState(blank(fromState))
                .toState(blank(toState))
                .operator(blank(operator))
                .note(blank(note))
                .createdAt(now)
                .prevHash(slice.prevHash())
                .hash(hash)
                .build();
        return auditRepository.save(row);
    }

    /** 内容哈希：与 {@link #verifyAuditChain} 重算口径必须逐字符一致。 */
    private static String chainHash(long seq, String keyId, String action, String fromState, String toState,
                                    String operator, Instant createdAt, String note, String prevHash) {
        long millis = createdAt == null ? 0L : createdAt.toEpochMilli();
        return Hkdf.sha256Hex(seq + "|" + keyId + "|" + action + "|" + fromState + "|" + toState + "|"
                + operator + "|" + millis + "|" + note + "|" + prevHash);
    }

    /** 一段链位（序号 + 前序哈希）。 */
    private record ChainSlice(long seq, String prevHash) {
    }

    /** 审计链校验结果。 */
    public record ChainVerification(boolean ok, long checked, long brokenAtSeq, String detail) {
    }

    // ------------------------------ 内部：派生与视图 ------------------------------

    /** 派生 32 字节子密钥并只返回其指纹；派生结果用后立即清零，绝不落库/留存。 */
    private String fingerprintOf(ManagedKey.Purpose purpose, byte[] saltBytes) {
        byte[] derived = Hkdf.derive(effectiveRoot(), saltBytes, purpose.name(), KEY_BYTES);
        try {
            return Hkdf.sha256Hex(Hkdf.hex(derived));
        } finally {
            Arrays.fill(derived, (byte) 0);
        }
    }

    private int nextVersion(ManagedKey.Purpose purpose) {
        Integer max = keyRepository.maxVersion(purpose.name());
        return (max == null ? 0 : max) + 1;
    }

    /** 严格解析用途（写入口）：未知/空值直接拒绝，控制器据此返回 400。 */
    private static ManagedKey.Purpose requirePurpose(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("密钥用途不能为空");
        }
        try {
            return ManagedKey.Purpose.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知密钥用途：" + raw);
        }
    }

    private static Map<String, Object> keyView(ManagedKey key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", blank(key.getId()));
        m.put("key_id", blank(key.getKeyId()));
        m.put("purpose", blank(key.getPurpose()));
        m.put("algorithm", blank(key.getAlgorithm()));
        m.put("state", blank(key.getState()));
        m.put("version", key.getVersion());
        m.put("derived_from", blank(key.getDerivedFrom()));
        m.put("fingerprint", blank(key.getFingerprint()));
        m.put("created_by", blank(key.getCreatedBy()));
        m.put("note", blank(key.getNote()));
        m.put("created_at", iso(key.getCreatedAt()));
        m.put("activated_at", iso(key.getActivatedAt()));
        m.put("rotated_at", iso(key.getRotatedAt()));
        m.put("expires_at", iso(key.getExpiresAt()));
        m.put("revoked_at", iso(key.getRevokedAt()));
        m.put("revoke_reason", blank(key.getRevokeReason()));
        return m;
    }

    private static Map<String, Object> auditView(KeyAuditLog row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", blank(row.getId()));
        m.put("key_id", blank(row.getKeyId()));
        m.put("action", blank(row.getAction()));
        m.put("from_state", blank(row.getFromState()));
        m.put("to_state", blank(row.getToState()));
        m.put("operator", blank(row.getOperator()));
        m.put("note", blank(row.getNote()));
        m.put("created_at", iso(row.getCreatedAt()));
        m.put("prev_hash", blank(row.getPrevHash()));
        m.put("hash", blank(row.getHash()));
        return m;
    }

    private static String iso(Instant at) {
        return at == null ? "" : at.toString();
    }

    private static String blank(String s) {
        return s == null ? "" : s;
    }
}