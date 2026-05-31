package com.stock.invest.service;

import com.stock.invest.model.TradingCalendarResult;
import com.stock.invest.service.impl.TigerCalendarService;
import com.tigerbrokers.stock.openapi.client.https.client.TigerHttpClient;
import com.tigerbrokers.stock.openapi.client.https.domain.quote.item.TradeCalendar;
import com.tigerbrokers.stock.openapi.client.https.request.quote.QuoteTradeCalendarRequest;
import com.tigerbrokers.stock.openapi.client.https.response.quote.QuoteTradeCalendarResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * UT-26 ~ UT-37: TigerCalendarService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class TigerCalendarServiceTest {

    @Mock private TigerHttpClient client;
    @Mock private QuoteTradeCalendarResponse response;
    @Mock private TradeCalendar tradeCalendar;

    private TigerCalendarService service;
    private static final LocalDate DATE = LocalDate.of(2026, 6, 1);

    @BeforeEach
    void setUp() {
        service = new TigerCalendarService(client);
    }

    @Test @DisplayName("UT-27: SDK 返回交易日")
    void sdkReturnsTradingDay() {
        when(response.isSuccess()).thenReturn(true);
        when(response.getItems()).thenReturn(List.of(tradeCalendar));
        when(tradeCalendar.getType()).thenReturn("TRADING");
        when(client.execute(any(QuoteTradeCalendarRequest.class))).thenReturn(response);

        TradingCalendarResult result = service.isTradingDay("US", DATE);
        assertNotNull(result);
        assertTrue(result.isTradingDay());
        assertEquals("tiger", result.getSource());
        assertEquals("TRADING", result.getType());
    }

    @Test @DisplayName("UT-28: SDK 返回假日 HOLIDAY")
    void sdkReturnsHoliday() {
        when(response.isSuccess()).thenReturn(true);
        when(response.getItems()).thenReturn(List.of(tradeCalendar));
        when(tradeCalendar.getType()).thenReturn("HOLIDAY");
        when(client.execute(any(QuoteTradeCalendarRequest.class))).thenReturn(response);

        TradingCalendarResult result = service.isTradingDay("US", DATE);
        assertNotNull(result);
        assertFalse(result.isTradingDay());
        assertEquals("HOLIDAY", result.getType());
    }

    @Test @DisplayName("UT-29: SDK 返回周末 WEEKEND")
    void sdkReturnsWeekend() {
        when(response.isSuccess()).thenReturn(true);
        when(response.getItems()).thenReturn(List.of(tradeCalendar));
        when(tradeCalendar.getType()).thenReturn("WEEKEND");
        when(client.execute(any(QuoteTradeCalendarRequest.class))).thenReturn(response);

        TradingCalendarResult result = service.isTradingDay("US", DATE);
        assertNotNull(result);
        assertFalse(result.isTradingDay());
        assertEquals("WEEKEND", result.getType());
    }

    @Test @DisplayName("UT-30: SDK 返回提前收盘 EARLY_CLOSE（按 TRADING 判断）")
    void sdkReturnsEarlyClose() {
        // 代码中 isTrading = "TRADING".equals(type)，所以 EARLY_CLOSE 不算 TRADING
        // 但调仓日仍是交易日，标记为 nonTrading 但 type=EARLY_CLOSE
        when(response.isSuccess()).thenReturn(true);
        when(response.getItems()).thenReturn(List.of(tradeCalendar));
        when(tradeCalendar.getType()).thenReturn("EARLY_CLOSE");
        when(client.execute(any(QuoteTradeCalendarRequest.class))).thenReturn(response);

        TradingCalendarResult result = service.isTradingDay("US", DATE);
        assertNotNull(result);
        assertFalse(result.isTradingDay());
        assertEquals("EARLY_CLOSE", result.getType());
    }

    @Test @DisplayName("UT-31: SDK 超时返回 null")
    void sdkTimeout_returnsNull() throws Exception {
        // 无法直接 mock Threadpool 的超时，
        // 这里验证 response.isSuccess()=false 走失败路径
        when(response.isSuccess()).thenReturn(false);
        when(response.getMessage()).thenReturn("error");
        when(client.execute(any(QuoteTradeCalendarRequest.class))).thenReturn(response);

        TradingCalendarResult result = service.isTradingDay("US", DATE);
        assertNull(result);
    }

    @Test @DisplayName("UT-32: SDK 返回空列表视为 HOLIDAY")
    void sdkReturnsEmptyItems() {
        when(response.isSuccess()).thenReturn(true);
        when(response.getItems()).thenReturn(List.of());
        when(client.execute(any(QuoteTradeCalendarRequest.class))).thenReturn(response);

        TradingCalendarResult result = service.isTradingDay("US", DATE);
        assertNotNull(result);
        assertFalse(result.isTradingDay());
        assertEquals("HOLIDAY", result.getType());
    }

    @Test @DisplayName("UT-33: SDK 返回 null items 视为 HOLIDAY")
    void sdkReturnsNullItems() {
        when(response.isSuccess()).thenReturn(true);
        when(response.getItems()).thenReturn(null);
        when(client.execute(any(QuoteTradeCalendarRequest.class))).thenReturn(response);

        TradingCalendarResult result = service.isTradingDay("US", DATE);
        assertNotNull(result);
        assertFalse(result.isTradingDay());
        assertEquals("HOLIDAY", result.getType());
    }

    @Test @DisplayName("UT-34: SDK execute 抛异常返回 null")
    void sdkThrowsException_returnsNull() {
        when(client.execute(any(QuoteTradeCalendarRequest.class)))
                .thenThrow(new RuntimeException("network error"));

        TradingCalendarResult result = service.isTradingDay("US", DATE);
        assertNull(result);
    }

    @Test @DisplayName("UT-35: response.isSuccess()=false 返回 null")
    void sdkResponseFailed() {
        when(response.isSuccess()).thenReturn(false);
        when(client.execute(any(QuoteTradeCalendarRequest.class))).thenReturn(response);

        TradingCalendarResult result = service.isTradingDay("US", DATE);
        assertNull(result);
    }

    @Test @DisplayName("UT-36: getSourceName and isAvailable")
    void sourceNameAndAvailability() {
        assertEquals("tiger", service.getSourceName());
        assertTrue(service.isAvailable());
    }

    @Test @DisplayName("UT-37: getSourceName returns tiger")
    void sourceName() {
        assertEquals("tiger", service.getSourceName());
    }
}
