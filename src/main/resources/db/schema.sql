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
    close_price DOUBLE NOT NULL,
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
