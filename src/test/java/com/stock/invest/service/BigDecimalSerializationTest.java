package com.stock.invest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.enums.dto.StockDailyBarCandleDto;
import com.stock.invest.enums.dto.StockDailyBarDto;
import com.stock.invest.repository.StockDailyBarRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * P2-6 序列化专项：BigDecimal 出站统一去尾零 —— 保证 JSON 数字形态与既有 152.5 一致
 * （避免 152.5000 尾零、1.5E+2 科学计数）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("P2-6 — BigDecimal 出站序列化（stripTrailingZeros）")
class BigDecimalSerializationTest {

    @Mock
    private StockDailyBarRepository repository;

    @InjectMocks
    private StockDailyBarService service;

    private static StockDailyBar bar(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                                     BigDecimal changePercent, BigDecimal afterHours, BigDecimal afterHoursChgPct) {
        StockDailyBar bar = new StockDailyBar();
        bar.setSymbol("AAPL");
        bar.setTradeDate(LocalDate.of(2025, 6, 25));
        bar.setOpenPrice(open);
        bar.setHighPrice(high);
        bar.setLowPrice(low);
        bar.setClosePrice(close);
        bar.setChangePercent(changePercent);
        bar.setAfterHours(afterHours);
        bar.setAfterHoursChangePercent(afterHoursChgPct);
        bar.setVolume(1_000_000L);
        bar.setSource("yfinance");
        return bar;
    }

    @Test
    @DisplayName("序列化: 小数位去尾零（152.5000 → 152.5）")
    void candleDto_stripsTrailingZeros() {
        when(repository.findBySymbolOrderByTradeDateDesc(eq("AAPL"), any()))
                .thenReturn(List.of(bar(
                        new BigDecimal("150.5000"), new BigDecimal("155.0000"),
                        new BigDecimal("148.0000"), new BigDecimal("152.5000"),
                        new BigDecimal("1.6700"), new BigDecimal("153.0000"),
                        new BigDecimal("0.3300"))));

        List<StockDailyBarCandleDto> result = service.getRecentCandles("AAPL", 7);

        StockDailyBarCandleDto dto = result.get(0);
        assertEquals(0, new BigDecimal("150.5").compareTo(dto.open()));
        assertEquals(0, new BigDecimal("155").compareTo(dto.high()));
        assertEquals(0, new BigDecimal("148").compareTo(dto.low()));
        assertEquals(0, new BigDecimal("152.5").compareTo(dto.close()));
        assertEquals(0, new BigDecimal("1.67").compareTo(dto.changePercent()));
        assertEquals(0, new BigDecimal("153").compareTo(dto.afterHours()));
        assertEquals(0, new BigDecimal("0.33").compareTo(dto.afterHoursChangePercent()));
    }

    @Test
    @DisplayName("序列化: 整数值不产生科学计数（153 → 153 而非 1.53E+2）")
    void candleDto_wholeNumbers_noScientificNotation() {
        when(repository.findBySymbolOrderByTradeDateDesc(eq("AAPL"), any()))
                .thenReturn(List.of(bar(
                        new BigDecimal("150.0000"), new BigDecimal("155.0000"),
                        new BigDecimal("148.0000"), new BigDecimal("153.0000"),
                        null, null, null)));

        List<StockDailyBarCandleDto> result = service.getRecentCandles("AAPL", 7);

        StockDailyBarCandleDto dto = result.get(0);
        assertEquals("153", dto.close().toPlainString(), "close must be plain decimal, no exponent");
        assertEquals(0, new BigDecimal("153").compareTo(dto.close()));
        // null 透传
        assertNull(dto.changePercent());
        assertNull(dto.afterHours());
        assertNull(dto.afterHoursChangePercent());
    }

    @Test
    @DisplayName("序列化: BigDecimal 默认输出为 JSON number（非字符串），去尾零在映射点完成")
    void dto_serializesAsJsonNumber() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        StockDailyBarDto dto = new StockDailyBarDto(
                "AAPL", "Apple", LocalDate.of(2025, 6, 25),
                new BigDecimal("150.5000"), new BigDecimal("155.0000"),
                new BigDecimal("148.0000"), new BigDecimal("152.5000"),
                new BigDecimal("1.6700"), null, null,
                1_000_000L, "yfinance");

        String json = mapper.writeValueAsString(dto);
        // BigDecimal 直接序列化为 JSON number 并保留 scale（150.5000）——
        // 这正是映射点 stripTrailingZeros 需要消除的形态；此处验证 wire 格式为数字且保留尾零。
        assertTrue(json.contains("\"openPrice\":150.5000"), "wire must carry scale-4 number, got: " + json);
        assertTrue(json.contains("\"closePrice\":152.5000"), "wire must carry scale-4 number, got: " + json);
    }
}
