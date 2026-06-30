package com.stock.invest.service.impl;

import com.stock.invest.model.KLineIterator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class PatternEvaluateServiceImplTest {

    private final PatternEvaluateServiceImpl service = new PatternEvaluateServiceImpl();

    /**
     * Helper: build a KLineIterator list oldest-first with given volumes.
     * All other fields (open/high/low/close/time) are filled with dummy values.
     */
    private List<KLineIterator> klineBars(long... volumes) {
        List<KLineIterator> list = new ArrayList<>();
        for (int i = 0; i < volumes.length; i++) {
            KLineIterator bar = new KLineIterator(
                    "TEST",           // symbol
                    1700000000000L + i * 86400000L, // time (one day apart)
                    100.0 + i,        // open
                    105.0 + i,        // high
                    95.0 + i,         // low
                    102.0 + i,        // close
                    volumes[i],       // volume
                    0.0,              // amount
                    0.0,              // changePercent
                    0.0,              // afterHours
                    0.0               // afterHoursChangePercent
            );
            list.add(bar);
        }
        return list;
    }

    @Test
    @DisplayName("zero-volume bars are excluded - pattern returns false")
    void zeroVolumeBars_areExcluded() {
        // Arrange: 7 bars, oldest first; the 5th bar has volume=0, so the window (last 7)
        // will include that zero-volume bar → should return false
        List<KLineIterator> bars = klineBars(
                1000L, 2000L, 3000L, 4000L,   // first 4 bars increasing
                0L,                            // 5th bar: zero volume
                6000L, 7000L                   // 6th, 7th bars increasing
        );

        // Act: all 7 bars are consumed (windowDays = 7)
        boolean result = service.matchesIncreasingVolumePatternFromKLine(bars, 7);

        // Assert: should be false because 5th bar has volume=0
        assertFalse(result, "zero-volume bar in window should cause pattern to return false");
    }

    @Test
    @DisplayName("all-positive volumes with increasing trend returns true")
    void allPositiveVolumes_increasingTrend_returnsTrue() {
        // Arrange: 7 strictly increasing volumes
        List<KLineIterator> bars = klineBars(1000L, 2000L, 3000L, 4000L, 5000L, 6000L, 7000L);

        // Act
        boolean result = service.matchesIncreasingVolumePatternFromKLine(bars, 7);

        // Assert
        assertTrue(result, "strictly increasing volumes should match");
    }

    @Test
    @DisplayName("zero-volume at first position still returns false when it lands in the window")
    void zeroVolumeAtFirstPosition_returnsFalse() {
        // Arrange: last bar in window has volume=0
        List<KLineIterator> bars = klineBars(1000L, 2000L, 3000L, 4000L, 5000L, 6000L, 0L);

        // Act: last bar in the window has volume=0
        boolean result = service.matchesIncreasingVolumePatternFromKLine(bars, 7);

        // Assert
        assertFalse(result, "last bar with volume=0 should return false");
    }

    @Test
    @DisplayName("insufficient bars returns false")
    void insufficientBars_returnsFalse() {
        // Arrange: only 3 bars, need 7
        List<KLineIterator> bars = klineBars(1000L, 2000L, 3000L);

        // Act
        boolean result = service.matchesIncreasingVolumePatternFromKLine(bars, 7);

        // Assert
        assertFalse(result, "insufficient bars should return false");
    }

    @Test
    @DisplayName("null list returns false")
    void nullList_returnsFalse() {
        assertFalse(service.matchesIncreasingVolumePatternFromKLine(null, 7));
    }

    @Test
    @DisplayName("PE-008: matchesIncreasingVolumePattern StockDailyBar version - increasing volumes")
    void test_matchesIncreasingVolumePattern_stockDailyBar_increasing() {
        // createContinuousBars 参数: symbol, startDate, count, basePrice, baseVolume, source
        // 每天 volume 递增: baseVolume + idx*1000 → 末位递增，pattern 应匹配
        java.util.List<com.stock.invest.entity.StockDailyBar> bars =
            com.stock.invest.support.TestDataFactory.createContinuousBars(
                "AAPL", java.time.LocalDate.of(2026, 6, 22), 7, 100.0, 100000L, "test");
        assertTrue(service.matchesIncreasingVolumePattern(bars, 7));
    }

    @Test
    @DisplayName("PE-009: matchesIncreasingVolumePattern StockDailyBar version - non-increasing")
    void test_matchesIncreasingVolumePattern_stockDailyBar_nonIncreasing() {
        // 手动构造递减 volume: 前大后小，pattern 不应匹配
        java.util.List<com.stock.invest.entity.StockDailyBar> bars = new java.util.ArrayList<>();
        String sym = "AAPL";
        java.time.LocalDate base = java.time.LocalDate.of(2026, 6, 22);
        for (int i = 0; i < 7; i++) {
            bars.add(com.stock.invest.support.TestDataFactory.createStockDailyBar(
                sym, base.plusDays(i), 100.0 + i, 105.0 + i, (7 - i) * 100000L, "test"));
        }
        assertFalse(service.matchesIncreasingVolumePattern(bars, 7));
    }

    @Test
    @DisplayName("PE-010: multi-window days=2 matches")
    void test_multiWindow_2days() {
        java.util.List<com.stock.invest.entity.StockDailyBar> bars =
            com.stock.invest.support.TestDataFactory.createContinuousBars(
                "AAPL", java.time.LocalDate.of(2026, 6, 22), 2, 100.0, 100000L, "test");
        assertTrue(service.matchesIncreasingVolumePattern(bars, 2));
    }

    @Test
    @DisplayName("PE-011: multi-window days=3 matches")
    void test_multiWindow_3days() {
        java.util.List<com.stock.invest.entity.StockDailyBar> bars =
            com.stock.invest.support.TestDataFactory.createContinuousBars(
                "AAPL", java.time.LocalDate.of(2026, 6, 22), 3, 100.0, 100000L, "test");
        assertTrue(service.matchesIncreasingVolumePattern(bars, 3));
    }

    @Test
    @DisplayName("PE-012: multi-window days=5 volume spike (last day 5x avg)")
    void test_multiWindow_5days_spike() {
        // 手动构造：前 4 天 volume=10000（avg=10000），第 5 天=60000（6x > 5x threshold）
        java.util.List<com.stock.invest.entity.StockDailyBar> bars = new java.util.ArrayList<>();
        String sym = "AAPL";
        java.time.LocalDate base = java.time.LocalDate.of(2026, 6, 22);
        for (int i = 0; i < 5; i++) {
            long vol = (i < 4) ? 10000L : 60000L;
            bars.add(com.stock.invest.support.TestDataFactory.createStockDailyBar(
                sym, base.plusDays(i), 100.0 + i, 105.0 + i, vol, "test"));
        }
        assertTrue(service.matchesVolumeSpikePattern(bars, 5));
    }

}
