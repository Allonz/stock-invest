package com.stock.invest.service;

import com.stock.invest.model.KLineData;
import com.stock.invest.service.impl.TigerStockServiceImpl;
import com.tigerbrokers.stock.openapi.client.https.client.TigerHttpClient;
import com.tigerbrokers.stock.openapi.client.https.domain.quote.item.MarketScannerBatchItem;
import com.tigerbrokers.stock.openapi.client.https.domain.quote.item.MarketScannerItem;
import com.tigerbrokers.stock.openapi.client.https.request.quote.MarketScannerRequest;
import com.tigerbrokers.stock.openapi.client.https.request.quote.QuoteKlineRequest;
import com.tigerbrokers.stock.openapi.client.https.response.quote.MarketScannerResponse;
import com.tigerbrokers.stock.openapi.client.struct.enums.Market;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    // ---- R2 P3-4: changePercent 精度统一（计算点 setScale(4, HALF_UP)） ----

    @Test
    @DisplayName("R2 P3-4: getStockInfo changePercent 精度统一 —— scale() <= 4（与 DB DECIMAL(12,4) 对齐）")
    void getStockInfo_changePercentScaleLimitedTo4() {
        com.tigerbrokers.stock.openapi.client.https.domain.quote.item.KlineItem item1 =
                new com.tigerbrokers.stock.openapi.client.https.domain.quote.item.KlineItem();
        item1.setSymbol("AAPL");
        com.tigerbrokers.stock.openapi.client.https.domain.quote.item.KlinePoint p1 =
                new com.tigerbrokers.stock.openapi.client.https.domain.quote.item.KlinePoint();
        p1.setTime(1719331200000L);
        p1.setOpen(150.0);
        p1.setHigh(152.0);
        p1.setLow(149.0);
        p1.setClose(151.0);
        p1.setVolume(1000000L);
        p1.setAmount(0.0);
        item1.setItems(List.of(p1));

        com.tigerbrokers.stock.openapi.client.https.domain.quote.item.KlineItem item2 =
                new com.tigerbrokers.stock.openapi.client.https.domain.quote.item.KlineItem();
        item2.setSymbol("AAPL");
        com.tigerbrokers.stock.openapi.client.https.domain.quote.item.KlinePoint p2 =
                new com.tigerbrokers.stock.openapi.client.https.domain.quote.item.KlinePoint();
        p2.setTime(1719244800000L);
        p2.setOpen(148.0);
        p2.setHigh(150.0);
        p2.setLow(147.0);
        p2.setClose(149.0);
        p2.setVolume(900000L);
        p2.setAmount(0.0);
        item2.setItems(List.of(p2));

        com.tigerbrokers.stock.openapi.client.https.response.quote.QuoteKlineResponse resp =
                mock(com.tigerbrokers.stock.openapi.client.https.response.quote.QuoteKlineResponse.class);
        when(resp.isSuccess()).thenReturn(true);
        when(resp.getKlineItems()).thenReturn(List.of(item1, item2));
        when(tigerHttpClient.execute(any(QuoteKlineRequest.class))).thenReturn(resp);

        com.stock.invest.model.StockInfo info = service.getStockInfo("AAPL");

        assertNotNull(info.getChangePercent());
        // (151 - 149) / 149 * 100 —— 计算点 setScale(4, HALF_UP) 后必须收敛到 4 位
        assertTrue(info.getChangePercent().scale() <= 4,
                "R2 P3-4: changePercent scale must be capped at 4, got scale="
                        + info.getChangePercent().scale() + " value=" + info.getChangePercent());
        assertEquals(0, new java.math.BigDecimal("1.3423").compareTo(info.getChangePercent()),
                "changePercent must round to 4 decimal places (1.3423)");
    }
}
