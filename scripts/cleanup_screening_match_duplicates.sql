-- ============================================================
-- stock-invest 一次性运维脚本：清理 screening_match 重复组（R2 P1-5）
-- 用途：生产库存在历史重复行导致唯一约束 uk_screening_match_trade_symbol_window_algorithm
--       无法通过迁移自动添加时，由运维人工执行本脚本清理，随后手工 ADD CONSTRAINT。
-- 幂等：重复执行删除 0 行（每组仅保留 MIN(id)），无副作用。
-- 事务：单事务，失败自动回滚。
-- 执行：mysql -h127.0.0.1 -P3307 -uroot -p stock_invest < scripts/cleanup_screening_match_duplicates.sql
-- ============================================================
START TRANSACTION;

DELETE sm FROM screening_match sm
JOIN (
    SELECT trade_date, symbol, window_days, algorithm, MIN(id) AS keep_id
    FROM screening_match
    GROUP BY trade_date, symbol, window_days, algorithm
    HAVING COUNT(*) > 1
) dup
    ON sm.trade_date = dup.trade_date
   AND sm.symbol = dup.symbol
   AND sm.window_days = dup.window_days
   AND sm.algorithm = dup.algorithm
   AND sm.id <> dup.keep_id;

SELECT ROW_COUNT() AS deleted_duplicate_rows;

COMMIT;

-- 清理完成后手工执行（或交由 SchemaVerifier 校验确认）：
-- ALTER TABLE screening_match ADD CONSTRAINT uk_screening_match_trade_symbol_window_algorithm
--     UNIQUE (trade_date, symbol, window_days, algorithm);
