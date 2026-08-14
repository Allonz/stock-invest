package com.stock.invest.service.impl;

import com.stock.invest.config.GapFillProperties;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.exception.StockDataException;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.repository.DataFillTaskRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.DataFillProgressService;
import com.stock.invest.service.DataSourceStrategy;
import com.stock.invest.service.FieldCapabilityService;
import com.stock.invest.service.RetryProgressService;
import com.stock.invest.service.StockDataSourcePriorityService;
import com.stock.invest.service.SymbolBlacklistService;
import com.stock.invest.service.TradingCalendarDbService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 字段增补流程测试（2026-08-14）：
 * 发现阶段（discoverMissingFields）+ 增补阶段（fillMissingFields / fillMissingFieldsForBar）。
 */
@ExtendWith(MockitoExtension.class)
class FillMissingFieldsTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 8, 13);

    @Mock private StockDailyBarRepository stockDailyBarRepository;
    @Mock private DataFillTaskRepository dataFillTaskRepository;
    @Mock private GapFillProperties gapFillProperties;
    @Mock private DataFillProgressService dataFillProgressService;
    @Mock private RetryProgressService retryProgressService;
    @Mock private TradingCalendarDbService tradingCalendarDbService;
    @Mock private StockDataSourcePriorityService stockDataSourcePriorityService;
    @Mock private SymbolBlacklistService symbolBlacklistService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private FieldCapabilityService fieldCapabilityService;
    @Mock private DataSourceStrategy yfinanceSource;

    private DataGapFillerServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DataGapFillerServiceImpl(
                stockDailyBarRepository, dataFillTaskRepository, List.of(yfinanceSource),
                gapFillProperties, dataFillProgressService, retryProgressService, tradingCalendarDbService,
                stockDataSourcePriorityService, symbolBlacklistService,
                transactionManager, fieldCapabilityService);

        lenient().when(yfinanceSource.getSourceName()).thenReturn("yfinance");
        lenient().when(yfinanceSource.isAvailable()).thenReturn(true);
        // yfinance 支持盘后（supportsAfterHoursMerge）
        lenient().when(fieldCapabilityService.isMarkable(anyString(), anyString())).thenReturn(false);
        lenient().when(fieldCapabilityService.isMarkable("yfinance", "after_hours")).thenReturn(true);
        lenient().when(fieldCapabilityService.isMarkable("yfinance", "after_hours_change_percent")).thenReturn(true);
    }

    private static StockDailyBar pendingBar(String missingFields) {
        StockDailyBar b = new StockDailyBar();
        b.setSymbol("JSPR");
        b.setTradeDate(TRADE_DATE);
        b.setOpenPrice(BigDecimal.valueOf(0.8));
        b.setHighPrice(BigDecimal.valueOf(0.9));
        b.setLowPrice(BigDecimal.valueOf(0.7));
        b.setClosePrice(BigDecimal.valueOf(0.86));
        b.setVolume(1000L);
        b.setChangePercent(BigDecimal.valueOf(4.38));
        b.setSource("yfinance");
        b.setMissingFields(missingFields);
        b.setFieldFillStatus(DataGapFillerServiceImpl.STATUS_PENDING);
        return b;
    }

    private static KLineData ahData(String symbol, LocalDate date, double close) {
        return ahData(symbol, date, close, null);
    }

    private static KLineData ahData(String symbol, LocalDate date, double close, java.math.BigDecimal ahChangePercent) {
        KLineData kd = new KLineData();
        kd.setSymbol(symbol);
        KLineIterator item = new KLineIterator(symbol, date.atStartOfDay(java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli(),
                BigDecimal.valueOf(close), BigDecimal.valueOf(close), BigDecimal.valueOf(close), BigDecimal.valueOf(close),
                0, 0, BigDecimal.ZERO, BigDecimal.ZERO, ahChangePercent);
        item.setTimeString(date.toString());
        kd.setItems(List.of(item));
        return kd;
    }

    private static KLineData dailyData(String symbol, LocalDate date, double close) {
        KLineData kd = new KLineData();
        kd.setSymbol(symbol);
        KLineIterator item = new KLineIterator(symbol, date.atStartOfDay(java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli(),
                BigDecimal.valueOf(0.8), BigDecimal.valueOf(0.9), BigDecimal.valueOf(0.7), BigDecimal.valueOf(close),
                1000, 0, null, null, null);
        item.setTimeString(date.toString());
        kd.setItems(List.of(item));
        return kd;
    }

    // ============ 发现阶段 ============

    @Test
    @DisplayName("DF-01: 存量未检查记录 → 发现阶段正确标记")
    void discoverMarksUnchecked() {
        StockDailyBar legacy = pendingBar(null);
        legacy.setFieldFillStatus(null);          // 未检查
        legacy.setAfterHours(null);               // 盘后缺失
        legacy.setAfterHoursChangePercent(null);

        when(stockDailyBarRepository.findUnchecked()).thenReturn(List.of(legacy));

        int n = service.discoverMissingFields();

        assertEquals(1, n);
        assertEquals("after_hours,after_hours_change_percent", legacy.getMissingFields());
        assertEquals(DataGapFillerServiceImpl.STATUS_PENDING, legacy.getFieldFillStatus());
        verify(stockDailyBarRepository).save(legacy);
    }

    @Test
    @DisplayName("DF-02: 超窗记录（30 交易日外）→ 直接 CONFIRMED 不标记（不补历史）")
    void discoverStaleWindow_confirmed() {
        StockDailyBar legacy = pendingBar(null);
        legacy.setFieldFillStatus(null);
        legacy.setTradeDate(TRADE_DATE.minusDays(60));   // 60 天前（超 45 天窗口）
        legacy.setAfterHours(null);
        legacy.setAfterHoursChangePercent(null);

        when(stockDailyBarRepository.findUnchecked()).thenReturn(List.of(legacy));

        service.discoverMissingFields();

        assertNull(legacy.getMissingFields());
        assertEquals(DataGapFillerServiceImpl.STATUS_CONFIRMED, legacy.getFieldFillStatus());
    }

    @Test
    @DisplayName("DF-03: 窗口内记录 → 正常标记 PENDING")
    void discoverInWindow_marksPending() {
        StockDailyBar bar = pendingBar(null);
        bar.setFieldFillStatus(null);
        bar.setTradeDate(TRADE_DATE);   // 8/13，窗口内
        bar.setAfterHours(null);
        bar.setAfterHoursChangePercent(null);

        when(stockDailyBarRepository.findUnchecked()).thenReturn(List.of(bar));

        service.discoverMissingFields();

        assertNotNull(bar.getMissingFields());
        assertEquals(DataGapFillerServiceImpl.STATUS_PENDING, bar.getFieldFillStatus());
    }

    // ============ 增补阶段：盘后 ============

    @Test
    @DisplayName("FF-01: 盘后增补成功 → 写入 + 清标记 + CONFIRMED")
    void fillAfterHours_success() {
        StockDailyBar bar = pendingBar("after_hours,after_hours_change_percent");
        when(stockDailyBarRepository.findByFieldFillStatus(DataGapFillerServiceImpl.STATUS_PENDING))
                .thenReturn(List.of(bar));
        when(yfinanceSource.getAfterHoursKLineDataByDateRange(eq("JSPR"), eq(TRADE_DATE)))
                .thenReturn(ahData("JSPR", TRADE_DATE, 0.87));

        int completed = service.fillMissingFields();

        assertEquals(1, completed);
        assertEquals(0, new BigDecimal("0.87").compareTo(bar.getAfterHours()));
        // (0.87 - 0.86) / 0.86 * 100 = 1.1628
        assertEquals(0, new BigDecimal("1.1628").compareTo(bar.getAfterHoursChangePercent()));
        assertNull(bar.getMissingFields());
        assertEquals(DataGapFillerServiceImpl.STATUS_CONFIRMED, bar.getFieldFillStatus());
    }

    @Test
    @DisplayName("FF-01b: 脚本直取盘后涨跌幅 → 直接使用源值（不兜底计算）")
    void fillAfterHours_sourceDirectChangePercent() {
        StockDailyBar bar = pendingBar("after_hours,after_hours_change_percent");
        when(stockDailyBarRepository.findByFieldFillStatus(DataGapFillerServiceImpl.STATUS_PENDING))
                .thenReturn(List.of(bar));
        // 脚本返回 afterHoursChangePercent=5.5（源直取优先）
        when(yfinanceSource.getAfterHoursKLineDataByDateRange(eq("JSPR"), eq(TRADE_DATE)))
                .thenReturn(ahData("JSPR", TRADE_DATE, 0.87, new BigDecimal("5.5000")));

        int completed = service.fillMissingFields();

        assertEquals(1, completed);
        assertEquals(0, new BigDecimal("0.87").compareTo(bar.getAfterHours()));
        // 直接用源值 5.5，而非 (0.87-0.86)/0.86*100=1.1628
        assertEquals(0, new BigDecimal("5.5000").compareTo(bar.getAfterHoursChangePercent()));
        assertNull(bar.getMissingFields());
        assertEquals(DataGapFillerServiceImpl.STATUS_CONFIRMED, bar.getFieldFillStatus());
    }

    @Test
    @DisplayName("FF-02: 源确认无盘后（null）→ 清标记 + CONFIRMED（防死循环）")
    void fillAfterHours_confirmedNone() {
        StockDailyBar bar = pendingBar("after_hours,after_hours_change_percent");
        when(stockDailyBarRepository.findByFieldFillStatus(DataGapFillerServiceImpl.STATUS_PENDING))
                .thenReturn(List.of(bar));
        when(yfinanceSource.getAfterHoursKLineDataByDateRange(eq("JSPR"), eq(TRADE_DATE)))
                .thenReturn(new KLineData());

        int completed = service.fillMissingFields();

        assertEquals(1, completed);
        assertNull(bar.getMissingFields());
        assertEquals(DataGapFillerServiceImpl.STATUS_CONFIRMED, bar.getFieldFillStatus());
        verify(yfinanceSource, atMostOnce()).getAfterHoursKLineDataByDateRange(anyString(), any());
    }

    @Test
    @DisplayName("FF-03: 瞬态失败 → 保留 PENDING + 缺失标记（下次再试）")
    void fillAfterHours_transientFailure_keepsPending() {
        StockDailyBar bar = pendingBar("after_hours,after_hours_change_percent");
        when(stockDailyBarRepository.findByFieldFillStatus(DataGapFillerServiceImpl.STATUS_PENDING))
                .thenReturn(List.of(bar));
        when(yfinanceSource.getAfterHoursKLineDataByDateRange(eq("JSPR"), eq(TRADE_DATE)))
                .thenThrow(new RuntimeException("API timeout"));

        int completed = service.fillMissingFields();

        assertEquals(0, completed);
        assertEquals("after_hours,after_hours_change_percent", bar.getMissingFields());
        assertEquals(DataGapFillerServiceImpl.STATUS_PENDING, bar.getFieldFillStatus());
    }

    // ============ 增补阶段：行情字段 + change_percent ============

    @Test
    @DisplayName("FF-04: change_percent 缺失 → 用日K + 前一日收盘重算")
    void fillChangePercent_recalc() {
        StockDailyBar bar = pendingBar("change_percent");
        bar.setChangePercent(null);
        when(stockDailyBarRepository.findByFieldFillStatus(DataGapFillerServiceImpl.STATUS_PENDING))
                .thenReturn(List.of(bar));
        when(yfinanceSource.getDailyKLineDataByDateRange(eq("JSPR"), eq(TRADE_DATE)))
                .thenReturn(dailyData("JSPR", TRADE_DATE, 0.86));
        // 前一日 close=0.8239 → (0.86-0.8239)/0.8239*100 = 4.3816
        StockDailyBar prev = new StockDailyBar();
        prev.setSymbol("JSPR");
        prev.setTradeDate(TRADE_DATE.minusDays(1));
        prev.setClosePrice(new BigDecimal("0.8239"));
        when(stockDailyBarRepository.findTopBySymbolAndTradeDateBeforeOrderByTradeDateDesc(eq("JSPR"), eq(TRADE_DATE)))
                .thenReturn(Optional.of(prev));

        int completed = service.fillMissingFields();

        assertEquals(1, completed);
        assertEquals(0, new BigDecimal("4.3816").compareTo(bar.getChangePercent()));
        assertNull(bar.getMissingFields());
        assertEquals(DataGapFillerServiceImpl.STATUS_CONFIRMED, bar.getFieldFillStatus());
    }

    @Test
    @DisplayName("FF-05: 源确认不存在（404）→ 清行情标记 + CONFIRMED")
    void fillKline_confirmedNotFound() {
        StockDailyBar bar = pendingBar("close_price");
        bar.setClosePrice(null);
        when(stockDailyBarRepository.findByFieldFillStatus(DataGapFillerServiceImpl.STATUS_PENDING))
                .thenReturn(List.of(bar));
        when(yfinanceSource.getDailyKLineDataByDateRange(eq("JSPR"), eq(TRADE_DATE)))
                .thenThrow(new StockDataException("JSPR", "yfinance", "symbol not found",
                        StockDataException.ErrorCategory.CONFIRMED_NOT_FOUND));

        int completed = service.fillMissingFields();

        assertEquals(1, completed);
        assertNull(bar.getMissingFields());
        assertEquals(DataGapFillerServiceImpl.STATUS_CONFIRMED, bar.getFieldFillStatus());
    }

    @Test
    @DisplayName("FF-06: 全部源不可用 → 保持 PENDING（下次再试）")
    void fill_noSource_keepsPending() {
        StockDailyBar bar = pendingBar("after_hours,after_hours_change_percent");
        when(stockDailyBarRepository.findByFieldFillStatus(DataGapFillerServiceImpl.STATUS_PENDING))
                .thenReturn(List.of(bar));
        when(yfinanceSource.isAvailable()).thenReturn(false);

        int completed = service.fillMissingFields();

        assertEquals(0, completed);
        assertEquals(DataGapFillerServiceImpl.STATUS_PENDING, bar.getFieldFillStatus());
    }

    @Test
    @DisplayName("FF-06b: 无对应源 bean（tiger_snap 截图源）→ fallback 到可用源增补（用户设计）")
    void fill_snapSource_fallbackToAvailableSource() {
        StockDailyBar bar = pendingBar("after_hours,after_hours_change_percent");
        bar.setSource("tiger_snap");   // 截图源无对应 bean → fallback yfinance
        when(stockDailyBarRepository.findByFieldFillStatus(DataGapFillerServiceImpl.STATUS_PENDING))
                .thenReturn(List.of(bar));
        when(yfinanceSource.getAfterHoursKLineDataByDateRange(eq("JSPR"), eq(TRADE_DATE)))
                .thenReturn(ahData("JSPR", TRADE_DATE, 0.87));

        int completed = service.fillMissingFields();

        assertEquals(1, completed);
        assertEquals(0, new BigDecimal("0.87").compareTo(bar.getAfterHours()));
        assertNull(bar.getMissingFields());
        assertEquals(DataGapFillerServiceImpl.STATUS_CONFIRMED, bar.getFieldFillStatus());
    }

    // ============ 防死循环 ============

    @Test
    @DisplayName("FF-07: CONFIRMED 记录不被增补阶段处理（防死循环）")
    void confirmedRecordsSkipped() {
        when(stockDailyBarRepository.findByFieldFillStatus(DataGapFillerServiceImpl.STATUS_PENDING))
                .thenReturn(new ArrayList<>());

        int completed = service.fillMissingFields();

        assertEquals(0, completed);
        verify(yfinanceSource, never()).getAfterHoursKLineDataByDateRange(anyString(), any());
        verify(yfinanceSource, never()).getDailyKLineDataByDateRange(anyString(), any());
    }
}
