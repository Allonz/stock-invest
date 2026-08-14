-- ============================================================
-- V5: 字段能力表 + 补缺字段增补支持
-- 1. stock_data_source_field_capability —— 字段 × 数据源能力矩阵（数据驱动）
-- 2. stock_daily_bar 加 missing_fields / field_fill_status（补缺增补用）
-- ============================================================

-- 1. 字段能力表
CREATE TABLE IF NOT EXISTS stock_data_source_field_capability (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  data_source   VARCHAR(16)  NOT NULL COMMENT '数据源：yfinance/tiingo/twelvedata/tigeropen/tiger_snap',
  field_name    VARCHAR(64)  NOT NULL COMMENT '字段：open_price/high_price/low_price/close_price/volume/change_percent/after_hours/after_hours_change_percent',
  supported     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '该源能否查询到该字段（1=能）',
  markable      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '该源该字段缺失时是否标记待补（1=标记）',
  query_method  VARCHAR(32)  NOT NULL DEFAULT 'DAILY_KLINE' COMMENT '补缺获取方式：DAILY_KLINE/AFTER_HOURS_API/CALC',
  remark        VARCHAR(255) NULL,
  created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_source_field (data_source, field_name)
) COMMENT '数据源字段能力矩阵';

-- 2. stock_daily_bar 补缺增补状态列
-- 注意：MySQL 不支持 ADD COLUMN IF NOT EXISTS（Flyway 版本记录保证只执行一次，无需幂等）
ALTER TABLE stock_daily_bar
  ADD COLUMN missing_fields VARCHAR(255) NULL COMMENT '缺失字段列表（逗号分隔，如 after_hours,after_hours_change_percent）',
  ADD COLUMN field_fill_status VARCHAR(20) NULL COMMENT '字段增补状态：NULL=未检查 / PENDING=待增补 / CONFIRMED=已确认';

-- 3. 能力数据（5 源 × 8 字段 = 40 行）
-- 核心 OHLCV：全部源支持 + 标记（DAILY_KLINE）
INSERT IGNORE INTO stock_data_source_field_capability (data_source, field_name, supported, markable, query_method, remark) VALUES
('yfinance', 'open_price', 1, 1, 'DAILY_KLINE', '日K线'),
('yfinance', 'high_price', 1, 1, 'DAILY_KLINE', '日K线'),
('yfinance', 'low_price', 1, 1, 'DAILY_KLINE', '日K线'),
('yfinance', 'close_price', 1, 1, 'DAILY_KLINE', '日K线'),
('yfinance', 'volume', 1, 1, 'DAILY_KLINE', '日K线'),
('tiingo', 'open_price', 1, 1, 'DAILY_KLINE', '日K线'),
('tiingo', 'high_price', 1, 1, 'DAILY_KLINE', '日K线'),
('tiingo', 'low_price', 1, 1, 'DAILY_KLINE', '日K线'),
('tiingo', 'close_price', 1, 1, 'DAILY_KLINE', '日K线'),
('tiingo', 'volume', 1, 1, 'DAILY_KLINE', '日K线'),
('twelvedata', 'open_price', 1, 1, 'DAILY_KLINE', '日K线'),
('twelvedata', 'high_price', 1, 1, 'DAILY_KLINE', '日K线'),
('twelvedata', 'low_price', 1, 1, 'DAILY_KLINE', '日K线'),
('twelvedata', 'close_price', 1, 1, 'DAILY_KLINE', '日K线'),
('twelvedata', 'volume', 1, 1, 'DAILY_KLINE', '日K线'),
('tigeropen', 'open_price', 1, 1, 'DAILY_KLINE', '日K线'),
('tigeropen', 'high_price', 1, 1, 'DAILY_KLINE', '日K线'),
('tigeropen', 'low_price', 1, 1, 'DAILY_KLINE', '日K线'),
('tigeropen', 'close_price', 1, 1, 'DAILY_KLINE', '日K线'),
('tigeropen', 'volume', 1, 1, 'DAILY_KLINE', '日K线'),
('tiger_snap', 'open_price', 1, 1, 'DAILY_KLINE', 'OCR 截图源'),
('tiger_snap', 'high_price', 1, 1, 'DAILY_KLINE', 'OCR 截图源'),
('tiger_snap', 'low_price', 1, 1, 'DAILY_KLINE', 'OCR 截图源'),
('tiger_snap', 'close_price', 1, 1, 'DAILY_KLINE', 'OCR 截图源'),
('tiger_snap', 'volume', 1, 1, 'DAILY_KLINE', 'OCR 截图源'),
-- change_percent：全部源标记（本地重算，0 不算缺）
('yfinance', 'change_percent', 1, 1, 'CALC', '本地隔日涨跌幅重算'),
('tiingo', 'change_percent', 1, 1, 'CALC', '本地隔日涨跌幅重算'),
('twelvedata', 'change_percent', 1, 1, 'CALC', '本地隔日涨跌幅重算'),
('tigeropen', 'change_percent', 1, 1, 'CALC', '本地隔日涨跌幅重算'),
('tiger_snap', 'change_percent', 1, 1, 'CALC', '本地隔日涨跌幅重算'),
-- 盘后：仅 yfinance/tigeropen/tiger_snap 支持；tiingo/twelvedata 不支持（不标记，防无限补）
('yfinance', 'after_hours', 1, 1, 'AFTER_HOURS_API', '盘后接口'),
('yfinance', 'after_hours_change_percent', 1, 1, 'AFTER_HOURS_API', '盘后接口'),
('tiingo', 'after_hours', 0, 0, 'AFTER_HOURS_API', '不支持盘后'),
('tiingo', 'after_hours_change_percent', 0, 0, 'AFTER_HOURS_API', '不支持盘后'),
('twelvedata', 'after_hours', 0, 0, 'AFTER_HOURS_API', '不支持盘后'),
('twelvedata', 'after_hours_change_percent', 0, 0, 'AFTER_HOURS_API', '不支持盘后'),
('tigeropen', 'after_hours', 1, 1, 'AFTER_HOURS_API', '盘后接口'),
('tigeropen', 'after_hours_change_percent', 1, 1, 'AFTER_HOURS_API', '盘后接口'),
('tiger_snap', 'after_hours', 1, 1, 'AFTER_HOURS_API', 'OCR 截图含盘后价'),
('tiger_snap', 'after_hours_change_percent', 1, 1, 'AFTER_HOURS_API', 'OCR 截图含盘后涨跌幅');
