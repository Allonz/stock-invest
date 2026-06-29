package com.stock.invest.controller;

import com.stock.invest.enums.dto.ApiResponse;
import com.stock.invest.enums.dto.StockDailyBarCandleDto;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.StockDailyBarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CTRL-001~008: BarsController 蜡烛图端点单元测试。
 */
@WebMvcTest(BarsController.class)
class BarsControllerCandlesTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StockDailyBarService stockDailyBarService;

    @MockitoBean
    private StockDailyBarRepository stockDailyBarRepository;

    private List<StockDailyBarCandleDto> mockCandles;

    @BeforeEach
    void setUp() {
        mockCandles = List.of(
            new StockDailyBarCandleDto("2026-06-25", 150.0, 152.0, 149.0, 151.0, 1.0, 150.5, 0.5, 1000000L),
            new StockDailyBarCandleDto("2026-06-24", 149.0, 151.0, 148.0, 150.0, 0.5, 149.5, 0.3, 900000L)
        );
    }

    // ---- CTRL-001: /{symbol}/candles returns 200 with candle data ----

    @Test
    @DisplayName("CTRL-001: GET /api/bars/AAPL/candles return 200 with candle data")
    void getCandles_returnsCandles() throws Exception {
        when(stockDailyBarService.getRecentCandles("AAPL", 7)).thenReturn(mockCandles);

        mockMvc.perform(get("/api/bars/AAPL/candles").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].date").value("2026-06-25"))
                .andExpect(jsonPath("$.data[1].date").value("2026-06-24"));

        verify(stockDailyBarService).getRecentCandles("AAPL", 7);
    }

    // ---- CTRL-002: default days=7 when no days param ----

    @Test
    @DisplayName("CTRL-002: GET /api/bars/AAPL/candles without days param uses default 7")
    void getCandles_defaultDays() throws Exception {
        when(stockDailyBarService.getRecentCandles("AAPL", 7)).thenReturn(mockCandles);

        mockMvc.perform(get("/api/bars/AAPL/candles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(stockDailyBarService).getRecentCandles("AAPL", 7);
    }

    // ---- CTRL-003: custom days parameter works ----

    @Test
    @DisplayName("CTRL-003: GET /api/bars/TSLA/candles?days=3 passes custom days")
    void getCandles_customDays() throws Exception {
        var tslaCandles = List.of(
            new StockDailyBarCandleDto("2026-06-25", 200.0, 205.0, 199.0, 204.0, 2.0, 203.0, 1.0, 5000000L)
        );
        when(stockDailyBarService.getRecentCandles("TSLA", 3)).thenReturn(tslaCandles);

        mockMvc.perform(get("/api/bars/TSLA/candles").param("days", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].close").value(204.0));

        verify(stockDailyBarService).getRecentCandles("TSLA", 3);
    }

    // ---- CTRL-004: empty candle list handled gracefully ----

    @Test
    @DisplayName("CTRL-004: GET /api/bars/ZXYZ/candles returns success with empty array")
    void getCandles_emptyList() throws Exception {
        when(stockDailyBarService.getRecentCandles("ZXYZ", 7)).thenReturn(List.of());

        mockMvc.perform(get("/api/bars/ZXYZ/candles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ---- CTRL-005: service throws exception - returns 500 ----

    @Test
    @DisplayName("CTRL-005: service exception returns 500 with error message")
    void getCandles_serviceException_returns500() throws Exception {
        when(stockDailyBarService.getRecentCandles("AAPL", 7))
                .thenThrow(new RuntimeException("DB connection failed"));

        mockMvc.perform(get("/api/bars/AAPL/candles"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Failed to retrieve candle data"));
    }

    // ---- CTRL-006: symbol with special characters ----

    @Test
    @DisplayName("CTRL-006: GET /api/bars/BRK.A/candles handles dot in symbol")
    void getCandles_specialCharSymbol() throws Exception {
        var brkCandles = List.of(
            new StockDailyBarCandleDto("2026-06-25", 400.0, 401.0, 399.0, 400.5, 0.2, 400.0, 0.1, 100000L)
        );
        when(stockDailyBarService.getRecentCandles("BRK.A", 7)).thenReturn(brkCandles);

        mockMvc.perform(get("/api/bars/BRK.A/candles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].symbol").doesNotExist());
    }

    // ---- CTRL-007: all candle fields present in response ----

    @Test
    @DisplayName("CTRL-007: response contains all StockDailyBarCandleDto fields")
    void getCandles_allFieldsPresent() throws Exception {
        when(stockDailyBarService.getRecentCandles("AAPL", 7)).thenReturn(mockCandles);

        mockMvc.perform(get("/api/bars/AAPL/candles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].date").isString())
                .andExpect(jsonPath("$.data[0].open").isNumber())
                .andExpect(jsonPath("$.data[0].high").isNumber())
                .andExpect(jsonPath("$.data[0].low").isNumber())
                .andExpect(jsonPath("$.data[0].close").isNumber())
                .andExpect(jsonPath("$.data[0].changePercent").isNumber())
                .andExpect(jsonPath("$.data[0].afterHours").isNumber())
                .andExpect(jsonPath("$.data[0].afterHoursChangePercent").isNumber())
                .andExpect(jsonPath("$.data[0].volume").isNumber());
    }

    // ---- CTRL-008: days=0 or negative defaults gracefully ----

    @Test
    @DisplayName("CTRL-008: GET /api/bars/AAPL/candles?days=0 passes 0 to service")
    void getCandles_zeroDays() throws Exception {
        when(stockDailyBarService.getRecentCandles("AAPL", 0)).thenReturn(List.of());

        mockMvc.perform(get("/api/bars/AAPL/candles").param("days", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        verify(stockDailyBarService).getRecentCandles("AAPL", 0);
    }
}
