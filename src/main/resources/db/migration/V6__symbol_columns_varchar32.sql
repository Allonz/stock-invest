-- ============================================================
-- V6: 统一 symbol 列宽为 VARCHAR(32)（评审 F-3）
-- 背景：symbol_blacklist.symbol / stock_data_source_priority.symbol 原为 VARCHAR(20)，
--   与 stock_daily_bar.symbol VARCHAR(32) 及 TigerWatchlistIngestServiceImpl SYMBOL_PATTERN
--   （^[A-Z0-9\-]{1,32}$）不一致：21-32 字符 symbol 截图导入成功后，黑名单写入
--   （GapFetcher）与优先级写入会触发 DataIntegrityViolation（MySQL 严格模式）。
-- 说明：MySQL 不支持 ADD COLUMN IF NOT EXISTS 风格守卫，Flyway 版本记录保证只执行一次。
-- ============================================================

ALTER TABLE symbol_blacklist
  MODIFY COLUMN symbol VARCHAR(32) NOT NULL COMMENT '股票代码';

ALTER TABLE stock_data_source_priority
  MODIFY COLUMN symbol VARCHAR(32) NOT NULL COMMENT '股票代码';
