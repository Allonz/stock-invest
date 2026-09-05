package com.stock.invest.dto;

/**
 * 补缺任务条目 DTO（评审 A-1：替代 Controller 手工 LinkedHashMap，字段顺序即 JSON 输出顺序）。
 *
 * @param tradeDate yyyy-MM-dd 文本（保持原 JSON 契约）
 * @param createdAt ISO-8601 文本（保持原 JSON 契约）
 */
public record FillTaskItemDto(
        long id,
        String symbol,
        String tradeDate,
        String status,
        Integer retryCount,
        Integer maxRetries,
        String lastError,
        String createdAt
) {
}
