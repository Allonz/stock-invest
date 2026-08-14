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
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

/**
 * DataGapFiller 持久化逻辑测试 —— mock repository
 * 覆盖 FILL-001 ~ FILL-005
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DataGapFiller — 持久化逻辑 (persist / mergeAfterHours)")
class DataGapFillerPersistTest {

    private static final ZoneId AMERICA_NY = ZoneId.of("America/New_York");

    @Mock private StockDailyBarRepository stockDailyBarRepository;
    @Mock private DataFillTaskRepository dataFillTaskRepository;
    @Mock private DataSourceStrategy tigerDataSource;
    @Mock private DataSourceStrategy yfinanceDataSource;
    @Mock private GapFillProperties gapFillProperties;
    @Mock private DataFillProgressService dataFillProgressService;
    @Mock private com.stock.invest.service.RetryProgressService retryProgressService;
    @Mock private TradingCalendarDbService tradingCalendarDbService;
    @Mock private StockDataSourcePriorityService stockDataSourcePriorityService;
    @Mock private SymbolBlacklistService symbolBlacklistService;
    @Mock private PlatformTransactionManager transactionManager;

    @Captor private ArgumentCaptor<StockDailyBar> barCaptor;
    @Captor private ArgumentCaptor<DataFillTask> taskCaptor;

    private DataGapFillerServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(tigerDataSource.getSourceName()).thenReturn("tiger");
        lenient().when(tigerDataSource.isAvailable()).thenReturn(true);
        lenient().when(yfinanceDataSource.getSourceName()).thenReturn("yfinance");
        lenient().when(yfinanceDataSource.isAvailable()).thenReturn(true);

        when(gapFillProperties.getMinPriceThreshold()).thenReturn(java.math.BigDecimal.valueOf(1.0));

        // Allow trading on weekdays so findMissingTradeDates finds gaps
        when(tradingCalendarDbService.isTradingDay(anyString(), any(LocalDate.class))).thenReturn(true);

        // Priority service returns fallback chain order
        when(stockDataSourcePriorityService.getPriorityList(anyString()))
                .thenReturn(java.util.List.of("yfinance", "tiger"));

        List<DataSourceStrategy> dataSources = List.of(tigerDataSource, yfinanceDataSource);
        service = new DataGapFillerServiceImpl(
                stockDailyBarRepository, dataFillTaskRepository, dataSources,
                gapFillProperties, dataFillProgressService, retryProgressService, tradingCalendarDbService,
                stockDataSourcePriorityService, symbolBlacklistService,
                transactionManager);
    }

    private LocalDate nyToday() {
        return ZonedDateTime.now(AMERICA_NY).toLocalDate();
    }

    // FILL-001: persist 保存全部字段（含新增字段）—— 通过 ArgumentCaptor 验证
    @Test
    @DisplayName("FILL-001: persist 保存全部字段（含 highPrice/lowPrice/changePercent/afterHours）")
    void persistSavesAllNewFields() {
        LocalDate today = nyToday();
        LocalDate stopDate = today.minusDays(5);

        StockDailyBar existingBar = new StockDailyBar();
        existingBar.setSymbol("AAPL");
        existingBar.setTradeDate(stopDate);
        existingBar.setOpenPrice(java.math.BigDecimal.valueOf(0.5));
        existingBar.setClosePrice(java.math.BigDecimal.valueOf(0.5));
        existingBar.setHighPrice(java.math.BigDecimal.valueOf(0.6));
        existingBar.setLowPrice(java.math.BigDecimal.valueOf(0.4));
        existingBar.setVolume(100L);
        existingBar.setSource("yfinance");

        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("AAPL"));
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq("AAPL"), any()))
                .thenReturn(new ArrayList<>(List.of(existingBar)));
        when(stockDailyBarRepository.findBySymbolAndTradeDate(eq("AAPL"), any()))
                .thenReturn(Optional.empty());

        lenient().when(tigerDataSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenAnswer(inv -> {
                    LocalDate date = inv.getArgument(1);
                    KLineData kd = new KLineData();
                    kd.setSymbol("AAPL");
                    KLineIterator item = new KLineIterator(
                            "AAPL", date.atStartOfDay(AMERICA_NY).toInstant().toEpochMilli(),
                            java.math.BigDecimal.valueOf(150.0), java.math.BigDecimal.valueOf(155.0),
                            java.math.BigDecimal.valueOf(148.0), java.math.BigDecimal.valueOf(152.5),
                            1_000_000L, 5_000_000.0,
                            java.math.BigDecimal.valueOf(1.67), java.math.BigDecimal.valueOf(153.0),
                            java.math.BigDecimal.valueOf(0.33));
                    item.setTimeString(date.toString());
                    kd.setItems(List.of(item));
                    return kd;
                });

        service.fillGaps();

        verify(stockDailyBarRepository, atLeastOnce()).save(barCaptor.capture());
        StockDailyBar saved = barCaptor.getValue();

        assertEquals("AAPL", saved.getSymbol());
        assertEquals(0, java.math.BigDecimal.valueOf(150.0).compareTo(saved.getOpenPrice()));
        assertEquals(0, java.math.BigDecimal.valueOf(155.0).compareTo(saved.getHighPrice()));
        assertEquals(0, java.math.BigDecimal.valueOf(148.0).compareTo(saved.getLowPrice()));
        assertEquals(0, java.math.BigDecimal.valueOf(152.5).compareTo(saved.getClosePrice()));
        assertEquals(0, java.math.BigDecimal.valueOf(1.67).compareTo(saved.getChangePercent()));
        assertEquals(0, java.math.BigDecimal.valueOf(153.0).compareTo(saved.getAfterHours()));
        assertEquals(0, java.math.BigDecimal.valueOf(0.33).compareTo(saved.getAfterHoursChangePercent()));
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
        existingBar.setOpenPrice(java.math.BigDecimal.valueOf(0.5));
        existingBar.setClosePrice(java.math.BigDecimal.valueOf(0.5));
        existingBar.setVolume(100L);
        existingBar.setSource("yfinance");

        StockDailyBar existingEntity = new StockDailyBar();
        existingEntity.setId(99L);
        existingEntity.setSymbol("AAPL");
        existingEntity.setTradeDate(tradeDate);
        existingEntity.setOpenPrice(java.math.BigDecimal.valueOf(140.0));
        existingEntity.setClosePrice(java.math.BigDecimal.valueOf(142.0));
        existingEntity.setVolume(500_000L);
        existingEntity.setSource("yfinance");

        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("AAPL"));
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq("AAPL"), any()))
                .thenReturn(new ArrayList<>(List.of(existingBar)));
        when(stockDailyBarRepository.findBySymbolAndTradeDate(eq("AAPL"), any()))
                .thenReturn(Optional.of(existingEntity));

        lenient().when(tigerDataSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenAnswer(inv -> {
                    LocalDate date = inv.getArgument(1);
                    KLineData kd = new KLineData();
                    kd.setSymbol("AAPL");
                    KLineIterator item = new KLineIterator(
                            "AAPL", date.atStartOfDay(AMERICA_NY).toInstant().toEpochMilli(),
                            java.math.BigDecimal.valueOf(155.0), java.math.BigDecimal.valueOf(158.0),
                            java.math.BigDecimal.valueOf(152.0), java.math.BigDecimal.valueOf(156.0),
                            2_000_000L, 10_000_000.0,
                            java.math.BigDecimal.valueOf(2.5), java.math.BigDecimal.valueOf(157.0),
                            java.math.BigDecimal.valueOf(0.64));
                    item.setTimeString(date.toString());
                    kd.setItems(List.of(item));
                    return kd;
                });

        service.fillGaps();

        verify(stockDailyBarRepository, atLeastOnce()).save(barCaptor.capture());
        StockDailyBar saved = barCaptor.getValue();

        assertEquals(99L, saved.getId());
        assertEquals(0, java.math.BigDecimal.valueOf(155.0).compareTo(saved.getOpenPrice()));
        assertEquals(0, java.math.BigDecimal.valueOf(158.0).compareTo(saved.getHighPrice()));
        assertEquals(0, java.math.BigDecimal.valueOf(2.5).compareTo(saved.getChangePercent()));
        assertEquals(0, java.math.BigDecimal.valueOf(157.0).compareTo(saved.getAfterHours()));
    }

    // FILL-003: persist 设置 source 为数据源名称
    @Test
    @DisplayName("FILL-003: persist 正确设置 source 字段")
    void persistSetsSourceCorrectly() {
        LocalDate today = nyToday();
        LocalDate stopDate = today.minusDays(5);

        StockDailyBar existingBar = new StockDailyBar();
        existingBar.setSymbol("AAPL");
        existingBar.setTradeDate(stopDate);
        existingBar.setOpenPrice(java.math.BigDecimal.valueOf(0.5));
        existingBar.setClosePrice(java.math.BigDecimal.valueOf(0.5));
        existingBar.setVolume(100L);
        existingBar.setSource("yfinance");

        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("AAPL"));
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq("AAPL"), any()))
                .thenReturn(new ArrayList<>(List.of(existingBar)));
        when(stockDailyBarRepository.findBySymbolAndTradeDate(eq("AAPL"), any()))
                .thenReturn(Optional.empty());

        lenient().when(yfinanceDataSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenAnswer(inv -> {
                    LocalDate date = inv.getArgument(1);
                    KLineData kd = new KLineData();
                    kd.setSymbol("AAPL");
                    KLineIterator item = new KLineIterator(
                            "AAPL", date.atStartOfDay(AMERICA_NY).toInstant().toEpochMilli(),
                            java.math.BigDecimal.valueOf(150.0), java.math.BigDecimal.valueOf(155.0),
                            java.math.BigDecimal.valueOf(148.0), java.math.BigDecimal.valueOf(152.5),
                            1_000_000L, 5_000_000.0,
                            java.math.BigDecimal.valueOf(1.67), java.math.BigDecimal.valueOf(153.0),
                            java.math.BigDecimal.valueOf(0.33));
                    item.setTimeString(date.toString());
                    kd.setItems(List.of(item));
                    return kd;
                });
        lenient().when(tigerDataSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
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
    // 2026-08-13 修正：EMPTY（成功但无数据）计入黑名单判定 —— ≥2 源报空即 1 次确认不存在 → 进黑名单
    // （原 P1-3 语义：EMPTY 不计黑名单，导致 4 源全空时 notFoundCount=0、永不进黑名单、任务无限重试）
    @Test
    @DisplayName("FILL-004: 所有数据源空结果时进黑名单（EMPTY 计入判定），retry 任务停止")
    void createsRetryTaskWhenAllSourcesFail() throws Exception {
        LocalDate today = nyToday();
        LocalDate stopDate = today.minusDays(5);

        StockDailyBar existingBar = new StockDailyBar();
        existingBar.setSymbol("AAPL");
        existingBar.setTradeDate(stopDate);
        existingBar.setOpenPrice(java.math.BigDecimal.valueOf(0.5));
        existingBar.setClosePrice(java.math.BigDecimal.valueOf(0.5));
        existingBar.setVolume(100L);
        existingBar.setSource("yfinance");

        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("AAPL"));
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq("AAPL"), any()))
                .thenReturn(new ArrayList<>(List.of(existingBar)));

        // Both data sources return empty (成功但无数据 = EMPTY，计入黑名单判定)
        KLineData empty = new KLineData();
        empty.setSymbol("AAPL");
        empty.setItems(List.of());

        lenient().when(tigerDataSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenReturn(empty);
        lenient().when(yfinanceDataSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenReturn(empty);

        service.fillGaps();

        // 2026-08-13：EMPTY 计入 → 2 源报空 → recordNotFound + 停任务（一次进黑名单）
        // fillGaps 对每个缺失日期都会触发一次 recordNotFound，故用 atLeastOnce
        verify(symbolBlacklistService, atLeastOnce()).recordNotFound(eq("AAPL"), anyMap());
        verify(dataFillTaskRepository, atLeastOnce()).updateStatusBySymbolAndStatusIn(
                eq("AAPL"), anyList(), eq("stopped"), anyString());
        verify(dataFillTaskRepository, never()).save(taskCaptor.capture());
    }

    // FILL-005: mergeAfterHours 仅对 tiger/tigeropen source 执行
    @Test
    @DisplayName("FILL-005: mergeAfterHours 对非 tiger source 跳过")
    void mergeAfterHoursSkipsNonTigerSource() {
        LocalDate today = nyToday();
        LocalDate stopDate = today.minusDays(5);

        StockDailyBar existingBar = new StockDailyBar();
        existingBar.setSymbol("AAPL");
        existingBar.setTradeDate(stopDate);
        existingBar.setOpenPrice(java.math.BigDecimal.valueOf(0.5));
        existingBar.setClosePrice(java.math.BigDecimal.valueOf(0.5));
        existingBar.setVolume(100L);
        existingBar.setSource("yfinance");

        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("AAPL"));
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq("AAPL"), any()))
                .thenReturn(new ArrayList<>(List.of(existingBar)));
        when(stockDailyBarRepository.findBySymbolAndTradeDate(eq("AAPL"), any()))
                .thenReturn(Optional.empty());

        // tiger returns empty so yfinance is used
        lenient().when(tigerDataSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenAnswer(inv -> { KLineData e = new KLineData(); e.setSymbol("AAPL"); e.setItems(List.of()); return e; });
        lenient().when(yfinanceDataSource.getDailyKLineDataByDateRange(eq("AAPL"), any()))
                .thenAnswer(inv -> {
                    LocalDate date = inv.getArgument(1);
                    KLineData kd = new KLineData();
                    kd.setSymbol("AAPL");
                    KLineIterator item = new KLineIterator(
                            "AAPL", date.atStartOfDay(AMERICA_NY).toInstant().toEpochMilli(),
                            java.math.BigDecimal.valueOf(150.0), java.math.BigDecimal.valueOf(155.0),
                            java.math.BigDecimal.valueOf(148.0), java.math.BigDecimal.valueOf(152.5),
                            1_000_000L, 5_000_000.0,
                            java.math.BigDecimal.valueOf(1.67), java.math.BigDecimal.ZERO,
                            java.math.BigDecimal.ZERO);
                    item.setTimeString(date.toString());
                    kd.setItems(List.of(item));
                    return kd;
                });

        service.fillGaps();

        verify(stockDailyBarRepository, atLeastOnce()).save(barCaptor.capture());
        StockDailyBar saved = barCaptor.getValue();
        assertEquals("yfinance", saved.getSource());
        // mergeAfterHours should not be called for yfinance source
        // The afterHours should remain ZERO (from KLineIterator constructor default)
        assertEquals(0, java.math.BigDecimal.ZERO.compareTo(saved.getAfterHours()),
                "afterHours remains ZERO for non-tiger source");
    }

    // §4.2: 事务回滚专项 —— TransactionTemplate 独立事务下失败不整体回滚
    @Test
    @DisplayName("§4.2: persist 失败仅回滚自身事务，已落库数据保留，批次继续")
    void persistFailureRollsBackOnlyItsOwnTransaction() {
        org.springframework.transaction.TransactionStatus status =
                mock(org.springframework.transaction.TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(status);

        LocalDate today = nyToday();
        LocalDate stopDate = today.minusDays(5);

        StockDailyBar s1Bar = lowBar("S1", stopDate);
        StockDailyBar s2Bar = lowBar("S2", stopDate);

        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("S1", "S2"));
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq("S1"), any()))
                .thenReturn(new ArrayList<>(List.of(s1Bar)));
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq("S2"), any()))
                .thenReturn(new ArrayList<>(List.of(s2Bar)));
        when(stockDailyBarRepository.findBySymbolAndTradeDate(anyString(), any()))
                .thenReturn(Optional.empty());

        // yfinance（优先级最高）对每个缺失日期返回匹配数据 → 走 persist
        lenient().when(yfinanceDataSource.getDailyKLineDataByDateRange(anyString(), any()))
                .thenAnswer(inv -> {
                    LocalDate date = inv.getArgument(1);
                    KLineData kd = new KLineData();
                    kd.setSymbol(inv.getArgument(0));
                    KLineIterator item = new KLineIterator(
                            inv.getArgument(0), date.atStartOfDay(AMERICA_NY).toInstant().toEpochMilli(),
                            java.math.BigDecimal.valueOf(150.0), java.math.BigDecimal.valueOf(155.0),
                            java.math.BigDecimal.valueOf(148.0), java.math.BigDecimal.valueOf(152.5),
                            1_000_000L, 5_000_000.0, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                            java.math.BigDecimal.ZERO);
                    item.setTimeString(date.toString());
                    kd.setItems(List.of(item));
                    return kd;
                });

        // 首个 save（S1 第一个缺失日期）抛异常 —— 模拟单条落库失败
        AtomicInteger saveCount = new AtomicInteger();
        doAnswer(inv -> {
            if (saveCount.getAndIncrement() == 0) {
                throw new RuntimeException("db write failed");
            }
            return inv.getArgument(0);
        }).when(stockDailyBarRepository).save(any(StockDailyBar.class));

        assertDoesNotThrow(() -> service.fillGaps());

        // 失败的那条落库：独立事务回滚（不连带其他成功 save）
        verify(transactionManager, atLeastOnce()).rollback(status);
        // 其余成功落库：独立事务提交 —— 证明无整体回滚
        verify(transactionManager, atLeastOnce()).commit(status);
        // 批次未中止：S2 仍被处理
        verify(stockDailyBarRepository, times(1)).findBySymbolOrderByTradeDateDesc(eq("S2"), any());
        // 至少有一条 save 成功（S2 的缺失日期）
        verify(stockDailyBarRepository, atLeastOnce()).save(any(StockDailyBar.class));
    }

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
}
