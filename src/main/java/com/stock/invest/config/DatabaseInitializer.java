package com.stock.invest.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时执行必要的数据库 DDL 变更，
 * 避免 JPA ddl-auto=update 在某些 MySQL 版本上不自动加列的问题。
 */
@Component
public class DatabaseInitializer {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);
    private final JdbcTemplate jdbcTemplate;

    public DatabaseInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        addColumnIfNotExists("screening_match", "algorithm",
                "VARCHAR(32) NOT NULL DEFAULT 'increasing_volume'",
                "window_days");
    }

    private void addColumnIfNotExists(String table, String column, String definition, String after) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = ? "
                    + "AND COLUMN_NAME = ?",
                    Integer.class, table, column);
            if (count != null && count > 0) {
                log.info("[DBInit] Column {}.{} already exists, skip", table, column);
                return;
            }
            String sql = String.format("ALTER TABLE %s ADD COLUMN %s %s AFTER %s",
                    table, column, definition, after);
            jdbcTemplate.execute(sql);
            log.info("[DBInit] Added column {}.{}", table, column);
        } catch (Exception e) {
            log.error("[DBInit] Failed to add column {}.{}: {}", table, column, e.getMessage());
        }
    }
}
