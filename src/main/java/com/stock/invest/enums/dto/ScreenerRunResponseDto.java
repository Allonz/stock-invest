package com.stock.invest.enums.dto;

import java.time.LocalDate;

public record ScreenerRunResponseDto(
    String batchId,
    LocalDate tradeDate,
    Integer totalCandidates,
    Integer processedStocks,
    Integer matchedStocks
) {
    public ScreenerRunResponseDto {
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("batchId cannot be blank");
        }
        if (tradeDate == null) {
            throw new IllegalArgumentException("tradeDate cannot be null");
        }
    }
}
