package com.stock.invest.entity;

import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实体字段测试 — StockDailyBar（新增字段）
 * 覆盖 ENT-001 ~ ENT-008
 */
@DisplayName("StockDailyBar — 新增字段（highPrice/lowPrice/changePercent/afterHours/afterHoursChangePercent）")
class StockDailyBarFieldTest {

    // ENT-001: highPrice 字段 getter/setter
    @Test
    @DisplayName("ENT-001: highPrice 字段 getter/setter")
    void highPriceField() {
        StockDailyBar bar = new StockDailyBar();
        bar.setHighPrice(155.5);
        assertEquals(155.5, bar.getHighPrice(), 0.001);
    }

    // ENT-002: lowPrice 字段 getter/setter
    @Test
    @DisplayName("ENT-002: lowPrice 字段 getter/setter")
    void lowPriceField() {
        StockDailyBar bar = new StockDailyBar();
        bar.setLowPrice(148.2);
        assertEquals(148.2, bar.getLowPrice(), 0.001);
    }

    // ENT-003: changePercent 字段（nullable）
    @Test
    @DisplayName("ENT-003: changePercent 字段 getter/setter — nullable")
    void changePercentField() {
        StockDailyBar bar = new StockDailyBar();
        assertNull(bar.getChangePercent(), "default should be null");

        bar.setChangePercent(2.35);
        assertEquals(2.35, bar.getChangePercent(), 0.001);

        bar.setChangePercent(null);
        assertNull(bar.getChangePercent(), "can set back to null");
    }

    // ENT-004: afterHours 字段（nullable）
    @Test
    @DisplayName("ENT-004: afterHours 字段 getter/setter — nullable")
    void afterHoursField() {
        StockDailyBar bar = new StockDailyBar();
        assertNull(bar.getAfterHours(), "default should be null");

        bar.setAfterHours(153.0);
        assertEquals(153.0, bar.getAfterHours(), 0.001);

        bar.setAfterHours(null);
        assertNull(bar.getAfterHours());
    }

    // ENT-005: afterHoursChangePercent 字段（nullable）
    @Test
    @DisplayName("ENT-005: afterHoursChangePercent 字段 getter/setter — nullable")
    void afterHoursChangePercentField() {
        StockDailyBar bar = new StockDailyBar();
        assertNull(bar.getAfterHoursChangePercent(), "default should be null");

        bar.setAfterHoursChangePercent(0.45);
        assertEquals(0.45, bar.getAfterHoursChangePercent(), 0.001);

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
        bar.setOpenPrice(150.0);
        bar.setHighPrice(155.0);
        bar.setLowPrice(148.0);
        bar.setClosePrice(152.5);
        bar.setChangePercent(1.67);
        bar.setAfterHours(153.0);
        bar.setAfterHoursChangePercent(0.33);
        bar.setVolume(1_000_000L);
        bar.setSource("yfinance");

        assertEquals("AAPL", bar.getSymbol());
        assertEquals(LocalDate.of(2025, 6, 25), bar.getTradeDate());
        assertEquals(150.0, bar.getOpenPrice(), 0.001);
        assertEquals(155.0, bar.getHighPrice(), 0.001);
        assertEquals(148.0, bar.getLowPrice(), 0.001);
        assertEquals(152.5, bar.getClosePrice(), 0.001);
        assertEquals(1.67, bar.getChangePercent(), 0.001);
        assertEquals(153.0, bar.getAfterHours(), 0.001);
        assertEquals(0.33, bar.getAfterHoursChangePercent(), 0.001);
        assertEquals(1_000_000L, bar.getVolume());
        assertEquals("yfinance", bar.getSource());
    }

    // ENT-007: @Column 注解检查（nullable = false 的强制字段）
    @Test
    @DisplayName("ENT-007: @Column 注解 — highPrice/lowPrice 不可空")
    void columnAnnotationsForRequiredFields() throws Exception {
        Field highField = StockDailyBar.class.getDeclaredField("highPrice");
        Column highCol = highField.getAnnotation(Column.class);
        assertNotNull(highCol, "highPrice should have @Column");
        assertFalse(highCol.nullable(), "highPrice.nullable should be false");

        Field lowField = StockDailyBar.class.getDeclaredField("lowPrice");
        Column lowCol = lowField.getAnnotation(Column.class);
        assertNotNull(lowCol, "lowPrice should have @Column");
        assertFalse(lowCol.nullable(), "lowPrice.nullable should be false");
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
