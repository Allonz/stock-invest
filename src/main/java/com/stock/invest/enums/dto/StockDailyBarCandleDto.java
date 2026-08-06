package com.stock.invest.enums.dto;

import java.math.BigDecimal;

public record StockDailyBarCandleDto(
    String date,           // 格式: yyyy-MM-dd
    BigDecimal open,       // 开盘价
    BigDecimal high,       // 最高价
    BigDecimal low,        // 最低价
    BigDecimal close,      // 收盘价
    BigDecimal changePercent,  // 涨跌幅（%）
    BigDecimal afterHours,     // 盘后价
    BigDecimal afterHoursChangePercent,  // 盘后涨跌幅（%）
    Long volume            // 成交量
) {}
