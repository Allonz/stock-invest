package com.stock.invest.enums.dto;

import java.time.LocalDateTime;

/**
 * 数据源优先级记录展示 DTO（P3-5：Controller 不直接返回实体）。
 */
public record StockDataSourcePriorityDto(
        String symbol,
        String dataSource,
        LocalDateTime lastSuccessTime
) {
}
