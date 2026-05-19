package com.stock.invest.service.impl;

import com.stock.invest.entity.ScreeningMatch;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.enums.dto.ScreenerRunResponseDto;
import com.stock.invest.model.KLineData;
import com.stock.invest.repository.ScreeningMatchRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.MarketDataSourceRouter;
import com.stock.invest.service.ScanOrchestratorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("ScanOrchestratorSnapshotOnly Integration Tests")
public class ScanOrchestratorSnapshotOnlyTest {

    @Autowired
    private ScanOrchestratorService scanOrchestratorService;

    @Autowired
    private StockDailyBarRepository stockDailyBarRepository;

    @Autowired
    private ScreeningMatchRepository screeningMatchRepository;

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        public MarketDataSourceRouter noopMarketDataSourceRouter() {
            return new MarketDataSourceRouter() {
                @Override
                public List<String> loadCandidates(int limit, double minPrice, double maxPrice) {
                    return List.of();
                }

                @Override
                public Optional<KLineData> fetchDailyBars(String symbol, String preferredSource, int barsCount) {
                    return Optional.empty();
                }

                @Override
                public Optional<KLineData> fetchLatestDailyBar(String symbol, String preferredSource) {
                    return Optional.empty();
                }
            };
        }
    }

    @AfterEach
    void cleanUp() {
        screeningMatchRepository.deleteAll();
        stockDailyBarRepository.deleteAll();
    }

    private StockDailyBar bar(String symbol, LocalDate date, double open, double close, long volume) {
        StockDailyBar b = new StockDailyBar();
        b.setSymbol(symbol);
        b.setTradeDate(date);
        b.setOpenPrice(open);
        b.setClosePrice(close);
        b.setVolume(volume);
        b.setSource("tiger_snap");
        return b;
    }

    @Test
    @DisplayName("should create match for symbol with increasing volume pattern")
    void shouldCreateMatchForIncreasingVolume() {
        String symbol = "TEST";
        LocalDate tradeDate = LocalDate.of(2025, 7, 15);
        stockDailyBarRepository.saveAll(List.of(
                bar(symbol, tradeDate.minusDays(6), 0.10, 0.10, 10000L),
                bar(symbol, tradeDate.minusDays(5), 0.11, 0.11, 15000L),
                bar(symbol, tradeDate.minusDays(4), 0.12, 0.12, 20000L),
                bar(symbol, tradeDate.minusDays(3), 0.13, 0.13, 30000L),
                bar(symbol, tradeDate.minusDays(2), 0.14, 0.14, 40000L),
                bar(symbol, tradeDate.minusDays(1), 0.15, 0.15, 50000L),
                bar(symbol, tradeDate, 0.16, 0.16, 60000L)
        ));

        ScreenerRunResponseDto response = scanOrchestratorService.runDailyScanFromSnapshotImport(tradeDate, 10, 7);

        assertThat(response.matchedStocks()).isPositive();
        assertThat(response.batchId()).isNotBlank();
        List<ScreeningMatch> matches = screeningMatchRepository.findByBatchIdOrderByIdAsc(response.batchId());
        assertThat(matches).isNotEmpty();
        assertThat(matches.get(0).getSymbol()).isEqualTo(symbol);
        assertThat(matches.get(0).getTradeDate()).isEqualTo(tradeDate);
        assertThat(matches.get(0).getBatchId()).isEqualTo(response.batchId());
    }

    @Test
    @DisplayName("should NOT create match for symbol with decreasing volume pattern")
    void shouldNotCreateMatchForDecreasingVolume() {
        String symbol = "DEC";
        LocalDate tradeDate = LocalDate.of(2025, 7, 15);
        stockDailyBarRepository.saveAll(List.of(
                bar(symbol, tradeDate.minusDays(6), 0.10, 0.10, 60000L),
                bar(symbol, tradeDate.minusDays(5), 0.11, 0.11, 50000L),
                bar(symbol, tradeDate.minusDays(4), 0.12, 0.12, 40000L),
                bar(symbol, tradeDate.minusDays(3), 0.13, 0.13, 30000L),
                bar(symbol, tradeDate.minusDays(2), 0.14, 0.14, 20000L),
                bar(symbol, tradeDate.minusDays(1), 0.15, 0.15, 15000L),
                bar(symbol, tradeDate, 0.16, 0.16, 10000L)
        ));

        ScreenerRunResponseDto response = scanOrchestratorService.runDailyScanFromSnapshotImport(tradeDate, 10, 7);

        assertThat(response.matchedStocks()).isZero();
        List<ScreeningMatch> matches = screeningMatchRepository.findByBatchIdOrderByIdAsc(response.batchId());
        assertThat(matches).isEmpty();
    }

    @Test
    @DisplayName("should create match with exactly minimum window of 3 bars with increasing volume")
    void shouldCreateMatchWithMinimumWindow() {
        String symbol = "MIN3";
        LocalDate tradeDate = LocalDate.of(2025, 8, 1);
        stockDailyBarRepository.saveAll(List.of(
                bar(symbol, tradeDate.minusDays(2), 0.10, 0.10, 10000L),
                bar(symbol, tradeDate.minusDays(1), 0.11, 0.11, 15000L),
                bar(symbol, tradeDate, 0.12, 0.12, 20000L)
        ));

        ScreenerRunResponseDto response = scanOrchestratorService.runDailyScanFromSnapshotImport(tradeDate, 10, 3);

        assertThat(response.matchedStocks()).isPositive();
        List<ScreeningMatch> matches = screeningMatchRepository.findByBatchIdOrderByIdAsc(response.batchId());
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getSymbol()).isEqualTo(symbol);
    }

    @Test
    @DisplayName("should handle empty stock_daily_bar gracefully with no matches")
    void shouldHandleEmptyDatabaseGracefully() {
        LocalDate tradeDate = LocalDate.of(2025, 9, 1);

        ScreenerRunResponseDto response = scanOrchestratorService.runDailyScanFromSnapshotImport(tradeDate, 10, 7);

        assertThat(response.totalCandidates()).isZero();
        assertThat(response.processedStocks()).isZero();
        assertThat(response.matchedStocks()).isZero();
        assertThat(response.batchId()).isNotBlank();
    }

    @Test
    @DisplayName("should not match when latest bar tradeDate differs from targetDate")
    void shouldNotMatchWhenLatestBarDiffersFromTargetDate() {
        String symbol = "MISMATCH";
        LocalDate targetTradeDate = LocalDate.of(2025, 10, 5);
        stockDailyBarRepository.saveAll(List.of(
                bar(symbol, LocalDate.of(2025, 10, 1), 0.10, 0.10, 10000L),
                bar(symbol, LocalDate.of(2025, 10, 2), 0.11, 0.11, 15000L),
                bar(symbol, LocalDate.of(2025, 10, 3), 0.12, 0.12, 20000L)
        ));

        ScreenerRunResponseDto response = scanOrchestratorService.runDailyScanFromSnapshotImport(targetTradeDate, 10, 3);

        assertThat(response.matchedStocks()).isZero();
    }

    @Test
    @DisplayName("should only match symbols that pass price filter")
    void shouldOnlyMatchSymbolsPassingPriceFilter() {
        String symbolInRange = "INRNG";
        String symbolOutOfRange = "OUTRN";
        LocalDate tradeDate = LocalDate.of(2025, 11, 1);
        List<StockDailyBar> inRange = List.of(
                bar(symbolInRange, tradeDate.minusDays(2), 0.10, 0.10, 10000L),
                bar(symbolInRange, tradeDate.minusDays(1), 0.11, 0.11, 15000L),
                bar(symbolInRange, tradeDate, 0.12, 0.15, 20000L)
        );
        List<StockDailyBar> outOfRange = List.of(
                bar(symbolOutOfRange, tradeDate.minusDays(2), 5.00, 5.00, 10000L),
                bar(symbolOutOfRange, tradeDate.minusDays(1), 5.50, 5.50, 15000L),
                bar(symbolOutOfRange, tradeDate, 5.50, 6.00, 20000L)
        );
        stockDailyBarRepository.saveAll(inRange);
        stockDailyBarRepository.saveAll(outOfRange);

        ScreenerRunResponseDto response = scanOrchestratorService.runDailyScanFromSnapshotImport(tradeDate, 10, 3);

        assertThat(response.matchedStocks()).isPositive();
        List<ScreeningMatch> matches = screeningMatchRepository.findByBatchIdOrderByIdAsc(response.batchId());
        assertThat(matches).allMatch(m -> symbolInRange.equals(m.getSymbol()));
    }
}
