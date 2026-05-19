package com.stock.invest.enums.dto;

import java.util.Collections;
import java.util.List;

public record TigerWatchlistIngestRequestDto(
    String tradeDate,
    List<TigerWatchlistRowDto> rows
) {
    public TigerWatchlistIngestRequestDto {
        rows = rows == null ? null : List.copyOf(rows);
    }

    @Override
    public List<TigerWatchlistRowDto> rows() {
        return rows == null ? null : Collections.unmodifiableList(rows);
    }
}
