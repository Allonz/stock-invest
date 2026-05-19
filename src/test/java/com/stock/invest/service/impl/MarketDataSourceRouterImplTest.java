package com.stock.invest.service.impl;

import com.stock.invest.client.TigerOpenPythonBridge;
import com.stock.invest.client.TwelveDataRestClient;
import com.stock.invest.client.TiingoRestClient;
import com.stock.invest.client.YahooFinanceRestClient;
import com.stock.invest.datasource.DataSourceAvailabilityChecker;
import com.stock.invest.model.KLineData;
import com.tigerbrokers.stock.openapi.client.struct.enums.Market;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TC-ROUTER-001~003: MarketDataSourceRouterImpl 集成测试
 * 验证路由层只使用可用数据源。
 */
@ActiveProfiles("test")
@SpringBootTest
class MarketDataSourceRouterImplTest {

    @Autowired
    private MarketDataSourceRouterImpl router;

    @MockBean
    private DataSourceAvailabilityChecker availabilityChecker;

    @MockBean
    private TwelveDataRestClient twelveDataRestClient;

    @MockBean
    private YahooFinanceRestClient yahooFinanceRestClient;

    @MockBean
    private TiingoRestClient tiingoRestClient;

    @MockBean
    private TigerOpenPythonBridge tigerOpenPythonBridge;

    @MockBean
    private TigerStockServiceImpl tigerStockService;

    @Test
    @DisplayName("TC-ROUTER-001: 部分源不可用，只使用可用源")
    void partialSourcesUnavailable_usesOnlyAvailableSources() throws Exception {
        when(availabilityChecker.getAvailableSourceNames())
                .thenReturn(Arrays.asList("yfinance", "twelvedata"));

        when(yahooFinanceRestClient.fetchMostActiveSymbols(anyInt()))
                .thenReturn(Arrays.asList("AAPL", "MSFT"));
        when(yahooFinanceRestClient.fetchRegularMarketPrices(anyList()))
                .thenReturn(Map.of("AAPL", 150.0, "MSFT", 300.0));

        List<String> candidates = router.loadCandidates(5, 0, 500);

        assertNotNull(candidates);
        verify(yahooFinanceRestClient, atLeastOnce()).fetchMostActiveSymbols(anyInt());
        verify(twelveDataRestClient, never()).listUsStockSymbols(anyInt());
        verify(tiingoRestClient, never()).listUsSymbolsByPriceRange(anyInt(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("TC-ROUTER-002: 所有源可用，按优先级遍历至成功")
    void allSourcesAvailable_traversesInPriority() throws Exception {
        when(availabilityChecker.getAvailableSourceNames())
                .thenReturn(Arrays.asList("tiger", "tigeropen", "yfinance", "twelvedata", "tiingo"));

        // Tiger returns empty (no candidates found)
        when(tigerStockService.scanStocks(any(Market.class), anyInt(), anyDouble(), anyDouble()))
                .thenReturn(Collections.emptyList());
        // TigerOpen has no credentials (falls through)
        when(tigerOpenPythonBridge.hasCredentials()).thenReturn(false);
        // YFinance returns empty
        when(yahooFinanceRestClient.fetchMostActiveSymbols(anyInt()))
                .thenReturn(Collections.emptyList());
        // TwelveData succeeds
        when(twelveDataRestClient.listUsStockSymbols(anyInt()))
                .thenReturn(Arrays.asList("AAPL"));
        when(twelveDataRestClient.fetchLastClose("AAPL"))
                .thenReturn(150.0);

        List<String> candidates = router.loadCandidates(5, 0, 500);

        assertNotNull(candidates);
        assertFalse(candidates.isEmpty());
        verify(tigerStockService, atLeastOnce()).scanStocks(any(Market.class), anyInt(), anyDouble(), anyDouble());
        verify(tigerOpenPythonBridge, atLeastOnce()).hasCredentials();
        verify(yahooFinanceRestClient, atLeastOnce()).fetchMostActiveSymbols(anyInt());
        verify(twelveDataRestClient, atLeastOnce()).listUsStockSymbols(anyInt());
        verify(tiingoRestClient, never()).listUsSymbolsByPriceRange(anyInt(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("TC-ROUTER-003: 所有源不可用，返回空不抛异常")
    void allSourcesUnavailable_returnsEmpty() {
        when(availabilityChecker.getAvailableSourceNames())
                .thenReturn(Collections.emptyList());

        List<String> candidates = router.loadCandidates(5, 0, 500);
        assertNotNull(candidates);
        assertTrue(candidates.isEmpty());

        Optional<KLineData> result = router.fetchDailyBars("AAPL", null, 5);
        assertNotNull(result);
        assertFalse(result.isPresent());
    }
}
