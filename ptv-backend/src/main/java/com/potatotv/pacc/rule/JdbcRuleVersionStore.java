package com.potatotv.pacc.rule;

import com.potatotv.prl.engine.RuleStatus;
import com.potatotv.prl.engine.RuleVersion;
import com.potatotv.prl.engine.RuleVersionStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PRL 规则版本库的 JDBC 实现（设计文档 §2.13.1 的 {@code prl_rule_versions}）。
 *
 * <p>表结构由 {@code V40__prl_rule_versions.sql} 建，这里只负责读写。选 JDBC 而不是 JPA：
 * {@code bytecode} 是一整块二进制，发布时要原样读回来装进引擎，走 Hibernate 的 LOB 映射在
 * H2/MySQL 之间行为不一致，而这条路径上的任何一次「读回来差一个字节」都会让 checksum 比对
 * 失效、灰度对比结果失真。</p>
 *
 * <p>写入用「先 UPDATE 再 INSERT」而不是方言化的 upsert：{@code save} 在发布流程里被反复调用
 * （状态迁移就是把同一行改来改去），语义上就是覆盖写，用标准 SQL 表达反而更贴。</p>
 */
@Repository
public class JdbcRuleVersionStore implements RuleVersionStore {

    /** 灰度比例缺省值：没登记过就是全量，避免新规则因为少一行记录而不下发。 */
    static final int DEFAULT_ROLLOUT_PERCENT = 100;

    private static final String COLUMNS =
            "rule_name, version, source, bytecode, checksum, author, status, created_at, "
                    + "approved_by, rollback_to";

    private static final RowMapper<RuleVersion> MAPPER = JdbcRuleVersionStore::mapRow;

    private final JdbcTemplate jdbc;

    public JdbcRuleVersionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(RuleVersion version) {
        int updated = jdbc.update("""
                UPDATE prl_rule_versions
                   SET source = ?, bytecode = ?, checksum = ?, author = ?, status = ?,
                       created_at = ?, approved_by = ?, rollback_to = ?
                 WHERE rule_name = ? AND version = ?""",
                version.source(), version.bytecode(), version.checksum(), version.author(),
                version.status().name(), toTimestamp(version.createdAtMs()),
                version.approvedBy(), version.rollbackTo(), version.ruleName(), version.version());
        if (updated > 0) {
            return;
        }
        jdbc.update("INSERT INTO prl_rule_versions (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?)",
                version.ruleName(), version.version(), version.source(), version.bytecode(),
                version.checksum(), version.author(), version.status().name(),
                toTimestamp(version.createdAtMs()), version.approvedBy(), version.rollbackTo());
    }

    @Override
    public Optional<RuleVersion> find(String ruleName, String version) {
        return jdbc.query("SELECT " + COLUMNS + " FROM prl_rule_versions WHERE rule_name = ? AND version = ?",
                MAPPER, ruleName, version).stream().findFirst();
    }

    @Override
    public Optional<RuleVersion> active(String ruleName) {
        return jdbc.query("SELECT " + COLUMNS + " FROM prl_rule_versions "
                        + "WHERE rule_name = ? AND status = ? ORDER BY created_at DESC, version DESC",
                MAPPER, ruleName, RuleStatus.ACTIVE.name()).stream().findFirst();
    }

    @Override
    public List<RuleVersion> history(String ruleName) {
        return jdbc.query("SELECT " + COLUMNS + " FROM prl_rule_versions "
                + "WHERE rule_name = ? ORDER BY created_at DESC, version DESC", MAPPER, ruleName);
    }

    @Override
    public List<RuleVersion> all() {
        return jdbc.query("SELECT " + COLUMNS + " FROM prl_rule_versions "
                + "ORDER BY rule_name ASC, created_at DESC, version DESC", MAPPER);
    }

    // ------------------------------------------------------------------ 灰度比例

    /** 登记/调整一条规则的灰度比例；未登记过的规则按 {@link #DEFAULT_ROLLOUT_PERCENT} 处理。 */
    void setRolloutPercent(String ruleName, int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        int updated = jdbc.update("UPDATE prl_rule_rollout SET percent = ?, updated_at = ? WHERE rule_name = ?",
                clamped, toTimestamp(System.currentTimeMillis()), ruleName);
        if (updated == 0) {
            jdbc.update("INSERT INTO prl_rule_rollout (rule_name, percent, updated_at) VALUES (?,?,?)",
                    ruleName, clamped, toTimestamp(System.currentTimeMillis()));
        }
    }

    /** 全部规则的灰度比例；没登记的规则不在返回的 map 里。 */
    Map<String, Integer> rolloutPercents() {
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbc.query("SELECT rule_name, percent FROM prl_rule_rollout",
                rs -> { out.put(rs.getString("rule_name"), rs.getInt("percent")); });
        return out;
    }

    private static RuleVersion mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return new RuleVersion(
                rs.getString("rule_name"),
                rs.getString("version"),
                rs.getString("source"),
                rs.getBytes("bytecode"),
                rs.getString("checksum"),
                rs.getString("author"),
                RuleStatus.valueOf(rs.getString("status")),
                created == null ? 0L : created.toInstant().toEpochMilli(),
                rs.getString("approved_by"),
                rs.getString("rollback_to"));
    }

    private static Timestamp toTimestamp(long epochMillis) {
        return Timestamp.from(Instant.ofEpochMilli(epochMillis));
    }
}