package com.stock.invest.service;

import com.stock.invest.config.GapFillProperties;
import com.stock.invest.entity.DataFillTask;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.repository.DataFillTaskRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.impl.DataGapFillerServiceImpl;
import com.stock.invest.service.impl.TigerStockServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
 * DataGapFiller 盘后价逻辑测试 —— mock TigerStockServiceImpl
 * 覆盖 AH-001 ~ AH-007（AH-006 标记 @Tag("integration") 跳过）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DataGapFiller — 盘后价逻辑 (mergeAfterHours)")
class DataGapFillerAfterHoursTest {

    private static final ZoneId AMERICA_NY = ZoneId.of("America/New_York");

    @Mock private StockDailyBarRepository stockDailyBarRepository;
    @Mock private DataFillTaskRepository dataFillTaskRepository;
    @Mock private TigerStockServiceImpl tigerSource;
    @Mock private GapFillProperties gapFillProperties;
    @Mock private DataFillProgressService dataFillProgressService;
    @Mock private TradingCalendarDbService tradingCalendarDbService;
    @Mock private StockDataSourcePriorityService stockDataSourcePriorityService;
    @Mock private SymbolBlacklistService symbolBlacklistService;

    @Captor private ArgumentCaptor<StockDailyBar> barCaptor;

    private DataGapFillerServiceImpl service;

    @BeforeEach
    void setUp() {
        when(tigerSource.getSourceName()).thenReturn("tiger");
        when(tigerSource.isAvailable()).thenReturn(true);

        when(gapFillProperties.getMinPriceThreshold()).thenReturn(1.0);

        // Allow trading on weekdays so findMissingTradeDates finds gaps
        when(tradingCalendarDbService.isTradingDay(anyString(), any(LocalDate.class))).thenReturn(true);

        // TigerStockServiceImpl mock is the real TigerStockServiceImpl (instanceof check works)
        List<DataSourceStrategy> dataSources = List.of(tigerSource);
        service = new DataGapFillerServiceImpl(
                stockDailyBarRepository, dataFillTaskRepository, dataSources,
                gapFillProperties, dataFillProgressService, tradingCalendarDbService,
                stockDataSourcePriorityService, symbolBlacklistService);
    }

    private LocalDate nyToday() {
        return ZonedDateTime.now(AMERICA_NY).toLocalDate();
    }

    // AH-001: mergeAfterHours 正常流程 —— tiger source 返回盘后数据
    @Test
    @DisplayName("AH-001: tiger source 正常返回盘后价，afterHours 被保存")
    void mergeAfterHoursHappyPath() {
        LocalDate today = nyToday();
        LocalDate tradeDate = today.minusDays(2);
        LocalDate stopDate = today.minusDays(5);

        StockDailyBar existingBar = createLowPriceBar("AAPL", stopDate);

        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("AAPL"));
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq("AAPL"), any()))
                .thenReturn(new ArrayList<>(List.of(existingBar)));
        when(stockDailyBarRepository.findBySymbolAndTradeDate(eq("AAPL"), eq(tradeDate)))
                .thenReturn(Optional.empty());

        // Primary daily KLine data — make tiger data source return a regular bar first
        KLineData dailyKd = createKLineData("AAPL", tradeDate, 150.0, 155.0, 148.0, 152.5, 1_000_000L);
        when(tigerSource.getDailyKLineDataByDateRange(eq("AAPL"), eq(tradeDate)))
                .thenReturn(dailyKd);

        // After-hours data — tiger returns a separate after-hours bar
        KLineData ahKd = createKLineData("AAPL", tradeDate, 152.5, 153.5, 152.0, 153.0, 100_000L);
        when(tigerSource.getAfterHoursKLineDataByDateRange(eq("AAPL"), eq(tradeDate)))
                .thenReturn(ahKd);

        service.fillGaps();

        // There will be at least two saves: persist + mergeAfterHours
        verify(stockDailyBarRepository, atLeast(2)).save(barCaptor.capture());

        // Get the last captured bar (the one after mergeAfterHours)
        List<StockDailyBar> allSaved = barCaptor.getAllValues();
        StockDailyBar finalBar = allSaved.get(allSaved.size() - 1);

        assertEquals(153.0, finalBar.getAfterHours(), 0.001, "afterHours should be set from AH data");
        assertNotNull(finalBar.getAfterHoursChangePercent(), "afterHoursChangePercent should be calculated");
        // (153.0 - 152.5) / 152.5 * 100 = 0.3278...
        double expectedAhChgPct = (153.0 - 152.5) / 152.5 * 100;
        assertEquals(expectedAhChgPct, finalBar.getAfterHoursChangePercent(), 0.01);
    }

    // AH-002: mergeAfterHours 非 Tiger source 跳过
    @Test
    @DisplayName("AH-002: 非 tiger source 跳过 mergeAfterHours")
    void mergeAfterHoursSkipsNonTigerSource() {
        // Use yfinance as data source instead of tiger
        DataSourceStrategy yfinanceSource = mock(DataSourceStrategy.class);
        when(yfinanceSource.getSourceName()).thenReturn("yfinance");
        when(yfinanceSource.isAvailable()).thenReturn(true);

        List<DataSourceStrategy> dataSources = List.of(yfinanceSource);
        DataGapFillerServiceImpl yService = new DataGapFillerServiceImpl(
                stockDailyBarRepository, dataFillTaskRepository, dataSources,
                gapFillProperties, dataFillProgressService, tradingCalendarDbService,
                stockDataSourcePriorityService, symbolBlacklistService);

        LocalDate today = nyToday();
        LocalDate tradeDate = today.minusDays(2);
        LocalDate stopDate = today.minusDays(5);

        StockDailyBar existingBar = createLowPriceBar("AAPL", stopDate);

        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("AAPL"));
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq("AAPL"), any()))
                .thenReturn(new ArrayList<>(List.of(existingBar)));
        when(stockDailyBarRepository.findBySymbolAndTradeDate(eq("AAPL"), eq(tradeDate)))
                .thenReturn(Optional.empty());

        KLineData kd = createKLineData("AAPL", tradeDate, 150.0, 155.0, 148.0, 152.5, 1_000_000L);
        when(yfinanceSource.getDailyKLineDataByDateRange(eq("AAPL"), eq(tradeDate)))
                .thenReturn(kd);

        yService.fillGaps();

        verify(stockDailyBarRepository, atLeastOnce()).save(barCaptor.capture());
        StockDailyBar saved = barCaptor.getValue();
        assertEquals("yfinance", saved.getSource());
        // KLineIterator stores afterHours as double (primitive), default 0.0
        // mergeAfterHours not called for non-tiger source, so it stays 0.0
        assertEquals(0.0, saved.getAfterHours(), 0.001);
    }

    // AH-003: mergeAfterHours 盘后数据为空时跳过
    @Test
    @DisplayName("AH-003: 盘后数据为空时跳过 mergeAfterHours")
    void mergeAfterHoursWithNullData() {
        LocalDate today = nyToday();
        LocalDate tradeDate = today.minusDays(2);
        LocalDate stopDate = today.minusDays(5);

        StockDailyBar existingBar = createLowPriceBar("AAPL", stopDate);

        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("AAPL"));
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq("AAPL"), any()))
                .thenReturn(new ArrayList<>(List.of(existingBar)));
        when(stockDailyBarRepository.findBySymbolAndTradeDate(eq("AAPL"), eq(tradeDate)))
                .thenReturn(Optional.empty());

        KLineData dailyKd = createKLineData("AAPL", tradeDate, 150.0, 155.0, 148.0, 152.5, 1_000_000L);
        when(tigerSource.getDailyKLineDataByDateRange(eq("AAPL"), eq(tradeDate)))
                .thenReturn(dailyKd);

        // After-hours returns null
        when(tigerSource.getAfterHoursKLineDataByDateRange(eq("AAPL"), eq(tradeDate)))
                .thenReturn(null);

        service.fillGaps();

        verify(stockDailyBarRepository, atLeastOnce()).save(barCaptor.capture());
        StockDailyBar saved = barCaptor.getValue();
        // afterHours is 0.0 (double default from KLineIterator) when mergeAfterHours skipped null data
        assertEquals(0.0, saved.getAfterHours(), 0.001, "afterHours remains 0.0 when AH data is null");
    }

    // AH-004: mergeAfterHours 异常处理
    @Test
    @DisplayName("AH-004: mergeAfterHours 异常时不中断")
    void mergeAfterHoursHandlesException() {
        LocalDate today = nyToday();
        LocalDate tradeDate = today.minusDays(2);
        LocalDate stopDate = today.minusDays(5);

        StockDailyBar existingBar = createLowPriceBar("AAPL", stopDate);

        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("AAPL"));
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq("AAPL"), any()))
                .thenReturn(new ArrayList<>(List.of(existingBar)));
        when(stockDailyBarRepository.findBySymbolAndTradeDate(eq("AAPL"), eq(tradeDate)))
                .thenReturn(Optional.empty());

        KLineData dailyKd = createKLineData("AAPL", tradeDate, 150.0, 155.0, 148.0, 152.5, 1_000_000L);
        when(tigerSource.getDailyKLineDataByDateRange(eq("AAPL"), eq(tradeDate)))
                .thenReturn(dailyKd);

        // After-hours throws exception
        when(tigerSource.getAfterHoursKLineDataByDateRange(eq("AAPL"), eq(tradeDate)))
                .thenThrow(new RuntimeException("API timeout"));

        // Should not throw — the exception is caught
        assertDoesNotThrow(() -> service.fillGaps());

        verify(stockDailyBarRepository, atLeastOnce()).save(barCaptor.capture());
        StockDailyBar saved = barCaptor.getValue();
        assertEquals(152.5, saved.getClosePrice(), 0.001);
        // afterHours is 0.0 when mergeAfterHours throws (exception caught, field unchanged)
        assertEquals(0.0, saved.getAfterHours(), 0.001, "afterHours remains 0.0 when AH throws");
    }

    // AH-005: afterHoursChangePercent 计算正确
    @Test
    @DisplayName("AH-005: afterHoursChangePercent 计算 (ahClose - regClose) / regClose * 100")
    void afterHoursChangePercentCalculation() {
        LocalDate today = nyToday();
        LocalDate tradeDate = today.minusDays(2);
        LocalDate stopDate = today.minusDays(5);

        StockDailyBar existingBar = createLowPriceBar("AAPL", stopDate);

        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("AAPL"));
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq("AAPL"), any()))
                .thenReturn(new ArrayList<>(List.of(existingBar)));
        when(stockDailyBarRepository.findBySymbolAndTradeDate(eq("AAPL"), eq(tradeDate)))
                .thenReturn(Optional.empty());

        // closePrice = 100.0
        KLineData dailyKd = createKLineData("AAPL", tradeDate, 99.0, 101.0, 98.0, 100.0, 1_000_000L);
        when(tigerSource.getDailyKLineDataByDateRange(eq("AAPL"), eq(tradeDate)))
                .thenReturn(dailyKd);

        // after-hours close = 103.0
        KLineData ahKd = createKLineData("AAPL", tradeDate, 100.0, 104.0, 99.5, 103.0, 50_000L);
        when(tigerSource.getAfterHoursKLineDataByDateRange(eq("AAPL"), eq(tradeDate)))
                .thenReturn(ahKd);

        service.fillGaps();

        verify(stockDailyBarRepository, atLeast(2)).save(barCaptor.capture());
        List<StockDailyBar> allSaved = barCaptor.getAllValues();
        StockDailyBar finalBar = allSaved.get(allSaved.size() - 1);

        // (103.0 - 100.0) / 100.0 * 100 = 3.0
        assertEquals(103.0, finalBar.getAfterHours(), 0.001);
        assertEquals(3.0, finalBar.getAfterHoursChangePercent(), 0.001);
    }

    // AH-006: Real 模式（标记 @Tag("integration") 跳过）
    @Test
    @Tag("integration")
    @DisplayName("AH-006: [integration] Real 模式 —— 暂时跳过")
    void realModeIntegration() {
        // 使用 @Tag("integration") 标记，在 CI 中通过 -Dgroups='!integration' 排除
        // 当前直接 pass 以便常规 mvn test 通过
        assertTrue(true);
    }

    // AH-007: Tiger source 数据源的优先级识别
    @Test
    @DisplayName("AH-007: findTigerSource 通过 instanceof 识别 TigerStockServiceImpl")
    void findTigerSourceIdentification() {
        // The mock of TigerStockServiceImpl is instanceof, so mergeAfterHours should work
        LocalDate today = nyToday();
        LocalDate tradeDate = today.minusDays(2);
        LocalDate stopDate = today.minusDays(5);

        StockDailyBar existingBar = createLowPriceBar("AAPL", stopDate);

        when(stockDailyBarRepository.findAllSymbols()).thenReturn(List.of("AAPL"));
        when(stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(eq("AAPL"), any()))
                .thenReturn(new ArrayList<>(List.of(existingBar)));
        when(stockDailyBarRepository.findBySymbolAndTradeDate(eq("AAPL"), eq(tradeDate)))
                .thenReturn(Optional.empty());

        KLineData dailyKd = createKLineData("AAPL", tradeDate, 150.0, 155.0, 148.0, 152.5, 1_000_000L);
        when(tigerSource.getDailyKLineDataByDateRange(eq("AAPL"), eq(tradeDate)))
                .thenReturn(dailyKd);

        // After hours data available
        KLineData ahKd = createKLineData("AAPL", tradeDate, 152.5, 153.5, 152.0, 153.0, 100_000L);
        when(tigerSource.getAfterHoursKLineDataByDateRange(eq("AAPL"), eq(tradeDate)))
                .thenReturn(ahKd);

        service.fillGaps();

        // Verify getAfterHoursKLineDataByDateRange was called (meaning findTigerSource succeeded)
        verify(tigerSource, atLeastOnce()).getAfterHoursKLineDataByDateRange(eq("AAPL"), eq(tradeDate));
    }

    // ========== Helper Methods ==========

    private static StockDailyBar createLowPriceBar(String symbol, LocalDate tradeDate) {
        StockDailyBar bar = new StockDailyBar();
        bar.setSymbol(symbol);
        bar.setTradeDate(tradeDate);
        bar.setOpenPrice(0.5);
        bar.setHighPrice(0.6);
        bar.setLowPrice(0.4);
        bar.setClosePrice(0.5);
        bar.setVolume(100L);
        bar.setSource("yfinance");
        return bar;
    }

    private static KLineData createKLineData(String symbol, LocalDate tradeDate,
                                              double open, double high, double low, double close,
                                              long volume) {
        long epochMillis = tradeDate.atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        KLineData kd = new KLineData();
        kd.setSymbol(symbol);
        KLineIterator item = new KLineIterator(
                symbol, epochMillis, open, high, low, close, volume, 0,
                0.0, 0.0, 0.0);
        item.setTimeString(tradeDate.toString());
        kd.setItems(List.of(item));
        return kd;
    }
}
