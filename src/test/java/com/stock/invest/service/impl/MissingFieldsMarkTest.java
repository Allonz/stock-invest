package com.stock.invest.service.impl;

import com.stock.invest.config.GapFillProperties;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.repository.DataFillTaskRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.DataFillProgressService;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;

/**
 * 字段缺失标记规则测试（2026-08-14）。
 * 覆盖 applyMissingFieldsMark：数据源能力 × 字段缺失判定矩阵。
 */
@ExtendWith(MockitoExtension.class)
class MissingFieldsMarkTest {

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

    private DataGapFillerServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DataGapFillerServiceImpl(
                stockDailyBarRepository, dataFillTaskRepository, java.util.List.of(),
                gapFillProperties, dataFillProgressService, retryProgressService, tradingCalendarDbService,
                stockDataSourcePriorityService, symbolBlacklistService,
                transactionManager, fieldCapabilityService);

        // 能力矩阵 stub：yfinance 支持盘后；tiingo 不支持
        lenient().when(fieldCapabilityService.isMarkable(anyString(), anyString())).thenReturn(false);
        lenient().when(fieldCapabilityService.isMarkable("yfinance", "after_hours")).thenReturn(true);
        lenient().when(fieldCapabilityService.isMarkable("yfinance", "after_hours_change_percent")).thenReturn(true);
        lenient().when(fieldCapabilityService.isMarkable("yfinance", "close_price")).thenReturn(true);
        lenient().when(fieldCapabilityService.isMarkable("yfinance", "change_percent")).thenReturn(true);
        lenient().when(fieldCapabilityService.isMarkable("tiingo", "change_percent")).thenReturn(true);
    }

    private static StockDailyBar bar(String source, BigDecimal close, BigDecimal changePercent,
                                     BigDecimal afterHours, BigDecimal afterHoursChangePercent) {
        StockDailyBar b = new StockDailyBar();
        b.setSymbol("JSPR");
        b.setTradeDate(LocalDate.of(2026, 8, 13));
        b.setOpenPrice(BigDecimal.valueOf(0.8));
        b.setHighPrice(BigDecimal.valueOf(0.9));
        b.setLowPrice(BigDecimal.valueOf(0.7));
        b.setClosePrice(close);
        b.setVolume(1000L);
        b.setChangePercent(changePercent);
        b.setAfterHours(afterHours);
        b.setAfterHoursChangePercent(afterHoursChangePercent);
        b.setSource(source);
        return b;
    }

    @Test
    @DisplayName("MM-01: yfinance 缺盘后 → 标记 after_hours 相关 + PENDING")
    void yfinanceMissingAfterHours_marks() {
        StockDailyBar b = bar("yfinance", BigDecimal.valueOf(0.86), BigDecimal.valueOf(4.38), null, null);
        service.applyMissingFieldsMark(b);
        assertEquals("after_hours,after_hours_change_percent", b.getMissingFields());
        assertEquals(DataGapFillerServiceImpl.STATUS_PENDING, b.getFieldFillStatus());
    }

    @Test
    @DisplayName("MM-02: tiingo 缺盘后 → 不标记（源不支持，防无限补）")
    void tiingoMissingAfterHours_notMarked() {
        StockDailyBar b = bar("tiingo", BigDecimal.valueOf(0.86), BigDecimal.valueOf(4.38), null, null);
        service.applyMissingFieldsMark(b);
        assertNull(b.getMissingFields());
        assertEquals(DataGapFillerServiceImpl.STATUS_CONFIRMED, b.getFieldFillStatus());
    }

    @Test
    @DisplayName("MM-03: change_percent NULL → 标记；change_percent=0 → 不标记（真实没涨跌）")
    void changePercentNull_marked_zeroNotMarked() {
        StockDailyBar b1 = bar("yfinance", BigDecimal.valueOf(0.86), null, BigDecimal.valueOf(0.87), BigDecimal.valueOf(1.16));
        service.applyMissingFieldsMark(b1);
        assertTrue(b1.getMissingFields().contains("change_percent"));

        StockDailyBar b2 = bar("yfinance", BigDecimal.valueOf(0.86), BigDecimal.ZERO, BigDecimal.valueOf(0.87), BigDecimal.valueOf(1.16));
        service.applyMissingFieldsMark(b2);
        assertFalse(b2.getMissingFields() == null ? false : b2.getMissingFields().contains("change_percent"));
        assertNull(b2.getMissingFields());
    }

    @Test
    @DisplayName("MM-04: close_price NULL/0 → 标记")
    void closeMissing_marked() {
        StockDailyBar b1 = bar("yfinance", null, BigDecimal.valueOf(4.38), BigDecimal.valueOf(0.87), BigDecimal.valueOf(1.16));
        service.applyMissingFieldsMark(b1);
        assertTrue(b1.getMissingFields().contains("close_price"));

        StockDailyBar b2 = bar("yfinance", BigDecimal.ZERO, BigDecimal.valueOf(4.38), BigDecimal.valueOf(0.87), BigDecimal.valueOf(1.16));
        service.applyMissingFieldsMark(b2);
        assertTrue(b2.getMissingFields().contains("close_price"));
    }

    @Test
    @DisplayName("MM-05: 全字段完整 → CONFIRMED 无缺失标记")
    void completeBar_confirmed() {
        StockDailyBar b = bar("yfinance", BigDecimal.valueOf(0.86), BigDecimal.valueOf(4.38),
                BigDecimal.valueOf(0.87), BigDecimal.valueOf(1.16));
        service.applyMissingFieldsMark(b);
        assertNull(b.getMissingFields());
        assertEquals(DataGapFillerServiceImpl.STATUS_CONFIRMED, b.getFieldFillStatus());
    }
}
