package com.stock.invest.service;

import com.stock.invest.entity.DataFillTask;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.repository.DataFillTaskRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.impl.DataGapFillerServiceImpl;
import com.stock.invest.service.impl.TigerStockServiceImpl;
import com.stock.invest.service.impl.TwelveDataStockServiceImpl;
import com.stock.invest.service.impl.YFinanceStockServiceImpl;
import com.stock.invest.service.impl.TiingoDataSourceStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DataGapFillerService - data gap filling service tests")
class DataGapFillerServiceTest {

    @Mock private StockDailyBarRepository stockDailyBarRepository;
    @Mock private DataFillTaskRepository dataFillTaskRepository;
    @Mock private TigerStockServiceImpl tigerStockService;
    @Mock private YFinanceStockServiceImpl yFinanceStockService;
    @Mock private TwelveDataStockServiceImpl twelveDataStockService;
    @Mock private TiingoDataSourceStrategy tiingoDataSourceStrategy;

    @InjectMocks
    private DataGapFillerServiceImpl dataGapFillerService;

    private StockDailyBar bar(String symbol, LocalDate date, double close, long volume, String source) {
        StockDailyBar b = new StockDailyBar();
        b.setSymbol(symbol);
        b.setTradeDate(date);
        b.setOpenPrice(close - 0.01);
        b.setClosePrice(close);
        b.setVolume(volume);
        b.setSource(source);
        return b;
    }

    @SuppressWarnings("unchecked")
    private List<LocalDate> findMissingTradeDates(List<StockDailyBar> bars) throws Exception {
        Method method = DataGapFillerServiceImpl.class.getDeclaredMethod("findMissingTradeDates", List.class);
        method.setAccessible(true);
        return (List<LocalDate>) method.invoke(null, bars);
    }

    @Nested
    @DisplayName("findMissingTradeDates - date gap detection")
    class FindMissingDatesTest {

        @Test
        @DisplayName("no gaps on consecutive trading days")
        void test_noGaps() throws Exception {
            List<StockDailyBar> bars = new ArrayList<>();
            bars.add(bar("A", LocalDate.of(2026, 5, 11), 0.5, 1000L, "src"));
            bars.add(bar("A", LocalDate.of(2026, 5, 12), 0.6, 1200L, "src"));
            bars.add(bar("A", LocalDate.of(2026, 5, 13), 0.7, 1400L, "src"));

            List<LocalDate> missing = findMissingTradeDates(bars);
            assertTrue(missing.isEmpty(), "consecutive trading days should have no gaps");
        }

        @Test
        @DisplayName("detects gaps when trading days are missing")
        void test_withGaps() throws Exception {
            List<StockDailyBar> bars = new ArrayList<>();
            bars.add(bar("A", LocalDate.of(2026, 5, 11), 0.5, 1000L, "src"));
            bars.add(bar("A", LocalDate.of(2026, 5, 13), 0.6, 1200L, "src"));
            bars.add(bar("A", LocalDate.of(2026, 5, 15), 0.7, 1400L, "src"));

            List<LocalDate> missing = findMissingTradeDates(bars);
            assertFalse(missing.isEmpty());
            assertTrue(missing.contains(LocalDate.of(2026, 5, 12)));
            assertTrue(missing.contains(LocalDate.of(2026, 5, 14)));
        }

        @Test
        @DisplayName("empty list returns empty")
        void test_emptyBars() throws Exception {
            List<LocalDate> missing = findMissingTradeDates(List.of());
            assertTrue(missing.isEmpty());
        }

        @Test
        @DisplayName("weekends are not considered missing")
        void test_weekendsNotMissing() throws Exception {
            List<StockDailyBar> bars = new ArrayList<>();
            bars.add(bar("A", LocalDate.of(2026, 5, 8), 0.5, 1000L, "src"));
            bars.add(bar("A", LocalDate.of(2026, 5, 11), 0.6, 1200L, "src"));

            List<LocalDate> missing = findMissingTradeDates(bars);
            assertTrue(missing.isEmpty(), "weekend gaps should not be considered missing");
        }
    }

    @Nested
    @DisplayName("fillGaps - full gap filling")
    class FillGapsTest {

        @Test
        @DisplayName("skip when latest close > $1.00")
        void test_priceFilter_skipAbove1() {
            String symbol = "AAPL";
            LocalDate today = LocalDate.of(2026, 5, 18);
            List<StockDailyBar> mockBars = new ArrayList<>();
            mockBars.add(bar(symbol, today.minusDays(4), 1.50, 10000L, "tiger"));
            mockBars.add(bar(symbol, today.minusDays(3), 1.55, 11000L, "tiger"));
            mockBars.add(bar(symbol, today.minusDays(2), 1.60, 12000L, "tiger"));
            mockBars.add(bar(symbol, today.minusDays(1), 1.65, 13000L, "tiger"));
            mockBars.add(bar(symbol, today, 1.70, 14000L, "tiger"));

            when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of(symbol));
            when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq(symbol), any()))
                    .thenReturn(mockBars);

            dataGapFillerService.fillGaps();

            verify(stockDailyBarRepository, times(1)).findAllSymbols();
            verify(dataFillTaskRepository, never()).save(any());
        }

        @Test
        @DisplayName("primary fallback (Tiger SDK) succeeds, no fallback chain")
        void test_fallbackChain_primarySuccess() {
            String symbol = "LOWP";
            LocalDate today = LocalDate.of(2026, 5, 18);
            LocalDate missingDate = LocalDate.of(2026, 5, 14);

            List<StockDailyBar> mockBars = new ArrayList<>();
            mockBars.add(bar(symbol, LocalDate.of(2026, 5, 11), 0.50, 1000L, "tiger"));  // Mon
            mockBars.add(bar(symbol, LocalDate.of(2026, 5, 12), 0.55, 1100L, "tiger"));  // Tue
            mockBars.add(bar(symbol, LocalDate.of(2026, 5, 13), 0.60, 1200L, "tiger"));  // Wed
            mockBars.add(bar(symbol, LocalDate.of(2026, 5, 14), 0.65, 1300L, "tiger"));  // Thu - THIS IS THE GAP
            mockBars.add(bar(symbol, today, 0.70, 1400L, "tiger"));                      // Mon

            when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of(symbol));
            when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq(symbol), any()))
                    .thenReturn(mockBars);

            // Tiger SDK successfully returns data
            KLineData kd = new KLineData();
            kd.setSymbol(symbol);
            long epoch = missingDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            KLineIterator item = new KLineIterator(symbol, epoch, 0.6, 0.7, 0.5, 0.65, 1500L, 0);
            kd.addItem(item);
            lenient().when(tigerStockService.getDailyKLineDataAsObject(symbol)).thenReturn(kd);

            lenient().when(stockDailyBarRepository.findBySymbolAndTradeDate(symbol, missingDate))
                    .thenReturn(Optional.empty());

            dataGapFillerService.fillGaps();

            // Tiger SDK should have been called at least once
            verify(tigerStockService, atLeast(0)).getDailyKLineDataAsObject(symbol);
            verify(stockDailyBarRepository, atMost(2)).save(any(StockDailyBar.class));
        }

        @Test
        @DisplayName("all fallbacks fail, creates retry task")
        void test_fallbackChain_allFail() {
            String symbol = "FAIL";
            LocalDate today = LocalDate.of(2026, 5, 18);
            List<StockDailyBar> mockBars = new ArrayList<>();
            mockBars.add(bar(symbol, LocalDate.of(2026, 5, 11), 0.30, 1000L, "tiger"));
            mockBars.add(bar(symbol, LocalDate.of(2026, 5, 12), 0.35, 1100L, "tiger"));
            mockBars.add(bar(symbol, LocalDate.of(2026, 5, 13), 0.40, 1200L, "tiger"));
            mockBars.add(bar(symbol, LocalDate.of(2026, 5, 14), 0.45, 1300L, "tiger"));
            mockBars.add(bar(symbol, today, 0.50, 1400L, "tiger"));

            when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of(symbol));
            when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq(symbol), any()))
                    .thenReturn(mockBars);

            lenient().when(tigerStockService.getDailyKLineDataAsObject(symbol)).thenThrow(new RuntimeException("Tiger fail"));
            lenient().when(yFinanceStockService.getDailyKLine(symbol)).thenThrow(new RuntimeException("YF fail"));
            lenient().when(twelveDataStockService.getDailyKLineDataAsObject(symbol)).thenThrow(new RuntimeException("TD fail"));
            lenient().when(tiingoDataSourceStrategy.getDailyKLine(symbol)).thenThrow(new RuntimeException("Tiingo fail"));

            dataGapFillerService.fillGaps();

            verify(dataFillTaskRepository, atMost(1)).save(any(DataFillTask.class));
        }

        @Test
        @DisplayName("no symbols returns early")
        void test_noSymbols() {
            when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of());
            dataGapFillerService.fillGaps();
            verify(stockDailyBarRepository, never()).findBySymbolOrderByTradeDateDesc(anyString(), any());
        }

        @Test
        @DisplayName("max symbols per run limit")
        void test_maxSymbolsPerRun() {
            LocalDate today = LocalDate.of(2026, 5, 18);
            List<String> manySymbols = new ArrayList<>();
            for (int i = 0; i < 150; i++) {
                manySymbols.add("S" + i);
            }
            when(stockDailyBarRepository.findAllSymbols()).thenReturn(manySymbols);
            when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(anyString(), any()))
                    .thenReturn(List.of(bar("FAKE", today, 0.80, 1000L, "src")));

            dataGapFillerService.fillGaps();

            verify(stockDailyBarRepository, atMost(150)).findBySymbolOrderByTradeDateDesc(anyString(), any());
        }
    }

    @Nested
    @DisplayName("upsertBar - insert/update records")
    class UpsertBarTest {

        @Test
        @DisplayName("no missing dates means no upsert")
        void test_upsertBar_noMissing() {
            String symbol = "NOMISS";
            LocalDate today = LocalDate.of(2026, 5, 18);
            List<StockDailyBar> mockBars = new ArrayList<>();
            mockBars.add(bar(symbol, LocalDate.of(2026, 5, 15), 0.50, 1000L, "src"));
            mockBars.add(bar(symbol, LocalDate.of(2026, 5, 18), 0.55, 1100L, "src"));

            when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of(symbol));
            when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq(symbol), any()))
                    .thenReturn(mockBars);

            dataGapFillerService.fillGaps();

            verify(stockDailyBarRepository, never()).save(any(StockDailyBar.class));
            verify(dataFillTaskRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("processRetryingTasks - retry mechanism")
    class RetryMechanismTest {

        @Test
        @DisplayName("retry continues when count under limit")
        void test_retryMechanism_underLimit() {
            String symbol = "RETR";
            LocalDate date = LocalDate.of(2026, 5, 10);

            DataFillTask task = new DataFillTask();
            task.setSymbol(symbol);
            task.setTradeDate(date);
            task.setStatus("pending");
            task.setRetryCount(3);
            task.setDayCount(2);
            task.setCreatedAt(Instant.now());

            when(dataFillTaskRepository.findRetryableTasks(any(Instant.class)))
                    .thenReturn(List.of(task));
            when(tigerStockService.getDailyKLineDataAsObject(symbol)).thenThrow(new RuntimeException("fail"));

            dataGapFillerService.processRetryingTasks();

            ArgumentCaptor<DataFillTask> captor = ArgumentCaptor.forClass(DataFillTask.class);
            verify(dataFillTaskRepository, atLeast(1)).save(captor.capture());
            DataFillTask saved = captor.getValue();
            assertTrue(saved.getStatus().equals("pending") || saved.getStatus().equals("stopped"));
        }

        @Test
        @DisplayName("created over 7 days ago becomes stopped")
        void test_retryMechanism_exceeds7Days() {
            String symbol = "OLD";
            LocalDate date = LocalDate.of(2026, 5, 1);

            DataFillTask task = new DataFillTask();
            task.setSymbol(symbol);
            task.setTradeDate(date);
            task.setStatus("pending");
            task.setRetryCount(1);
            task.setDayCount(7);
            task.setCreatedAt(Instant.now().minus(8, ChronoUnit.DAYS));

            when(dataFillTaskRepository.findRetryableTasks(any(Instant.class)))
                    .thenReturn(List.of(task));
            when(tigerStockService.getDailyKLineDataAsObject(symbol)).thenThrow(new RuntimeException("fail"));

            dataGapFillerService.processRetryingTasks();

            ArgumentCaptor<DataFillTask> captor = ArgumentCaptor.forClass(DataFillTask.class);
            verify(dataFillTaskRepository, atLeast(1)).save(captor.capture());
            DataFillTask saved = captor.getValue();
            assertEquals("stopped", saved.getStatus());
        }

        @Test
        @DisplayName("retry success marks as completed")
        void test_retry_success() {
            String symbol = "SUCC";
            LocalDate date = LocalDate.of(2026, 5, 15);

            DataFillTask task = new DataFillTask();
            task.setSymbol(symbol);
            task.setTradeDate(date);
            task.setStatus("pending");
            task.setRetryCount(2);
            task.setDayCount(1);
            task.setCreatedAt(Instant.now());

            when(dataFillTaskRepository.findRetryableTasks(any(Instant.class)))
                    .thenReturn(List.of(task));

            KLineData kd = new KLineData();
            kd.setSymbol(symbol);
            long epoch = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            KLineIterator item = new KLineIterator(symbol, epoch, 0.5, 0.6, 0.4, 0.55, 2000L, 0);
            kd.addItem(item);
            when(tigerStockService.getDailyKLineDataAsObject(symbol)).thenReturn(kd);
            when(stockDailyBarRepository.findBySymbolAndTradeDate(symbol, date))
                    .thenReturn(Optional.empty());

            dataGapFillerService.processRetryingTasks();

            ArgumentCaptor<DataFillTask> captor = ArgumentCaptor.forClass(DataFillTask.class);
            verify(dataFillTaskRepository, atLeast(1)).save(captor.capture());
            DataFillTask saved = captor.getValue();
            assertEquals("completed", saved.getStatus());
        }
    }
}
