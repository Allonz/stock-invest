package com.stock.invest.enums.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record SnapshotGridRowDto(
    String symbol,
    String stockName,
    List<String> cells
) {
    public SnapshotGridRowDto {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol cannot be blank");
        }
        symbol = symbol.trim();
        stockName = stockName == null ? "" : stockName.trim();
        cells = cells == null ? new ArrayList<>() : new ArrayList<>(cells);
    }

    @Override
    public List<String> cells() {
        return Collections.unmodifiableList(cells);
    }
}
