package com.stock.invest.enums.dto;

import java.time.LocalDate;

/**
 * 日 K 线数据 DTO —— 供前端展示用，不暴露内部审计字段（id/createdAt/updatedAt）。
 */
public record StockDailyBarDto(
    String symbol,
    String name,
    LocalDate tradeDate,
    Double openPrice,
    Double highPrice,
    Double lowPrice,
    Double closePrice,
    Double changePercent,
    Double afterHours,
    Double afterHoursChangePercent,
    Long volume,
    String source
) {}
