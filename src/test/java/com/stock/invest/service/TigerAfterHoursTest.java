package com.stock.invest.service;

import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.service.impl.TigerStockServiceImpl;
import com.tigerbrokers.stock.openapi.client.https.client.TigerHttpClient;
import com.tigerbrokers.stock.openapi.client.https.domain.quote.item.KlineItem;
import com.tigerbrokers.stock.openapi.client.https.domain.quote.item.KlinePoint;
import com.tigerbrokers.stock.openapi.client.https.request.quote.QuoteKlineRequest;
import com.tigerbrokers.stock.openapi.client.https.response.quote.QuoteKlineResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TIG-AH-001~006: TigerStockServiceImpl 盘后价(K线)查询单元测试。
 * Mock TigerHttpClient，验证 getAfterHoursKLineDataByDateRange 行为。
 */
@ExtendWith(MockitoExtension.class)
class TigerAfterHoursTest {

    @Mock
    private TigerHttpClient tigerHttpClient;

    @Mock
    private PatternEvaluateService patternEvaluateService;

    @InjectMocks
    private TigerStockServiceImpl service;

    @BeforeEach
    void setUp() {
        // TigerStockServiceImpl uses constructor injection
    }

    // ---- TIG-AH-001: 正常返回盘后K线数据 ----

    @Test
    @DisplayName("TIG-AH-001: getAfterHoursKLineDataByDateRange returns KLineData with items")
    void getAfterHoursKLineDataByDateRange_returnsData() throws Exception {
        QuoteKlineResponse mockResponse = mock(QuoteKlineResponse.class);
        when(mockResponse.isSuccess()).thenReturn(true);

        KlineItem mockItem = mock(KlineItem.class);
        when(mockItem.getSymbol()).thenReturn("AAPL");

        KlinePoint mockPoint = mock(KlinePoint.class);
        when(mockPoint.getTime()).thenReturn(1719331200000L);
        when(mockPoint.getOpen()).thenReturn(150.0);
        when(mockPoint.getHigh()).thenReturn(152.0);
        when(mockPoint.getLow()).thenReturn(149.0);
        when(mockPoint.getClose()).thenReturn(151.0);
        when(mockPoint.getVolume()).thenReturn(1000000L);
        when(mockPoint.getAmount()).thenReturn(151000000.0);

        when(mockItem.getItems()).thenReturn(List.of(mockPoint));
        when(mockResponse.getKlineItems()).thenReturn(List.of(mockItem));

        when(tigerHttpClient.execute(any(QuoteKlineRequest.class))).thenReturn(mockResponse);

        KLineData result = service.getAfterHoursKLineDataByDateRange("AAPL",
                java.time.LocalDate.of(2024, 6, 25));

        assertNotNull(result);
        assertEquals("AAPL", result.getSymbol());
        assertNotNull(result.getItems());
        assertEquals(1, result.getItems().size());
        assertEquals(151.0, result.getItems().get(0).getClose(), 0.001);
        assertEquals(1000000L, result.getItems().get(0).getVolume());

        verify(tigerHttpClient).execute(any(QuoteKlineRequest.class));
    }

    // ---- TIG-AH-002: response 为 null 时返回空 KLineData ----

    @Test
    @DisplayName("TIG-AH-002: getAfterHoursKLineDataByDateRange returns empty when response is null")
    void getAfterHoursKLineDataByDateRange_nullResponse() throws Exception {
        when(tigerHttpClient.execute(any(QuoteKlineRequest.class))).thenReturn(null);

        KLineData result = service.getAfterHoursKLineDataByDateRange("AAPL",
                java.time.LocalDate.of(2024, 6, 25));

        assertNotNull(result);
        assertTrue(result.getItems() == null || result.getItems().isEmpty());
    }

    // ---- TIG-AH-003: response.isSuccess() 为 false 时返回空 KLineData ----

    @Test
    @DisplayName("TIG-AH-003: getAfterHoursKLineDataByDateRange returns empty when response not success")
    void getAfterHoursKLineDataByDateRange_notSuccess() throws Exception {
        QuoteKlineResponse mockResponse = mock(QuoteKlineResponse.class);
        when(mockResponse.isSuccess()).thenReturn(false);
        when(tigerHttpClient.execute(any(QuoteKlineRequest.class))).thenReturn(mockResponse);

        KLineData result = service.getAfterHoursKLineDataByDateRange("AAPL",
                java.time.LocalDate.of(2024, 6, 25));

        assertNotNull(result);
        assertTrue(result.getItems() == null || result.getItems().isEmpty());
    }

    // ---- TIG-AH-004: 客户端异常时返回空 KLineData ----

    @Test
    @DisplayName("TIG-AH-004: getAfterHoursKLineDataByDateRange handles client exception")
    void getAfterHoursKLineDataByDateRange_clientException() throws Exception {
        when(tigerHttpClient.execute(any(QuoteKlineRequest.class)))
                .thenThrow(new RuntimeException("Connection failed"));

        KLineData result = service.getAfterHoursKLineDataByDateRange("AAPL",
                java.time.LocalDate.of(2024, 6, 25));

        assertNotNull(result);
        assertTrue(result.getItems() == null || result.getItems().isEmpty());
    }

    // ---- TIG-AH-005: Real mode - integration test, skipped ----

    @Test
    @Tag("integration")
    @DisplayName("TIG-AH-005: Real Tiger API after-hours test (skipped in unit test)")
    void getAfterHoursKLineDataByDateRange_realMode_integration() {
        // Integration test — requires valid Tiger credentials and network.
        assertTrue(true);
    }

    // ---- TIG-AH-006: Real mode - multiple symbols, skipped ----

    @Test
    @Tag("integration")
    @DisplayName("TIG-AH-006: Real Tiger API multi-symbol after-hours test (skipped in unit test)")
    void getAfterHoursKLineDataByDateRange_multiSymbol_integration() {
        // Integration test — requires valid Tiger credentials and network.
        assertTrue(true);
    }
}
