package com.stock.invest.service.impl;

import com.stock.invest.config.GapFillProperties;
import com.stock.invest.entity.DataFillTask;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.exception.StockDataException;
import com.stock.invest.repository.DataFillTaskRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.DataFillProgressService;
import com.stock.invest.service.DataSourceStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataGapFillerServiceImplTest {

    private static final ZoneId AMERICA_NY = ZoneId.of("America/New_York");

    @Mock
    private StockDailyBarRepository stockDailyBarRepository;
    @Mock
    private DataFillTaskRepository dataFillTaskRepository;
    @Mock
    private DataSourceStrategy tigerDataSource;
    @Mock
    private DataSourceStrategy yfinanceDataSource;
    @Mock
    private DataSourceStrategy twelvedataDataSource;
    @Mock
    private DataSourceStrategy tiingoDataSource;
    @Mock
    private GapFillProperties gapFillProperties;
    @Mock
    private DataFillProgressService dataFillProgressService;
    @Mock
    private com.stock.invest.service.RetryProgressService retryProgressService;
    @Mock
    private com.stock.invest.service.TradingCalendarDbService tradingCalendarDbService;
    @Mock
    private com.stock.invest.service.StockDataSourcePriorityService stockDataSourcePriorityService;
    @Mock
    private com.stock.invest.service.SymbolBlacklistService symbolBlacklistService;
    @Mock
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private DataGapFillerServiceImpl service;

    @Captor
    private ArgumentCaptor<DataFillTask> taskCaptor;

    @Captor
    private ArgumentCaptor<StockDailyBar> barCaptor;

    @BeforeEach
    void setUp() {
        lenient().when(tigerDataSource.getSourceName()).thenReturn("tiger");
        lenient().when(tigerDataSource.isAvailable()).thenReturn(true);
        lenient().when(yfinanceDataSource.getSourceName()).thenReturn("yfinance");
        lenient().when(yfinanceDataSource.isAvailable()).thenReturn(true);
        lenient().when(twelvedataDataSource.getSourceName()).thenReturn("twelvedata");
        lenient().when(twelvedataDataSource.isAvailable()).thenReturn(true);
        lenient().when(tiingoDataSource.getSourceName()).thenReturn("tiingo");
        lenient().when(tiingoDataSource.isAvailable()).thenReturn(true);
        // P3-4：processRetryingTasks 接入进度追踪，mock 返回真实进度对象
        lenient().when(retryProgressService.startRetry())
                .thenReturn(new com.stock.invest.service.RetryProgressService.RetryProgress());

        // For retry tests: make all sources return non-null KLineData with epoch-0 items
        // that won't match any real tradeDate, so notFoundCount stays < 2 and retResult == 0
        com.stock.invest.model.KLineData defaultKd = new com.stock.invest.model.KLineData();
        defaultKd.setSymbol("AAPL");
        defaultKd.setItems(java.util.List.of(
            new com.stock.invest.model.KLineIterator("AAPL", 0L,
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                    0, 0,
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO)));
        lenient().when(tigerDataSource.getDailyKLineDataByDateRange(anyString(), any())).thenReturn(defaultKd);
        lenient().when(yfinanceDataSource.getDailyKLineDataByDateRange(anyString(), any())).thenReturn(defaultKd);
        lenient().when(twelvedataDataSource.getDailyKLineDataByDateRange(anyString(), any())).thenReturn(defaultKd);
        lenient().when(tiingoDataSource.getDailyKLineDataByDateRange(anyString(), any())).thenReturn(defaultKd);

        lenient().when(stockDataSourcePriorityService.getPriorityList(anyString()))
                .thenReturn(java.util.List.of("tiger", "yfinance", "twelvedata", "tiingo"));

        List<DataSourceStrategy> dataSources = List.of(
                tigerDataSource, yfinanceDataSource, twelvedataDataSource, tiingoDataSource);
        service = new DataGapFillerServiceImpl(
                stockDailyBarRepository,
                dataFillTaskRepository,
                dataSources,
                gapFillProperties,
                dataFillProgressService,
                retryProgressService,
                tradingCalendarDbService,
                stockDataSourcePriorityService,
                symbolBlacklistService,
                transactionManager);
    }

    private LocalDate nyToday() {
        return ZonedDateTime.now(AMERICA_NY).toLocalDate();
    }

    // ========== findMissingTradeDates static method tests ==========

    private List<StockDailyBar> barsOf(LocalDate... dates) {
        return Arrays.stream(dates)
                .sorted(Comparator.reverseOrder())
                .map(d -> {
                    StockDailyBar bar = new StockDailyBar();
                    bar.setTradeDate(d);
                    bar.setSymbol("AAPL");
                    return bar;
                })
                .collect(Collectors.toList());
    }

    @Test
    void shouldFindMissingWhenDataStopsDaysAgo() {
        LocalDate today = nyToday();
        LocalDate stopDate = today.minusDays(5);
        List<StockDailyBar> bars = barsOf(stopDate);
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars, null);
        assertFalse(missing.isEmpty(), "should have missing dates");
        for (LocalDate d : missing) {
            assertFalse(d.isBefore(stopDate), d + " should not be before stopDate");
            assertFalse(d.isAfter(today), d + " should not be after today");
        }
    }

    @Test
    void shouldNotLookbackBeyond30Days() {
        LocalDate today = nyToday();
        LocalDate veryOld = today.minusDays(60);
        List<StockDailyBar> bars = barsOf(veryOld);
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars, null);
        LocalDate lookbackLimit = today.minusDays(30);
        for (LocalDate d : missing) {
            assertFalse(d.isBefore(lookbackLimit),
                    () -> d + " should not be before limit " + lookbackLimit);
        }
    }

    @Test
    void shouldReturnEmptyWhenOnlyTodayData() {
        LocalDate today = nyToday();
        List<StockDailyBar> bars = barsOf(today);
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars, null);
        assertTrue(missing.isEmpty(), "no gaps when only today data");
    }

    @Test
    void shouldReturnEmptyWhenEmptyBars() {
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(Collections.emptyList(), null);
        assertTrue(missing.isEmpty());
    }

    @Test
    void shouldLimitMaxMissingDates() {
        LocalDate today = nyToday();
        LocalDate stopDate = today.minusDays(20);
        List<StockDailyBar> bars = barsOf(stopDate);
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars, null);
        assertTrue(missing.size() <= 15, "max 15, actual " + missing.size());
    }

    @Test
    void shouldFindGapsInMultiBarData() {
        LocalDate today = nyToday();
        LocalDate mon = today.minusDays(today.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
        if (mon.isAfter(today)) mon = mon.minusDays(7);
        LocalDate wed = mon.plusDays(2);
        LocalDate tue = mon.plusDays(1);
        if (!tue.isAfter(today)) {
            List<StockDailyBar> bars = barsOf(wed, mon);
            List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars, null);
            assertTrue(missing.contains(tue),
                    "should detect " + tue + ", actual: " + missing);
        }
    }

    @Test
    void shouldSkipWeekends() {
        LocalDate today = nyToday();
        LocalDate friday = today;
        while (friday.getDayOfWeek() != DayOfWeek.FRIDAY) {
            friday = friday.minusDays(1);
        }
        List<StockDailyBar> bars = barsOf(friday);
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars, null);
        for (LocalDate d : missing) {
            assertNotEquals(DayOfWeek.SATURDAY, d.getDayOfWeek(), d + " is Saturday");
            assertNotEquals(DayOfWeek.SUNDAY, d.getDayOfWeek(), d + " is Sunday");
        }
    }

    @Test
    void shouldHandleFutureDataGracefully() {
        LocalDate today = nyToday();
        LocalDate future = today.plusDays(3);
        List<StockDailyBar> bars = barsOf(future);
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars, null);
        assertNotNull(missing, "future data should not cause NPE");
    }

    // ========== Retry task logic tests ==========

    @Test
    @DisplayName("retryTask sets status to retrying, increments retryCount")
    void retryTask_setsStatusToRetrying() {
        LocalDate today = nyToday();
        Instant recent = Instant.now().minus(40, ChronoUnit.MINUTES);
        DataFillTask task = new DataFillTask();
        task.setId(1L);
        task.setSymbol("AAPL");
        task.setTradeDate(today.minusDays(1));
        task.setStatus("retrying");
        task.setRetryCount(2);
        task.setDayCount(2);
        task.setRetryDate(today);
        task.setLastError("previous error");
        task.setCreatedAt(recent);
        task.setUpdatedAt(recent);

        when(dataFillTaskRepository.findRetryableTasks()).thenReturn(List.of(task));
        com.stock.invest.model.KLineData nonNullKd = new com.stock.invest.model.KLineData();
        nonNullKd.setSymbol("AAPL");
        nonNullKd.setItems(java.util.List.of(
            new com.stock.invest.model.KLineIterator("AAPL", 0L,
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                    0, 0,
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO)));
        when(tigerDataSource.getDailyKLineDataByDateRange(anyString(), any())).thenReturn(nonNullKd);

        service.processRetryingTasks();

        verify(dataFillTaskRepository).save(taskCaptor.capture());
        DataFillTask saved = taskCaptor.getValue();

        assertEquals("retrying", saved.getStatus(), "status should remain retrying after failed retry");
        assertEquals(3, saved.getRetryCount(), "retryCount should increment from 2 to 3");
        assertEquals("retry attempt failed again", saved.getLastError());
    }

    @Test
    @DisplayName("processRetryingTasks finds retryable tasks")
    void processRetryingTasks_findsRetryableTasks() {
        LocalDate today = nyToday();
        Instant recent = Instant.now().minus(40, ChronoUnit.MINUTES);
        DataFillTask task = new DataFillTask();
        task.setId(1L);
        task.setSymbol("AAPL");
        task.setTradeDate(today.minusDays(1));
        task.setStatus("retrying");
        task.setRetryCount(0);
        task.setDayCount(1);
        task.setRetryDate(today);
        task.setLastError("initial failure");
        task.setCreatedAt(recent);
        task.setUpdatedAt(recent);

        when(dataFillTaskRepository.findRetryableTasks()).thenReturn(List.of(task));

        service.processRetryingTasks();

        verify(dataFillTaskRepository, times(1)).findRetryableTasks();
    }

    // ========== P1-4: findMissingTradeDates 顺序无关 ==========

    private static StockDailyBar lowBar(String symbol, LocalDate tradeDate) {
        StockDailyBar b = new StockDailyBar();
        b.setSymbol(symbol);
        b.setTradeDate(tradeDate);
        b.setOpenPrice(java.math.BigDecimal.valueOf(0.5));
        b.setClosePrice(java.math.BigDecimal.valueOf(0.5));
        b.setVolume(10L);
        b.setSource("yfinance");
        return b;
    }

    private com.stock.invest.service.TradingCalendarDbService allOpenCalendar() {
        com.stock.invest.service.TradingCalendarDbService cal =
                mock(com.stock.invest.service.TradingCalendarDbService.class);
        when(cal.isTradingDay(anyString(), any(LocalDate.class))).thenReturn(true);
        return cal;
    }

    /**
     * 生成覆盖 [today-30, today+7] 全部工作日（除 excluded）的 bars，DESC 序。
     * 注：missing 结果受 MAX_MISSING_DATES_PER_SYMBOL=5 截断约束 ——
     * 用"全窗口挖洞"保证 missing 精确等于被挖掉的那一天，规避截断干扰。
     */
    private static List<StockDailyBar> weekdaysExcept(LocalDate today, Set<LocalDate> excluded) {
        List<StockDailyBar> bars = new ArrayList<>();
        for (LocalDate d = today.minusDays(30); !d.isAfter(today.plusDays(7)); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5 && !excluded.contains(d)) {
                bars.add(lowBar("AAPL", d));
            }
        }
        bars.sort(Comparator.comparing(StockDailyBar::getTradeDate).reversed());
        return bars;
    }

    /** 返回 today-daysBack 前最近的一个工作日（用于确定性的空洞/节假日日期）。 */
    private static LocalDate weekdayNear(LocalDate today, int daysBack) {
        LocalDate d = today.minusDays(daysBack);
        while (d.getDayOfWeek().getValue() > 5) {
            d = d.minusDays(1);
        }
        return d;
    }

    @Test
    @DisplayName("P1-4: DESC 序输入与 ASC 序输入返回一致的 missing 集合")
    void findMissingTradeDates_descInputConsistent() {
        LocalDate today = nyToday();
        LocalDate gap = weekdayNear(today, 3);

        List<StockDailyBar> desc = weekdaysExcept(today, Set.of(gap));   // newest-first
        List<StockDailyBar> asc = new ArrayList<>(desc);
        java.util.Collections.reverse(asc);                              // oldest-first

        List<LocalDate> fromDesc = DataGapFillerServiceImpl.findMissingTradeDates(desc, allOpenCalendar());
        List<LocalDate> fromAsc = DataGapFillerServiceImpl.findMissingTradeDates(asc, allOpenCalendar());

        assertEquals(fromAsc, fromDesc, "missing dates must not depend on input bar order");
        assertEquals(List.of(gap), fromDesc, "exactly the removed weekday should be missing");
    }

    @Test
    @DisplayName("P1-4: 中间空洞（前后均有数据的工作日缺失）被检出")
    void findMissingTradeDates_internalGapDetected() {
        LocalDate today = nyToday();
        LocalDate gap = weekdayNear(today, 3);

        // 除 gap 外全窗口都有数据 —— 修复前内部空洞永不发现
        List<StockDailyBar> bars = weekdaysExcept(today, Set.of(gap));

        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars, allOpenCalendar());

        assertEquals(List.of(gap), missing, "internal gap " + gap + " should be the only missing date");
    }

    @Test
    @DisplayName("P1-4: 最新 bar 之后的尾部缺口被检出")
    void findMissingTradeDates_tailGapDetected() {
        LocalDate today = nyToday();
        LocalDate newest = weekdayNear(today, 5);

        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(barsOf(newest), allOpenCalendar());

        assertFalse(missing.isEmpty(), "tail gap after " + newest + " should be detected");
        assertTrue(missing.size() <= 5, "max 5 missing dates, actual " + missing);
        for (LocalDate d : missing) {
            assertTrue(d.isAfter(newest), d + " should be after newest bar " + newest);
            assertNotEquals(DayOfWeek.SATURDAY, d.getDayOfWeek());
            assertNotEquals(DayOfWeek.SUNDAY, d.getDayOfWeek());
        }
    }

    @Test
    @DisplayName("P1-4: 节假日（日历 closed）不出现在 missing 中，周末恒被跳过")
    void findMissingTradeDates_weekendAndHolidaySkipped() {
        LocalDate today = nyToday();
        LocalDate holiday = weekdayNear(today, 3);
        // holiday 为唯一潜在缺失（其余全窗口在库）—— 跳过必须源于日历 closed
        List<StockDailyBar> bars = weekdaysExcept(today, Set.of(holiday));

        com.stock.invest.service.TradingCalendarDbService cal =
                mock(com.stock.invest.service.TradingCalendarDbService.class);
        when(cal.isTradingDay(anyString(), any(LocalDate.class)))
                .thenAnswer(inv -> !holiday.equals(inv.getArgument(1, LocalDate.class)));

        // 对照组：日历全开 → holiday 是唯一缺失（证明该工作日确实在考察范围内）
        List<LocalDate> missingAllOpen = DataGapFillerServiceImpl.findMissingTradeDates(bars, allOpenCalendar());
        assertEquals(List.of(holiday), missingAllOpen,
                "holiday should be the only missing day when calendar is open");
        // 节假日不开盘 → 该日被跳过，missing 为空
        List<LocalDate> missingWithHoliday = DataGapFillerServiceImpl.findMissingTradeDates(bars, cal);
        assertFalse(missingWithHoliday.contains(holiday),
                "holiday " + holiday + " must be skipped, actual: " + missingWithHoliday);
        // 周末恒不被报告（日期归属校验）
        assertTrue(missingAllOpen.stream().allMatch(d -> d.getDayOfWeek().getValue() <= 5));
    }

    @Test
    @DisplayName("P1-4: 早于 today-30d 的空洞被范围截断")
    void findMissingTradeDates_lookbackBoundary() {
        LocalDate today = nyToday();
        LocalDate veryOld = today.minusDays(60);

        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(barsOf(veryOld), allOpenCalendar());

        assertFalse(missing.isEmpty(), "should still find gaps within lookback window");
        LocalDate lookbackLimit = today.minusDays(30);
        for (LocalDate d : missing) {
            assertFalse(d.isBefore(lookbackLimit),
                    () -> d + " should not be before lookback limit " + lookbackLimit);
        }
    }

    @Test
    @DisplayName("§4.3: 日历未知态（isTradingDay=null）日期被跳过，不白打配额")
    void findMissingTradeDates_unknownCalendarDaySkipped() {
        LocalDate today = nyToday();
        LocalDate unknown = weekdayNear(today, 3);
        // unknown 为唯一潜在缺失（其余全窗口在库）—— 日历未知态应将其跳过
        List<StockDailyBar> bars = weekdaysExcept(today, Set.of(unknown));

        com.stock.invest.service.TradingCalendarDbService cal =
                mock(com.stock.invest.service.TradingCalendarDbService.class);
        when(cal.isTradingDay(anyString(), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    LocalDate d = inv.getArgument(1, LocalDate.class);
                    if (d.equals(unknown)) {
                        return null; // 日历数据源全挂 → 未知态
                    }
                    return true;
                });

        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars, cal);

        assertFalse(missing.contains(unknown), "unknown calendar day " + unknown + " must be skipped");
        // 对照组：日历可用时该日正常返回
        List<LocalDate> missingAllOpen = DataGapFillerServiceImpl.findMissingTradeDates(bars, allOpenCalendar());
        assertTrue(missingAllOpen.contains(unknown));
    }

    // ========== P1-3: not-found 三态判定（经 fetchAndPersist 行为断言） ==========

    /** 单 symbol + 单缺失日期（日历仅开放 probeDate）的最小化补缺场景。 */
    private void stubSingleGapScenario(String symbol, LocalDate probeDate) {
        lenient().when(gapFillProperties.getMinPriceThreshold()).thenReturn(java.math.BigDecimal.valueOf(1.0));
        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of(symbol));
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq(symbol), any()))
                .thenReturn(new ArrayList<>(List.of(lowBar(symbol, probeDate.minusDays(1)))));
        when(tradingCalendarDbService.isTradingDay(anyString(), any(LocalDate.class)))
                .thenAnswer(inv -> probeDate.equals(inv.getArgument(1, LocalDate.class)));
    }

    @Test
    @DisplayName("P1-3: 2 源确认 not-found → 入黑名单并停 retry")
    void confirmedNotFound_countsAndBlacklists() {
        LocalDate probeDate = nyToday().minusDays(9);
        while (probeDate.getDayOfWeek().getValue() > 5) {
            probeDate = probeDate.minusDays(1);
        }
        stubSingleGapScenario("AAPL", probeDate);

        when(tigerDataSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenThrow(new StockDataException("AAPL", "tiger", "symbol not found",
                        StockDataException.ErrorCategory.CONFIRMED_NOT_FOUND));
        when(yfinanceDataSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenThrow(new StockDataException("AAPL", "yfinance", "invalid symbol",
                        StockDataException.ErrorCategory.CONFIRMED_NOT_FOUND));

        service.fillGaps();

        verify(symbolBlacklistService).recordNotFound(eq("AAPL"), anyMap());
        verify(dataFillTaskRepository).updateStatusBySymbolAndStatusIn(
                eq("AAPL"), anyList(), eq("stopped"), anyString());
        // 黑名单路径 skipRetry → 不再创建 retry 任务
        verify(dataFillTaskRepository, never()).save(any(DataFillTask.class));
    }

    @Test
    @DisplayName("P1-3: 全部源瞬态失败 → 不入黑名单，生成 retry 任务")
    void transientFailure_neverCountsToBlacklist() {
        LocalDate probeDate = nyToday().minusDays(9);
        while (probeDate.getDayOfWeek().getValue() > 5) {
            probeDate = probeDate.minusDays(1);
        }
        stubSingleGapScenario("AAPL", probeDate);

        for (DataSourceStrategy ds : List.of(tigerDataSource, yfinanceDataSource,
                twelvedataDataSource, tiingoDataSource)) {
            String name = ds.getSourceName(); // 先取值，避免 stubbing 链中调用 mock
            when(ds.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                    .thenThrow(new StockDataException("AAPL", name, "connect timeout"));
        }

        service.fillGaps();

        verify(symbolBlacklistService, never()).recordNotFound(anyString(), anyMap());
        verify(dataFillTaskRepository, atLeastOnce()).save(taskCaptor.capture());
        for (DataFillTask t : taskCaptor.getAllValues()) {
            assertEquals("retrying", t.getStatus());
        }
    }

    @Test
    @DisplayName("P1-3: 账户级错误终止 fallback 链（后续源不再请求）")
    void accountLevelError_abortsFallbackChain() {
        LocalDate probeDate = nyToday().minusDays(9);
        while (probeDate.getDayOfWeek().getValue() > 5) {
            probeDate = probeDate.minusDays(1);
        }
        stubSingleGapScenario("AAPL", probeDate);

        when(tigerDataSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenThrow(new StockDataException("AAPL", "tiger", "4000:permission denied",
                        StockDataException.ErrorCategory.ACCOUNT_LEVEL));

        service.fillGaps();

        // 首个缺失日期上 tiger 抛账户级错误后链终止：其余源一次都不应被调用
        verify(tigerDataSource, atLeastOnce()).getDailyKLineDataByDateRange(eq("AAPL"), any());
        verify(yfinanceDataSource, never()).getDailyKLineDataByDateRange(anyString(), any());
        verify(twelvedataDataSource, never()).getDailyKLineDataByDateRange(anyString(), any());
        verify(tiingoDataSource, never()).getDailyKLineDataByDateRange(anyString(), any());
        // 账户级错误不是 not-found：不入黑名单，生成 retry 任务
        verify(symbolBlacklistService, never()).recordNotFound(anyString(), anyMap());
        verify(dataFillTaskRepository, atLeastOnce()).save(any(DataFillTask.class));
    }

    @Test
    @DisplayName("P1-3: 成功但空结果（EMPTY）不计入 not-found 计数")
    void emptySuccess_doesNotCountAsNotFound() {
        LocalDate probeDate = nyToday().minusDays(9);
        while (probeDate.getDayOfWeek().getValue() > 5) {
            probeDate = probeDate.minusDays(1);
        }
        stubSingleGapScenario("AAPL", probeDate);

        when(tigerDataSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenThrow(new StockDataException("AAPL", "tiger", "symbol not found",
                        StockDataException.ErrorCategory.CONFIRMED_NOT_FOUND));
        // yfinance 成功但空列表（EMPTY）
        com.stock.invest.model.KLineData empty = new com.stock.invest.model.KLineData();
        empty.setSymbol("AAPL");
        empty.setItems(List.of());
        when(yfinanceDataSource.getDailyKLineDataByDateRange(eq("AAPL"), any())).thenReturn(empty);
        // twelvedata / tiingo 瞬态
        when(twelvedataDataSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenThrow(new StockDataException("AAPL", "twelvedata", "rate limited"));
        when(tiingoDataSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenThrow(new StockDataException("AAPL", "tiingo", "socket timeout"));

        service.fillGaps();

        // notFoundCount = 1（仅 tiger 确认）< 2 → 不入黑名单
        verify(symbolBlacklistService, never()).recordNotFound(anyString(), anyMap());
        verify(dataFillTaskRepository, atLeastOnce()).save(any(DataFillTask.class));
    }

    @Test
    @DisplayName("P1-3: 1 确认 + 3 瞬态 → 不入黑名单")
    void threeTransientOneConfirmed_noBlacklist() {
        LocalDate probeDate = nyToday().minusDays(9);
        while (probeDate.getDayOfWeek().getValue() > 5) {
            probeDate = probeDate.minusDays(1);
        }
        stubSingleGapScenario("AAPL", probeDate);

        when(tigerDataSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenThrow(new StockDataException("AAPL", "tiger", "symbol not found",
                        StockDataException.ErrorCategory.CONFIRMED_NOT_FOUND));
        when(yfinanceDataSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenThrow(new StockDataException("AAPL", "yfinance", "connect timeout"));
        when(twelvedataDataSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenThrow(new StockDataException("AAPL", "twelvedata", "connect timeout"));
        when(tiingoDataSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenThrow(new StockDataException("AAPL", "tiingo", "connect timeout"));

        service.fillGaps();

        verify(symbolBlacklistService, never()).recordNotFound(anyString(), anyMap());
        verify(dataFillTaskRepository, atLeastOnce()).save(any(DataFillTask.class));
    }

    @Test
    @DisplayName("P1-3: 2 确认 + 1 瞬态 → 入黑名单（确认计数优先）")
    void twoConfirmedOneTransient_blacklists() {
        LocalDate probeDate = nyToday().minusDays(9);
        while (probeDate.getDayOfWeek().getValue() > 5) {
            probeDate = probeDate.minusDays(1);
        }
        stubSingleGapScenario("AAPL", probeDate);

        when(tigerDataSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenThrow(new StockDataException("AAPL", "tiger", "symbol not found",
                        StockDataException.ErrorCategory.CONFIRMED_NOT_FOUND));
        when(yfinanceDataSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenThrow(new StockDataException("AAPL", "yfinance", "invalid symbol",
                        StockDataException.ErrorCategory.CONFIRMED_NOT_FOUND));
        when(twelvedataDataSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenThrow(new StockDataException("AAPL", "twelvedata", "connect timeout"));

        service.fillGaps();

        verify(symbolBlacklistService).recordNotFound(eq("AAPL"), anyMap());
    }

    // ========== P1-2: 事务边界与运行互斥 ==========

    @Test
    @DisplayName("P1-2: 单 symbol 持久化失败不整体回滚，批次继续")
    void fillGaps_partialFailureKeepsCommittedRows() {
        lenient().when(gapFillProperties.getMinPriceThreshold()).thenReturn(java.math.BigDecimal.valueOf(1.0));
        lenient().when(tradingCalendarDbService.isTradingDay(anyString(), any(LocalDate.class))).thenReturn(true);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(status);

        LocalDate today = nyToday();
        LocalDate stopDate = today.minusDays(5);
        List<String> symbols = List.of("S1", "S2", "S3", "S4", "S5");
        when(stockDailyBarRepository.findAllSymbols()).thenReturn(symbols);
        for (String s : symbols) {
            // S3 的此 stub 会被下方 doThrow 覆盖 → lenient 避免 UnnecessaryStubbing
            lenient().when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq(s), any()))
                    .thenReturn(new ArrayList<>(List.of(lowBar(s, stopDate))));
        }
        // S3 在 bars 查询阶段抛异常 → fillGapsForSymbol 失败，批次捕获后继续
        doThrow(new RuntimeException("db boom"))
                .when(stockDailyBarRepository).findBySymbolOrderByTradeDateDesc(eq("S3"), any());

        // tiger 对每个缺失日期返回匹配数据 → 走 persist + 独立事务 save
        lenient().when(tigerDataSource.getDailyKLineDataByDateRange(anyString(), any()))
                .thenAnswer(inv -> {
                    LocalDate date = inv.getArgument(1);
                    String sym = inv.getArgument(0);
                    com.stock.invest.model.KLineData kd = new com.stock.invest.model.KLineData();
                    kd.setSymbol(sym);
                    com.stock.invest.model.KLineIterator item = new com.stock.invest.model.KLineIterator(
                            sym, date.atStartOfDay(AMERICA_NY).toInstant().toEpochMilli(),
                            java.math.BigDecimal.valueOf(150.0), java.math.BigDecimal.valueOf(155.0),
                            java.math.BigDecimal.valueOf(148.0), java.math.BigDecimal.valueOf(152.5),
                            1_000_000L, 5_000_000.0,
                            java.math.BigDecimal.valueOf(1.67), java.math.BigDecimal.valueOf(153.0),
                            java.math.BigDecimal.valueOf(0.33));
                    item.setTimeString(date.toString());
                    kd.setItems(List.of(item));
                    return kd;
                });

        assertDoesNotThrow(() -> service.fillGaps());

        // 5 个 symbol 全部被尝试（S3 失败但批次未中止）
        verify(stockDailyBarRepository, times(5)).findBySymbolOrderByTradeDateDesc(anyString(), any());
        // S1/S2/S4/S5 完成持久化（每个缺失日期至少一次 save）
        verify(stockDailyBarRepository, atLeast(4)).save(any(StockDailyBar.class));
        // 独立事务提交：成功路径 commit 至少 4 次（S3 未进入事务，无回滚）
        verify(transactionManager, atLeast(4)).commit(status);
        verify(transactionManager, never()).rollback(status);
    }

    @Test
    @DisplayName("P1-2: fillGaps 运行中第二个调用被互斥拒绝并立即返回")
    void fillGaps_runningGuardBlocksSecondCall() throws Exception {
        lenient().when(gapFillProperties.getMinPriceThreshold()).thenReturn(java.math.BigDecimal.valueOf(1.0));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        }).when(stockDailyBarRepository).findAllSymbols();

        Thread a = new Thread(() -> service.fillGaps(), "fillA");
        a.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "thread A should enter fillGaps");

        long t0 = System.nanoTime();
        service.fillGaps(); // B —— 互斥拒绝
        long elapsedMs = java.time.Duration.ofNanos(System.nanoTime() - t0).toMillis();
        assertTrue(elapsedMs < 1000, "rejected call must return immediately, elapsed=" + elapsedMs);
        verify(stockDailyBarRepository, times(1)).findAllSymbols(); // B 未进入循环

        release.countDown();
        a.join(5000);
        assertFalse(a.isAlive(), "thread A should finish after release");

        // A 结束后互斥释放 —— 再次调用可进入
        service.fillGaps();
        verify(stockDailyBarRepository, times(2)).findAllSymbols();
    }

    @Test
    @DisplayName("P1-2: fillGaps 与 processRetryingTasks 共用互斥（双向）")
    void processRetryingTasks_sharedMutexWithFillGaps() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        }).when(stockDailyBarRepository).findAllSymbols();

        Thread a = new Thread(() -> service.fillGaps(), "fillA");
        a.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "thread A should enter fillGaps");

        // fillGaps 运行中 → processRetryingTasks 被拒
        service.processRetryingTasks();
        verify(dataFillTaskRepository, never()).findRetryableTasks();

        release.countDown();
        a.join(5000);

        // 反向：processRetryingTasks 运行中 → fillGaps 被拒
        CountDownLatch entered2 = new CountDownLatch(1);
        CountDownLatch release2 = new CountDownLatch(1);
        doAnswer(inv -> {
            entered2.countDown();
            release2.await(5, TimeUnit.SECONDS);
            return List.of();
        }).when(dataFillTaskRepository).findRetryableTasks();

        Thread b = new Thread(() -> service.processRetryingTasks(), "retryB");
        b.start();
        assertTrue(entered2.await(5, TimeUnit.SECONDS), "thread B should enter processRetryingTasks");

        service.fillGaps(); // 被拒
        verify(stockDailyBarRepository, times(1)).findAllSymbols(); // 仍是 A 的那一次

        release2.countDown();
        b.join(5000);
        assertFalse(b.isAlive(), "thread B should finish after release");
    }

    @Test
    @DisplayName("P1-2: 批次失败后 retry 任务独立事务落库（status=retrying）")
    void retryTask_persistsOwnTransaction() {
        lenient().when(gapFillProperties.getMinPriceThreshold()).thenReturn(java.math.BigDecimal.valueOf(1.0));
        lenient().when(tradingCalendarDbService.isTradingDay(anyString(), any(LocalDate.class))).thenReturn(true);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(status);

        LocalDate today = nyToday();
        LocalDate stopDate = today.minusDays(5);
        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("AAPL"));
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq("AAPL"), any()))
                .thenReturn(new ArrayList<>(List.of(lowBar("AAPL", stopDate))));
        when(dataFillTaskRepository.findBySymbolAndTradeDate(eq("AAPL"), any()))
                .thenReturn(Optional.empty());
        // 默认：所有源日期不匹配 → retryableFailure → createRetryTask

        service.fillGaps();

        verify(dataFillTaskRepository, atLeastOnce()).save(taskCaptor.capture());
        for (DataFillTask t : taskCaptor.getAllValues()) {
            assertEquals("retrying", t.getStatus(), "retry task must persist in own transaction");
        }
        // 独立事务提交（非整批包裹 → 无整体回滚语义）
        verify(transactionManager, atLeastOnce()).commit(status);
    }

    @Test
    @DisplayName("P1-2/§4.4: 含中间空洞的 DESC 序 bars → 空洞日期被补缺")
    void fillGaps_fillsInternalGapDate() {
        lenient().when(gapFillProperties.getMinPriceThreshold()).thenReturn(java.math.BigDecimal.valueOf(1.0));
        lenient().when(tradingCalendarDbService.isTradingDay(anyString(), any(LocalDate.class))).thenReturn(true);
        LocalDate today = nyToday();
        LocalDate tue = weekdayNear(today, 3);

        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("AAPL"));
        // DESC 序全窗口 bars，仅挖掉 tue 这一个内部空洞
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq("AAPL"), any()))
                .thenReturn(new ArrayList<>(weekdaysExcept(today, Set.of(tue))));
        when(stockDailyBarRepository.findBySymbolAndTradeDate(eq("AAPL"), any()))
                .thenReturn(Optional.empty());

        lenient().when(tigerDataSource.getDailyKLineDataByDateRange(anyString(), any()))
                .thenAnswer(inv -> {
                    LocalDate date = inv.getArgument(1);
                    com.stock.invest.model.KLineData kd = new com.stock.invest.model.KLineData();
                    kd.setSymbol("AAPL");
                    com.stock.invest.model.KLineIterator item = new com.stock.invest.model.KLineIterator(
                            "AAPL", date.atStartOfDay(AMERICA_NY).toInstant().toEpochMilli(),
                            java.math.BigDecimal.valueOf(150.0), java.math.BigDecimal.valueOf(155.0),
                            java.math.BigDecimal.valueOf(148.0), java.math.BigDecimal.valueOf(152.5),
                            1_000_000L, 5_000_000.0,
                            java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);
                    item.setTimeString(date.toString());
                    kd.setItems(List.of(item));
                    return kd;
                });

        service.fillGaps();

        // 空洞日期 tue 出现在补缺请求与落库中（P1-4 修复前内部空洞永不发现）
        verify(tigerDataSource).getDailyKLineDataByDateRange(eq("AAPL"), eq(tue));
        verify(stockDailyBarRepository, atLeastOnce()).save(barCaptor.capture());
        List<com.stock.invest.entity.StockDailyBar> saved = barCaptor.getAllValues();
        assertTrue(saved.stream().anyMatch(b -> tue.equals(b.getTradeDate())),
                "internal gap date " + tue + " should be persisted, saved=" + saved);
    }

    @Test
    @DisplayName("§4.5: 数据源挂起（阻塞 2s）不永久阻塞批次与互斥释放")
    void hangingSource_doesNotBlockBatchOrMutex() throws Exception {
        lenient().when(gapFillProperties.getMinPriceThreshold()).thenReturn(java.math.BigDecimal.valueOf(1.0));
        lenient().when(tradingCalendarDbService.isTradingDay(anyString(), any(LocalDate.class))).thenReturn(true);
        LocalDate stopDate = nyToday().minusDays(5);

        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("AAPL"));
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(anyString(), any()))
                .thenReturn(new ArrayList<>(List.of(lowBar("AAPL", stopDate))));

        // 模拟 Python 脚本挂起：每个缺失日期阻塞 400ms 后返回空结果
        // （真实挂起由 PythonScriptExecutor 30s 超时兜底 —— P1-1 timeout_kills_hung_process）
        lenient().when(tigerDataSource.getDailyKLineDataByDateRange(anyString(), any()))
                .thenAnswer(inv -> {
                    Thread.sleep(400);
                    com.stock.invest.model.KLineData kd = new com.stock.invest.model.KLineData();
                    kd.setSymbol(inv.getArgument(0));
                    kd.setItems(List.of());
                    return kd;
                });

        long start = System.nanoTime();
        service.fillGaps();
        long elapsedMs = java.time.Duration.ofNanos(System.nanoTime() - start).toMillis();

        // 批次在可接受时间内完成（缺失日期 × 挂起源阻塞），未整体卡死
        assertTrue(elapsedMs < 10_000, "batch should complete despite hanging source, elapsed=" + elapsedMs);
        // 互斥释放：立即再次触发可进入
        service.fillGaps();
        verify(stockDailyBarRepository, times(2)).findAllSymbols();
    }

    @Test
    @DisplayName("§4.1: fillGaps 中途异常 → finally 释放互斥，后续可再次进入")
    void mutexReleasedAfterException() {
        lenient().when(gapFillProperties.getMinPriceThreshold()).thenReturn(java.math.BigDecimal.valueOf(1.0));
        // 首次调用抛异常，之后恢复（链式 stub，避免 doThrow 后重 stub 触发旧异常）
        when(stockDailyBarRepository.findAllSymbols())
                .thenThrow(new RuntimeException("findAllSymbols boom"))
                .thenReturn(List.of());

        assertThrows(RuntimeException.class, () -> service.fillGaps());
        assertFalse(service.isRunning(), "mutex must be released after exception");

        // 互斥已释放：再次调用可进入
        assertDoesNotThrow(() -> service.fillGaps());
        verify(stockDailyBarRepository, times(2)).findAllSymbols();
    }
}
