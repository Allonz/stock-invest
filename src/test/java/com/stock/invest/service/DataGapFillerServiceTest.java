package com.stock.invest.service;

import com.stock.invest.config.GapFillProperties;
import com.stock.invest.entity.DataFillTask;
import com.stock.invest.repository.DataFillTaskRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.impl.DataGapFillerServiceImpl;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
    private com.stock.invest.service.TradingCalendarDbService tradingCalendarDbService;
    @Mock
    private StockDataSourcePriorityService stockDataSourcePriorityService;

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

        List<DataSourceStrategy> dataSources = List.of(tigerDataSource, yfinanceDataSource, twelvedataDataSource, tiingoDataSource);
        service = new DataGapFillerServiceImpl(
                stockDailyBarRepository,
                dataFillTaskRepository,
                dataSources,
                gapFillProperties,
                dataFillProgressService,
                tradingCalendarDbService,
                stockDataSourcePriorityService);
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
        when(tigerDataSource.getDailyKLineDataAsObject(anyString())).thenReturn(null);

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
        when(tigerDataSource.getDailyKLineDataAsObject(anyString())).thenReturn(null);

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
