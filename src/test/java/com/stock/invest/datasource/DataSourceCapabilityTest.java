package com.stock.invest.datasource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * UT-08 ~ UT-09: DataSourceCapability 枚举测试
 */
class DataSourceCapabilityTest {

    @Test @DisplayName("UT-08: enum has exactly 2 values")
    void enumValues() {
        DataSourceCapability[] values = DataSourceCapability.values();
        assertEquals(2, values.length, "应恰好有 STOCK_QUOTE 和 TRADING_CALENDAR");
    }

    @Test @DisplayName("UT-09: STOCK_QUOTE ordinal 0")
    void stockQuoteOrdinal() {
        assertEquals(0, DataSourceCapability.STOCK_QUOTE.ordinal());
        assertEquals("STOCK_QUOTE", DataSourceCapability.STOCK_QUOTE.name());
    }

    @Test @DisplayName("UT-09b: TRADING_CALENDAR ordinal 1")
    void tradingCalendarOrdinal() {
        assertEquals(1, DataSourceCapability.TRADING_CALENDAR.ordinal());
        assertEquals("TRADING_CALENDAR", DataSourceCapability.TRADING_CALENDAR.name());
    }
}
