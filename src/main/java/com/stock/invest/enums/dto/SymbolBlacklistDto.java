package com.stock.invest.enums.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 黑名单记录展示 DTO（P3-5：Controller 不直接返回实体）。
 */
public record SymbolBlacklistDto(
        Long id,
        String symbol,
        Integer consecutive404Count,
        LocalDate first404Date,
        LocalDate last404Date,
        String sourceErrors,
        String status,
        LocalDateTime updatedAt
) {
}
