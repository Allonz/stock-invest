-- ============================================================
-- stock-invest V1 baseline —— 全表初始化（对齐生产库实际结构）
-- 依据：2026-08-06 mysqldump --no-data 对拍生产库 stock_invest
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，存量库跳过
-- ============================================================

-- ------------------------------------------------------------
-- data_fill_task（补缺重试任务；单数表名，生产由仓库外历史 DDL 创建）
-- 唯一键沿用生产实际名 uk_data_fill_task_symbol_missing_date
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS data_fill_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL,
    trade_date DATE NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    retry_count INT NOT NULL DEFAULT 0,
    retry_date DATE NULL,
    day_count INT NULL DEFAULT 0,
    last_error VARCHAR(512) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_data_fill_task_symbol_missing_date (symbol, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ------------------------------------------------------------
-- screening_match（筛选命中记录）
-- 含 algorithm 列；新增唯一约束（P2-5）：
--   同交易日同股票同窗口同算法仅一条，防重复触发插入重复行
-- 存量库存在历史重复行时由 V2 守卫式迁移处理
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS screening_match (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id VARCHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    data_source VARCHAR(32) NOT NULL,
    last_close DECIMAL(12,4) NULL,
    price DECIMAL(12,4) NULL,
    rise BIT(1) NOT NULL,
    symbol VARCHAR(32) NOT NULL,
    trade_date DATE NOT NULL,
    window_days INT NOT NULL DEFAULT 7 COMMENT 'window days(2-7)',
    algorithm VARCHAR(32) NOT NULL DEFAULT 'increasing_volume',
    PRIMARY KEY (id),
    KEY idx_screening_match_trade_date_price (trade_date, price),
    KEY idx_screening_match_batch_id (batch_id),
    UNIQUE KEY uk_screening_match_trade_symbol_window_algorithm (trade_date, symbol, window_days, algorithm)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ------------------------------------------------------------
-- stock_daily_bar（日 K 线）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS stock_daily_bar (
    id BIGINT NOT NULL AUTO_INCREMENT,
    close_price DECIMAL(12,4) NOT NULL,
    change_percent DECIMAL(12,4) NULL,
    after_hours DECIMAL(12,4) NULL,
    after_hours_change_percent DECIMAL(12,4) NULL,
    created_at DATETIME(6) NOT NULL,
    open_price DECIMAL(12,4) NOT NULL,
    high_price DECIMAL(12,4) NULL,
    low_price DECIMAL(12,4) NULL,
    source VARCHAR(16) NOT NULL,
    symbol VARCHAR(32) NOT NULL,
    trade_date DATE NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    volume BIGINT NOT NULL,
    stock_name VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_daily_bar_symbol_trade_date (symbol, trade_date),
    KEY idx_stock_daily_bar_trade_date (trade_date),
    KEY idx_stock_daily_bar_symbol (symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ------------------------------------------------------------
-- stock_data_source_priority（每股票数据源优先级）
-- 唯一键 (symbol, data_source) 兜底并发（P2-3）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS stock_data_source_priority (
    id BIGINT NOT NULL AUTO_INCREMENT,
    symbol VARCHAR(20) NOT NULL COMMENT '股票代码',
    data_source VARCHAR(50) NOT NULL COMMENT '数据源名称',
    last_success_time DATETIME NOT NULL COMMENT '最近一次补填成功时间',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sds_priority_symbol_ds (symbol, data_source),
    KEY idx_sds_priority_symbol (symbol),
    KEY idx_sds_priority_symbol_time (symbol, last_success_time DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='stock data source priority';

-- ------------------------------------------------------------
-- symbol_blacklist（股票黑名单，吸收原 V2__create_symbol_blacklist.sql）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS symbol_blacklist (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL COMMENT '股票代码',
    consecutive_404_count INT NOT NULL DEFAULT 0 COMMENT '连续触发 404 判定次数',
    first_404_date DATE NOT NULL COMMENT '首次进入黑名单的日期',
    last_404_date DATE NULL COMMENT '最近一次 404 判定的日期',
    source_errors VARCHAR(1000) NULL COMMENT '哪些数据源报 404，JSON 格式',
    status VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT '状态：active=活跃, cleared=已清理',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_symbol_blacklist_symbol (symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='股票黑名单表，记录被多数据源判定为不存在的股票';

-- ------------------------------------------------------------
-- trading_calendar（交易日历）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trading_calendar (
    id BIGINT NOT NULL AUTO_INCREMENT,
    market VARCHAR(16) NOT NULL DEFAULT 'US' COMMENT '市场代码',
    trade_date DATE NOT NULL COMMENT '交易日',
    is_open BIT(1) NOT NULL DEFAULT b'1' COMMENT '是否开盘',
    source VARCHAR(32) NULL DEFAULT NULL COMMENT '数据来源',
    type VARCHAR(16) NULL DEFAULT NULL COMMENT '类型',
    detail VARCHAR(256) NULL DEFAULT NULL COMMENT '详情',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_trading_calendar_market_trade_date (market, trade_date),
    KEY idx_tc_market (market),
    KEY idx_tc_trade_date (trade_date),
    KEY idx_tc_market_date (market, trade_date),
    KEY idx_tc_is_open (is_open)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
