package com.stock.invest.verifier;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R2 P1-5 / P2-4 / P3-7 / P3-12：SchemaVerifier 分支逻辑单测。
 * <p>mock {@link JdbcTemplate} 返回值驱动 verification 分支，经 Logback ListAppender
 * 捕获 log.error / log.info 断言输出内容；不依赖真实 DB。</p>
 */
class SchemaVerifierTest {

    private static final String UNIQUE_CONSTRAINT = "uk_screening_match_trade_symbol_window_algorithm";

    private ListAppender<ILoggingEvent> appender;

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(SchemaVerifier.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
        this.appender = listAppender;
        return listAppender;
    }

    @AfterEach
    void detachAppender() {
        if (appender != null) {
            Logger logger = (Logger) LoggerFactory.getLogger(SchemaVerifier.class);
            logger.detachAppender(appender);
            appender = null;
        }
    }

    /**
     * 按 SQL 内容驱动返回值（Mockito 对 varargs 的匹配不一致：any()/anyString() 仅命中 1 个
     * vararg、0 个 vararg 的通路调用无法命中；any(Object[].class) 可命中 0/1/2 全部形态）。
     * constraintCount —— TABLE_CONSTRAINTS 查询；dupGroups —— 重复组查询；
     * missingColumn —— 指定列缺失（0 表示不存在）；missingIndex —— 指定索引缺失。
     */
    private JdbcTemplate jdbc(long constraintCount, Long dupGroups,
                              String missingColumn, String missingIndex) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            Object[] raw = inv.getArguments();
            Object[] varargs;
            if (raw.length > 2 && raw[2] instanceof Object[] arr) {
                varargs = arr;
            } else {
                varargs = new Object[raw.length - 2];
                System.arraycopy(raw, 2, varargs, 0, varargs.length);
            }
            if (sql.contains("TABLE_CONSTRAINTS")) {
                return constraintCount;
            }
            if (sql.contains("GROUP BY trade_date")) {
                return dupGroups;
            }
            if (sql.contains("STATISTICS")) {
                String index = varargs.length > 1 ? (String) varargs[1] : null;
                return missingIndex != null && missingIndex.equals(index) ? 0L : 1L;
            }
            // COLUMNS 查询
            String column = varargs.length > 0 ? (String) varargs[0] : null;
            return missingColumn != null && missingColumn.equals(column) ? 0L : 1L;
        }).when(jdbc).queryForObject(anyString(), eq(Long.class), any(Object[].class));
        return jdbc;
    }

    private List<String> errorMessages() {
        return appender.list.stream()
                .filter(e -> e.getLevel().toString().equals("ERROR"))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private List<String> infoMessages() {
        return appender.list.stream()
                .filter(e -> e.getLevel().toString().equals("INFO"))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    @DisplayName("R2 P1-5: 唯一约束存在 → 通过分支（无 error 日志）")
    void constraintExists_passesSilently() {
        attachAppender();
        new SchemaVerifier(jdbc(1L, 0L, null, null)).run(null);

        assertTrue(errorMessages().isEmpty(), "no error expected when constraint exists");
        assertTrue(infoMessages().stream().anyMatch(m -> m.contains("通过")),
                "pass info log expected: " + infoMessages());
    }

    @Test
    @DisplayName("R2 P1-5: 约束缺失 + 重复组=0 → error 含手工 ALTER 指引")
    void constraintMissing_noDuplicates_errorWithAlterHint() {
        attachAppender();
        new SchemaVerifier(jdbc(0L, 0L, null, null)).run(null);

        List<String> errors = errorMessages();
        assertFalse(errors.isEmpty(), "error expected when constraint missing");
        assertTrue(errors.stream().anyMatch(m -> m.contains("ALTER TABLE screening_match ADD CONSTRAINT")),
                "manual ALTER hint expected: " + errors);
        assertTrue(errors.stream().anyMatch(m -> m.contains(UNIQUE_CONSTRAINT)),
                "constraint name in hint expected: " + errors);
    }

    @Test
    @DisplayName("R2 P1-5: 约束缺失 + 重复组=2 → error 含重复组数与清理脚本路径")
    void constraintMissing_withDuplicates_errorWithCountAndScript() {
        attachAppender();
        new SchemaVerifier(jdbc(0L, 2L, null, null)).run(null);

        List<String> errors = errorMessages();
        assertFalse(errors.isEmpty(), "error expected when constraint missing with duplicates");
        assertTrue(errors.stream().anyMatch(m -> m.contains("2 组重复数据")),
                "dup group count expected: " + errors);
        assertTrue(errors.stream().anyMatch(m -> m.contains("cleanup_screening_match_duplicates.sql")),
                "cleanup script path expected: " + errors);
    }

    @Test
    @DisplayName("R2 P2-4: data_fill_task 缺列 → error 列出缺失列名")
    void missingColumn_errorListsColumn() {
        attachAppender();
        new SchemaVerifier(jdbc(1L, 0L, "day_count", null)).run(null);

        List<String> errors = errorMessages();
        assertFalse(errors.isEmpty(), "error expected when a required column is missing");
        assertTrue(errors.stream().anyMatch(m -> m.contains("'day_count'")),
                "missing column name expected: " + errors);
        assertTrue(errors.stream().anyMatch(m -> m.contains("V4")),
                "V4 migration hint expected: " + errors);
    }

    @Test
    @DisplayName("R2 P3-12: data_fill_task 缺 status 索引 → error 列出索引名")
    void missingDftIndex_errorListsIndex() {
        attachAppender();
        new SchemaVerifier(jdbc(1L, 0L, null, "idx_dft_status")).run(null);

        List<String> errors = errorMessages();
        assertFalse(errors.isEmpty(), "error expected when idx_dft_status missing");
        assertTrue(errors.stream().anyMatch(m -> m.contains("idx_dft_status")),
                "missing index name expected: " + errors);
    }

    @Test
    @DisplayName("R2 P3-7: screening_match 缺查询索引 → error 列出索引名")
    void missingScreeningIndex_errorListsIndex() {
        attachAppender();
        new SchemaVerifier(jdbc(1L, 0L, null, "idx_screening_match_window_days")).run(null);

        List<String> errors = errorMessages();
        assertFalse(errors.isEmpty(), "error expected when screening index missing");
        assertTrue(errors.stream().anyMatch(m -> m.contains("idx_screening_match_window_days")),
                "missing index name expected: " + errors);
    }

    @Test
    @DisplayName("R2 P1-5: 非 MySQL 环境查询异常 → 仅 warn 跳过，不抛错")
    void nonMysqlEnvironment_warnsAndSkips() {
        attachAppender();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(inv -> {
            throw new RuntimeException("H2 不支持 information_schema 查询");
        }).when(jdbc).queryForObject(anyString(), eq(Long.class), any(Object[].class));

        // 不应抛异常
        new SchemaVerifier(jdbc).run(null);

        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel().toString().equals("WARN")),
                "warn skip log expected for non-MySQL environment");
    }
}
