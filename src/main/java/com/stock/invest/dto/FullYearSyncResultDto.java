package com.stock.invest.dto;

/**
 * 整年日历同步结果 DTO（评审 A-1：替代 Controller 手工 LinkedHashMap）。
 */
public record FullYearSyncResultDto(
        int fetched,
        String market,
        int year
) {
}
