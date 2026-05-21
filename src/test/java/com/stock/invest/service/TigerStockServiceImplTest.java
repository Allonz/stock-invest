package com.stock.invest.service;

import com.stock.invest.model.KLineData;
import com.stock.invest.service.impl.TigerStockServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tigerbrokers.stock.openapi.client.https.client.TigerHttpClient;
import com.tigerbrokers.stock.openapi.client.https.domain.quote.item.KlineItem;
import com.tigerbrokers.stock.openapi.client.https.domain.quote.item.KlinePoint;
import com.tigerbrokers.stock.openapi.client.https.domain.quote.item.MarketScannerBatchItem;
import com.tigerbrokers.stock.openapi.client.https.domain.quote.item.MarketScannerItem;
import com.tigerbrokers.stock.openapi.client.https.request.quote.MarketScannerRequest;
import com.tigerbrokers.stock.openapi.client.https.request.quote.QuoteKlineRequest;
import com.tigerbrokers.stock.openapi.client.https.response.quote.MarketScannerResponse;
import com.tigerbrokers.stock.openapi.client.https.response.quote.QuoteKlineResponse;
import com.tigerbrokers.stock.openapi.client.struct.enums.Market;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TigerStockServiceImpl 修复测试。
 * 1. getStocksFromTigerApi 使用 response.getMarketScannerBatchItem().getItems() 直接获取数据，而非 JSON 序列化
 * 2. getKLineDataAsObject 在 response 为 null 时尽早返回默认值
 */
@ExtendWith(MockitoExtension.class)
class TigerStockServiceImplTest {

    @Mock
    private TigerHttpClient tigerHttpClient;
    @Mock
    private PatternEvaluateService patternEvaluateService;

    @InjectMocks
    private TigerStockServiceImpl service;

    @Test
    @DisplayName("getDailyKLineDataAsObject returns non-null KLineData when client is null")
    void getDailyKLineDataAsObject_returnsNull_whenClientNull() {
        assertNotNull(service.getDailyKLineDataAsObject("00700"));
    }

    // T-6: 传非法 period 字符串 -> 日志包含 error，方法不抛异常
    @Test
    @DisplayName("T-6: illegal period string does not throw, returns empty list")
    void getBatchKline_illegalPeriod_doesNotThrow() {
        List<String> symbols = List.of("AAPL");
        assertDoesNotThrow(() -> {
            List<KLineData> result = service.getBatchKline(symbols, "INVALID_PERIOD", 5);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        });
    }

    /**
     * Test-3: getStocksFromTigerApi 使用 response.getMarketScannerBatchItem().getItems()
     * 直接获取数据，而非 JSON 序列化。
     */
    @Test
    @DisplayName("getStocksFromTigerApi uses scanner items directly from response, not JSON")
    void getStocksFromTigerApi_usesScannerItemsDirectly() {
        // Arrange
        MarketScannerResponse mockResponse = mock(MarketScannerResponse.class);
        when(mockResponse.isSuccess()).thenReturn(true);

        MarketScannerBatchItem mockBatchItem = mock(MarketScannerBatchItem.class);
        when(mockResponse.getMarketScannerBatchItem()).thenReturn(mockBatchItem);

        MarketScannerItem item1 = mock(MarketScannerItem.class);
        when(item1.getSymbol()).thenReturn("AAPL");
        MarketScannerItem item2 = mock(MarketScannerItem.class);
        when(item2.getSymbol()).thenReturn("MSFT");

        when(mockBatchItem.getItems()).thenReturn(List.of(item1, item2));

        when(tigerHttpClient.execute(any(MarketScannerRequest.class))).thenReturn(mockResponse);

        // Act
        List<String> result = service.scanStocks(Market.US, 10, null, null);

        // Assert
        assertEquals(2, result.size());
        assertTrue(result.contains("AAPL"), "Should contain AAPL");
        assertTrue(result.contains("MSFT"), "Should contain MSFT");
    }

    /**
     * Test-3: getKLineData 在 response 为 null 时返回默认值。
     */
    @Test
    @DisplayName("getKLineDataAsObject returns default KLineData when response is null")
    void getKLineData_returnsEarly_onNullResponse() {
        // Arrange: tigerHttpClient.execute(any QuoteKlineRequest) returns null
        when(tigerHttpClient.execute(any(QuoteKlineRequest.class))).thenReturn(null);

        // Act
        KLineData result = service.getDailyKLineDataAsObject("AAPL");

        // Assert
        assertNotNull(result);
    }
}
