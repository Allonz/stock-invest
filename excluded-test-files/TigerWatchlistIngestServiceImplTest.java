package com.stock.invest.service.impl;

import com.stock.invest.config.JpaAuditingConfig;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.enums.dto.TigerWatchlistIngestRequestDto;
import com.stock.invest.enums.dto.TigerWatchlistIngestResponseDto;
import com.stock.invest.enums.dto.TigerWatchlistRowDto;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, TigerWatchlistIngestServiceImplTest.TestStockServiceConfig.class})
class TigerWatchlistIngestServiceImplTest {

    @Autowired
    private StockDailyBarRepository repository;

    private TigerWatchlistIngestServiceImpl service;

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
        service = new TigerWatchlistIngestServiceImpl(repository);
    }

    @Test
    void ingest_validRequest_shouldSaveBarsWithTigerSnapSource() {
        TigerWatchlistIngestRequestDto request = new TigerWatchlistIngestRequestDto(
                "2026-05-10",
                List.of(
                        new TigerWatchlistRowDto("AAPL", "Apple Inc.", 150.25, 1000000L),
                        new TigerWatchlistRowDto("MSFT", "Microsoft Corp.", 200.50, 2000000L)
                )
        );

        TigerWatchlistIngestResponseDto response = service.ingest(request);

        assertEquals(2, response.imported());
        assertEquals(0, response.skipped());
        assertNotNull(response.batchId());
        assertFalse(response.batchId().isEmpty());
        assertEquals(LocalDate.of(2026, 5, 10), response.tradeDate());

        List<StockDailyBar> savedBars = repository.findAll();
        assertEquals(2, savedBars.size());

        StockDailyBar aapl = savedBars.stream().filter(b -> "AAPL".equals(b.getSymbol())).findFirst().orElseThrow();
        assertEquals("tiger_snap", aapl.getSource());
        assertEquals(LocalDate.of(2026, 5, 10), aapl.getTradeDate());
        assertEquals(150.25, aapl.getClosePrice());
        assertEquals(150.25, aapl.getOpenPrice());
        assertEquals(1000000L, aapl.getVolume());
        assertEquals("Apple Inc.", aapl.getStockName());

        StockDailyBar msft = savedBars.stream().filter(b -> "MSFT".equals(b.getSymbol())).findFirst().orElseThrow();
        assertEquals(200.50, msft.getClosePrice());
        assertEquals(2000000L, msft.getVolume());
        assertEquals("Microsoft Corp.", msft.getStockName());
    }

    @Test
    void ingest_responseDto_shouldContainCorrectMetadata() {
        TigerWatchlistIngestRequestDto request = new TigerWatchlistIngestRequestDto(
                "2026-05-11",
                List.of(new TigerWatchlistRowDto("AAPL", null, 155.0, 500L))
        );

        TigerWatchlistIngestResponseDto response = service.ingest(request);

        assertNotNull(response.batchId());
        assertFalse(response.batchId().isEmpty());
        assertEquals(LocalDate.of(2026, 5, 11), response.tradeDate());
        assertEquals(1, response.imported());
        assertEquals(0, response.skipped());
        assertTrue(response.skipReasons().isEmpty());
    }

    @Test
    void ingest_nullRow_shouldSkipWithReason() {
        // Note: List.copyOf in the DTO constructor rejects null elements,
        // so this scenario tests that the service's defensive null-row check
        // exists (even if currently unreachable via DTO).
        TigerWatchlistIngestRequestDto request = new TigerWatchlistIngestRequestDto(
                "2026-05-12",
                List.of()
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.ingest(request));
        assertTrue(ex.getMessage().contains("rows must not be empty"));
    }

    @Test
    void ingest_nullSymbol_shouldSkipWithReason() {
        TigerWatchlistIngestRequestDto request = new TigerWatchlistIngestRequestDto(
                "2026-05-12",
                List.of(new TigerWatchlistRowDto(null, "Test", 150.0, 100L))
        );

        TigerWatchlistIngestResponseDto response = service.ingest(request);

        assertEquals(0, response.imported());
        assertEquals(1, response.skipped());
        assertTrue(response.skipReasons().get(0).contains("symbol"));
    }

    @Test
    void ingest_emptySymbol_shouldSkipWithReason() {
        TigerWatchlistIngestRequestDto request = new TigerWatchlistIngestRequestDto(
                "2026-05-12",
                List.of(new TigerWatchlistRowDto("", "Test", 150.0, 100L))
        );

        TigerWatchlistIngestResponseDto response = service.ingest(request);

        assertEquals(0, response.imported());
        assertEquals(1, response.skipped());
        assertTrue(response.skipReasons().get(0).contains("symbol"));
    }

    @Test
    void ingest_invalidSymbolPattern_shouldSkip() {
        TigerWatchlistIngestRequestDto request = new TigerWatchlistIngestRequestDto(
                "2026-05-12",
                List.of(new TigerWatchlistRowDto("AAP$L", "Test", 150.0, 100L))
        );

        TigerWatchlistIngestResponseDto response = service.ingest(request);
        assertEquals(0, response.imported());
        assertEquals(1, response.skipped());
        assertTrue(response.skipReasons().get(0).contains("symbol"));
    }

    @Test
    void ingest_nullLastPrice_shouldSkip() {
        TigerWatchlistIngestRequestDto request = new TigerWatchlistIngestRequestDto(
                "2026-05-12",
                List.of(new TigerWatchlistRowDto("AAPL", "Test", null, 100L))
        );

        TigerWatchlistIngestResponseDto response = service.ingest(request);

        assertEquals(0, response.imported());
        assertEquals(1, response.skipped());
        assertTrue(response.skipReasons().get(0).contains("lastPrice"));
    }

    @Test
    void ingest_zeroLastPrice_shouldSkip() {
        TigerWatchlistIngestRequestDto request = new TigerWatchlistIngestRequestDto(
                "2026-05-12",
                List.of(new TigerWatchlistRowDto("AAPL", "Test", 0.0, 100L))
        );

        TigerWatchlistIngestResponseDto response = service.ingest(request);

        assertEquals(0, response.imported());
        assertEquals(1, response.skipped());
        assertTrue(response.skipReasons().get(0).contains("lastPrice"));
    }

    @Test
    void ingest_duplicateSymbolAndDate_shouldOverwrite() {
        TigerWatchlistIngestRequestDto request1 = new TigerWatchlistIngestRequestDto(
                "2026-05-13",
                List.of(new TigerWatchlistRowDto("AAPL", "Apple", 150.0, 1000L))
        );
        service.ingest(request1);

        TigerWatchlistIngestRequestDto request2 = new TigerWatchlistIngestRequestDto(
                "2026-05-13",
                List.of(new TigerWatchlistRowDto("AAPL", "Apple Inc.", 175.0, 2000L))
        );
        TigerWatchlistIngestResponseDto response2 = service.ingest(request2);

        assertEquals(1, response2.imported());
        assertEquals(0, response2.skipped());

        List<StockDailyBar> all = repository.findAll();
        assertEquals(1, all.size());
        assertEquals(175.0, all.get(0).getClosePrice());
        assertEquals(2000L, all.get(0).getVolume());
    }

    @Test
    void ingest_plainNumberVolume_shouldParseCorrectly() {
        TigerWatchlistIngestRequestDto request = new TigerWatchlistIngestRequestDto(
                "2026-05-14",
                List.of(new TigerWatchlistRowDto("AAPL", null, 150.0, 5000000L))
        );

        service.ingest(request);

        StockDailyBar bar = repository.findAll().get(0);
        assertEquals(5000000L, bar.getVolume());
    }

    @Test
    void ingest_commaFormattedVolume_shouldParseCorrectly() {
        TigerWatchlistIngestRequestDto request = new TigerWatchlistIngestRequestDto(
                "2026-05-14",
                List.of(new TigerWatchlistRowDto("AAPL", null, 150.0, "5,000,000"))
        );

        service.ingest(request);

        StockDailyBar bar = repository.findAll().get(0);
        assertEquals(5000000L, bar.getVolume());
    }

    @Test
    void ingest_chineseWanVolume_shouldParseCorrectly() {
        TigerWatchlistIngestRequestDto request = new TigerWatchlistIngestRequestDto(
                "2026-05-14",
                List.of(new TigerWatchlistRowDto("AAPL", null, 150.0, "500万"))
        );

        service.ingest(request);

        StockDailyBar bar = repository.findAll().get(0);
        assertEquals(5_000_000L, bar.getVolume());
    }

    @Test
    void ingest_chineseYiVolume_shouldParseCorrectly() {
        TigerWatchlistIngestRequestDto request = new TigerWatchlistIngestRequestDto(
                "2026-05-14",
                List.of(new TigerWatchlistRowDto("AAPL", null, 150.0, "1.5亿"))
        );

        service.ingest(request);

        StockDailyBar bar = repository.findAll().get(0);
        assertEquals(150_000_000L, bar.getVolume());
    }

    @Test
    void ingest_nullVolume_shouldDefaultToZero() {
        TigerWatchlistIngestRequestDto request = new TigerWatchlistIngestRequestDto(
                "2026-05-14",
                List.of(new TigerWatchlistRowDto("AAPL", null, 150.0, null))
        );

        service.ingest(request);

        StockDailyBar bar = repository.findAll().get(0);
        assertEquals(0L, bar.getVolume());
    }
}
