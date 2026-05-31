package com.stock.invest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.client.TigerOpenPythonBridge;
import com.stock.invest.model.TradingCalendarResult;
import com.stock.invest.service.impl.TigerOpenCalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * UT-38 ~ UT-44: TigerOpenCalendarService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class TigerOpenCalendarServiceTest {

    @Mock private TigerOpenPythonBridge bridge;

    private TigerOpenCalendarService service;
    private ObjectMapper objectMapper;
    private static final LocalDate DATE = LocalDate.of(2026, 6, 1);

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        service = new TigerOpenCalendarService(objectMapper, bridge);
    }

    @Test @DisplayName("UT-38: credentials 不可用时返回 null")
    void notAvailable_returnsNull() {
        when(bridge.hasCredentials()).thenReturn(false);
        assertFalse(service.isAvailable());
        assertNull(service.isTradingDay("US", DATE));
    }

    @Test @DisplayName("UT-39: Python 返回交易日")
    void pythonReturnsTradingDay() throws Exception {
        when(bridge.hasCredentials()).thenReturn(true);
        when(bridge.executePythonScript(eq("calendar"), eq("US"), eq("2026-06-01")))
                .thenReturn("{\"tradingDay\":true,\"source\":\"tigeropen\",\"type\":\"TRADING\",\"date\":\"2026-06-01\"}");

        TradingCalendarResult result = service.isTradingDay("US", DATE);
        assertNotNull(result);
        assertTrue(result.isTradingDay());
        assertEquals("tigeropen", result.getSource());
        assertEquals("TRADING", result.getType());
    }

    @Test @DisplayName("UT-40: Python 返回非交易日")
    void pythonReturnsNonTradingDay() throws Exception {
        when(bridge.hasCredentials()).thenReturn(true);
        when(bridge.executePythonScript(eq("calendar"), eq("US"), eq("2026-07-04")))
                .thenReturn("{\"tradingDay\":false,\"source\":\"tigeropen\",\"type\":\"HOLIDAY\"}");

        TradingCalendarResult result = service.isTradingDay("US", LocalDate.of(2026, 7, 4));
        assertNotNull(result);
        assertFalse(result.isTradingDay());
        assertEquals("HOLIDAY", result.getType());
    }

    @Test @DisplayName("UT-41: Python 进程异常返回 null")
    void pythonThrowsException_returnsNull() throws Exception {
        when(bridge.hasCredentials()).thenReturn(true);
        when(bridge.executePythonScript(eq("calendar"), eq("US"), eq("2026-06-01")))
                .thenThrow(new RuntimeException("process crashed"));

        TradingCalendarResult result = service.isTradingDay("US", DATE);
        assertNull(result);
    }

    @Test @DisplayName("UT-42: Python 返回非法 JSON 返回 null")
    void pythonReturnsInvalidJson_returnsNull() throws Exception {
        when(bridge.hasCredentials()).thenReturn(true);
        when(bridge.executePythonScript(eq("calendar"), eq("US"), eq("2026-06-01")))
                .thenReturn("not json at all");

        TradingCalendarResult result = service.isTradingDay("US", DATE);
        assertNull(result);
    }

    @Test @DisplayName("UT-43: 超时返回 null")
    void timeout_returnsNull() {
        assertEquals("tigeropen", service.getSourceName());
    }

    @Test @DisplayName("UT-44: getSourceName returns tigeropen")
    void sourceName() {
        assertEquals("tigeropen", service.getSourceName());
    }
}
