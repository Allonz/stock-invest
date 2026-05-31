package com.stock.invest.service;

import com.stock.invest.client.AlpacaRestClient;
import com.stock.invest.model.TradingCalendarResult;
import com.stock.invest.service.impl.AlpacaCalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * UT-45 ~ UT-51: AlpacaCalendarService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class AlpacaCalendarServiceTest {

    @Mock private AlpacaRestClient alpacaClient;

    private AlpacaCalendarService service;
    private static final LocalDate DATE = LocalDate.of(2026, 6, 1);

    @BeforeEach
    void setUp() {
        service = new AlpacaCalendarService(alpacaClient);
    }

    @Test @DisplayName("UT-45: credentials 不存在时不可用")
    void notAvailable_returnsNull() {
        when(alpacaClient.hasCredentials()).thenReturn(false);
        assertFalse(service.isAvailable());
        assertNull(service.isTradingDay("US", DATE));
    }

    @Test @DisplayName("UT-46: 非 US 市场返回 null")
    void nonUSMarket_returnsNull() throws IOException {
        when(alpacaClient.hasCredentials()).thenReturn(true);
        assertNull(service.isTradingDay("HK", DATE));
        verify(alpacaClient, never()).isTradingDay(any());
    }

    @Test @DisplayName("UT-47: CN 市场也返回 null")
    void cnMarket_returnsNull() {
        when(alpacaClient.hasCredentials()).thenReturn(true);
        assertNull(service.isTradingDay("CN", DATE));
    }

    @Test @DisplayName("UT-48: 美股交易日")
    void usTradingDay() throws IOException {
        when(alpacaClient.hasCredentials()).thenReturn(true);
        when(alpacaClient.isTradingDay(DATE)).thenReturn(true);

        TradingCalendarResult result = service.isTradingDay("US", DATE);
        assertNotNull(result);
        assertTrue(result.isTradingDay());
        assertEquals("alpaca", result.getSource());
        assertEquals("TRADING", result.getType());
    }

    @Test @DisplayName("UT-49: 美股非交易日")
    void usNonTradingDay() throws IOException {
        when(alpacaClient.hasCredentials()).thenReturn(true);
        when(alpacaClient.isTradingDay(DATE)).thenReturn(false);

        TradingCalendarResult result = service.isTradingDay("US", DATE);
        assertNotNull(result);
        assertFalse(result.isTradingDay());
        assertEquals("alpaca", result.getSource());
        assertEquals("HOLIDAY", result.getType());
    }

    @Test @DisplayName("UT-50: 网络异常返回 null")
    void ioException_returnsNull() throws IOException {
        when(alpacaClient.hasCredentials()).thenReturn(true);
        when(alpacaClient.isTradingDay(DATE)).thenThrow(new IOException("network error"));

        TradingCalendarResult result = service.isTradingDay("US", DATE);
        assertNull(result);
    }

    @Test @DisplayName("UT-51: isAvailable 与 hasCredentials 一致")
    void isAvailable_matchesCredentials() {
        when(alpacaClient.hasCredentials()).thenReturn(true);
        assertTrue(service.isAvailable());

        when(alpacaClient.hasCredentials()).thenReturn(false);
        assertFalse(service.isAvailable());
    }

    @Test @DisplayName("UT-51b: getSourceName returns alpaca")
    void sourceName() {
        assertEquals("alpaca", service.getSourceName());
    }
}
