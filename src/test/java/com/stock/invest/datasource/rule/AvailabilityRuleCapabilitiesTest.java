package com.stock.invest.datasource.rule;

import com.stock.invest.config.TigerApiConfig;
import com.stock.invest.config.TiingoProperties;
import com.stock.invest.config.TwelveDataProperties;
import com.stock.invest.datasource.AvailabilityRule;
import com.stock.invest.datasource.DataSourceCapability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UT-10 ~ UT-15: 各 AvailabilityRule 的 capabilities() 返回值测试
 */
@ExtendWith(MockitoExtension.class)
class AvailabilityRuleCapabilitiesTest {

    @Mock private TwelveDataProperties twelveDataProps;
    @Mock private TiingoProperties tiingoProps;
    @Mock private TigerApiConfig tigerApiConfig;

    // UT-10 已删除：Tiger Java 数据源移除（2026-08-14）

    @Test @DisplayName("UT-11: TigerOpen supports STOCK_QUOTE + TRADING_CALENDAR")
    void tigerOpenCapabilities() {
        AvailabilityRule rule = new TigerOpenAvailabilityRule(tigerApiConfig);
        assertEquals(Set.of(DataSourceCapability.STOCK_QUOTE, DataSourceCapability.TRADING_CALENDAR),
                     rule.capabilities());
    }

    @Test @DisplayName("UT-12: TwelveData supports STOCK_QUOTE only")
    void twelveDataCapabilities() {
        TwelveDataAvailabilityRule rule = new TwelveDataAvailabilityRule(twelveDataProps);
        assertEquals(Set.of(DataSourceCapability.STOCK_QUOTE), rule.capabilities());
    }

    @Test @DisplayName("UT-13: Tiingo supports STOCK_QUOTE only")
    void tiingoCapabilities() {
        TiingoAvailabilityRule rule = new TiingoAvailabilityRule(tiingoProps);
        assertEquals(Set.of(DataSourceCapability.STOCK_QUOTE), rule.capabilities());
    }

    @Test @DisplayName("UT-14: YFinance supports STOCK_QUOTE only")
    void yfinanceCapabilities() {
        YFinanceAvailabilityRule rule = new YFinanceAvailabilityRule();
        assertEquals(Set.of(DataSourceCapability.STOCK_QUOTE), rule.capabilities());
    }
}
