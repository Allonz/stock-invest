package com.stock.invest.enums.dto;

import java.time.LocalDate;

public record StockDailyBarCandleDto(
    String date,           // 格式: yyyy-MM-dd
    Double open,           // 开盘价
    Double high,           // 最高价
    Double low,            // 最低价
    Double close,          // 收盘价
    Double changePercent,  // 涨跌幅（%）
    Double afterHours,     // 盘后价
    Double afterHoursChangePercent,  // 盘后涨跌幅（%）
    Long volume            // 成交量
) {}
