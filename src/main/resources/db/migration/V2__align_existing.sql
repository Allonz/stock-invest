-- ============================================================
-- stock-invest V2 align —— 存量库增量对齐（只增不改删，无破坏性变更）
-- 说明：
--   * 全新库由 V1 完整建表，本脚本均为 no-op；
--   * 存量库（仓库外历史 DDL 创建）经此脚本补齐缺失列/约束；
--   * MySQL 不支持 ADD COLUMN IF NOT EXISTS（2026-08-06 实测 8.0.46 报语法错误），
--     条件 DDL 统一走 information_schema 探测 + PREPARE/EXECUTE 动态 SQL；
--   * screening_match 唯一约束（P2-5）守卫式添加：仅当无历史重复行时执行，
--     生产库存在重复行（2026-08-06 实测 2566 组）时跳过，
--     需人工清理重复数据后重跑本脚本方可生效。
-- ============================================================

-- ------------------------------------------------------------
-- 1. screening_match.algorithm 列补齐（历史库缺失时）
-- ------------------------------------------------------------
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'screening_match' AND COLUMN_NAME = 'algorithm');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE screening_match ADD COLUMN algorithm VARCHAR(32) NOT NULL DEFAULT ''increasing_volume''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 2. data_fill_task.version 乐观锁列补齐（P2-4，存量库均缺失）
-- ------------------------------------------------------------
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'data_fill_task' AND COLUMN_NAME = 'version');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE data_fill_task ADD COLUMN version INT NOT NULL DEFAULT 0',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 3. screening_match 唯一约束（守卫式，P2-5）
--    约束名：uk_screening_match_trade_symbol_window_algorithm
--    条件：约束不存在 且 无历史重复行（V1 全新库已含该约束，直接跳过）
-- ------------------------------------------------------------
SET @constraint_exists = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'screening_match'
    AND CONSTRAINT_NAME = 'uk_screening_match_trade_symbol_window_algorithm');

SET @screening_dup = (SELECT COUNT(*) FROM (
    SELECT 1 FROM screening_match
    GROUP BY trade_date, symbol, window_days, algorithm
    HAVING COUNT(*) > 1
    LIMIT 1
) t);

SET @ddl = IF(@constraint_exists = 0 AND @screening_dup = 0,
    'ALTER TABLE screening_match ADD CONSTRAINT uk_screening_match_trade_symbol_window_algorithm UNIQUE (trade_date, symbol, window_days, algorithm)',
    'SELECT 1');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
