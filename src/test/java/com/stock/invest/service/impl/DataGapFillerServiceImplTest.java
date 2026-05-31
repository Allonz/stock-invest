package com.stock.invest.service.impl;

import com.stock.invest.config.GapFillProperties;
import com.stock.invest.entity.DataFillTask;
import com.stock.invest.entity.StockDailyBar;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
    private com.stock.invest.service.TradingCalendarDbService tradingCalendarDbService;

    private DataGapFillerServiceImpl service;

    @Captor
    private ArgumentCaptor<DataFillTask> taskCaptor;

    @BeforeEach
    void setUp() {
        when(tigerDataSource.getSourceName()).thenReturn("tiger");
        when(tigerDataSource.isAvailable()).thenReturn(true);
        when(yfinanceDataSource.getSourceName()).thenReturn("yfinance");
        when(yfinanceDataSource.isAvailable()).thenReturn(true);
        when(twelvedataDataSource.getSourceName()).thenReturn("twelvedata");
        when(twelvedataDataSource.isAvailable()).thenReturn(true);
        when(tiingoDataSource.getSourceName()).thenReturn("tiingo");
        when(tiingoDataSource.isAvailable()).thenReturn(true);

        List<DataSourceStrategy> dataSources = List.of(
                tigerDataSource, yfinanceDataSource, twelvedataDataSource, tiingoDataSource);
        service = new DataGapFillerServiceImpl(
                stockDailyBarRepository,
                dataFillTaskRepository,
                dataSources,
                gapFillProperties,
                dataFillProgressService,
                tradingCalendarDbService);
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
        assertTrue(missing.size() <= 5, "max 5, actual " + missing.size());
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
        when(tigerDataSource.getDailyKLineDataAsObject(anyString())).thenReturn(null);

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
}
