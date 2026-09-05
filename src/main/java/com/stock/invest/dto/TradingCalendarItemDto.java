package com.stock.invest.dto;

/**
 * 交易日历条目 DTO（评审 A-1：替代 Controller 手工 LinkedHashMap）。
 */
public record TradingCalendarItemDto(
        String tradeDate,
        Boolean isOpen,
        String market,
        String source,
        String type,
        String detail
) {
}
