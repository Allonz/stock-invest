package com.stock.invest.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.stock.invest.config.GapFillProperties;
import com.stock.invest.entity.DataFillTask;
import com.stock.invest.repository.DataFillTaskRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.RetryProgressService;
import com.stock.invest.service.impl.DataGapFillerServiceImpl;

@ExtendWith(MockitoExtension.class)
@DisplayName("DataGapFillerService - data fill task retry logic")
class DataGapFillerServiceTest {

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
    private RetryProgressService retryProgressService;
    @Mock
    private com.stock.invest.service.TradingCalendarDbService tradingCalendarDbService;
    @Mock
    private StockDataSourcePriorityService stockDataSourcePriorityService;
    @Mock
    private SymbolBlacklistService symbolBlacklistService;
    @Mock
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private DataGapFillerServiceImpl service;

    @Captor
    private ArgumentCaptor<DataFillTask> taskCaptor;

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
                .thenReturn(new RetryProgressService.RetryProgress());

        List<DataSourceStrategy> dataSources = List.of(tigerDataSource, yfinanceDataSource, twelvedataDataSource, tiingoDataSource);
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

        // Priority service returns fallback chain order
        lenient().when(stockDataSourcePriorityService.getPriorityList(anyString()))
                .thenReturn(java.util.List.of("yfinance", "tiger"));

        // All data sources return non-null KLineData to avoid blacklist (date mismatch is fine)
        com.stock.invest.model.KLineData nonNullKd = new com.stock.invest.model.KLineData();
        nonNullKd.setSymbol("TEST");
        nonNullKd.setItems(java.util.List.of(
            new com.stock.invest.model.KLineIterator("TEST", 0L,
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                    0, 0,
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO)));
        lenient().when(twelvedataDataSource.getDailyKLineDataByDateRange(anyString(), any())).thenReturn(nonNullKd);
        lenient().when(tiingoDataSource.getDailyKLineDataByDateRange(anyString(), any())).thenReturn(nonNullKd);
    }

    private DataFillTask createTask(String symbol, LocalDate tradeDate,
                                    Integer dayCount, LocalDate retryDate,
                                    Instant createdAt, Instant updatedAt) {
        DataFillTask task = new DataFillTask();
        task.setId(1L);
        task.setSymbol(symbol);
        task.setTradeDate(tradeDate);
        task.setStatus("retrying");
        task.setRetryCount(3);
        task.setDayCount(dayCount);
        task.setRetryDate(retryDate);
        task.setLastError("previous error");
        task.setCreatedAt(createdAt);
        task.setUpdatedAt(updatedAt);
        return task;
    }

    private LocalDate nyToday() {
        return ZonedDateTime.now(AMERICA_NY).toLocalDate();
    }

    // T-1
    @Test
    @DisplayName("T-1: dayCount=5 and retryDate=today -> skip retry, no save")
    void test_skipWhenDayLimitReached() {
        LocalDate today = nyToday();
        DataFillTask task = createTask(
                "AAPL", today.minusDays(1),
                5, today,
                Instant.now(), Instant.now().minus(2, ChronoUnit.HOURS)
        );
        when(dataFillTaskRepository.findRetryableTasks()).thenReturn(List.of(task));

        service.processRetryingTasks();

        verify(dataFillTaskRepository, never()).save(any(DataFillTask.class));
    }

    // T-2
    @Test
    @DisplayName("T-2: retryDate!=today -> dayCount reset to 0, then retry")
    void test_resetDayCountWhenRetryDateNotToday() {
        LocalDate today = nyToday();
        LocalDate yesterday = today.minusDays(1);
        DataFillTask task = createTask(
                "AAPL", yesterday,
                5, yesterday,
                Instant.now(), Instant.now().minus(2, ChronoUnit.HOURS)
        );
        when(dataFillTaskRepository.findRetryableTasks()).thenReturn(List.of(task));
        // yfinance 返回包含 item 的数据（日期不匹配），避免两个数据源都 null 导致 blacklist
        com.stock.invest.model.KLineData yfKd = new com.stock.invest.model.KLineData();
        yfKd.setSymbol("AAPL");
        yfKd.setItems(java.util.List.of(new com.stock.invest.model.KLineIterator("AAPL", 0L,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                0, 0,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO)));
        when(yfinanceDataSource.getDailyKLineDataByDateRange(anyString(), any())).thenReturn(yfKd);
        com.stock.invest.model.KLineData tgKd = new com.stock.invest.model.KLineData();
        tgKd.setSymbol("AAPL");
        tgKd.setItems(java.util.List.of(
            new com.stock.invest.model.KLineIterator("AAPL", 0L,
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                    0, 0,
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO)));
        when(tigerDataSource.getDailyKLineDataByDateRange(anyString(), any())).thenReturn(tgKd);

        service.processRetryingTasks();

        verify(dataFillTaskRepository).save(taskCaptor.capture());
        DataFillTask saved = taskCaptor.getValue();

        assertEquals(1, saved.getDayCount());
        assertEquals(today, saved.getRetryDate());
        assertEquals(4, saved.getRetryCount());
        assertEquals("retrying", saved.getStatus());
    }

    // T-3
    @Test
    @DisplayName("T-3: createdAt+7d <= now -> status=stopped")
    void test_stopWhenExpired() {
        Instant weekAgo = Instant.now().minus(8, ChronoUnit.DAYS);
        LocalDate today = nyToday();
        DataFillTask task = createTask(
                "AAPL", today.minusDays(1),
                1, today,
                weekAgo, Instant.now().minus(2, ChronoUnit.HOURS)
        );
        when(dataFillTaskRepository.findRetryableTasks()).thenReturn(List.of(task));

        service.processRetryingTasks();

        verify(dataFillTaskRepository).save(taskCaptor.capture());
        DataFillTask saved = taskCaptor.getValue();
        assertEquals("stopped", saved.getStatus());
    }

    // T-4
    @Test
    @DisplayName("T-4: new task fields exist and setters work")
    void test_newRetryTaskFieldStructure() {
        DataFillTask task = new DataFillTask();
        task.setSymbol("TEST");
        task.setTradeDate(LocalDate.now());

        assertDoesNotThrow(() -> {
            task.setRetryDate(LocalDate.now());
            task.setDayCount(1);
        });
        assertEquals(LocalDate.now(), task.getRetryDate());
        assertEquals(1, task.getDayCount());
    }

    // T-5
    @Test
    @DisplayName("T-5: retry failure -> dayCount++, retryCount++")
    void test_retryFailedIncrementsCounters() {
        LocalDate today = nyToday();
        Instant recent = Instant.now();
        DataFillTask task = createTask(
                "AAPL", today.minusDays(1),
                2, today,
                recent, Instant.now().minus(40, ChronoUnit.MINUTES)
        );
        when(dataFillTaskRepository.findRetryableTasks()).thenReturn(List.of(task));
        // yfinance 返回包含 item 的数据（日期不匹配），避免 blacklist
        com.stock.invest.model.KLineData yfKd = new com.stock.invest.model.KLineData();
        yfKd.setSymbol("AAPL");
        yfKd.setItems(java.util.List.of(new com.stock.invest.model.KLineIterator("AAPL", 0L,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                0, 0,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO)));
        when(yfinanceDataSource.getDailyKLineDataByDateRange(anyString(), any())).thenReturn(yfKd);
        com.stock.invest.model.KLineData tgKd2 = new com.stock.invest.model.KLineData();
        tgKd2.setSymbol("AAPL");
        tgKd2.setItems(java.util.List.of(
            new com.stock.invest.model.KLineIterator("AAPL", 0L,
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                    0, 0,
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO)));
        when(tigerDataSource.getDailyKLineDataByDateRange(anyString(), any())).thenReturn(tgKd2);

        service.processRetryingTasks();

        verify(dataFillTaskRepository, times(1)).save(taskCaptor.capture());
        DataFillTask saved = taskCaptor.getValue();

        assertEquals(3, saved.getDayCount());
        assertEquals(4, saved.getRetryCount());
        assertEquals("retrying", saved.getStatus());

        assertTrue(saved.getDayCount() > 2);
        assertTrue(saved.getRetryCount() > 3);
    }
}
