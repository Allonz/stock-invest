package com.stock.invest.dto;

import java.util.List;

/**
 * 补缺任务分页 DTO（评审 A-1：替代 Controller 手工 LinkedHashMap）。
 */
public record FillTaskPageDto(
        long total,
        int page,
        int size,
        List<FillTaskItemDto> data
) {
}
