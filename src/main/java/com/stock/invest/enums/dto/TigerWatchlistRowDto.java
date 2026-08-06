package com.stock.invest.enums.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.math.BigDecimal;

public record TigerWatchlistRowDto(
    @JsonAlias("code") String symbol,
    String name,
    @JsonAlias("closePrice") BigDecimal lastPrice,
    BigDecimal openPrice,
    BigDecimal highPrice,
    BigDecimal lowPrice,
    BigDecimal changePercent,
    BigDecimal afterHours,
    BigDecimal afterHoursChangePercent,
    Object volume
) {}
