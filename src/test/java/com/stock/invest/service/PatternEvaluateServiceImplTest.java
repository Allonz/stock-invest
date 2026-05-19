package com.stock.invest.service;

import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.service.impl.PatternEvaluateServiceImpl;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link PatternEvaluateServiceImpl}.
 * No mocking — calls the real implementation directly.
 */
public class PatternEvaluateServiceImplTest {

    private static List<StockDailyBar> bars(long... volumes) {
        List<StockDailyBar> out = new ArrayList<>();
        for (int i = 0; i < volumes.length; i++) {
            StockDailyBar bar = new StockDailyBar();
            bar.setSymbol("AAA");
            bar.setTradeDate(LocalDate.of(2026, 1, 1).plusDays(i));
            bar.setOpenPrice(0.1D);
            bar.setClosePrice(0.1D);
            bar.setVolume(volumes[i]);
            bar.setSource("test");
            out.add(bar);
        }
        return out;
    }

    private static List<KLineIterator> kLineBars(long... volumes) {
        List<KLineIterator> out = new ArrayList<>();
        for (int i = 0; i < volumes.length; i++) {
            KLineIterator bar = new KLineIterator();
            bar.setSymbol("AAA");
            bar.setTime((long) i);
            bar.setOpen(0.1D);
            bar.setHigh(0.2D);
            bar.setLow(0.05D);
            bar.setClose(0.1D);
            bar.setVolume(volumes[i]);
            bar.setAmount(1000.0D);
            out.add(bar);
        }
        return out;
    }

    // ── Default window (7 days) tests ──────────────────────────────────

    @Test
    public void strictlyIncreasingVolume_shouldMatch() {
        PatternEvaluateServiceImpl service = new PatternEvaluateServiceImpl();
        assertTrue(service.matchesIncreasingVolumePattern(bars(100, 200, 300, 400, 500, 600, 700)));
    }

    @Test
    public void allGoUp5bars_window5_shouldMatch() {
        PatternEvaluateServiceImpl service = new PatternEvaluateServiceImpl();
        assertTrue(service.matchesIncreasingVolumePattern(bars(100, 200, 300, 400, 500), 5));
    }

    @Test
    public void lastElementDrops_shouldNotMatch() {
        PatternEvaluateServiceImpl service = new PatternEvaluateServiceImpl();
        // avg(last 2)=595 < 590 → false
        assertFalse(service.matchesIncreasingVolumePattern(bars(100, 200, 300, 400, 500, 600, 590)));
    }

    @Test
    public void flatEndingEqualValues_shouldNotMatch() {
        PatternEvaluateServiceImpl service = new PatternEvaluateServiceImpl();
        // avg(last 3)=500 < avg(last 2)=500 → 500 < 500 is false
        assertFalse(service.matchesIncreasingVolumePattern(bars(100, 200, 300, 400, 500, 500, 500)));
    }

    @Test
    public void volumeDecreaseInMiddle_shouldNotMatch() {
        PatternEvaluateServiceImpl service = new PatternEvaluateServiceImpl();
        // avg(last 4)=450 < avg(last 3)=466.67 → yes
        // avg(last 3)=466.67 < avg(last 2)=550 → yes  
        // avg(last 2)=550 < last=600 → yes
        // But check all: 7: 342.86<400, 6: 400<457.14, 5: 457.14<525,
        // 4: 525<633.33, 3: 633.33<575? NO → false
        // Actually: [100, 50, 200, 300, 700, 600, 500]
        // avg last 3 of [100,50,200,300,700,600,500] = (700+600+500)/3 = 600
        // avg last 2 = (600+500)/2 = 550. 600 < 550? NO → false
        assertFalse(service.matchesIncreasingVolumePattern(bars(100, 50, 200, 300, 700, 600, 500)));
    }

    @Test
    public void decreasingVolume_shouldNotMatch() {
        PatternEvaluateServiceImpl service = new PatternEvaluateServiceImpl();
        assertFalse(service.matchesIncreasingVolumePattern(bars(700, 600, 500, 400, 300, 200, 100)));
    }

    // ── Custom window tests ────────────────────────────────────────────

    @Test
    public void exactlyMinimumWindow_3days_shouldMatch() {
        PatternEvaluateServiceImpl service = new PatternEvaluateServiceImpl();
        // 7 bars, window=3 means last 3 bars [30, 40, 50] → strictly increasing
        assertTrue(service.matchesIncreasingVolumePattern(bars(10, 20, 30, 40, 50, 60, 70), 3));
    }

    @Test
    public void exactlyMaximumWindow_7days_shouldMatch() {
        PatternEvaluateServiceImpl service = new PatternEvaluateServiceImpl();
        assertTrue(service.matchesIncreasingVolumePattern(bars(10, 20, 30, 40, 50, 60, 70), 7));
    }

    @Test
    public void windowBelowMin_shouldNotMatch() {
        PatternEvaluateServiceImpl service = new PatternEvaluateServiceImpl();
        assertFalse(service.matchesIncreasingVolumePattern(bars(10, 20, 30, 40, 50, 60, 70), 1));
    }

    @Test
    public void windowAboveMax_shouldNotMatch() {
        PatternEvaluateServiceImpl service = new PatternEvaluateServiceImpl();
        assertFalse(service.matchesIncreasingVolumePattern(bars(10, 20, 30, 40, 50, 60, 70, 80, 90), 8));
    }

    // ── Edge cases ─────────────────────────────────────────────────────

    @Test
    public void nullBars_shouldNotMatch() {
        PatternEvaluateServiceImpl service = new PatternEvaluateServiceImpl();
        assertFalse(service.matchesIncreasingVolumePattern(null));
    }

    @Test
    public void emptyBars_shouldNotMatch() {
        PatternEvaluateServiceImpl service = new PatternEvaluateServiceImpl();
        assertFalse(service.matchesIncreasingVolumePattern(Collections.emptyList()));
    }

    @Test
    public void singleBar_shouldNotMatch() {
        PatternEvaluateServiceImpl service = new PatternEvaluateServiceImpl();
        assertFalse(service.matchesIncreasingVolumePattern(bars(100)));
    }

    @Test
    public void twoBars_shouldNotMatch() {
        PatternEvaluateServiceImpl service = new PatternEvaluateServiceImpl();
        assertFalse(service.matchesIncreasingVolumePattern(bars(100, 200)));
    }

    @Test
    public void allZeroVolumes_shouldNotMatch() {
        PatternEvaluateServiceImpl service = new PatternEvaluateServiceImpl();
        assertFalse(service.matchesIncreasingVolumePattern(bars(0, 0, 0, 0, 0, 0, 0)));
    }

    @Test
    public void barWithNullVolume_shouldNotMatch() {
        PatternEvaluateServiceImpl service = new PatternEvaluateServiceImpl();
        List<StockDailyBar> bars = bars(100, 200, 300, 400, 500, 600, 700);
        bars.get(bars.size() - 1).setVolume(null);
        assertFalse(service.matchesIncreasingVolumePattern(bars));
    }

    // ── KLineIterator-based tests ──────────────────────────────────────

    @Test
    public void kLineStrictlyIncreasingVolume_shouldMatch() {
        PatternEvaluateServiceImpl service = new PatternEvaluateServiceImpl();
        assertTrue(service.matchesIncreasingVolumePatternFromKLine(kLineBars(100, 200, 300, 400, 500, 600, 700), 7));
    }

    @Test
    public void kLineDecreasingVolume_shouldNotMatch() {
        PatternEvaluateServiceImpl service = new PatternEvaluateServiceImpl();
        assertFalse(service.matchesIncreasingVolumePatternFromKLine(kLineBars(700, 600, 500, 400, 300, 200, 100), 7));
    }

    @Test
    public void kLineNotEnoughBars_shouldNotMatch() {
        PatternEvaluateServiceImpl service = new PatternEvaluateServiceImpl();
        assertFalse(service.matchesIncreasingVolumePatternFromKLine(kLineBars(100, 200), 3));
    }

    @Test
    public void kLineEmptyList_shouldNotMatch() {
        PatternEvaluateServiceImpl service = new PatternEvaluateServiceImpl();
        assertFalse(service.matchesIncreasingVolumePatternFromKLine(Collections.emptyList(), 3));
    }
}
