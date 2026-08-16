package com.stock.invest.enums.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 日 K 线数据 DTO —— 供前端展示用，不暴露内部审计字段（createdAt/updatedAt）。
 */
public record StockDailyBarDto(
    Long id,
    String symbol,
    String name,
    LocalDate tradeDate,
    BigDecimal openPrice,
    BigDecimal highPrice,
    BigDecimal lowPrice,
    BigDecimal closePrice,
    BigDecimal changePercent,
    BigDecimal afterHours,
    BigDecimal afterHoursChangePercent,
    Long volume,
    String source
) {}
