package com.stock.invest.service;

import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.enums.dto.StockDailyBarCandleDto;
import com.stock.invest.repository.StockDailyBarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * StockDailyBarService 单元测试 —— mock repository
 * 覆盖 SVC-001 ~ SVC-008
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StockDailyBarService — DTO 映射与查询")
class StockDailyBarServiceTest {

    @Mock
    private StockDailyBarRepository repository;

    @InjectMocks
    private StockDailyBarService service;

    private StockDailyBar bar1;
    private StockDailyBar bar2;
    private StockDailyBar bar3;

    @BeforeEach
    void setUp() {
        bar1 = createTestBar("AAPL", LocalDate.of(2025, 6, 25),
                150.0, 155.0, 148.0, 152.5, 1.67, 153.0, 0.33, 1_000_000L);
        bar2 = createTestBar("AAPL", LocalDate.of(2025, 6, 24),
                149.0, 154.0, 147.5, 151.0, 0.67, 151.5, 0.33, 900_000L);
        bar3 = createTestBar("AAPL", LocalDate.of(2025, 6, 23),
                148.0, 153.0, 146.0, 150.0, 1.35, 150.5, 0.33, 850_000L);
    }

    // SVC-001: getRecentCandles 正常返回 DTO 列表
    @Test
    @DisplayName("SVC-001: 正常返回 DTO 列表（含全部字段）")
    void getRecentCandlesReturnsDtos() {
        when(repository.findTop7BySymbolOrderByTradeDateDesc("AAPL"))
                .thenReturn(new java.util.ArrayList<>(List.of(bar1, bar2, bar3)));

        List<StockDailyBarCandleDto> result = service.getRecentCandles("AAPL", 7);

        assertEquals(3, result.size());
        StockDailyBarCandleDto dto = result.get(0);
        assertEquals("2025-06-23", dto.date());
        assertEquals(148.0, dto.open(), 0.001);
        // high/low/changePercent/afterHours covered in SVC-004
        assertEquals(146.0, dto.low(), 0.001);
        assertEquals(150.0, dto.close(), 0.001);
        assertEquals(1.35, dto.changePercent(), 0.001);
        assertEquals(150.5, dto.afterHours(), 0.001);
        assertEquals(0.33, dto.afterHoursChangePercent(), 0.001);
        assertEquals(850_000L, dto.volume());
    }

    // SVC-002: getRecentCandles 小于请求 days 时返回全部
    @Test
    @DisplayName("SVC-002: bars 少于 days 时返回全部")
    void getRecentCandlesLessThanRequestedDays() {
        when(repository.findTop7BySymbolOrderByTradeDateDesc("AAPL"))
                .thenReturn(new ArrayList<>(List.of(bar1)));

        List<StockDailyBarCandleDto> result = service.getRecentCandles("AAPL", 7);

        assertEquals(1, result.size());
        assertEquals("2025-06-25", result.get(0).date());
    }

    // SVC-003: getRecentCandles repository 返回空
    @Test
    @DisplayName("SVC-003: repository 为空时返回空列表")
    void getRecentCandlesEmptyRepository() {
        when(repository.findTop7BySymbolOrderByTradeDateDesc("AAPL"))
                .thenReturn(new ArrayList<>(List.of()));

        List<StockDailyBarCandleDto> result = service.getRecentCandles("AAPL", 7);

        assertTrue(result.isEmpty());
    }

    // SVC-004: DTO 映射保留全部字段（含 highPrice/lowPrice/changePercent/afterHours）
    @Test
    @DisplayName("SVC-004: DTO 映射保留所有新增字段")
    void dtoMappingPreservesAllFields() {
        when(repository.findTop7BySymbolOrderByTradeDateDesc("AAPL"))
                .thenReturn(new ArrayList<>(List.of(bar1)));

        List<StockDailyBarCandleDto> result = service.getRecentCandles("AAPL", 7);

        assertEquals(1, result.size());
        StockDailyBarCandleDto dto = result.get(0);
        assertEquals(155.0, dto.high(), 0.001, "highPrice mapped");
        assertEquals(148.0, dto.low(), 0.001, "lowPrice mapped");
        assertEquals(1.67, dto.changePercent(), 0.001, "changePercent mapped");
        assertEquals(153.0, dto.afterHours(), 0.001, "afterHours mapped");
        assertEquals(0.33, dto.afterHoursChangePercent(), 0.001, "afterHoursChangePercent mapped");
    }

    // SVC-005: 日期格式为 yyyy-MM-dd
    @Test
    @DisplayName("SVC-005: tradeDate 转换为 yyyy-MM-dd 字符串")
    void dateFormatIsYyyyMmDd() {
        when(repository.findTop7BySymbolOrderByTradeDateDesc("AAPL"))
                .thenReturn(new ArrayList<>(List.of(bar1)));

        List<StockDailyBarCandleDto> result = service.getRecentCandles("AAPL", 7);

        assertEquals("2025-06-25", result.get(0).date());
        assertTrue(result.get(0).date().matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    // SVC-006: 返回顺序为 tradeDate ASC（内部做了 reverse）
    @Test
    @DisplayName("SVC-006: 结果按 tradeDate 升序排列")
    void resultIsAscendingOrder() {
        when(repository.findTop7BySymbolOrderByTradeDateDesc("AAPL"))
                .thenReturn(new ArrayList<>(List.of(bar1, bar2, bar3))); // newest-first

        List<StockDailyBarCandleDto> result = service.getRecentCandles("AAPL", 7);

        assertEquals(3, result.size());
        assertTrue(result.get(0).date().compareTo(result.get(1).date()) <= 0);
        assertTrue(result.get(1).date().compareTo(result.get(2).date()) <= 0);
    }

    // SVC-007: days 参数限制返回数量
    @Test
    @DisplayName("SVC-007: days 参数限制 DTO 返回数量")
    void daysParameterLimitsResult() {
        when(repository.findTop7BySymbolOrderByTradeDateDesc("AAPL"))
                .thenReturn(new ArrayList<>(List.of(bar1, bar2, bar3)));

        List<StockDailyBarCandleDto> result = service.getRecentCandles("AAPL", 2);

        assertEquals(2, result.size(), "should be limited to 2");
    }

    // SVC-008: changePercent/afterHours 为 null 时 DTO 组件为 null
    @Test
    @DisplayName("SVC-008: null 的新字段映射为 null")
    void nullableFieldsMapToNull() {
        StockDailyBar bar = createTestBar("AAPL", LocalDate.of(2025, 6, 25),
                150.0, 155.0, 148.0, 152.5, null, null, null, 1_000_000L);
        when(repository.findTop7BySymbolOrderByTradeDateDesc("AAPL"))
                .thenReturn(new ArrayList<>(List.of(bar)));

        List<StockDailyBarCandleDto> result = service.getRecentCandles("AAPL", 7);

        assertNull(result.get(0).changePercent());
        assertNull(result.get(0).afterHours());
        assertNull(result.get(0).afterHoursChangePercent());
    }

    // === Helper ===

    private static StockDailyBar createTestBar(String symbol, LocalDate tradeDate,
                                                double open, double high, double low, double close,
                                                Double changePct, Double afterHours, Double afterHoursChgPct,
                                                long volume) {
        StockDailyBar bar = new StockDailyBar();
        bar.setSymbol(symbol);
        bar.setTradeDate(tradeDate);
        bar.setOpenPrice(open);
        bar.setHighPrice(high);
        bar.setLowPrice(low);
        bar.setClosePrice(close);
        bar.setChangePercent(changePct);
        bar.setAfterHours(afterHours);
        bar.setAfterHoursChangePercent(afterHoursChgPct);
        bar.setVolume(volume);
        bar.setSource("yfinance");
        return bar;
    }
}
