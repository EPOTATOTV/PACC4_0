package com.potatotv.pacc.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 迁移脚本的离线校验（只覆盖本仓库新加的脚本，见下面的说明）。
 *
 * <p><b>为什么不用 Flyway 跑整条链。</b>试过了，跑不通，而且原因不在本文件：{@code V2__appeal_upgrade.sql}
 * 使用 MySQL 的逗号多列写法 {@code ALTER TABLE t ADD COLUMN a ..., ADD COLUMN b ...}，H2 的 MySQL
 * 兼容模式不接受这种形式。已合入的迁移脚本按仓库约定不可修改，所以「整条链在本地数据库上跑一遍」
 * 这条路要靠 Testcontainers 起真 MySQL 才能走通，不在本测试的范围内。</p>
 *
 * <p><b>由此带来的既有缺口</b>（不是本测试能补的）：整条迁移链目前<strong>只在生产首次执行过</strong>。
 * {@code local} profile 关掉了 Flyway 改用 {@code ddl-auto: update}，生产是 {@code ddl-auto: validate}，
 * 中间没有任何环节会执行这些脚本。本测试至少保证「本次新增的脚本本身能被执行、且产出预期的列与默认值」，
 * 免得把语法错误留到部署当天。</p>
 */
class FlywayMigrationTest {

    private static final String MYSQL_COMPAT_URL_TEMPLATE =
            "jdbc:h2:mem:flyway-%s;MODE=MySQL;DATABASE_TO_UPPER=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    void 灰度迁移脚本可在MySQL兼容模式下执行并产出预期列() throws Exception {
        String url = String.format(MYSQL_COMPAT_URL_TEMPLATE, UUID.randomUUID());
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            // 造一张 V37 之后形态的最小 t_release（本测试只关心 V38 这一段 ALTER）
            execute(connection, "CREATE TABLE t_release (id VARCHAR(36) PRIMARY KEY, version VARCHAR(48))");
            execute(connection, "INSERT INTO t_release (id, version) VALUES ('r1', '5.4.0')");

            for (String statement : statementsOf("db/migration/V38__pcu_rollout_percent.sql")) {
                execute(connection, statement);
            }

            assertColumn(connection, "T_RELEASE", "ROLLOUT_PERCENT", "100");
            assertNotNullForExistingRows(connection);
        }
    }

    /** 既有行必须自动是 100（全量），否则迁移本身等于给所有历史版本做了灰度限流。 */
    private static void assertNotNullForExistingRows(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT rollout_percent FROM t_release WHERE id = 'r1'")) {
            assertTrue(rs.next(), "既有行应当仍然可读");
            assertEquals(100, rs.getInt(1), "既有版本的灰度比例必须默认全量");
        }
    }

    @Test
    void 更新上报版本索引脚本可在MySQL兼容模式下执行() throws Exception {
        String url = String.format(MYSQL_COMPAT_URL_TEMPLATE, UUID.randomUUID());
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            // V37 里 t_update_report 的最小形态；本测试只关心 V39 的建索引语句
            execute(connection, "CREATE TABLE t_update_report ("
                    + "id VARCHAR(36) PRIMARY KEY, to_version VARCHAR(48) NULL, created_at DATETIME(6) NOT NULL)");

            for (String statement : statementsOf("db/migration/V39__update_report_version_index.sql")) {
                execute(connection, statement);
            }

            assertIndexExists(connection, "T_UPDATE_REPORT", "IDX_UPDATE_REPORT_TOVERSION_CREATED");
        }
    }

    @Test
    void 规则版本库迁移脚本可在MySQL兼容模式下执行并产出两张表() throws Exception {
        String url = String.format(MYSQL_COMPAT_URL_TEMPLATE, UUID.randomUUID());
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            for (String statement : statementsOf("db/migration/V40__prl_rule_versions.sql")) {
                execute(connection, statement);
            }

            // 版本表：主键是（规则名, 版本号），同一规则的多版本必须能共存
            assertColumn(connection, "PRL_RULE_VERSIONS", "BYTECODE", null);
            assertIndexExists(connection, "PRL_RULE_VERSIONS", "IDX_PRL_RULE_VERSIONS_STATUS");
            execute(connection, "INSERT INTO prl_rule_versions (rule_name, version, source, bytecode,"
                    + " checksum, author, status, created_at, approved_by, rollback_to) VALUES"
                    + " ('account_history', '1.0.0', 'rule', X'00', 'sum1', 'tester', 'ACTIVE',"
                    + " CURRENT_TIMESTAMP, 'alice', NULL)");
            execute(connection, "INSERT INTO prl_rule_versions (rule_name, version, source, bytecode,"
                    + " checksum, author, status, created_at, approved_by, rollback_to) VALUES"
                    + " ('account_history', '2.0.0', 'rule', X'01', 'sum2', 'tester', 'DRAFT',"
                    + " CURRENT_TIMESTAMP, NULL, NULL)");
            assertEquals(2, countRows(connection, "prl_rule_versions"));

            // 灰度比例表：一条规则一行，主键即规则名
            assertColumn(connection, "PRL_RULE_ROLLOUT", "PERCENT", "100");
        }
    }

    private static int countRows(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rs.next(), "COUNT(*) 应当有结果");
            return rs.getInt(1);
        }
    }

    private static void assertIndexExists(Connection connection, String table, String index) throws Exception {
        Set<String> indexes = new LinkedHashSet<>();
        try (ResultSet rs = connection.getMetaData().getIndexInfo(null, null, table, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name != null) {
                    indexes.add(name.toUpperCase(Locale.ROOT));
                }
            }
        }
        assertTrue(indexes.contains(index), table + " 缺少索引 " + index + "，实际索引：" + indexes);
    }

    private static void assertColumn(Connection connection, String table, String column,
                                     String expectedDefault) throws Exception {
        List<String> columns = new ArrayList<>();
        String found = null;
        try (ResultSet rs = connection.getMetaData().getColumns(null, null, table, null)) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT);
                columns.add(name);
                if (name.equals(column)) {
                    found = rs.getString("COLUMN_DEF");
                }
            }
        }
        assertTrue(columns.contains(column), table + " 缺少列 " + column + "，实际列：" + columns);
        assertEquals(expectedDefault, found, column + " 的默认值不符合预期");
    }

    /** 读实际入库的脚本文件并按分号切开；注释行整行丢弃。 */
    private static List<String> statementsOf(String resource) throws IOException {
        StringBuilder text = new StringBuilder();
        try (InputStream in = FlywayMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertTrue(in != null, "找不到迁移脚本：" + resource);
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.trim().startsWith("--")) {
                    text.append(line).append('\n');
                }
            }
        }
        List<String> statements = new ArrayList<>();
        for (String part : text.toString().split(";")) {
            if (!part.isBlank()) {
                statements.add(part.trim());
            }
        }
        return statements;
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}