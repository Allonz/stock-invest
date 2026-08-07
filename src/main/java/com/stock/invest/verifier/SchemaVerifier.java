package com.stock.invest.verifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动期只读 Schema 校验（R2 P1-5 / P2-4 / P3-7 / P3-12）。
 * <p>
 * 背景：V2（现 V4）中 screening_match 唯一约束是守卫式添加 —— 生产库存在历史重复行时
 * 迁移跳过 ADD CONSTRAINT，且迁移只执行一次，即使后续清理完重复行约束也不会被自动补上。
 * 本组件在应用启动时只读校验以下项，缺失即 log.error 并给出明确修复指引，而不是等运行期 500：
 * <ul>
 *   <li>screening_match 唯一约束 uk_screening_match_trade_symbol_window_algorithm</li>
 *   <li>data_fill_task 必需列（version/status/retry_count/retry_date/day_count/last_error）</li>
 *   <li>查询索引：idx_screening_match_window_days、idx_screening_match_batch_id_window_days、idx_dft_status</li>
 * </ul>
 * 校验为只读 + 不抛异常：非 MySQL 环境（如 H2 测试库）查询失败时仅 warn 跳过，不影响启动。
 * </p>
 */
@Component
public class SchemaVerifier implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaVerifier.class);

    private static final String UNIQUE_CONSTRAINT = "uk_screening_match_trade_symbol_window_algorithm";

    /** data_fill_task 历史 DDL 可能缺失、需 V4 补齐的列（与 V1 定义对齐） */
    private static final String[] DFT_REQUIRED_COLUMNS = {
            "version", "status", "retry_count", "retry_date", "day_count", "last_error"
    };

    private static final String[] DFT_REQUIRED_INDEXES = {"idx_dft_status"};
    private static final String[] SM_REQUIRED_INDEXES = {
            "idx_screening_match_window_days", "idx_screening_match_batch_id_window_days"
    };

    private final JdbcTemplate jdbcTemplate;

    public SchemaVerifier(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            verify();
        } catch (Exception e) {
            // H2 测试库 / 表未就绪等环境不适用 MySQL information_schema 查询，仅 warn 跳过
            log.warn("[SchemaVerifier] 校验执行失败（可能为非 MySQL 环境），跳过：{}", e.getMessage());
        }
    }

    private void verify() {
        boolean ok = true;

        // 1. screening_match 唯一约束
        Long constraintCount = count(
                "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'screening_match' "
                        + "AND CONSTRAINT_NAME = ?",
                UNIQUE_CONSTRAINT);
        if (constraintCount != null && constraintCount == 0) {
            Long dupGroups = count(
                    "SELECT COUNT(*) FROM ("
                            + "SELECT 1 FROM screening_match "
                            + "GROUP BY trade_date, symbol, window_days, algorithm HAVING COUNT(*) > 1"
                            + ") t");
            if (dupGroups != null && dupGroups > 0) {
                log.error("[SchemaVerifier] screening_match 唯一约束缺失且存在 {} 组重复数据！"
                                + "请先执行 scripts/cleanup_screening_match_duplicates.sql 清理重复，"
                                + "再手工执行：ALTER TABLE screening_match ADD CONSTRAINT "
                                + "uk_screening_match_trade_symbol_window_algorithm "
                                + "UNIQUE (trade_date, symbol, window_days, algorithm)",
                        dupGroups);
            } else {
                log.error("[SchemaVerifier] screening_match 唯一约束缺失且无重复数据，可直接手工执行："
                        + "ALTER TABLE screening_match ADD CONSTRAINT "
                        + "uk_screening_match_trade_symbol_window_algorithm "
                        + "UNIQUE (trade_date, symbol, window_days, algorithm)");
            }
            ok = false;
        }

        // 2. data_fill_task 必需列
        for (String column : DFT_REQUIRED_COLUMNS) {
            Long n = count(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'data_fill_task' "
                            + "AND COLUMN_NAME = ?",
                    column);
            if (n != null && n == 0) {
                log.error("[SchemaVerifier] data_fill_task 缺少必需列 '{}'：请核对 V4 迁移是否已执行，"
                        + "或手工执行 ALTER TABLE data_fill_task ADD COLUMN 补齐（列定义见 V1__baseline.sql）", column);
                ok = false;
            }
        }

        // 3. 查询索引
        for (String index : SM_REQUIRED_INDEXES) {
            if (!indexExists("screening_match", index)) {
                log.error("[SchemaVerifier] screening_match 缺少索引 '{}'：请核对 V4 迁移或手工执行 "
                        + "ALTER TABLE screening_match ADD KEY 补建", index);
                ok = false;
            }
        }
        if (!indexExists("data_fill_task", DFT_REQUIRED_INDEXES[0])) {
            log.error("[SchemaVerifier] data_fill_task 缺少索引 '{}'：请核对 V4 迁移或手工执行 "
                    + "ALTER TABLE data_fill_task ADD KEY 补建", DFT_REQUIRED_INDEXES[0]);
            ok = false;
        }

        if (ok) {
            log.info("[SchemaVerifier] 通过：screening_match 唯一约束、data_fill_task 列、查询索引均就绪");
        }
    }

    private boolean indexExists(String table, String index) {
        Long n = count(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                table, index);
        return n != null && n > 0;
    }

    private Long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }
}
