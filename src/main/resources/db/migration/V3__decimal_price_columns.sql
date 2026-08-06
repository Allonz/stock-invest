-- ============================================================
-- stock-invest V3 —— 价格列 DOUBLE → DECIMAL(12,4)（P2-6）
-- 说明：
--   * 全新库：V1 已改为 DECIMAL 建表（V1__baseline.sql 同步），本脚本为 no-op；
--   * 存量库：MODIFY COLUMN 就地转换，MySQL 自动按 4dp 四舍五入存量值；
--   * 幂等：对已转换的库重复执行 MODIFY 无副作用（类型不变，MySQL 无操作）；
--   * 测试环境 H2：Flyway 关闭（application-test.yml），不执行本脚本。
-- 依据：docs/p2-6-decimal-migration-plan.md §5.1
-- ============================================================

ALTER TABLE stock_daily_bar
    MODIFY COLUMN open_price DECIMAL(12,4) NOT NULL,
    MODIFY COLUMN high_price DECIMAL(12,4) NULL,
    MODIFY COLUMN low_price DECIMAL(12,4) NULL,
    MODIFY COLUMN close_price DECIMAL(12,4) NOT NULL,
    MODIFY COLUMN change_percent DECIMAL(12,4) NULL,
    MODIFY COLUMN after_hours DECIMAL(12,4) NULL,
    MODIFY COLUMN after_hours_change_percent DECIMAL(12,4) NULL;

ALTER TABLE screening_match
    MODIFY COLUMN last_close DECIMAL(12,4) NULL,
    MODIFY COLUMN price DECIMAL(12,4) NULL;
