package com.stock.invest.service;

import com.stock.invest.config.GapFillProperties;
import com.stock.invest.entity.DataFillTask;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
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

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DataGapFiller 持久化逻辑测试 —— mock repository
 * 覆盖 FILL-001 ~ FILL-005
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DataGapFiller — 持久化逻辑 (persist / mergeAfterHours)")
class DataGapFillerPersistTest {

    private static final ZoneId AMERICA_NY = ZoneId.of("America/New_York");

    @Mock private StockDailyBarRepository stockDailyBarRepository;
    @Mock private DataFillTaskRepository dataFillTaskRepository;
    @Mock private DataSourceStrategy tigerDataSource;
    @Mock private DataSourceStrategy yfinanceDataSource;
    @Mock private GapFillProperties gapFillProperties;
    @Mock private DataFillProgressService dataFillProgressService;
    @Mock private TradingCalendarDbService tradingCalendarDbService;
    @Mock private StockDataSourcePriorityService stockDataSourcePriorityService;
    @Mock private SymbolBlacklistService symbolBlacklistService;

    @Captor private ArgumentCaptor<StockDailyBar> barCaptor;
    @Captor private ArgumentCaptor<DataFillTask> taskCaptor;

    private DataGapFillerServiceImpl service;

    @BeforeEach
    void setUp() {
        when(tigerDataSource.getSourceName()).thenReturn("tiger");
        when(tigerDataSource.isAvailable()).thenReturn(true);
        when(yfinanceDataSource.getSourceName()).thenReturn("yfinance");
        when(yfinanceDataSource.isAvailable()).thenReturn(true);

        when(gapFillProperties.getMinPriceThreshold()).thenReturn(1.0);

        // Allow trading on weekdays so findMissingTradeDates finds gaps
        when(tradingCalendarDbService.isTradingDay(anyString(), any(LocalDate.class))).thenReturn(true);

        List<DataSourceStrategy> dataSources = List.of(tigerDataSource, yfinanceDataSource);
        service = new DataGapFillerServiceImpl(
                stockDailyBarRepository, dataFillTaskRepository, dataSources,
                gapFillProperties, dataFillProgressService, tradingCalendarDbService,
                stockDataSourcePriorityService, symbolBlacklistService);
    }

    private LocalDate nyToday() {
        return ZonedDateTime.now(AMERICA_NY).toLocalDate();
    }

    // FILL-001: persist 保存全部字段（含新增字段）—— 通过 ArgumentCaptor 验证
    @Test
    @DisplayName("FILL-001: persist 保存全部字段（含 highPrice/lowPrice/changePercent/afterHours）")
    void persistSavesAllNewFields() {
        LocalDate today = nyToday();
        LocalDate tradeDate = today.minusDays(2);
        LocalDate stopDate = today.minusDays(5);

        StockDailyBar existingBar = new StockDailyBar();
        existingBar.setSymbol("AAPL");
        existingBar.setTradeDate(stopDate);
        existingBar.setOpenPrice(0.5);
        existingBar.setClosePrice(0.5);
        existingBar.setHighPrice(0.6);
        existingBar.setLowPrice(0.4);
        existingBar.setVolume(100L);
        existingBar.setSource("yfinance");

        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("AAPL"));
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq("AAPL"), any()))
                .thenReturn(new ArrayList<>(List.of(existingBar)));
        when(stockDailyBarRepository.findBySymbolAndTradeDate(eq("AAPL"), eq(tradeDate)))
                .thenReturn(Optional.empty());

        KLineData kd = new KLineData();
        kd.setSymbol("AAPL");
        KLineIterator item = new KLineIterator(
                "AAPL", tradeDate.atStartOfDay(AMERICA_NY).toInstant().toEpochMilli(),
                150.0, 155.0, 148.0, 152.5,
                1_000_000L, 5_000_000.0,
                1.67, 153.0, 0.33);
        item.setTimeString(tradeDate.toString());
        kd.setItems(List.of(item));

        when(tigerDataSource.getDailyKLineDataByDateRange(eq("AAPL"), eq(tradeDate)))
                .thenReturn(kd);

        service.fillGaps();

        verify(stockDailyBarRepository, atLeastOnce()).save(barCaptor.capture());
        StockDailyBar saved = barCaptor.getValue();

        assertEquals("AAPL", saved.getSymbol());
        assertEquals(tradeDate, saved.getTradeDate());
        assertEquals(150.0, saved.getOpenPrice(), 0.001);
        assertEquals(155.0, saved.getHighPrice(), 0.001);
        assertEquals(148.0, saved.getLowPrice(), 0.001);
        assertEquals(152.5, saved.getClosePrice(), 0.001);
        assertEquals(1.67, saved.getChangePercent(), 0.001);
        assertEquals(153.0, saved.getAfterHours(), 0.001);
        assertEquals(0.33, saved.getAfterHoursChangePercent(), 0.001);
        assertEquals(1_000_000L, saved.getVolume());
        assertEquals("tiger", saved.getSource());
    }

    // FILL-002: persist 更新已有实体（findBySymbolAndTradeDate 有值）
    @Test
    @DisplayName("FILL-002: persist 更新已有实体字段")
    void persistUpdatesExistingEntity() {
        LocalDate today = nyToday();
        LocalDate tradeDate = today.minusDays(2);
        LocalDate stopDate = today.minusDays(5);

        StockDailyBar existingBar = new StockDailyBar();
        existingBar.setSymbol("AAPL");
        existingBar.setTradeDate(stopDate);
        existingBar.setOpenPrice(0.5);
        existingBar.setClosePrice(0.5);
        existingBar.setVolume(100L);
        existingBar.setSource("yfinance");

        StockDailyBar existingEntity = new StockDailyBar();
        existingEntity.setId(99L);
        existingEntity.setSymbol("AAPL");
        existingEntity.setTradeDate(tradeDate);
        existingEntity.setOpenPrice(140.0);
        existingEntity.setClosePrice(142.0);
        existingEntity.setVolume(500_000L);
        existingEntity.setSource("yfinance");

        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("AAPL"));
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq("AAPL"), any()))
                .thenReturn(new ArrayList<>(List.of(existingBar)));
        when(stockDailyBarRepository.findBySymbolAndTradeDate(eq("AAPL"), eq(tradeDate)))
                .thenReturn(Optional.of(existingEntity));

        KLineData kd = new KLineData();
        kd.setSymbol("AAPL");
        KLineIterator item = new KLineIterator(
                "AAPL", tradeDate.atStartOfDay(AMERICA_NY).toInstant().toEpochMilli(),
                155.0, 158.0, 152.0, 156.0,
                2_000_000L, 10_000_000.0,
                2.5, 157.0, 0.64);
        item.setTimeString(tradeDate.toString());
        kd.setItems(List.of(item));

        when(tigerDataSource.getDailyKLineDataByDateRange(eq("AAPL"), eq(tradeDate)))
                .thenReturn(kd);

        service.fillGaps();

        verify(stockDailyBarRepository, atLeastOnce()).save(barCaptor.capture());
        StockDailyBar saved = barCaptor.getValue();

        assertEquals(99L, saved.getId());
        assertEquals(155.0, saved.getOpenPrice(), 0.001);
        assertEquals(158.0, saved.getHighPrice(), 0.001);
        assertEquals(2.5, saved.getChangePercent(), 0.001);
        assertEquals(157.0, saved.getAfterHours(), 0.001);
    }

    // FILL-003: persist 设置 source 为数据源名称
    @Test
    @DisplayName("FILL-003: persist 正确设置 source 字段")
    void persistSetsSourceCorrectly() {
        LocalDate today = nyToday();
        LocalDate tradeDate = today.minusDays(2);
        LocalDate stopDate = today.minusDays(5);

        StockDailyBar existingBar = new StockDailyBar();
        existingBar.setSymbol("AAPL");
        existingBar.setTradeDate(stopDate);
        existingBar.setOpenPrice(0.5);
        existingBar.setClosePrice(0.5);
        existingBar.setVolume(100L);
        existingBar.setSource("yfinance");

        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("AAPL"));
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq("AAPL"), any()))
                .thenReturn(new ArrayList<>(List.of(existingBar)));
        when(stockDailyBarRepository.findBySymbolAndTradeDate(eq("AAPL"), eq(tradeDate)))
                .thenReturn(Optional.empty());

        // Use yfinance data source (not tiger)
        KLineData kd = new KLineData();
        kd.setSymbol("AAPL");
        KLineIterator item = new KLineIterator(
                "AAPL", tradeDate.atStartOfDay(AMERICA_NY).toInstant().toEpochMilli(),
                150.0, 155.0, 148.0, 152.5,
                1_000_000L, 5_000_000.0,
                1.67, 153.0, 0.33);
        item.setTimeString(tradeDate.toString());
        kd.setItems(List.of(item));

        when(yfinanceDataSource.getDailyKLineDataByDateRange(eq("AAPL"), eq(tradeDate)))
                .thenReturn(kd);
        when(tigerDataSource.getDailyKLineDataByDateRange(eq("AAPL"), eq(tradeDate)))
                .thenAnswer(inv -> {
                    KLineData empty = new KLineData();
                    empty.setSymbol("AAPL");
                    empty.setItems(List.of());
                    return empty;
                });

        service.fillGaps();

        verify(stockDailyBarRepository, atLeastOnce()).save(barCaptor.capture());
        // The second save is from mergeAfterHoursIfAvailable for tiger source
        // Actually yfinance data source is used, so source should be "yfinance"
        // But both data sources are tried: tiger first, then yfinance
        // Tiger returns empty, yfinance returns data, so source should be "yfinance"
        // But there may be multiple saves due to mergeAfterHours
        StockDailyBar saved = barCaptor.getValue();
        assertEquals("yfinance", saved.getSource());
    }

    // FILL-004: data_fill_task 重试任务在失败时创建
    @Test
    @DisplayName("FILL-004: 所有数据源失败时创建 retry 任务")
    void createsRetryTaskWhenAllSourcesFail() throws Exception {
        LocalDate today = nyToday();
        LocalDate tradeDate = today.minusDays(2);
        LocalDate stopDate = today.minusDays(5);

        StockDailyBar existingBar = new StockDailyBar();
        existingBar.setSymbol("AAPL");
        existingBar.setTradeDate(stopDate);
        existingBar.setOpenPrice(0.5);
        existingBar.setClosePrice(0.5);
        existingBar.setVolume(100L);
        existingBar.setSource("yfinance");

        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("AAPL"));
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq("AAPL"), any()))
                .thenReturn(new ArrayList<>(List.of(existingBar)));

        // Both data sources return empty
        KLineData empty = new KLineData();
        empty.setSymbol("AAPL");
        empty.setItems(List.of());

        when(tigerDataSource.getDailyKLineDataByDateRange(eq("AAPL"), eq(tradeDate)))
                .thenReturn(empty);
        when(yfinanceDataSource.getDailyKLineDataByDateRange(eq("AAPL"), eq(tradeDate)))
                .thenReturn(empty);

        when(dataFillTaskRepository.findBySymbolAndTradeDate(eq("AAPL"), eq(tradeDate)))
                .thenReturn(Optional.empty());

        service.fillGaps();

        verify(dataFillTaskRepository, atLeastOnce()).save(taskCaptor.capture());
        DataFillTask task = taskCaptor.getValue();
        assertEquals("AAPL", task.getSymbol());
        assertNotNull(task.getTradeDate(), "retry task should have a trade date");
        assertEquals("retrying", task.getStatus());
        assertEquals(1, task.getRetryCount());
    }

    // FILL-005: mergeAfterHours 仅对 tiger/tigeropen source 执行
    @Test
    @DisplayName("FILL-005: mergeAfterHours 对非 tiger source 跳过")
    void mergeAfterHoursSkipsNonTigerSource() {
        LocalDate today = nyToday();
        LocalDate tradeDate = today.minusDays(2);
        LocalDate stopDate = today.minusDays(5);

        StockDailyBar existingBar = new StockDailyBar();
        existingBar.setSymbol("AAPL");
        existingBar.setTradeDate(stopDate);
        existingBar.setOpenPrice(0.5);
        existingBar.setClosePrice(0.5);
        existingBar.setVolume(100L);
        existingBar.setSource("yfinance");

        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("AAPL"));
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq("AAPL"), any()))
                .thenReturn(new ArrayList<>(List.of(existingBar)));
        when(stockDailyBarRepository.findBySymbolAndTradeDate(eq("AAPL"), eq(tradeDate)))
                .thenReturn(Optional.empty());

        KLineData kd = new KLineData();
        kd.setSymbol("AAPL");
        KLineIterator item = new KLineIterator(
                "AAPL", tradeDate.atStartOfDay(AMERICA_NY).toInstant().toEpochMilli(),
                150.0, 155.0, 148.0, 152.5,
                1_000_000L, 5_000_000.0,
                1.67, 0.0, 0.0);
        item.setTimeString(tradeDate.toString());
        kd.setItems(List.of(item));

        // tiger returns empty so yfinance is used
        when(tigerDataSource.getDailyKLineDataByDateRange(eq("AAPL"), eq(tradeDate)))
                .thenAnswer(inv -> { KLineData e = new KLineData(); e.setSymbol("AAPL"); e.setItems(List.of()); return e; });
        when(yfinanceDataSource.getDailyKLineDataByDateRange(eq("AAPL"), eq(tradeDate)))
                .thenReturn(kd);

        service.fillGaps();

        verify(stockDailyBarRepository, atLeastOnce()).save(barCaptor.capture());
        StockDailyBar saved = barCaptor.getValue();
        assertEquals("yfinance", saved.getSource());
        // mergeAfterHours should not be called for yfinance source
        // The afterHours should remain 0.0 (from KLineIterator double default)
        assertEquals(0.0, saved.getAfterHours(), 0.001, "afterHours remains 0.0 for non-tiger source");
    }
}
