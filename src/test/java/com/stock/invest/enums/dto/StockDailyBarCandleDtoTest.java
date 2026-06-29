package com.stock.invest.enums.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DTO 字段与结构测试 — StockDailyBarCandleDto（record）
 * 覆盖 DTO-001 ~ DTO-005
 */
@DisplayName("StockDailyBarCandleDto — record 字段 & 结构")
class StockDailyBarCandleDtoTest {

    // DTO-001: 正常创建 record，所有字段赋值
    @Test
    @DisplayName("DTO-001: 构造 record 并验证全部 9 个组件")
    void shouldCreateDtoWithAllFields() {
        StockDailyBarCandleDto dto = new StockDailyBarCandleDto(
                "2025-06-25", 150.0, 155.0, 148.0, 152.5,
                1.67, 153.0, 0.33, 1_000_000L);

        assertEquals("2025-06-25", dto.date());
        assertEquals(150.0, dto.open(), 0.001);
        assertEquals(155.0, dto.high(), 0.001);
        assertEquals(148.0, dto.low(), 0.001);
        assertEquals(152.5, dto.close(), 0.001);
        assertEquals(1.67, dto.changePercent(), 0.001);
        assertEquals(153.0, dto.afterHours(), 0.001);
        assertEquals(0.33, dto.afterHoursChangePercent(), 0.001);
        assertEquals(1_000_000L, dto.volume());
    }

    // DTO-002: 允许 null 字段（涨跌幅/盘后可空）
    @Test
    @DisplayName("DTO-002: 允许 null 的 numeric 字段")
    void shouldAllowNullForOptionalFields() {
        StockDailyBarCandleDto dto = new StockDailyBarCandleDto(
                "2025-06-25", 150.0, 155.0, 148.0, 152.5,
                null, null, null, 500_000L);

        assertNull(dto.changePercent());
        assertNull(dto.afterHours());
        assertNull(dto.afterHoursChangePercent());
        assertEquals("2025-06-25", dto.date());
    }

    // DTO-003: 边缘值 —— 零值和极小值
    @Test
    @DisplayName("DTO-003: 零值 & 极小值字段")
    void shouldHandleZeroAndExtremeValues() {
        StockDailyBarCandleDto dto = new StockDailyBarCandleDto(
                "1970-01-01", 0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0L);

        assertEquals(0.0, dto.open());
        assertEquals(0L, dto.volume());
        assertEquals(0.0, dto.changePercent());
    }

    // DTO-004: 空日期字符串
    @Test
    @DisplayName("DTO-004: 空日期字符串")
    void shouldAllowEmptyDateString() {
        StockDailyBarCandleDto dto = new StockDailyBarCandleDto(
                "", 1.0, 2.0, 0.5, 1.5,
                0.1, null, null, 100L);

        assertEquals("", dto.date());
    }

    // DTO-005: record 自动生成的 equals / hashCode / toString
    @Test
    @DisplayName("DTO-005: record 的 equals / hashCode / toString")
    void recordContracts() {
        StockDailyBarCandleDto a = new StockDailyBarCandleDto(
                "2025-06-25", 150.0, 155.0, 148.0, 152.5,
                1.67, 153.0, 0.33, 1_000_000L);
        StockDailyBarCandleDto b = new StockDailyBarCandleDto(
                "2025-06-25", 150.0, 155.0, 148.0, 152.5,
                1.67, 153.0, 0.33, 1_000_000L);

        assertEquals(a, b, "same values should be equal");
        assertEquals(a.hashCode(), b.hashCode(), "same values should have same hashCode");
        assertNotNull(a.toString());
        assertTrue(a.toString().contains("2025-06-25"));
        assertTrue(a.toString().contains("1.67"));
    }
}
