package com.stock.invest.enums.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ScreeningResultDto(
    String symbol,
    BigDecimal price,
    Boolean rise,
    String source,
    LocalDate tradeDate
) {
    public ScreeningResultDto {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol cannot be blank");
        }
        if (tradeDate == null) {
            throw new IllegalArgumentException("tradeDate cannot be null");
        }
    }
}
