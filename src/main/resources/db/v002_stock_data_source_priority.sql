-- ============================================================
-- stock_data_source_priority: per-stock data source priority list
-- Stores each (symbol, data_source) with last_success_time.
-- Query ordered by last_success_time DESC for fallback order.
-- UNIQUE constraint ensures max 1 row per (symbol, data_source).
-- Total 5 data sources: yfinance, tiingo, tiger, twelvedata, tigeropen
-- ============================================================
CREATE TABLE IF NOT EXISTS stock_data_source_priority (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    symbol            VARCHAR(20)  NOT NULL COMMENT '股票代码',
    data_source       VARCHAR(50)  NOT NULL COMMENT '数据源名称',
    last_success_time DATETIME     NOT NULL COMMENT '最近一次补填成功时间',
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sds_priority_symbol_ds (symbol, data_source),
    INDEX idx_sds_priority_symbol (symbol),
    INDEX idx_sds_priority_symbol_time (symbol, last_success_time DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='stock data source priority';