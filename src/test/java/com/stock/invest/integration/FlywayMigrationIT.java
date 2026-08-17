package com.stock.invest.integration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.stock.invest.verifier.SchemaVerifier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2 P1-4 / P2-4 / P3-7 / P3-12：Flyway 迁移集成测试（真实 MySQL 8.0.46，专用 schema）。
 * <p>环境约束（round2-test-plan §3.1）：迁移脚本含 MySQL 专有语法（information_schema 探测 +
 * PREPARE/EXECUTE 动态 SQL），必须跑 MySQL、禁止 H2；每用例自建自删专用 schema，
 * 绝不触碰 stock_invest 主库。</p>
 * <p>用例覆盖：fresh 全版本迁移（版本唯一/约束/索引/价格列）、legacy 缺列库对齐、
 * legacy 含重复行时守卫不建约束 + SchemaVerifier 报错、清理后手工补约束通过 +
 * 重复插入被 DB 拒绝、旧 V2 已应用库 validate 通过（P1-4 回归）、二次迁移幂等。</p>
 */
@Tag("integration")
class FlywayMigrationIT {

    private static final String BASE_URL = "jdbc:mysql://127.0.0.1:3307/";
    private static final String USER = "root";
    private static final String PASSWORD = System.getenv().getOrDefault("MYSQL_PASSWORD", "");
    private static final String URL_OPTS = "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai";

    private static final String UNIQUE_CONSTRAINT = "uk_screening_match_trade_symbol_window_algorithm";

    private static final AtomicInteger schemaSeq = new AtomicInteger();

    private String schemaName;

    @BeforeEach
    void createSchema() throws SQLException {
        schemaName = "stock_invest_round2_it_" + schemaSeq.incrementAndGet();
        try (Connection c = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + schemaName
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
    }

    @AfterEach
    void dropSchema() {
        try (Connection c = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + schemaName);
        } catch (SQLException ignored) {
            // cleanup best-effort
        }
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(BASE_URL + schemaName, USER, PASSWORD)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(BASE_URL + schemaName + URL_OPTS, USER, PASSWORD);
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = conn(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(BASE_URL + schemaName + URL_OPTS);
        ds.setUsername(USER);
        ds.setPassword(PASSWORD);
        return new JdbcTemplate(ds);
    }

    // ---- 辅助断言 ----

    private boolean constraintExists(String constraintName) throws SQLException {
        try (Connection c = conn();
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                             + "WHERE TABLE_SCHEMA = '" + schemaName + "' AND TABLE_NAME = 'screening_match' "
                             + "AND CONSTRAINT_NAME = '" + constraintName + "'")) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }

    private boolean indexExists(String table, String index) throws SQLException {
        try (Connection c = conn();
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT COUNT(*) FROM information_schema.STATISTICS "
                             + "WHERE TABLE_SCHEMA = '" + schemaName + "' AND TABLE_NAME = '" + table
                             + "' AND INDEX_NAME = '" + index + "'")) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (Connection c = conn();
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT COUNT(*) FROM information_schema.COLUMNS "
                             + "WHERE TABLE_SCHEMA = '" + schemaName + "' AND TABLE_NAME = '" + table
                             + "' AND COLUMN_NAME = '" + column + "'")) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }

    private boolean tableExists(String table) throws SQLException {
        try (Connection c = conn();
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT COUNT(*) FROM information_schema.TABLES "
                             + "WHERE TABLE_SCHEMA = '" + schemaName + "' AND TABLE_NAME = '" + table + "'")) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }

    private List<String> appliedDescriptions() throws SQLException {
        try (Connection c = conn();
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT version, description FROM " + schemaName + ".flyway_schema_history ORDER BY installed_rank")) {
            java.util.ArrayList<String> rows = new java.util.ArrayList<>();
            while (rs.next()) {
                rows.add(rs.getString(1) + ":" + rs.getString(2));
            }
            return rows;
        }
    }

    // ---- 用例 ----

    @Test
    @DisplayName("R2 P1-4: fresh 空库全版本迁移 → V1~V4 全部 success、版本唯一、约束/索引/价格列就位")
    void freshMigrate_allVersionsApplied() throws Exception {
        Flyway flyway = flyway();
        org.flywaydb.core.api.output.MigrateResult result = flyway.migrate();
        assertEquals(5, result.migrationsExecuted, "V1..V5 must all execute on a fresh schema");

        // flyway_schema_history 版本唯一（无复用）：1:baseline? no — baseline-version=0 不入库；1..5
        List<String> applied = appliedDescriptions();
        assertEquals(5, applied.size(), "exactly 5 migration rows: " + applied);
        Set<String> versions = new HashSet<>();
        for (String row : applied) {
            versions.add(row.split(":")[0]);
        }
        assertEquals(Set.of("1", "2", "3", "4", "5"), versions, "versions must be unique, no reuse: " + applied);
        assertEquals("create symbol blacklist", applied.get(1).split(":", 2)[1],
                "V2 must be the restored old create_symbol_blacklist script");

        // 6 张业务表就位
        for (String t : List.of("data_fill_task", "screening_match", "stock_daily_bar",
                "stock_data_source_priority", "symbol_blacklist", "trading_calendar")) {
            assertTrue(tableExists(t), "table " + t + " must exist after migrate");
        }

        // screening_match 唯一约束 + 3 个查询索引（P3-7/P3-12）
        assertTrue(constraintExists(UNIQUE_CONSTRAINT), "unique constraint must exist on fresh schema");
        assertTrue(indexExists("screening_match", "idx_screening_match_window_days"), "P3-7 index missing");
        assertTrue(indexExists("screening_match", "idx_screening_match_batch_id_window_days"), "P3-7 index missing");
        assertTrue(indexExists("data_fill_task", "idx_dft_status"), "P3-12 idx_dft_status missing");

        // data_fill_task 全列（P2-4）就位
        for (String col : List.of("version", "status", "retry_count", "retry_date", "day_count", "last_error")) {
            assertTrue(columnExists("data_fill_task", col), "data_fill_task." + col + " missing");
        }

        // 价格列 DECIMAL(12,4)（V3 回归护栏）
        try (Connection c = conn();
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                             + "WHERE TABLE_SCHEMA = '" + schemaName + "' AND TABLE_NAME = 'stock_daily_bar' "
                             + "AND COLUMN_NAME = 'close_price'")) {
            assertTrue(rs.next(), "close_price column must exist");
            assertEquals("decimal(12,4)", rs.getString(1), "close_price must be DECIMAL(12,4)");
        }

        // 幂等：二次 migrate 为 no-op，validate 通过
        org.flywaydb.core.api.output.MigrateResult again = flyway.migrate();
        assertEquals(0, again.migrationsExecuted, "second migrate must be a no-op");
        flyway.validate(); // 不抛异常即通过
    }

    @Test
    @DisplayName("R2 P2-4: legacy 缺列库 → 迁移后 data_fill_task/screening_match 列齐全")
    void legacyMissingColumns_columnsAligned() throws Exception {
        // 仓库外历史 DDL：data_fill_task 缺 version/retry_date/day_count/last_error，screening_match 缺 algorithm
        exec("CREATE TABLE data_fill_task ("
                + "id BIGINT NOT NULL AUTO_INCREMENT, symbol VARCHAR(32) NOT NULL, trade_date DATE NOT NULL, "
                + "status VARCHAR(32) NOT NULL DEFAULT 'pending', retry_count INT NOT NULL DEFAULT 0, "
                + "created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, PRIMARY KEY (id)) ENGINE=InnoDB");
        exec("CREATE TABLE screening_match ("
                + "id BIGINT NOT NULL AUTO_INCREMENT, batch_id VARCHAR(36) NOT NULL, created_at DATETIME(6) NOT NULL, "
                + "data_source VARCHAR(32) NOT NULL, last_close DECIMAL(12,4) NULL, price DECIMAL(12,4) NULL, "
                + "rise BIT(1) NOT NULL, symbol VARCHAR(32) NOT NULL, trade_date DATE NOT NULL, "
                + "window_days INT NOT NULL DEFAULT 7, PRIMARY KEY (id)) ENGINE=InnoDB");

        flyway().migrate();

        for (String col : List.of("version", "retry_date", "day_count", "last_error")) {
            assertTrue(columnExists("data_fill_task", col), "data_fill_task." + col + " must be aligned by V4");
        }
        assertTrue(columnExists("screening_match", "algorithm"), "screening_match.algorithm must be aligned by V4");
        assertTrue(constraintExists(UNIQUE_CONSTRAINT),
                "no-duplicate legacy schema must gain the unique constraint (guarded ADD)");
        flyway().validate();
    }

    @Test
    @DisplayName("R2 P1-5: legacy 含重复 + 无唯一约束 → 守卫不 ADD CONSTRAINT，SchemaVerifier 报 error（含组数）")
    void legacyWithDuplicates_constraintSkippedAndVerifierErrors() throws Exception {
        exec("CREATE TABLE screening_match ("
                + "id BIGINT NOT NULL AUTO_INCREMENT, batch_id VARCHAR(36) NOT NULL, created_at DATETIME(6) NOT NULL, "
                + "data_source VARCHAR(32) NOT NULL, last_close DECIMAL(12,4) NULL, price DECIMAL(12,4) NULL, "
                + "rise BIT(1) NOT NULL, symbol VARCHAR(32) NOT NULL, trade_date DATE NOT NULL, "
                + "window_days INT NOT NULL DEFAULT 7, algorithm VARCHAR(32) NOT NULL DEFAULT 'increasing_volume', "
                + "PRIMARY KEY (id)) ENGINE=InnoDB");
        // 2 组重复：(d1,AAPL,2,iv) ×2、(d1,MSFT,3,vs) ×2
        exec("INSERT INTO screening_match (batch_id, created_at, data_source, last_close, price, rise, symbol, trade_date, window_days, algorithm) VALUES "
                + "('b1', '2026-08-07 00:00:00', 'tiger', 1.0, 1.0, b'1', 'AAPL', '2026-05-18', 2, 'increasing_volume'), "
                + "('b1', '2026-08-07 00:00:00', 'tiger', 1.0, 1.0, b'1', 'AAPL', '2026-05-18', 2, 'increasing_volume'), "
                + "('b1', '2026-08-07 00:00:00', 'tiger', 1.0, 1.0, b'1', 'MSFT', '2026-05-18', 3, 'volume_spike'), "
                + "('b1', '2026-08-07 00:00:00', 'tiger', 1.0, 1.0, b'1', 'MSFT', '2026-05-18', 3, 'volume_spike')");

        flyway().migrate();

        assertFalse(constraintExists(UNIQUE_CONSTRAINT),
                "guard must skip ADD CONSTRAINT while duplicates exist");

        // SchemaVerifier 启动校验 → error 含重复组数与清理脚本路径
        ListAppender<ILoggingEvent> appender = attachSchemaVerifierAppender();
        try {
            new SchemaVerifier(jdbcTemplate()).run(null);
            List<String> errors = appender.list.stream()
                    .filter(e -> e.getLevel().toString().equals("ERROR"))
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertFalse(errors.isEmpty(), "SchemaVerifier must log error for missing constraint with duplicates");
            assertTrue(errors.stream().anyMatch(m -> m.contains("2 组重复数据")),
                    "error must contain duplicate group count: " + errors);
            assertTrue(errors.stream().anyMatch(m -> m.contains("cleanup_screening_match_duplicates.sql")),
                    "error must point to cleanup script: " + errors);
        } finally {
            detachSchemaVerifierAppender(appender);
        }
    }

    @Test
    @DisplayName("R2 P1-5: 清理重复 + 手工补约束后 → SchemaVerifier 通过，重复插入被 DB 拒绝")
    void legacyWithDuplicates_cleanupThenManualConstraint_allowsVerifierPass() throws Exception {
        exec("CREATE TABLE screening_match ("
                + "id BIGINT NOT NULL AUTO_INCREMENT, batch_id VARCHAR(36) NOT NULL, created_at DATETIME(6) NOT NULL, "
                + "data_source VARCHAR(32) NOT NULL, last_close DECIMAL(12,4) NULL, price DECIMAL(12,4) NULL, "
                + "rise BIT(1) NOT NULL, symbol VARCHAR(32) NOT NULL, trade_date DATE NOT NULL, "
                + "window_days INT NOT NULL DEFAULT 7, algorithm VARCHAR(32) NOT NULL DEFAULT 'increasing_volume', "
                + "PRIMARY KEY (id)) ENGINE=InnoDB");
        exec("INSERT INTO screening_match (batch_id, created_at, data_source, last_close, price, rise, symbol, trade_date, window_days, algorithm) VALUES "
                + "('b1', '2026-08-07 00:00:00', 'tiger', 1.0, 1.0, b'1', 'AAPL', '2026-05-18', 2, 'increasing_volume'), "
                + "('b1', '2026-08-07 00:00:00', 'tiger', 1.0, 1.0, b'1', 'AAPL', '2026-05-18', 2, 'increasing_volume')");

        flyway().migrate();
        assertFalse(constraintExists(UNIQUE_CONSTRAINT), "constraint must be skipped while duplicates exist");

        // 模拟 runbook：清理重复（保留 MIN(id)）→ 手工 ALTER 补约束
        exec("DELETE FROM screening_match WHERE id NOT IN ("
                + "SELECT * FROM (SELECT MIN(id) FROM screening_match "
                + "GROUP BY trade_date, symbol, window_days, algorithm) t)");
        exec("ALTER TABLE screening_match ADD CONSTRAINT " + UNIQUE_CONSTRAINT
                + " UNIQUE (trade_date, symbol, window_days, algorithm)");
        assertTrue(constraintExists(UNIQUE_CONSTRAINT), "manual ALTER must add the constraint");

        // SchemaVerifier 通过（无 error，info 含"通过"）
        ListAppender<ILoggingEvent> appender = attachSchemaVerifierAppender();
        try {
            new SchemaVerifier(jdbcTemplate()).run(null);
            assertTrue(appender.list.stream().noneMatch(e -> e.getLevel().toString().equals("ERROR")),
                    "no error expected after cleanup + manual constraint");
            assertTrue(appender.list.stream()
                            .filter(e -> e.getLevel().toString().equals("INFO"))
                            .map(ILoggingEvent::getFormattedMessage)
                            .anyMatch(m -> m.contains("通过")),
                    "pass info log expected");
        } finally {
            detachSchemaVerifierAppender(appender);
        }

        // 约束生效：重复插入被 DB 拒绝
        assertThrows(SQLException.class, () -> exec(
                "INSERT INTO screening_match (batch_id, created_at, data_source, last_close, price, rise, symbol, trade_date, window_days, algorithm) VALUES "
                        + "('b1', '2026-08-07 00:00:00', 'tiger', 1.0, 1.0, b'1', 'AAPL', '2026-05-18', 2, 'increasing_volume')"),
                "duplicate insert must be rejected by the unique constraint");
    }

    @Test
    @DisplayName("R2 P1-4: 旧 V2 已应用库 → validate 通过、V3/V4 正常应用")
    void legacyOldV2Applied_validatePasses() throws Exception {
        // 模拟"旧 V2 已应用"：仅迁移到 V2（恢复后的 V2 与历史旧文件同内容 → checksum 一致）
        Flyway partial = Flyway.configure()
                .dataSource(BASE_URL + schemaName, USER, PASSWORD)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .target("2")
                .load();
        assertEquals(2, partial.migrate().migrationsExecuted, "V1+V2 applied (simulating old V2 in history)");

        // 继续完整迁移：validate 必须通过（旧 V2 checksum/描述与历史一致），V3/V4 应用
        Flyway full = flyway();
        org.flywaydb.core.api.output.MigrateResult result = full.migrate();
        assertEquals(3, result.migrationsExecuted, "V3+V4+V5 must apply on top of old-V2 schema");

        List<String> applied = appliedDescriptions();
        assertEquals(5, applied.size(), "full history after old-V2 upgrade: " + applied);
        assertEquals("create symbol blacklist", applied.get(1).split(":", 2)[1],
                "V2 description must match the old create_symbol_blacklist script");

        full.validate(); // 不抛异常即通过
        assertNotNull(full.info(), "migration info must be readable");
    }

    // ---- SchemaVerifier 日志捕获 ----

    private ListAppender<ILoggingEvent> attachSchemaVerifierAppender() {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(SchemaVerifier.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachSchemaVerifierAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(SchemaVerifier.class);
        logger.detachAppender(appender);
    }
}
