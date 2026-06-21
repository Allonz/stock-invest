CREATE TABLE IF NOT EXISTS symbol_blacklist (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL COMMENT '股票代码',
    consecutive_404_count INT NOT NULL DEFAULT 0 COMMENT '连续触发 404 判定次数',
    first_404_date DATE NOT NULL COMMENT '首次进入黑名单的日期',
    last_404_date DATE COMMENT '最近一次 404 判定的日期',
    source_errors VARCHAR(1000) COMMENT '哪些数据源报 404，JSON 格式',
    status VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT '状态：active=活跃, cleared=已清理',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_symbol_blacklist_symbol (symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='股票黑名单表，记录被多数据源判定为不存在的股票';
