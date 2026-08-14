package com.stock.invest.service;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.stock.invest.model.TradingCalendarResult;
import com.stock.invest.service.impl.AlpacaCalendarService;
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
 * TradingCalendarFallback 编排 + 缓存测试。
 *
 * 2026-08-14：Tiger Java 数据源已删除，fallback 链 = TigerOpen -> Alpaca -> DEFAULT。
 */
@ExtendWith(MockitoExtension.class)
class TradingCalendarFallbackTest {

    @Mock private TigerOpenCalendarService tigerOpen;
    @Mock private AlpacaCalendarService alpaca;

    private TradingCalendarFallback fallback;
    private static final LocalDate DATE = LocalDate.of(2026, 6, 1);
    private static final LocalDate DATE2 = LocalDate.of(2026, 6, 2);

    @BeforeEach
    void setUp() {
        lenient().when(tigerOpen.getSourceName()).thenReturn("tigeropen");
        lenient().when(alpaca.getSourceName()).thenReturn("alpaca");
        fallback = new TradingCalendarFallback(tigerOpen, alpaca);
    }

    // ======= 正常 Fallback 链 =======

    @Test @DisplayName("FT-01: TigerOpen first succeeds")
    void tigerOpenFirstSucceeds() {
        when(tigerOpen.isAvailable()).thenReturn(true);
        when(tigerOpen.isTradingDay("US", DATE))
                .thenReturn(TradingCalendarResult.trading("US", DATE, "tigeropen", "TRADING"));

        TradingCalendarResult r = fallback.isTradingDay("US", DATE);
        assertTrue(r.isTradingDay());
        assertEquals("tigeropen", r.getSource());
        verify(tigerOpen).isTradingDay("US", DATE);
        verify(alpaca, never()).isTradingDay(any(), any());
    }

    @Test @DisplayName("FT-02: TigerOpen fails -> Alpaca succeeds")
    void tigerOpenFails_alpacaSucceeds() {
        when(tigerOpen.isAvailable()).thenReturn(true);
        when(tigerOpen.isTradingDay("US", DATE)).thenReturn(null);
        when(alpaca.isAvailable()).thenReturn(true);
        when(alpaca.isTradingDay("US", DATE))
                .thenReturn(TradingCalendarResult.nonTrading("US", DATE, "alpaca", "HOLIDAY"));

        TradingCalendarResult r = fallback.isTradingDay("US", DATE);
        assertFalse(r.isTradingDay());
        assertEquals("alpaca", r.getSource());
        verify(tigerOpen).isTradingDay("US", DATE);
        verify(alpaca).isTradingDay("US", DATE);
    }

    @Test @DisplayName("FT-03: TigerOpen unavailable -> Alpaca succeeds")
    void tigerOpenUnavailable_alpacaSucceeds() {
        when(tigerOpen.isAvailable()).thenReturn(false);
        when(alpaca.isAvailable()).thenReturn(true);
        when(alpaca.isTradingDay("US", DATE))
                .thenReturn(TradingCalendarResult.trading("US", DATE, "alpaca", "TRADING"));

        TradingCalendarResult r = fallback.isTradingDay("US", DATE);
        assertEquals("alpaca", r.getSource());
        verify(tigerOpen, never()).isTradingDay(any(), any());
        verify(alpaca).isTradingDay("US", DATE);
    }

    // ======= 全不可用场景 =======

    @Test @DisplayName("FT-07: ALL sources unavailable -> null (unknown, P2-11)")
    void allSourcesUnavailable_returnsNull() {
        when(tigerOpen.isAvailable()).thenReturn(false);
        when(alpaca.isAvailable()).thenReturn(false);

        TradingCalendarResult r = fallback.isTradingDay("US", DATE);
        assertNull(r);
        verify(tigerOpen, never()).isTradingDay(any(), any());
        verify(alpaca, never()).isTradingDay(any(), any());
    }

    @Test @DisplayName("FT-08: ALL sources timeout -> null (unknown, P2-11)")
    void allSourcesTimeout_returnsNull() {
        when(tigerOpen.isAvailable()).thenReturn(true);
        when(tigerOpen.isTradingDay("US", DATE)).thenReturn(null);
        when(alpaca.isAvailable()).thenReturn(true);
        when(alpaca.isTradingDay("US", DATE)).thenReturn(null);

        TradingCalendarResult r = fallback.isTradingDay("US", DATE);
        assertNull(r);
    }

    // ======= 缓存测试 =======

    @Test @DisplayName("FT-12: First query cache miss, calls source")
    void firstQuery_cacheMiss() {
        when(tigerOpen.isAvailable()).thenReturn(true);
        when(tigerOpen.isTradingDay("US", DATE))
                .thenReturn(TradingCalendarResult.trading("US", DATE, "tigeropen", "TRADING"));

        TradingCalendarResult r = fallback.isTradingDay("US", DATE);
        assertTrue(r.isTradingDay());
        verify(tigerOpen).isTradingDay("US", DATE);
    }

    @Test @DisplayName("FT-13: Cache hit on second query, no source call")
    void secondQuery_cacheHit() {
        when(tigerOpen.isAvailable()).thenReturn(true);
        when(tigerOpen.isTradingDay("US", DATE))
                .thenReturn(TradingCalendarResult.trading("US", DATE, "tigeropen", "TRADING"));

        TradingCalendarResult first = fallback.isTradingDay("US", DATE);
        TradingCalendarResult second = fallback.isTradingDay("US", DATE);

        assertTrue(first.isTradingDay());
        assertTrue(second.isTradingDay());
        verify(tigerOpen, times(1)).isTradingDay("US", DATE); // only once
        verify(alpaca, never()).isTradingDay(any(), any());
    }

    @Test @DisplayName("FT-14: Different market different cache keys")
    void differentMarket_differentCache() {
        when(tigerOpen.isAvailable()).thenReturn(true);
        when(tigerOpen.isTradingDay(eq("US"), eq(DATE)))
                .thenReturn(TradingCalendarResult.trading("US", DATE, "tigeropen", "TRADING"));
        when(tigerOpen.isTradingDay(eq("HK"), eq(DATE)))
                .thenReturn(TradingCalendarResult.nonTrading("HK", DATE, "tigeropen", "HOLIDAY"));

        TradingCalendarResult us = fallback.isTradingDay("US", DATE);
        TradingCalendarResult hk = fallback.isTradingDay("HK", DATE);

        assertTrue(us.isTradingDay());
        assertFalse(hk.isTradingDay());
        verify(tigerOpen, times(2)).isTradingDay(any(), any());
    }

    @Test @DisplayName("FT-15: Different date different cache keys")
    void differentDate_differentCache() {
        when(tigerOpen.isAvailable()).thenReturn(true);
        when(tigerOpen.isTradingDay(eq("US"), eq(DATE)))
                .thenReturn(TradingCalendarResult.trading("US", DATE, "tigeropen", "TRADING"));
        when(tigerOpen.isTradingDay(eq("US"), eq(DATE2)))
                .thenReturn(TradingCalendarResult.nonTrading("US", DATE2, "tigeropen", "HOLIDAY"));

        TradingCalendarResult d1 = fallback.isTradingDay("US", DATE);
        TradingCalendarResult d2 = fallback.isTradingDay("US", DATE2);

        assertTrue(d1.isTradingDay());
        assertFalse(d2.isTradingDay());
        verify(tigerOpen, times(2)).isTradingDay(any(), any());
    }

    @Test @DisplayName("FT-16: 全源失败结果不缓存（P2-11）")
    void allSourcesUnavailable_notCached() {
        when(tigerOpen.isAvailable()).thenReturn(false);
        when(alpaca.isAvailable()).thenReturn(false);

        TradingCalendarResult r1 = fallback.isTradingDay("US", DATE);
        assertNull(r1);

        TradingCalendarResult r2 = fallback.isTradingDay("US", DATE);
        assertNull(r2);

        verify(tigerOpen, never()).isTradingDay(any(), any());
        verify(alpaca, never()).isTradingDay(any(), any());
    }

    @Test @DisplayName("FT-18: getCacheStats returns valid stats")
    void getCacheStats_works() {
        CacheStats stats = fallback.getCacheStats();
        assertNotNull(stats);
        assertEquals(0, stats.hitCount());
        assertEquals(0, stats.missCount());

        when(tigerOpen.isAvailable()).thenReturn(true);
        LocalDate may29 = LocalDate.of(2026, 5, 29);
        when(tigerOpen.isTradingDay("US", may29))
                .thenReturn(TradingCalendarResult.trading("US", may29, "tigeropen", "TRADING"));

        fallback.isTradingDay("US", may29);
        stats = fallback.getCacheStats();
        assertEquals(1, stats.missCount());
        assertEquals("fallback", fallback.getSourceName());
    }

    @Test @DisplayName("FT-19: isAvailable true if any source available")
    void isAvailable_atLeastOne() {
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
