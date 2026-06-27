-- stock-invest schema
-- MySQL 8.x

CREATE TABLE IF NOT EXISTS screening_match (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id VARCHAR(32) NOT NULL,
    data_source VARCHAR(32) NOT NULL,
    symbol VARCHAR(32) NOT NULL,
    last_close DOUBLE NULL,
    trade_date DATE NOT NULL,
    price DOUBLE NULL,
    rise BIT(1) NOT NULL,
    window_days INT NOT NULL DEFAULT 7,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_screening_match_trade_date_price (trade_date, price),
    INDEX idx_screening_match_batch_id (batch_id),
    INDEX idx_screening_match_window_days (window_days),
    INDEX idx_screening_match_batch_id_window_days (batch_id, window_days)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS data_fill_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL,
    missing_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    persistent_days INT NULL,
    error_message TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_data_fill_tasks_symbol (symbol),
    INDEX idx_data_fill_tasks_status (status),
    INDEX idx_data_fill_tasks_symbol_missing (symbol, missing_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS stock_daily_bar (
    id BIGINT NOT NULL AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL,
    stock_name VARCHAR(128) NULL,
    trade_date DATE NOT NULL,
    open_price DOUBLE NOT NULL,
    high_price DOUBLE NOT NULL,
    low_price DOUBLE NOT NULL,
    close_price DOUBLE NOT NULL,
    change_percent DOUBLE NULL,
    after_hours DOUBLE NULL,
    after_hours_change_percent DOUBLE NULL,
    volume BIGINT NOT NULL,
    source VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_daily_bar_symbol_trade_date (symbol, trade_date),
    INDEX idx_stock_daily_bar_trade_date (trade_date),
    INDEX idx_stock_daily_bar_symbol (symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- data_fill_tasks: track retrying/stopped data-fill operations
-- ============================================================
CREATE TABLE IF NOT EXISTS data_fill_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL,
    trade_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'retrying' COMMENT 'retrying | completed | stopped',
    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 5,
    max_age_days INT NOT NULL DEFAULT 7,
    last_error TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_dft_status (status),
    INDEX idx_dft_symbol_trade_date (symbol, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- trading_calendar: track trading-day calendar for each market
-- ============================================================
CREATE TABLE IF NOT EXISTS trading_calendar (
    id BIGINT NOT NULL AUTO_INCREMENT,
    market VARCHAR(16) NOT NULL DEFAULT 'US' COMMENT '市场代码: US | HK | CN',
    trade_date DATE NOT NULL COMMENT '交易日',
    is_open BIT(1) NOT NULL DEFAULT 1 COMMENT '是否开盘: 1=开盘, 0=休市',
    source VARCHAR(32) NULL DEFAULT NULL COMMENT '数据来源: tiger | tigeropen | alpaca | default',
    type VARCHAR(16) NULL DEFAULT NULL COMMENT '类型: TRADING | HOLIDAY | WEEKEND',
    detail VARCHAR(256) NULL DEFAULT NULL COMMENT '详情/节假日名称',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_trading_calendar_market_trade_date (market, trade_date),
    INDEX idx_tc_market (market),
    INDEX idx_tc_trade_date (trade_date),
    INDEX idx_tc_market_date (market, trade_date),
    INDEX idx_tc_is_open (is_open)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
