package com.stock.invest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;

import com.stock.invest.config.ScannerProperties;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.repository.ScreeningMatchRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.impl.ScreeningServiceImpl;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
@DisplayName("ScreeningService — 模式筛选服务测试")
class ScreeningServiceTest {

    @Mock private StockDailyBarRepository stockDailyBarRepository;
    @Mock private ScreeningMatchRepository screeningMatchRepository;
    @Mock private PatternEvaluateService patternEvaluateService;
    @Mock private ScannerProperties scannerProperties;
    @Mock private TradingCalendarDbService tradingCalendarDbService;

    @InjectMocks
    private ScreeningServiceImpl screeningService;

    private StockDailyBar bar(String symbol, LocalDate date, double open, double close, long volume, String source) {
        StockDailyBar b = new StockDailyBar();
        b.setSymbol(symbol);
        b.setTradeDate(date);
        b.setOpenPrice(java.math.BigDecimal.valueOf(open));
        b.setClosePrice(java.math.BigDecimal.valueOf(close));
        b.setVolume(volume);
        b.setSource(source);
        return b;
    }

    @Nested
    @DisplayName("runScreening — 筛选流程")
    class RunScreeningTest {

        @BeforeEach
        void setUp() {
            // Mock trading calendar: all weekdays are open trading days
            lenient().when(tradingCalendarDbService.getRange(any(), any(), any())).thenAnswer(inv -> {
                LocalDate first = inv.getArgument(1);
                LocalDate last = inv.getArgument(2);
                List<com.stock.invest.entity.TradingCalendarEntity> entries = new ArrayList<>();
                for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
                    if (d.getDayOfWeek().getValue() <= 5) {
                        com.stock.invest.entity.TradingCalendarEntity e =
                                new com.stock.invest.entity.TradingCalendarEntity();
                        e.setTradeDate(d);
                        e.setIsOpen(true);
                        entries.add(e);
                    }
                }
                return entries;
            });
        }

        @Test
        @DisplayName("正常筛选流程，结果写入 screening_match")
        void test_screening_success() {
            String symbol = "TEST";
            LocalDate tradeDate = LocalDate.of(2026, 5, 18);

            // 生成过去14天的连续数据
            List<StockDailyBar> bars = new ArrayList<>();
            for (int i = 13; i >= 0; i--) {
                LocalDate d = tradeDate.minusDays(i);
                if (d.getDayOfWeek().getValue() <= 5) {
                    bars.add(bar(symbol, d, 0.08, 0.09, 10000L + i * 500L, "tiger"));
                }
            }

            when(patternEvaluateService.matchesIncreasingVolumePattern(anyList(), anyInt()))
                    .thenReturn(true);
            // bars 是 oldest-first（循环从 i=13 到 i=0），但查询返回 newest-first
            java.util.Collections.reverse(bars);
            when(stockDailyBarRepository.findByTradeDateBetweenOrderByTradeDateDesc(any(LocalDate.class), eq(tradeDate)))
                    .thenReturn(bars);
            when(screeningMatchRepository.saveAll(anyList()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            String batchId = screeningService.runScreening(tradeDate);

            assertNotNull(batchId);
            verify(screeningMatchRepository, times(1)).saveAll(anyList());
        }

        @Test
        @DisplayName("数据不足7天跳过")
        void test_screening_insufficientData() {
            String symbol = "SHORT";
            LocalDate tradeDate = LocalDate.of(2026, 5, 18);

            // 只有2天数据
            List<StockDailyBar> bars = new ArrayList<>();
            bars.add(bar(symbol, tradeDate.minusDays(1), 0.10, 0.11, 5000L, "tiger"));
            bars.add(bar(symbol, tradeDate, 0.11, 0.12, 6000L, "tiger"));

            when(stockDailyBarRepository.findByTradeDateBetweenOrderByTradeDateDesc(any(LocalDate.class), eq(tradeDate)))
                    .thenReturn(bars);

            screeningService.runScreening(tradeDate);

            // 数据不足7天，但内部取 windowDays = min(7, 2) = 2 < 3，所以被跳过
            verify(screeningMatchRepository, never()).saveAll(anyList());
        }


        @Test
        @DisplayName("无数据时返回空 batchId")
        void test_noData() {
            LocalDate tradeDate = LocalDate.of(2026, 5, 18);

            when(stockDailyBarRepository.findByTradeDateBetweenOrderByTradeDateDesc(any(LocalDate.class), eq(tradeDate)))
                    .thenReturn(List.of());

            String batchId = screeningService.runScreening(tradeDate);
            assertNotNull(batchId);
            verify(screeningMatchRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("模式不匹配时跳过")
        void test_patternNotMatch() {
            String symbol = "NOPAT";
            LocalDate tradeDate = LocalDate.of(2026, 5, 18);

            List<StockDailyBar> bars = new ArrayList<>();
            for (int i = 13; i >= 0; i--) {
                LocalDate d = tradeDate.minusDays(i);
                if (d.getDayOfWeek().getValue() <= 5) {
                    bars.add(bar(symbol, d, 0.08, 0.09, 10000L + i * 500L, "tiger"));
                }
            }

            when(stockDailyBarRepository.findByTradeDateBetweenOrderByTradeDateDesc(any(LocalDate.class), eq(tradeDate)))
                    .thenReturn(bars);

            screeningService.runScreening(tradeDate);

            verify(screeningMatchRepository, never()).saveAll(anyList());
        }

        // ---------- P1-7: windowDays / limit 参数生效 ----------

        /** 生成单个 symbol 过去 13 个交易日的 bars（newest-first），latest 恰为 tradeDate。 */
        private List<StockDailyBar> barsFor(String symbol, LocalDate tradeDate) {
            List<StockDailyBar> bars = new ArrayList<>();
            for (int i = 13; i >= 0; i--) {
                LocalDate d = tradeDate.minusDays(i);
                if (d.getDayOfWeek().getValue() <= 5) {
                    bars.add(bar(symbol, d, 0.08, 0.09, 10000L + i * 500L, "tiger"));
                }
            }
            java.util.Collections.reverse(bars); // 查询返回 newest-first
            return bars;
        }

        @Test
        @DisplayName("P1-7: windowDays=3 只评估 3 天窗口")
        void windowDays_limitsWindows() {
            LocalDate tradeDate = LocalDate.of(2026, 5, 18);
            when(stockDailyBarRepository.findByTradeDateBetweenOrderByTradeDateDesc(any(LocalDate.class), eq(tradeDate)))
                    .thenReturn(barsFor("TEST", tradeDate));

            screeningService.runScreening(tradeDate, 3, null);

            ArgumentCaptor<Integer> windowCaptor = ArgumentCaptor.forClass(Integer.class);
            verify(patternEvaluateService, atLeastOnce())
                    .matchesIncreasingVolumePattern(anyList(), windowCaptor.capture());
            for (Integer w : windowCaptor.getAllValues()) {
                assertEquals(3, w, "only window 3d should be evaluated");
            }
        }

        @Test
        @DisplayName("P1-7: windowDays=null 评估全部窗口 2~7 天（等于旧 runScreening(date)）")
        void nullParams_defaultAllWindows() {
            LocalDate tradeDate = LocalDate.of(2026, 5, 18);
            when(stockDailyBarRepository.findByTradeDateBetweenOrderByTradeDateDesc(any(LocalDate.class), eq(tradeDate)))
                    .thenReturn(barsFor("TEST", tradeDate));

            screeningService.runScreening(tradeDate, null, null);

            ArgumentCaptor<Integer> windowCaptor = ArgumentCaptor.forClass(Integer.class);
            verify(patternEvaluateService, atLeastOnce())
                    .matchesIncreasingVolumePattern(anyList(), windowCaptor.capture());
            Set<Integer> evaluated = new HashSet<>(windowCaptor.getAllValues());
            assertEquals(Set.of(2, 3, 4, 5, 6, 7), evaluated,
                    "null windowDays should fall back to all windows 2~7");
        }

        @Test
        @DisplayName("P1-7: windowDays=1（小于最小窗口）回退全部窗口")
        void invalidWindowOne_clampsToAllWindows() {
            LocalDate tradeDate = LocalDate.of(2026, 5, 18);
            when(stockDailyBarRepository.findByTradeDateBetweenOrderByTradeDateDesc(any(LocalDate.class), eq(tradeDate)))
                    .thenReturn(barsFor("TEST", tradeDate));

            screeningService.runScreening(tradeDate, 1, null);

            ArgumentCaptor<Integer> windowCaptor = ArgumentCaptor.forClass(Integer.class);
            verify(patternEvaluateService, atLeastOnce())
                    .matchesIncreasingVolumePattern(anyList(), windowCaptor.capture());
            Set<Integer> evaluated = new HashSet<>(windowCaptor.getAllValues());
            assertEquals(Set.of(2, 3, 4, 5, 6, 7), evaluated,
                    "windowDays < MIN_WINDOW_DAYS should fall back to all windows");
        }

        @Test
        @DisplayName("P1-7: limit 限制评估 symbol 数（10 个候选 + limit=5 → ≤5 个被评估）")
        void limit_capsSymbols() {
            LocalDate tradeDate = LocalDate.of(2026, 5, 18);
            List<StockDailyBar> allBars = new ArrayList<>();
            for (int s = 0; s < 10; s++) {
                allBars.addAll(barsFor("SYM" + s, tradeDate));
            }
            when(stockDailyBarRepository.findByTradeDateBetweenOrderByTradeDateDesc(any(LocalDate.class), eq(tradeDate)))
                    .thenReturn(allBars);

            screeningService.runScreening(tradeDate, null, 5);

            ArgumentCaptor<List<StockDailyBar>> sliceCaptor = ArgumentCaptor.forClass(List.class);
            verify(patternEvaluateService, atLeastOnce())
                    .matchesIncreasingVolumePattern(sliceCaptor.capture(), anyInt());
            Set<String> evaluatedSymbols = new HashSet<>();
            for (List<StockDailyBar> slice : sliceCaptor.getAllValues()) {
                evaluatedSymbols.add(slice.get(0).getSymbol());
            }
            assertTrue(evaluatedSymbols.size() <= 5,
                    "at most 5 symbols evaluated, actual: " + evaluatedSymbols);
            assertTrue(evaluatedSymbols.size() < 10,
                    "limit must cap below the 10 candidates, actual: " + evaluatedSymbols);
        }

        @Test
        @DisplayName("P1-7: 无 limit 时全部候选 symbol 被评估")
        void noLimit_evaluatesAllSymbols() {
            LocalDate tradeDate = LocalDate.of(2026, 5, 18);
            List<StockDailyBar> allBars = new ArrayList<>();
            for (int s = 0; s < 4; s++) {
                allBars.addAll(barsFor("SYM" + s, tradeDate));
            }
            when(stockDailyBarRepository.findByTradeDateBetweenOrderByTradeDateDesc(any(LocalDate.class), eq(tradeDate)))
                    .thenReturn(allBars);

            screeningService.runScreening(tradeDate, null, null);

            ArgumentCaptor<List<StockDailyBar>> sliceCaptor = ArgumentCaptor.forClass(List.class);
            verify(patternEvaluateService, atLeastOnce())
                    .matchesIncreasingVolumePattern(sliceCaptor.capture(), anyInt());
            Set<String> evaluatedSymbols = new HashSet<>();
            for (List<StockDailyBar> slice : sliceCaptor.getAllValues()) {
                evaluatedSymbols.add(slice.get(0).getSymbol());
            }
            assertEquals(Set.of("SYM0", "SYM1", "SYM2", "SYM3"), evaluatedSymbols,
                    "without limit every candidate should be evaluated");
        }

        @Test
        @DisplayName("P1-2/§4.1: 筛选互斥 —— 第二个并发调用立即返回 null")
        void screening_runningGuardSecondCallReturnsNull() throws Exception {
            LocalDate tradeDate = LocalDate.of(2026, 5, 18);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(inv -> {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
                return List.of();
            }).when(stockDailyBarRepository)
                    .findByTradeDateBetweenOrderByTradeDateDesc(any(LocalDate.class), any(LocalDate.class));

            AtomicReference<String> firstResult = new AtomicReference<>();
            Thread a = new Thread(() -> firstResult.set(screeningService.runScreening(tradeDate)), "screenA");
            a.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS), "thread A should enter runScreening");

            long t0 = System.nanoTime();
            String second = screeningService.runScreening(tradeDate); // B —— 互斥拒绝
            long elapsedMs = java.time.Duration.ofNanos(System.nanoTime() - t0).toMillis();

            assertNull(second, "second concurrent call must be rejected with null");
            assertTrue(elapsedMs < 1000, "rejected call must return immediately, elapsed=" + elapsedMs);
            verify(stockDailyBarRepository, times(1))
                    .findByTradeDateBetweenOrderByTradeDateDesc(any(LocalDate.class), any(LocalDate.class));

            release.countDown();
            a.join(5000);
            assertFalse(a.isAlive(), "thread A should finish after release");
            assertNotNull(firstResult.get(), "first call should complete with a batchId");
        }
    }
}
