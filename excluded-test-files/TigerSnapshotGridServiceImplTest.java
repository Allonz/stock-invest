package com.stock.invest.service.impl;

import com.stock.invest.config.JpaAuditingConfig;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.enums.dto.SnapshotGridViewDto;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.StockInfo;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.StockService;
import com.tigerbrokers.stock.openapi.client.struct.enums.Market;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, TigerSnapshotGridServiceImplTest.TestStockServiceConfig.class})
class TigerSnapshotGridServiceImplTest {

    @Autowired
    private StockDailyBarRepository repository;

    private TigerSnapshotGridServiceImpl service;

    @TestConfiguration
    static class TestStockServiceConfig {
        @Bean
        StockService stockService() {
            return new StockService() {
                @Override public String getDailyKLineData(String symbol) { return ""; }
                @Override public KLineData getDailyKLineDataAsObject(String symbol) { return null; }
                @Override public StockInfo getStockInfo(String symbol) { return null; }
                @Override public List<String> getStockList() { return List.of(); }
                @Override public KLineData getDailyKLine(String symbol) { return null; }
                @Override public List<KLineData> getBatchKlineData(List<String> symbols, Period period, int count) { return List.of(); }
                @Override public List<KLineData> getBatchKline(List<String> symbols, String period, int count) { return List.of(); }
                @Override public List<String> scanStocks(Market market, int limit, Double minPrice, Double maxPrice) { return List.of(); }
                @Override public List<String> scanStocks(String market, int limit, String minPrice, String maxPrice) { return List.of(); }
                @Override public Map<String, Object> scanLowPriceStocksWithVolumePattern() { return Map.of(); }
                @Override public Map<String, Object> scanLowPriceStocksWithVolumePattern(int limit) { return Map.of(); }
            };
        }
    }

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        service = new TigerSnapshotGridServiceImpl(repository);
    }

    private StockDailyBar createBar(String symbol, String stockName, LocalDate tradeDate, double closePrice, long volume) {
        StockDailyBar bar = new StockDailyBar();
        bar.setSymbol(symbol);
        bar.setStockName(stockName);
        bar.setTradeDate(tradeDate);
        bar.setOpenPrice(closePrice);
        bar.setClosePrice(closePrice);
        bar.setVolume(volume);
        bar.setSource("tiger_snap");
        return repository.save(bar);
    }

    @Test
    void buildGrid_multipleSymbolsMultipleDates_shouldReturnCorrectGrid() {
        createBar("MSFT", "Microsoft", LocalDate.of(2026, 5, 10), 200.0, 1000L);
        createBar("MSFT", "Microsoft", LocalDate.of(2026, 5, 11), 205.0, 1100L);
        createBar("MSFT", "Microsoft", LocalDate.of(2026, 5, 12), 210.0, 1200L);
        createBar("AAPL", "Apple", LocalDate.of(2026, 5, 10), 150.0, 2000L);
        createBar("AAPL", "Apple", LocalDate.of(2026, 5, 11), 155.0, 2100L);
        createBar("AAPL", "Apple", LocalDate.of(2026, 5, 12), 160.0, 2200L);
        createBar("ZOO", "Zoo Co", LocalDate.of(2026, 5, 10), 50.0, 500L);
        createBar("ZOO", "Zoo Co", LocalDate.of(2026, 5, 12), 55.0, 600L);

        SnapshotGridViewDto grid = service.buildGrid(10);

        assertEquals(3, grid.dateHeaders().size());
        assertEquals("2026-05-10", grid.dateHeaders().get(0));
        assertEquals("2026-05-12", grid.dateHeaders().get(2));

        assertEquals(3, grid.rows().size());
        assertEquals("AAPL", grid.rows().get(0).symbol());
        assertEquals("MSFT", grid.rows().get(1).symbol());
        assertEquals("ZOO", grid.rows().get(2).symbol());

        assertEquals(3, grid.rows().get(0).cells().size());
        assertTrue(grid.rows().get(0).cells().get(0).contains("150.0000"));
        assertTrue(grid.rows().get(0).cells().get(0).contains("2,000"));
        assertTrue(grid.rows().get(0).cells().get(1).contains("155.0000"));
        assertTrue(grid.rows().get(0).cells().get(2).contains("160.0000"));

        assertEquals("—", grid.rows().get(2).cells().get(1));
    }

    @Test
    void buildGrid_emptyDatabase_shouldReturnEmptyGrid() {
        SnapshotGridViewDto grid = service.buildGrid(10);
        assertTrue(grid.isEmpty());
        assertTrue(grid.dateHeaders().isEmpty());
        assertTrue(grid.rows().isEmpty());
    }

    @Test
    void buildGrid_sparseData_shouldMarkMissingDatesWithDash() {
        createBar("AAPL", "Apple", LocalDate.of(2026, 6, 1), 150.0, 100L);
        createBar("AAPL", "Apple", LocalDate.of(2026, 6, 3), 160.0, 200L);
        createBar("MSFT", "Microsoft", LocalDate.of(2026, 6, 2), 200.0, 300L);

        SnapshotGridViewDto grid = service.buildGrid(10);

        assertEquals(3, grid.dateHeaders().size());

        assertEquals("AAPL", grid.rows().get(0).symbol());
        assertTrue(grid.rows().get(0).cells().get(0).contains("150.0000"));
        assertEquals("—", grid.rows().get(0).cells().get(1));
        assertTrue(grid.rows().get(0).cells().get(2).contains("160.0000"));

        assertEquals("MSFT", grid.rows().get(1).symbol());
        assertEquals("—", grid.rows().get(1).cells().get(0));
        assertTrue(grid.rows().get(1).cells().get(1).contains("200.0000"));
        assertEquals("—", grid.rows().get(1).cells().get(2));
    }

    @Test
    void buildGrid_singleSymbolSingleDate_shouldReturnOneRowOneColumn() {
        createBar("AAPL", "Apple", LocalDate.of(2026, 7, 1), 150.0, 500L);

        SnapshotGridViewDto grid = service.buildGrid(10);

        assertEquals(1, grid.dateHeaders().size());
        assertEquals("2026-07-01", grid.dateHeaders().get(0));
        assertEquals(1, grid.rows().size());
        assertEquals("AAPL", grid.rows().get(0).symbol());

        String cell = grid.rows().get(0).cells().get(0);
        assertTrue(cell.contains("150.0000"));
        assertTrue(cell.contains("500"));
    }

    @Test
    void buildGrid_nameResolution_shouldUseLatestBarName() {
        createBar("AAPL", "Apple Inc.", LocalDate.of(2026, 8, 1), 150.0, 100L);
        createBar("AAPL", "Apple Inc. (Updated)", LocalDate.of(2026, 8, 2), 155.0, 200L);

        SnapshotGridViewDto grid = service.buildGrid(10);

        assertEquals("Apple Inc. (Updated)", grid.rows().get(0).stockName());
    }

    @Test
    void buildGrid_nameResolution_noName_shouldReturnEmpty() {
        createBar("AAPL", null, LocalDate.of(2026, 9, 1), 150.0, 100L);

        SnapshotGridViewDto grid = service.buildGrid(10);

        assertEquals("", grid.rows().get(0).stockName());
    }

    @Test
    void buildGrid_withDateCap_shouldLimitColumns() {
        for (int i = 0; i < 50; i++) {
            createBar("AAPL", "Apple", LocalDate.of(2026, 1, 1).plusDays(i), 150.0 + i, 100L);
        }

        SnapshotGridViewDto grid = service.buildGrid(5);

        assertEquals(5, grid.dateHeaders().size());
        // Indices 45-49: Jan 1 + 45 = Feb 15, Jan 1 + 49 = Feb 19
        assertEquals("2026-02-15", grid.dateHeaders().get(0));
        assertEquals("2026-02-19", grid.dateHeaders().get(4));
    }
}
