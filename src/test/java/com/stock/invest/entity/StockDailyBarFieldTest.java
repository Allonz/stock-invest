package com.stock.invest.entity;

import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实体字段测试 — StockDailyBar（新增字段）
 * 覆盖 ENT-001 ~ ENT-008
 */
@DisplayName("StockDailyBar — 新增字段（highPrice/lowPrice/changePercent/afterHours/afterHoursChangePercent）")
class StockDailyBarFieldTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    // ENT-001: highPrice 字段 getter/setter
    @Test
    @DisplayName("ENT-001: highPrice 字段 getter/setter")
    void highPriceField() {
        StockDailyBar bar = new StockDailyBar();
        bar.setHighPrice(bd("155.5"));
        assertEquals(0, bd("155.5").compareTo(bar.getHighPrice()));
    }

    // ENT-002: lowPrice 字段 getter/setter
    @Test
    @DisplayName("ENT-002: lowPrice 字段 getter/setter")
    void lowPriceField() {
        StockDailyBar bar = new StockDailyBar();
        bar.setLowPrice(bd("148.2"));
        assertEquals(0, bd("148.2").compareTo(bar.getLowPrice()));
    }

    // ENT-003: changePercent 字段（nullable）
    @Test
    @DisplayName("ENT-003: changePercent 字段 getter/setter — nullable")
    void changePercentField() {
        StockDailyBar bar = new StockDailyBar();
        assertNull(bar.getChangePercent(), "default should be null");

        bar.setChangePercent(bd("2.35"));
        assertEquals(0, bd("2.35").compareTo(bar.getChangePercent()));

        bar.setChangePercent(null);
        assertNull(bar.getChangePercent(), "can set back to null");
    }

    // ENT-004: afterHours 字段（nullable）
    @Test
    @DisplayName("ENT-004: afterHours 字段 getter/setter — nullable")
    void afterHoursField() {
        StockDailyBar bar = new StockDailyBar();
        assertNull(bar.getAfterHours(), "default should be null");

        bar.setAfterHours(bd("153.0"));
        assertEquals(0, bd("153.0").compareTo(bar.getAfterHours()));

        bar.setAfterHours(null);
        assertNull(bar.getAfterHours());
    }

    // ENT-005: afterHoursChangePercent 字段（nullable）
    @Test
    @DisplayName("ENT-005: afterHoursChangePercent 字段 getter/setter — nullable")
    void afterHoursChangePercentField() {
        StockDailyBar bar = new StockDailyBar();
        assertNull(bar.getAfterHoursChangePercent(), "default should be null");

        bar.setAfterHoursChangePercent(bd("0.45"));
        assertEquals(0, bd("0.45").compareTo(bar.getAfterHoursChangePercent()));

        bar.setAfterHoursChangePercent(null);
        assertNull(bar.getAfterHoursChangePercent());
    }

    // ENT-006: 所有新增字段一起设置
    @Test
    @DisplayName("ENT-006: 同时设置全部新增字段")
    void allNewFieldsTogether() {
        StockDailyBar bar = new StockDailyBar();
        bar.setSymbol("AAPL");
        bar.setTradeDate(LocalDate.of(2025, 6, 25));
        bar.setOpenPrice(bd("150.0"));
        bar.setHighPrice(bd("155.0"));
        bar.setLowPrice(bd("148.0"));
        bar.setClosePrice(bd("152.5"));
        bar.setChangePercent(bd("1.67"));
        bar.setAfterHours(bd("153.0"));
        bar.setAfterHoursChangePercent(bd("0.33"));
        bar.setVolume(1_000_000L);
        bar.setSource("yfinance");

        assertEquals("AAPL", bar.getSymbol());
        assertEquals(LocalDate.of(2025, 6, 25), bar.getTradeDate());
        assertEquals(0, bd("150.0").compareTo(bar.getOpenPrice()));
        assertEquals(0, bd("155.0").compareTo(bar.getHighPrice()));
        assertEquals(0, bd("148.0").compareTo(bar.getLowPrice()));
        assertEquals(0, bd("152.5").compareTo(bar.getClosePrice()));
        assertEquals(0, bd("1.67").compareTo(bar.getChangePercent()));
        assertEquals(0, bd("153.0").compareTo(bar.getAfterHours()));
        assertEquals(0, bd("0.33").compareTo(bar.getAfterHoursChangePercent()));
        assertEquals(1_000_000L, bar.getVolume());
        assertEquals("yfinance", bar.getSource());
    }

    // ENT-007: @Column 注解检查（与 V1 Schema 对齐：highPrice/lowPrice 可空）
    @Test
    @DisplayName("ENT-007: @Column 注解 — highPrice/lowPrice 可空")
    void columnAnnotationsForRequiredFields() throws Exception {
        Field highField = StockDailyBar.class.getDeclaredField("highPrice");
        Column highCol = highField.getAnnotation(Column.class);
        assertNotNull(highCol, "highPrice should have @Column");
        assertTrue(highCol.nullable(), "highPrice.nullable should be true");

        Field lowField = StockDailyBar.class.getDeclaredField("lowPrice");
        Column lowCol = lowField.getAnnotation(Column.class);
        assertNotNull(lowCol, "lowPrice should have @Column");
        assertTrue(lowCol.nullable(), "lowPrice.nullable should be true");
    }

    // ENT-008: @Column 注解 — 可选字段 nullable = true
    @Test
    @DisplayName("ENT-008: @Column 注解 — changePercent/afterHours 可空")
    void columnAnnotationsForNullableFields() throws Exception {
        Field cpField = StockDailyBar.class.getDeclaredField("changePercent");
        Column cpCol = cpField.getAnnotation(Column.class);
        assertNotNull(cpCol, "changePercent should have @Column");
        assertTrue(cpCol.nullable(), "changePercent.nullable should be true");

        Field ahField = StockDailyBar.class.getDeclaredField("afterHours");
        Column ahCol = ahField.getAnnotation(Column.class);
        assertNotNull(ahCol, "afterHours should have @Column");
        assertTrue(ahCol.nullable(), "afterHours.nullable should be true");
    }
}
