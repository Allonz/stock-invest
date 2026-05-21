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
                    0L                // amount
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
}
