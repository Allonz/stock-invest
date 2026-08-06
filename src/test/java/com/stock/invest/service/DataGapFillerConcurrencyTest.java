package com.stock.invest.service;

import com.stock.invest.config.GapFillProperties;
import com.stock.invest.repository.DataFillTaskRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.impl.DataGapFillerServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * test-plan §4.1：补缺/重试运行互斥专项。
 * <p>
 * 互斥由 {@link DataGapFillerServiceImpl} 的 AtomicBoolean running 承担，
 * 定时（19:00）、手动 REST、MCP 三路触发最终都汇入同一 Service 实例 ——
 * 本类验证任意两路并发时仅一份执行、拒绝路径立即返回、异常不锁死。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DataGapFiller — 并发互斥专项 (§4.1)")
class DataGapFillerConcurrencyTest {

    @Mock private StockDailyBarRepository stockDailyBarRepository;
    @Mock private DataFillTaskRepository dataFillTaskRepository;
    @Mock private DataSourceStrategy tigerSource;
    @Mock private GapFillProperties gapFillProperties;
    @Mock private DataFillProgressService dataFillProgressService;
    @Mock private RetryProgressService retryProgressService;
    @Mock private TradingCalendarDbService tradingCalendarDbService;
    @Mock private StockDataSourcePriorityService stockDataSourcePriorityService;
    @Mock private SymbolBlacklistService symbolBlacklistService;
    @Mock private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private DataGapFillerServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(tigerSource.getSourceName()).thenReturn("tiger");
        lenient().when(tigerSource.isAvailable()).thenReturn(true);
        lenient().when(gapFillProperties.getMinPriceThreshold()).thenReturn(1.0);
        lenient().when(stockDataSourcePriorityService.getPriorityList(anyString()))
                .thenReturn(List.of("tiger", "yfinance"));

        service = new DataGapFillerServiceImpl(
                stockDailyBarRepository, dataFillTaskRepository, List.of(tigerSource),
                gapFillProperties, dataFillProgressService, retryProgressService, tradingCalendarDbService,
                stockDataSourcePriorityService, symbolBlacklistService, transactionManager);
    }

    /** 让 findAllSymbols 在调用时阻塞，用于把线程 A 卡在 fillGaps 内部。 */
    private void blockOnFindAllSymbols(CountDownLatch entered, CountDownLatch release) throws Exception {
        doAnswer(inv -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("release latch timed out");
            }
            return List.of();
        }).when(stockDailyBarRepository).findAllSymbols();
    }

    @Test
    @DisplayName("§4.1-1: 双线程并发 fillGaps → 仅一份执行，running 标记可见")
    void concurrentFillGaps_onlyOneExecutes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockOnFindAllSymbols(entered, release);

        Thread a = new Thread(() -> service.fillGaps(), "fillA");
        a.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "thread A should enter fillGaps");
        assertTrue(service.isRunning(), "running flag must be visible while fillGaps in progress");

        service.fillGaps(); // B —— 拒绝

        verify(stockDailyBarRepository, times(1)).findAllSymbols(); // 无翻倍
        release.countDown();
        a.join(5000);
        assertFalse(a.isAlive(), "thread A should finish");
        assertFalse(service.isRunning(), "running flag must clear after completion");

        // 释放后可再次进入
        service.fillGaps();
        verify(stockDailyBarRepository, times(2)).findAllSymbols();
    }

    @Test
    @DisplayName("§4.1-2: 定时路径与手动路径并发 → 仅一份执行（数据源调用不翻倍）")
    void schedulerAndManualPaths_shareMutex() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockOnFindAllSymbols(entered, release);

        // 定时路径（DataFillScheduler → service.fillGaps()）
        Thread scheduler = new Thread(() -> service.fillGaps(), "scheduler");
        scheduler.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        // 手动路径（AdminController.triggerDataFill → isRunning 检查 → fillGaps）
        assertTrue(service.isRunning(), "manual path should observe running flag");
        service.fillGaps(); // 手动触发被拒绝

        verify(stockDailyBarRepository, times(1)).findAllSymbols();
        release.countDown();
        scheduler.join(5000);
    }

    @Test
    @DisplayName("§4.1-3: 筛选并发 → 第二个 runScreening 立即返回 null（仅一份执行）")
    void screeningConcurrent_secondReturnsNull() throws Exception {
        // 与补缺共享的互斥语义在 ScreeningServiceImpl 独立测试（ScreeningServiceTest）中覆盖；
        // 此处验证并发时序下拒绝路径返回 null 且不进入查询。
        com.stock.invest.service.impl.ScreeningServiceImpl screeningService =
                new com.stock.invest.service.impl.ScreeningServiceImpl(
                        stockDailyBarRepository,
                        mock(com.stock.invest.repository.ScreeningMatchRepository.class),
                        mock(PatternEvaluateService.class),
                        mock(TradingCalendarDbService.class));

        java.time.LocalDate tradeDate = java.time.LocalDate.of(2026, 5, 18);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        }).when(stockDailyBarRepository)
                .findByTradeDateBetweenOrderByTradeDateDesc(any(java.time.LocalDate.class), any(java.time.LocalDate.class));

        Thread a = new Thread(() -> screeningService.runScreening(tradeDate), "screenA");
        a.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        long t0 = System.nanoTime();
        String second = screeningService.runScreening(tradeDate);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();

        assertNull(second, "second concurrent screening must be rejected");
        assertTrue(elapsedMs < 1000, "rejected path must not wait, elapsed=" + elapsedMs);

        release.countDown();
        a.join(5000);
    }

    @Test
    @DisplayName("§4.1-4: fillGaps 中途异常 → finally 释放互斥，下次调用可进入")
    void exceptionReleasesMutex() {
        when(stockDailyBarRepository.findAllSymbols())
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(List.of());

        assertThrows(RuntimeException.class, () -> service.fillGaps());
        assertFalse(service.isRunning(), "mutex must be released in finally");

        assertDoesNotThrow(() -> service.fillGaps()); // 可再次进入
        verify(stockDailyBarRepository, times(2)).findAllSymbols();
    }

    @Test
    @DisplayName("§4.1-5: 互斥拒绝路径返回 < 1s（未排队等锁）")
    void rejectedCallReturnsWithin1s() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockOnFindAllSymbols(entered, release);

        Thread a = new Thread(() -> service.fillGaps(), "fillA");
        a.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        long t0 = System.nanoTime();
        service.fillGaps(); // 拒绝
        long elapsedMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();

        assertTrue(elapsedMs < 1000, "rejected concurrent call must return immediately, elapsed=" + elapsedMs);

        release.countDown();
        a.join(5000);
    }
}
