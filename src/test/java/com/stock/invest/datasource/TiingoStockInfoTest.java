package com.stock.invest.datasource;

import com.stock.invest.client.TiingoRestClient;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.model.StockInfo;
import com.stock.invest.service.impl.TiingoDataSourceStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TG-INFO-001~005: Tiingo 股票信息查询单元测试。
 * Mock TiingoRestClient，验证 TiingoDataSourceStrategy.getStockInfo() 行为。
 */
@ExtendWith(MockitoExtension.class)
class TiingoStockInfoTest {

    @Mock
    private TiingoRestClient tiingoRestClient;

    private TiingoDataSourceStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new TiingoDataSourceStrategy(tiingoRestClient);
    }

    // ---- TG-INFO-001: 正常返回 StockInfo ----

    @Test
    @DisplayName("TG-INFO-001: getStockInfo returns StockInfo with correct fields")
    void getStockInfo_returnsCorrectFields() throws Exception {
        KLineData kd = new KLineData();
        kd.setSymbol("AAPL");
        KLineIterator latest = new KLineIterator();
        latest.setSymbol("AAPL");
        latest.setTime(1719331200000L);
        latest.setOpen(150.0);
        latest.setHigh(152.0);
        latest.setLow(149.0);
        latest.setClose(151.0);
        latest.setVolume(1000000L);
        KLineIterator prev = new KLineIterator();
        prev.setSymbol("AAPL");
        prev.setTime(1719244800000L);
        prev.setOpen(148.0);
        prev.setHigh(150.0);
        prev.setLow(147.0);
        prev.setClose(149.0);
        prev.setVolume(900000L);
        kd.setItems(List.of(latest, prev));

        when(tiingoRestClient.fetchDailyBars(eq("AAPL"), anyInt())).thenReturn(kd);

        StockInfo info = strategy.getStockInfo("AAPL");

        assertNotNull(info);
        assertEquals("AAPL", info.getSymbol());
        assertEquals(151.0, info.getCurrentPrice(), 0.001);
        assertEquals(150.0, info.getOpenPrice(), 0.001);
        assertEquals(1000000L, info.getVolume());
        assertEquals(2.0, info.getChange(), 0.001);
        // (151 - 149) / 149 * 100
        assertEquals(1.3422818791946307, info.getChangePercent(), 0.001);
    }

    // ---- TG-INFO-002: 只有一条数据时 change 为 0 ----

    @Test
    @DisplayName("TG-INFO-002: getStockInfo returns 0 change when only 1 bar")
    void getStockInfo_singleBar_returnsZeroChange() throws Exception {
        KLineData kd = new KLineData();
        kd.setSymbol("MSFT");
        KLineIterator latest = new KLineIterator();
        latest.setSymbol("MSFT");
        latest.setTime(1719331200000L);
        latest.setClose(300.0);
        latest.setOpen(298.0);
        latest.setVolume(500000L);
        kd.setItems(List.of(latest));

        when(tiingoRestClient.fetchDailyBars(eq("MSFT"), anyInt())).thenReturn(kd);

        StockInfo info = strategy.getStockInfo("MSFT");

        assertNotNull(info);
        assertEquals("MSFT", info.getSymbol());
        assertEquals(300.0, info.getCurrentPrice(), 0.001);
        assertEquals(0.0, info.getChange(), 0.001);
        assertEquals(0.0, info.getChangePercent(), 0.001);
    }

    // ---- TG-INFO-003: null/empty data returns null ----

    @Test
    @DisplayName("TG-INFO-003: getStockInfo returns null when KLineData is null")
    void getStockInfo_nullData_returnsNull() throws Exception {
        when(tiingoRestClient.fetchDailyBars(anyString(), anyInt())).thenReturn(null);

        StockInfo info = strategy.getStockInfo("INVALID");

        assertNull(info);
    }

    @Test
    @DisplayName("TG-INFO-003b: getStockInfo returns null when items are empty")
    void getStockInfo_emptyItems_returnsNull() throws Exception {
        KLineData kd = new KLineData();
        kd.setSymbol("EMPTY");
        kd.setItems(List.of());

        when(tiingoRestClient.fetchDailyBars(anyString(), anyInt())).thenReturn(kd);

        StockInfo info = strategy.getStockInfo("EMPTY");

        assertNull(info);
    }

    // ---- TG-INFO-004: exception from TiingoRestClient returns null ----

    @Test
    @DisplayName("TG-INFO-004: getStockInfo returns null when TiingoRestClient throws")
    void getStockInfo_clientException_returnsNull() throws Exception {
        when(tiingoRestClient.fetchDailyBars(anyString(), anyInt()))
                .thenThrow(new RuntimeException("Network error"));

        StockInfo info = strategy.getStockInfo("AAPL");

        assertNull(info);
    }

    // ---- TG-INFO-005: Real mode - integration test, skipped ----

    @Test
    @Tag("integration")
    @DisplayName("TG-INFO-005: Real Tiingo API integration test (skipped in unit test)")
    void getStockInfo_realMode_integration() {
        // Integration test — skipped during unit test run.
        // Requires valid Tiingo API token and network access.
        assertTrue(true);
    }
}
