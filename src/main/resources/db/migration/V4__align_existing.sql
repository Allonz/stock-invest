-- ============================================================
-- stock-invest V4 align —— 存量库增量对齐（只增不改删，无破坏性变更）
-- 说明：
--   * 全新库由 V1 完整建表，本脚本均为 no-op；
--   * 存量库（仓库外历史 DDL 创建）经此脚本补齐缺失列/约束/索引；
--   * MySQL 不支持 ADD COLUMN IF NOT EXISTS（2026-08-06 实测 8.0.46 报语法错误），
--     条件 DDL 统一走 information_schema 探测 + PREPARE/EXECUTE 动态 SQL；
--   * screening_match 唯一约束（P2-5）守卫式添加：仅当无历史重复行时执行，
--     生产库存在重复行（2026-08-06 实测 2566 组）时跳过，
--     由启动期 SchemaVerifier（P1-5）提示人工清理后手工添加；
--   * 本文件为 V2__align_existing.sql（历史版本 2 已被 V2__create_symbol_blacklist.sql
--     占用）整体后移重排而来（P1-4），内容在首次执行后保持不可变。
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
-- 2. data_fill_task 乐观锁/重试列补齐（P2-4，与 V1 定义逐列对齐）
--    版本号列、重试计数字段、状态列：历史 DDL 缺失时守卫式补齐
-- ------------------------------------------------------------
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'data_fill_task' AND COLUMN_NAME = 'version');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE data_fill_task ADD COLUMN version INT NOT NULL DEFAULT 0',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'data_fill_task' AND COLUMN_NAME = 'status');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE data_fill_task ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT ''pending''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'data_fill_task' AND COLUMN_NAME = 'retry_count');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE data_fill_task ADD COLUMN retry_count INT NOT NULL DEFAULT 0',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'data_fill_task' AND COLUMN_NAME = 'retry_date');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE data_fill_task ADD COLUMN retry_date DATE NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'data_fill_task' AND COLUMN_NAME = 'day_count');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE data_fill_task ADD COLUMN day_count INT NULL DEFAULT 0',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'data_fill_task' AND COLUMN_NAME = 'last_error');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE data_fill_task ADD COLUMN last_error VARCHAR(512) NULL',
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

-- ------------------------------------------------------------
-- 4. screening_match 查询索引补齐（P3-7，与实体声明一致）
--    idx_screening_match_window_days
--    idx_screening_match_batch_id_window_days（覆盖 countByBatchIdGroupByWindowDays 的 GROUP BY 路径）
-- ------------------------------------------------------------
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'screening_match' AND INDEX_NAME = 'idx_screening_match_window_days');
SET @ddl = IF(@idx_exists = 0,
    'ALTER TABLE screening_match ADD KEY idx_screening_match_window_days (window_days)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'screening_match' AND INDEX_NAME = 'idx_screening_match_batch_id_window_days');
SET @ddl = IF(@idx_exists = 0,
    'ALTER TABLE screening_match ADD KEY idx_screening_match_batch_id_window_days (batch_id, window_days)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 5. data_fill_task.status 索引补齐（P3-12，覆盖 findRetryableTasks / findByStatus）
-- ------------------------------------------------------------
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'data_fill_task' AND INDEX_NAME = 'idx_dft_status');
SET @ddl = IF(@idx_exists = 0,
    'ALTER TABLE data_fill_task ADD KEY idx_dft_status (status)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
