package com.stock.invest.enums.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

public record TigerWatchlistRowDto(
    @JsonAlias("code") String symbol,
    String name,
    Double lastPrice,
    Object volume
) {}
