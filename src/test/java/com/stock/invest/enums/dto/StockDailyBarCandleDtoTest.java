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

    private static java.math.BigDecimal bd(String v) {
        return new java.math.BigDecimal(v);
    }

    // DTO-001: 正常创建 record，所有字段赋值
    @Test
    @DisplayName("DTO-001: 构造 record 并验证全部 9 个组件")
    void shouldCreateDtoWithAllFields() {
        StockDailyBarCandleDto dto = new StockDailyBarCandleDto(
                "2025-06-25", bd("150.0"), bd("155.0"), bd("148.0"), bd("152.5"),
                bd("1.67"), bd("153.0"), bd("0.33"), 1_000_000L);

        assertEquals("2025-06-25", dto.date());
        assertEquals(0, bd("150.0").compareTo(dto.open()));
        assertEquals(0, bd("155.0").compareTo(dto.high()));
        assertEquals(0, bd("148.0").compareTo(dto.low()));
        assertEquals(0, bd("152.5").compareTo(dto.close()));
        assertEquals(0, bd("1.67").compareTo(dto.changePercent()));
        assertEquals(0, bd("153.0").compareTo(dto.afterHours()));
        assertEquals(0, bd("0.33").compareTo(dto.afterHoursChangePercent()));
        assertEquals(1_000_000L, dto.volume());
    }

    // DTO-002: 允许 null 字段（涨跌幅/盘后可空）
    @Test
    @DisplayName("DTO-002: 允许 null 的 numeric 字段")
    void shouldAllowNullForOptionalFields() {
        StockDailyBarCandleDto dto = new StockDailyBarCandleDto(
                "2025-06-25", bd("150.0"), bd("155.0"), bd("148.0"), bd("152.5"),
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
                "1970-01-01", java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO, 0L);

        assertEquals(0, java.math.BigDecimal.ZERO.compareTo(dto.open()));
        assertEquals(0L, dto.volume());
        assertEquals(0, java.math.BigDecimal.ZERO.compareTo(dto.changePercent()));
    }

    // DTO-004: 空日期字符串
    @Test
    @DisplayName("DTO-004: 空日期字符串")
    void shouldAllowEmptyDateString() {
        StockDailyBarCandleDto dto = new StockDailyBarCandleDto(
                "", bd("1.0"), bd("2.0"), bd("0.5"), bd("1.5"),
                bd("0.1"), null, null, 100L);

        assertEquals("", dto.date());
    }

    // DTO-005: record 自动生成的 equals / hashCode / toString
    @Test
    @DisplayName("DTO-005: record 的 equals / hashCode / toString")
    void recordContracts() {
        StockDailyBarCandleDto a = new StockDailyBarCandleDto(
                "2025-06-25", bd("150.0"), bd("155.0"), bd("148.0"), bd("152.5"),
                bd("1.67"), bd("153.0"), bd("0.33"), 1_000_000L);
        StockDailyBarCandleDto b = new StockDailyBarCandleDto(
                "2025-06-25", bd("150.0"), bd("155.0"), bd("148.0"), bd("152.5"),
                bd("1.67"), bd("153.0"), bd("0.33"), 1_000_000L);

        assertEquals(a, b, "same values should be equal");
        assertEquals(a.hashCode(), b.hashCode(), "same values should have same hashCode");
        assertNotNull(a.toString());
        assertTrue(a.toString().contains("2025-06-25"));
        assertTrue(a.toString().contains("1.67"));
    }
}
