package com.stock.invest.enums.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

public record TigerWatchlistRowDto(
    @JsonAlias("code") String symbol,
    String name,
    @JsonAlias("closePrice") Double lastPrice,
    Double openPrice,
    Double highPrice,
    Double lowPrice,
    Double changePercent,
    Double afterHours,
    Double afterHoursChangePercent,
    Object volume
) {}
