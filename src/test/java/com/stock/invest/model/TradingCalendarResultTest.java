package com.stock.invest.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

/**
 * UT-01 ~ UT-07: TradingCalendarResult 模型测试
 */
class TradingCalendarResultTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 1);
    private static final String MARKET = "US";

    @Test @DisplayName("UT-01: trading() returns tradingDay=true")
    void trading_shouldReturnTradingDay() {
        TradingCalendarResult r = TradingCalendarResult.trading(MARKET, DATE, "tiger", "TRADING");
        assertTrue(r.isTradingDay());
        assertEquals("tiger", r.getSource());
        assertEquals("TRADING", r.getType());
        assertEquals(MARKET, r.getMarket());
        assertEquals(DATE, r.getDate());
        assertNull(r.getDetail());
    }

    @Test @DisplayName("UT-02: nonTrading() HOLIDAY")
    void nonTrading_holiday() {
        TradingCalendarResult r = TradingCalendarResult.nonTrading(MARKET, DATE, "tiger", "HOLIDAY");
        assertFalse(r.isTradingDay());
        assertEquals("HOLIDAY", r.getType());
    }

    @Test @DisplayName("UT-03: nonTrading() WEEKEND")
    void nonTrading_weekend() {
        TradingCalendarResult r = TradingCalendarResult.nonTrading(MARKET, DATE, "tiger", "WEEKEND");
        assertFalse(r.isTradingDay());
        assertEquals("WEEKEND", r.getType());
    }

    @Test @DisplayName("UT-04: trading() EARLY_CLOSE")
    void trading_earlyClose() {
        TradingCalendarResult r = TradingCalendarResult.trading(MARKET, DATE, "tiger", "EARLY_CLOSE");
        assertTrue(r.isTradingDay());
        assertEquals("EARLY_CLOSE", r.getType());
    }

    @Test @DisplayName("UT-05: defaultTradingDay() source=none type=DEFAULT")
    void defaultTradingDay_allSourcesUnavailable() {
        TradingCalendarResult r = TradingCalendarResult.defaultTradingDay(MARKET, DATE);
        assertTrue(r.isTradingDay());
        assertEquals("none", r.getSource());
        assertEquals("DEFAULT", r.getType());
        assertEquals("所有数据源均不可用，默认交易日", r.getDetail());
    }

    @Test @DisplayName("UT-06: constructor all fields")
    void constructor_allFields() {
        TradingCalendarResult r = new TradingCalendarResult(MARKET, DATE, true, "test", "TRADING", "detail");
        assertEquals(MARKET, r.getMarket());
        assertEquals(DATE, r.getDate());
        assertTrue(r.isTradingDay());
        assertEquals("test", r.getSource());
        assertEquals("detail", r.getDetail());
    }

    @Test @DisplayName("UT-07: defaultTradingDay preserves market")
    void defaultTradingDay_differentMarket() {
        TradingCalendarResult r = TradingCalendarResult.defaultTradingDay("HK", DATE);
        assertEquals("HK", r.getMarket());
        assertTrue(r.isTradingDay());
    }

    @Test @DisplayName("UT-07b: nonTrading with detail")
    void nonTrading_withDetail() {
        TradingCalendarResult r = TradingCalendarResult.nonTrading(MARKET, DATE, "tiger", "HOLIDAY", "休市");
        assertFalse(r.isTradingDay());
        assertEquals("休市", r.getDetail());
    }

    @Test @DisplayName("UT-07c: toString not throw")
    void toString_shouldNotThrow() {
        TradingCalendarResult r = TradingCalendarResult.trading(MARKET, DATE, "tiger", "TRADING");
        assertNotNull(r.toString());
        assertTrue(r.toString().contains("tradingDay=true"));
    }
}
