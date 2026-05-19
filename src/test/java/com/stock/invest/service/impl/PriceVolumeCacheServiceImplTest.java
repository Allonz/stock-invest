package com.stock.invest.service.impl;

import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.model.KLineData;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.MarketDataSourceRouter;
import com.stock.invest.service.PriceVolumeCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("PriceVolumeCacheServiceImpl Integration Tests")
public class PriceVolumeCacheServiceImplTest {

    @Autowired
    private PriceVolumeCacheService priceVolumeCacheService;

    @Autowired
    private StockDailyBarRepository stockDailyBarRepository;

    @Autowired
    private CacheManager cacheManager;

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
        stockDailyBarRepository.deleteAll();
        Objects.requireNonNull(cacheManager.getCache("dailyBars")).clear();
    }

    private StockDailyBar createBar(String symbol, LocalDate date, double open, double close, long volume, String source) {
        StockDailyBar bar = new StockDailyBar();
        bar.setSymbol(symbol);
        bar.setTradeDate(date);
        bar.setOpenPrice(open);
        bar.setClosePrice(close);
        bar.setVolume(volume);
        bar.setSource(source);
        return bar;
    }

    private void saveBars(List<StockDailyBar> bars) {
        stockDailyBarRepository.saveAll(bars);
    }

    @Test
    @DisplayName("getLatestBars should return requested number of bars ordered by trade date")
    void getLatestBars_shouldReturnRequestedNumberOfBarsOrderedByDate() {
        String symbol = "AAPL";
        List<StockDailyBar> bars = List.of(
                createBar(symbol, LocalDate.of(2025, 6, 1), 150.0, 155.0, 100000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 2), 155.0, 158.0, 120000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 3), 158.0, 157.0, 130000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 4), 157.0, 160.0, 140000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 5), 160.0, 162.0, 150000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 6), 162.0, 161.0, 160000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 7), 161.0, 165.0, 170000L, "tiger_snap")
        );
        saveBars(bars);

        List<StockDailyBar> result = priceVolumeCacheService.getLatestBars(symbol, 7);

        assertThat(result).hasSize(7);
        assertThat(result).isSortedAccordingTo((a, b) -> a.getTradeDate().compareTo(b.getTradeDate()));
        assertThat(result.get(0).getTradeDate()).isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(result.get(6).getTradeDate()).isEqualTo(LocalDate.of(2025, 6, 7));
    }

    @Test
    @DisplayName("getLatestBars should return all available bars when fewer than requested window")
    void getLatestBars_shouldReturnAllAvailableBarsWhenFewerThanRequestedWindow() {
        String symbol = "MSFT";
        List<StockDailyBar> bars = List.of(
                createBar(symbol, LocalDate.of(2025, 6, 1), 300.0, 305.0, 50000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 2), 305.0, 308.0, 55000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 3), 308.0, 310.0, 60000L, "tiger_snap")
        );
        saveBars(bars);

        List<StockDailyBar> result = priceVolumeCacheService.getLatestBars(symbol, 5);

        assertThat(result).hasSize(3);
        assertThat(result).isSortedAccordingTo((a, b) -> a.getTradeDate().compareTo(b.getTradeDate()));
    }

    @Test
    @DisplayName("getLatestBars should return only the specified symbol's bars when multiple symbols exist")
    void getLatestBars_shouldReturnOnlySpecifiedSymbolsBars() {
        String symbol = "AAPL";
        saveBars(List.of(
                createBar(symbol, LocalDate.of(2025, 6, 1), 150.0, 155.0, 100000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 2), 155.0, 158.0, 120000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 3), 158.0, 157.0, 130000L, "tiger_snap"),
                createBar("GOOGL", LocalDate.of(2025, 6, 1), 2000.0, 2100.0, 20000L, "tiger_snap"),
                createBar("GOOGL", LocalDate.of(2025, 6, 2), 2100.0, 2150.0, 22000L, "tiger_snap"),
                createBar("MSFT", LocalDate.of(2025, 6, 1), 300.0, 305.0, 50000L, "tiger_snap")
        ));

        List<StockDailyBar> result = priceVolumeCacheService.getLatestBars(symbol, 7);

        assertThat(result).hasSize(3);
        assertThat(result).allMatch(b -> symbol.equals(b.getSymbol()));
    }

    @Test
    @DisplayName("getLatestBars should return empty list for empty database")
    void getLatestBars_shouldReturnEmptyListForEmptyDatabase() {
        List<StockDailyBar> result = priceVolumeCacheService.getLatestBars("NONEXISTENT", 7);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getLatestBars should correctly order bars when there are gaps in dates")
    void getLatestBars_shouldCorrectlyOrderBarsWithDateGaps() {
        String symbol = "TSLA";
        List<StockDailyBar> bars = List.of(
                createBar(symbol, LocalDate.of(2025, 6, 1), 700.0, 710.0, 80000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 5), 720.0, 730.0, 90000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 3), 710.0, 715.0, 85000L, "tiger_snap")
        );
        saveBars(bars);

        List<StockDailyBar> result = priceVolumeCacheService.getLatestBars(symbol, 7);

        assertThat(result).hasSize(3);
        assertThat(result).isSortedAccordingTo((a, b) -> a.getTradeDate().compareTo(b.getTradeDate()));
        assertThat(result.get(0).getTradeDate()).isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(result.get(1).getTradeDate()).isEqualTo(LocalDate.of(2025, 6, 3));
        assertThat(result.get(2).getTradeDate()).isEqualTo(LocalDate.of(2025, 6, 5));
    }

    @Test
    @DisplayName("getLatestBars should sort bars by tradeDate ascending even if saved out of order")
    void getLatestBars_shouldSortBarsByTradeDateAscending() {
        String symbol = "AMZN";
        List<StockDailyBar> bars = List.of(
                createBar(symbol, LocalDate.of(2025, 6, 7), 3300.0, 3350.0, 40000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 5), 3200.0, 3250.0, 38000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 6), 3250.0, 3300.0, 39000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 1), 3000.0, 3100.0, 35000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 2), 3100.0, 3150.0, 36000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 3), 3150.0, 3180.0, 37000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 4), 3180.0, 3200.0, 37500L, "tiger_snap")
        );
        saveBars(bars);

        List<StockDailyBar> result = priceVolumeCacheService.getLatestBars(symbol, 7);

        assertThat(result).hasSize(7);
        assertThat(result).isSortedAccordingTo((a, b) -> a.getTradeDate().compareTo(b.getTradeDate()));
        assertThat(result.get(0).getTradeDate()).isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(result.get(6).getTradeDate()).isEqualTo(LocalDate.of(2025, 6, 7));
    }

    @Test
    @DisplayName("getLatestSevenBars should return up to 7 bars")
    void getLatestSevenBars_shouldReturnUpTo7Bars() {
        String symbol = "NVDA";
        List<StockDailyBar> bars = List.of(
                createBar(symbol, LocalDate.of(2025, 6, 1), 400.0, 410.0, 200000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 2), 410.0, 415.0, 210000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 3), 415.0, 420.0, 220000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 4), 420.0, 425.0, 230000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 5), 425.0, 430.0, 240000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 6), 430.0, 435.0, 250000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 7), 435.0, 440.0, 260000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 8), 440.0, 445.0, 270000L, "tiger_snap")
        );
        saveBars(bars);

        List<StockDailyBar> result = priceVolumeCacheService.getLatestSevenBars(symbol);

        assertThat(result).hasSize(7);
        assertThat(result).isSortedAccordingTo((a, b) -> a.getTradeDate().compareTo(b.getTradeDate()));
        assertThat(result.get(6).getTradeDate()).isEqualTo(LocalDate.of(2025, 6, 8));
    }

    @Test
    @DisplayName("getLatestSevenBars should return all available bars when fewer than 7")
    void getLatestSevenBars_shouldReturnAllAvailableBarsWhenFewerThan7() {
        String symbol = "AMD";
        List<StockDailyBar> bars = List.of(
                createBar(symbol, LocalDate.of(2025, 6, 1), 100.0, 105.0, 150000L, "tiger_snap"),
                createBar(symbol, LocalDate.of(2025, 6, 2), 105.0, 108.0, 160000L, "tiger_snap")
        );
        saveBars(bars);

        List<StockDailyBar> result = priceVolumeCacheService.getLatestSevenBars(symbol);

        assertThat(result).hasSize(2);
    }
}
