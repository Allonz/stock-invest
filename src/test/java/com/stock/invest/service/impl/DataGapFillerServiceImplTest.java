package com.stock.invest.service.impl;

import com.stock.invest.config.GapFillProperties;
import com.stock.invest.entity.DataFillTask;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.repository.DataFillTaskRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataGapFillerServiceImplTest {

    @Mock
    private StockDailyBarRepository stockDailyBarRepository;
    @Mock
    private DataFillTaskRepository dataFillTaskRepository;
    @Mock
    private TigerStockServiceImpl tigerStockService;
    @Mock
    private YFinanceStockServiceImpl yFinanceStockService;
    @Mock
    private TwelveDataStockServiceImpl twelveDataStockService;
    @Mock
    private TiingoDataSourceStrategy tiingoDataSourceStrategy;
    @Mock
    private GapFillProperties gapFillProperties;

    @InjectMocks
    private DataGapFillerServiceImpl service;

    @Captor
    private ArgumentCaptor<DataFillTask> taskCaptor;

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
        LocalDate today = ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate();
        LocalDate stopDate = today.minusDays(5);
        List<StockDailyBar> bars = barsOf(stopDate);
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars);
        assertFalse(missing.isEmpty(), "should have missing dates");
        for (LocalDate d : missing) {
            assertFalse(d.isBefore(stopDate), d + " should not be before stopDate");
            assertFalse(d.isAfter(today), d + " should not be after today");
        }
    }

    @Test
    void shouldNotLookbackBeyond30Days() {
        LocalDate today = ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate();
        LocalDate veryOld = today.minusDays(60);
        List<StockDailyBar> bars = barsOf(veryOld);
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars);
        LocalDate lookbackLimit = today.minusDays(30);
        for (LocalDate d : missing) {
            assertFalse(d.isBefore(lookbackLimit),
                    () -> d + " should not be before limit " + lookbackLimit);
        }
    }

    @Test
    void shouldReturnEmptyWhenOnlyTodayData() {
        LocalDate today = ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate();
        List<StockDailyBar> bars = barsOf(today);
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars);
        assertTrue(missing.isEmpty(), "no gaps when only today data");
    }

    @Test
    void shouldReturnEmptyWhenEmptyBars() {
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(Collections.emptyList());
        assertTrue(missing.isEmpty());
    }

    @Test
    void shouldLimitMaxMissingDates() {
        LocalDate today = ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate();
        LocalDate stopDate = today.minusDays(20);
        List<StockDailyBar> bars = barsOf(stopDate);
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars);
        assertTrue(missing.size() <= 5, "max 5, actual " + missing.size());
    }

    @Test
    void shouldFindGapsInMultiBarData() {
        LocalDate mon = LocalDate.of(2026, 5, 18);
        LocalDate wed = LocalDate.of(2026, 5, 20);
        List<StockDailyBar> bars = barsOf(wed, mon);
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars);
        assertTrue(missing.contains(LocalDate.of(2026, 5, 19)),
                "should detect Tue 5/19, actual: " + missing);
    }

    @Test
    void shouldSkipWeekends() {
        LocalDate today = ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate();
        LocalDate friday = today;
        while (friday.getDayOfWeek() != DayOfWeek.FRIDAY) {
            friday = friday.minusDays(1);
        }
        List<StockDailyBar> bars = barsOf(friday);
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars);
        for (LocalDate d : missing) {
            assertNotEquals(DayOfWeek.SATURDAY, d.getDayOfWeek(), d + " is Saturday");
            assertNotEquals(DayOfWeek.SUNDAY, d.getDayOfWeek(), d + " is Sunday");
        }
    }

    @Test
    void shouldHandleFutureDataGracefully() {
        LocalDate today = ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate();
        LocalDate future = today.plusDays(3);
        List<StockDailyBar> bars = barsOf(future);
        List<LocalDate> missing = DataGapFillerServiceImpl.findMissingTradeDates(bars);
        assertNotNull(missing, "future data should not cause NPE");
    }

    // ========== Retry task logic tests (Test-4) ==========

    @Test
    @DisplayName("retryTask sets status to retrying, increments retryCount")
    void retryTask_setsStatusToRetrying() {
        // Arrange: simulate a retry failing through processRetryingTasks
        LocalDate today = LocalDate.now();
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
        // All fallbacks return null → retry fails
        when(tigerStockService.getDailyKLineDataAsObject(anyString())).thenReturn(null);

        // Act
        service.processRetryingTasks();

        // Assert
        verify(dataFillTaskRepository).save(taskCaptor.capture());
        DataFillTask saved = taskCaptor.getValue();

        assertEquals("retrying", saved.getStatus(), "status should remain retrying after failed retry");
        assertEquals(3, saved.getRetryCount(), "retryCount should increment from 2 to 3");
        assertEquals("retry attempt failed again", saved.getLastError());
    }

    @Test
    @DisplayName("processRetryingTasks finds retryable tasks")
    void processRetryingTasks_findsRetryableTasks() {
        // Arrange
        LocalDate today = LocalDate.now();
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

        // Act
        service.processRetryingTasks();

        // Assert: verify findRetryableTasks was called exactly once
        verify(dataFillTaskRepository, times(1)).findRetryableTasks();
    }
}
