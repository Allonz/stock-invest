package com.stock.invest.service;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.stock.invest.model.TradingCalendarResult;
import com.stock.invest.service.impl.AlpacaCalendarService;
import com.stock.invest.service.impl.TigerCalendarService;
import com.stock.invest.service.impl.TigerOpenCalendarService;
import com.stock.invest.service.impl.TradingCalendarFallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * FT-01 ~ FT-20: TradingCalendarFallback 编排 + 缓存测试
 *
 * Fallback chain: Tiger -> TigerOpen -> Alpaca -> DEFAULT
 */
@ExtendWith(MockitoExtension.class)
class TradingCalendarFallbackTest {

    @Mock private TigerCalendarService tiger;
    @Mock private TigerOpenCalendarService tigerOpen;
    @Mock private AlpacaCalendarService alpaca;

    private TradingCalendarFallback fallback;
    private static final LocalDate DATE = LocalDate.of(2026, 6, 1);
    private static final LocalDate DATE2 = LocalDate.of(2026, 6, 2);

    @BeforeEach
    void setUp() {
        lenient().when(tiger.getSourceName()).thenReturn("tiger");
        lenient().when(tigerOpen.getSourceName()).thenReturn("tigeropen");
        lenient().when(alpaca.getSourceName()).thenReturn("alpaca");
        fallback = new TradingCalendarFallback(tiger, tigerOpen, alpaca);
    }

    // ======= 3.1 正常 Fallback 链 =======

    @Test @DisplayName("FT-01: Tiger first succeeds")
    void tigerFirstSucceeds() {
        when(tiger.isAvailable()).thenReturn(true);
        when(tiger.isTradingDay("US", DATE))
                .thenReturn(TradingCalendarResult.trading("US", DATE, "tiger", "TRADING"));

        TradingCalendarResult r = fallback.isTradingDay("US", DATE);
        assertTrue(r.isTradingDay());
        assertEquals("tiger", r.getSource());
        verify(tiger).isTradingDay("US", DATE);
        verify(tigerOpen, never()).isTradingDay(any(), any());
        verify(alpaca, never()).isTradingDay(any(), any());
    }

    @Test @DisplayName("FT-02: Tiger fails -> TigerOpen succeeds")
    void tigerFails_tigerOpenSucceeds() {
        when(tiger.isAvailable()).thenReturn(true);
        when(tiger.isTradingDay("US", DATE)).thenReturn(null);
        when(tigerOpen.isAvailable()).thenReturn(true);
        when(tigerOpen.isTradingDay("US", DATE))
                .thenReturn(TradingCalendarResult.nonTrading("US", DATE, "tigeropen", "HOLIDAY"));

        TradingCalendarResult r = fallback.isTradingDay("US", DATE);
        assertFalse(r.isTradingDay());
        assertEquals("tigeropen", r.getSource());
        verify(tiger).isTradingDay("US", DATE);
        verify(tigerOpen).isTradingDay("US", DATE);
        verify(alpaca, never()).isTradingDay(any(), any());
    }

    @Test @DisplayName("FT-03: Tiger+TigerOpen fail -> Alpaca succeeds")
    void allTigerFail_alpacaSucceeds() {
        when(tiger.isAvailable()).thenReturn(true);
        when(tiger.isTradingDay("US", DATE)).thenReturn(null);
        when(tigerOpen.isAvailable()).thenReturn(true);
        when(tigerOpen.isTradingDay("US", DATE)).thenReturn(null);
        when(alpaca.isAvailable()).thenReturn(true);
        when(alpaca.isTradingDay("US", DATE))
                .thenReturn(TradingCalendarResult.trading("US", DATE, "alpaca", "TRADING"));

        TradingCalendarResult r = fallback.isTradingDay("US", DATE);
        assertTrue(r.isTradingDay());
        assertEquals("alpaca", r.getSource());
        verify(tiger).isTradingDay("US", DATE);
        verify(tigerOpen).isTradingDay("US", DATE);
        verify(alpaca).isTradingDay("US", DATE);
    }

    @Test @DisplayName("FT-04: Tiger timeout -> TigerOpen succeeds")
    void tigerTimeout_tigerOpenSucceeds() {
        when(tiger.isAvailable()).thenReturn(true);
        when(tiger.isTradingDay("US", DATE)).thenReturn(null);
        when(tigerOpen.isAvailable()).thenReturn(true);
        when(tigerOpen.isTradingDay("US", DATE))
                .thenReturn(TradingCalendarResult.trading("US", DATE, "tigeropen", "TRADING"));

        TradingCalendarResult r = fallback.isTradingDay("US", DATE);
        assertEquals("tigeropen", r.getSource());
        assertTrue(r.isTradingDay());
    }

    @Test @DisplayName("FT-05: Tiger unavailable -> TigerOpen succeeds")
    void tigerUnavailable_tigerOpenSucceeds() {
        when(tiger.isAvailable()).thenReturn(false);
        when(tigerOpen.isAvailable()).thenReturn(true);
        when(tigerOpen.isTradingDay("US", DATE))
                .thenReturn(TradingCalendarResult.trading("US", DATE, "tigeropen", "TRADING"));

        TradingCalendarResult r = fallback.isTradingDay("US", DATE);
        assertEquals("tigeropen", r.getSource());
        verify(tiger, never()).isTradingDay(any(), any());
        verify(tigerOpen).isTradingDay("US", DATE);
    }

    @Test @DisplayName("FT-06: Tiger+TigerOpen unavailable -> Alpaca succeeds")
    void allTigerUnavailable_alpacaSucceeds() {
        when(tiger.isAvailable()).thenReturn(false);
        when(tigerOpen.isAvailable()).thenReturn(false);
        when(alpaca.isAvailable()).thenReturn(true);
        when(alpaca.isTradingDay("US", DATE))
                .thenReturn(TradingCalendarResult.nonTrading("US", DATE, "alpaca", "HOLIDAY"));

        TradingCalendarResult r = fallback.isTradingDay("US", DATE);
        assertFalse(r.isTradingDay());
        assertEquals("alpaca", r.getSource());
        verify(tiger, never()).isTradingDay(any(), any());
        verify(tigerOpen, never()).isTradingDay(any(), any());
        verify(alpaca).isTradingDay("US", DATE);
    }

    // ======= 3.2 全不可用场景 (CORE) =======

    @Test @DisplayName("FT-07: ALL 3 sources unavailable -> DEFAULT true")
    void allSourcesUnavailable_returnsDefault() {
        when(tiger.isAvailable()).thenReturn(false);
        when(tigerOpen.isAvailable()).thenReturn(false);
        when(alpaca.isAvailable()).thenReturn(false);

        TradingCalendarResult r = fallback.isTradingDay("US", DATE);
        assertTrue(r.isTradingDay());
        assertEquals("none", r.getSource());
        assertEquals("DEFAULT", r.getType());
        assertTrue(r.getDetail().contains("均不可用"));
        verify(tiger, never()).isTradingDay(any(), any());
        verify(tigerOpen, never()).isTradingDay(any(), any());
        verify(alpaca, never()).isTradingDay(any(), any());
    }

    @Test @DisplayName("FT-08: ALL 3 timeout -> DEFAULT true")
    void allSourcesTimeout_returnsDefault() {
        when(tiger.isAvailable()).thenReturn(true);
        when(tiger.isTradingDay("US", DATE)).thenReturn(null);
        when(tigerOpen.isAvailable()).thenReturn(true);
        when(tigerOpen.isTradingDay("US", DATE)).thenReturn(null);
        when(alpaca.isAvailable()).thenReturn(true);
        when(alpaca.isTradingDay("US", DATE)).thenReturn(null);

        TradingCalendarResult r = fallback.isTradingDay("US", DATE);
        assertTrue(r.isTradingDay());
        assertEquals("none", r.getSource());
        assertEquals("DEFAULT", r.getType());
    }

    @Test @DisplayName("FT-09: ALL 3 throw exception -> DEFAULT true")
    void allSourcesThrow_returnsDefault() {
        when(tiger.isAvailable()).thenReturn(true);
        when(tiger.isTradingDay("US", DATE)).thenReturn(null);
        when(tigerOpen.isAvailable()).thenReturn(true);
        when(tigerOpen.isTradingDay("US", DATE)).thenReturn(null);
        when(alpaca.isAvailable()).thenReturn(true);
        when(alpaca.isTradingDay("US", DATE)).thenReturn(null);

        TradingCalendarResult r = fallback.isTradingDay("US", DATE);
        assertTrue(r.isTradingDay());
        assertEquals("none", r.getSource());
        assertEquals("DEFAULT", r.getType());
    }

    @Test @DisplayName("FT-10: Mixed (unavailable+timeout+error) -> DEFAULT true")
    void mixedFailures_returnsDefault() {
        when(tiger.isAvailable()).thenReturn(false);
        when(tigerOpen.isAvailable()).thenReturn(true);
        when(tigerOpen.isTradingDay("US", DATE)).thenReturn(null);
        when(alpaca.isAvailable()).thenReturn(true);
        when(alpaca.isTradingDay("US", DATE)).thenReturn(null);

        TradingCalendarResult r = fallback.isTradingDay("US", DATE);
        assertTrue(r.isTradingDay());
        assertEquals("none", r.getSource());
        assertEquals("DEFAULT", r.getType());
    }

    @Test @DisplayName("FT-11: Tiger unavailable + remaining return null -> DEFAULT true")
    void tigerUnavailable_othersReturnNull_returnsDefault() {
        when(tiger.isAvailable()).thenReturn(false);
        when(tigerOpen.isAvailable()).thenReturn(true);
        when(tigerOpen.isTradingDay("US", DATE)).thenReturn(null);
        when(alpaca.isAvailable()).thenReturn(true);
        when(alpaca.isTradingDay("US", DATE)).thenReturn(null);

        TradingCalendarResult r = fallback.isTradingDay("US", DATE);
        assertTrue(r.isTradingDay());
        assertEquals("none", r.getSource());
        assertEquals("DEFAULT", r.getType());
    }

    // ======= 3.3 缓存测试 =======

    @Test @DisplayName("FT-12: First query cache miss, calls source")
    void firstQuery_cacheMiss() {
        when(tiger.isAvailable()).thenReturn(true);
        when(tiger.isTradingDay("US", DATE))
                .thenReturn(TradingCalendarResult.trading("US", DATE, "tiger", "TRADING"));

        TradingCalendarResult r = fallback.isTradingDay("US", DATE);
        assertTrue(r.isTradingDay());
        verify(tiger).isTradingDay("US", DATE);
    }

    @Test @DisplayName("FT-13: Cache hit on second query, no source call")
    void secondQuery_cacheHit() {
        when(tiger.isAvailable()).thenReturn(true);
        when(tiger.isTradingDay("US", DATE))
                .thenReturn(TradingCalendarResult.trading("US", DATE, "tiger", "TRADING"));

        TradingCalendarResult first = fallback.isTradingDay("US", DATE);
        TradingCalendarResult second = fallback.isTradingDay("US", DATE);

        assertTrue(first.isTradingDay());
        assertTrue(second.isTradingDay());
        verify(tiger, times(1)).isTradingDay("US", DATE); // only once
        verify(tigerOpen, never()).isTradingDay(any(), any());
        verify(alpaca, never()).isTradingDay(any(), any());
    }

    @Test @DisplayName("FT-14: Different market different cache keys")
    void differentMarket_differentCache() {
        when(tiger.isAvailable()).thenReturn(true);
        when(tiger.isTradingDay(eq("US"), eq(DATE)))
                .thenReturn(TradingCalendarResult.trading("US", DATE, "tiger", "TRADING"));
        when(tiger.isTradingDay(eq("HK"), eq(DATE)))
                .thenReturn(TradingCalendarResult.nonTrading("HK", DATE, "tiger", "HOLIDAY"));

        TradingCalendarResult us = fallback.isTradingDay("US", DATE);
        TradingCalendarResult hk = fallback.isTradingDay("HK", DATE);

        assertTrue(us.isTradingDay());
        assertFalse(hk.isTradingDay());
        verify(tiger, times(2)).isTradingDay(any(), any());
    }

    @Test @DisplayName("FT-15: Different date different cache keys")
    void differentDate_differentCache() {
        when(tiger.isAvailable()).thenReturn(true);
        when(tiger.isTradingDay(eq("US"), eq(DATE)))
                .thenReturn(TradingCalendarResult.trading("US", DATE, "tiger", "TRADING"));
        when(tiger.isTradingDay(eq("US"), eq(DATE2)))
                .thenReturn(TradingCalendarResult.nonTrading("US", DATE2, "tiger", "HOLIDAY"));

        TradingCalendarResult d1 = fallback.isTradingDay("US", DATE);
        TradingCalendarResult d2 = fallback.isTradingDay("US", DATE2);

        assertTrue(d1.isTradingDay());
        assertFalse(d2.isTradingDay());
        verify(tiger, times(2)).isTradingDay(any(), any());
    }

    @Test @DisplayName("FT-16: Default result is also cached")
    void defaultResult_isCached() {
        when(tiger.isAvailable()).thenReturn(false);
        when(tigerOpen.isAvailable()).thenReturn(false);
        when(alpaca.isAvailable()).thenReturn(false);

        TradingCalendarResult r1 = fallback.isTradingDay("US", DATE);
        assertTrue(r1.isTradingDay());
        assertEquals("DEFAULT", r1.getType());

        TradingCalendarResult r2 = fallback.isTradingDay("US", DATE);
        assertTrue(r2.isTradingDay());
        assertEquals("DEFAULT", r2.getType());

        verify(tiger, never()).isTradingDay(any(), any());
        verify(tigerOpen, never()).isTradingDay(any(), any());
        verify(alpaca, never()).isTradingDay(any(), any());
    }


    @Test @DisplayName("FT-18: getCacheStats returns valid stats")
    void getCacheStats_works() {
        CacheStats stats = fallback.getCacheStats();
        assertNotNull(stats);
        assertEquals(0, stats.hitCount());
        assertEquals(0, stats.missCount());

        when(tiger.isAvailable()).thenReturn(true);
        LocalDate may29 = LocalDate.of(2026, 5, 29);
        when(tiger.isTradingDay("US", may29))
                .thenReturn(TradingCalendarResult.trading("US", may29, "tiger", "TRADING"));

        fallback.isTradingDay("US", may29);
        stats = fallback.getCacheStats();
        assertEquals(1, stats.missCount());
        assertEquals(1, stats.missCount());
        assertEquals("fallback", fallback.getSourceName());
    }

    @Test @DisplayName("FT-19: isAvailable true if any source available")
    void isAvailable_atLeastOne() {
        when(tiger.isAvailable()).thenReturn(false);
        when(tigerOpen.isAvailable()).thenReturn(false);
        when(alpaca.isAvailable()).thenReturn(true);
        assertTrue(fallback.isAvailable());

        when(alpaca.isAvailable()).thenReturn(false);
        assertFalse(fallback.isAvailable());
    }

    @Test @DisplayName("FT-20: getSourceName returns fallback")
    void getSourceName() {
        assertEquals("fallback", fallback.getSourceName());
    }
}
