package com.stock.invest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import com.stock.invest.model.TradingCalendarResult;
import com.stock.invest.repository.TradingCalendarRepository;
import com.stock.invest.service.TradingCalendarDbService;
import com.stock.invest.service.impl.TradingCalendarFallback;

/**
 * ET-12 ~ ET-15: TradingCalendarController 端点测试
 */
@WebMvcTest(TradingCalendarController.class)
class TradingCalendarControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Default: repository returns empty to trigger fallback chain
        when(tradingCalendarRepository.findByMarketAndTradeDate(any(), any()))
                .thenReturn(java.util.Optional.empty());
    }

    @MockBean
    private TradingCalendarFallback fallback;
    @MockBean
    private TradingCalendarDbService tradingCalendarDbService;
    @MockBean
    private TradingCalendarRepository tradingCalendarRepository;

    @Test @DisplayName("ET-12: /is-open with date param returns trading day")
    void isOpen_tradingDay() throws Exception {
        when(fallback.isTradingDay(eq("US"), eq(LocalDate.of(2026, 6, 1))))
                .thenReturn(TradingCalendarResult.trading("US", LocalDate.of(2026, 6, 1), "tiger", "TRADING"));

        mockMvc.perform(get("/api/v1/trading-calendar/is-open")
                        .param("date", "2026-06-01")
                        .param("exchange", "XNYS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isOpen").value(true))
                .andExpect(jsonPath("$.data.source").value("tiger"))
                .andExpect(jsonPath("$.data.market").value("US"))
                .andExpect(jsonPath("$.data.date").value("2026-06-01"));
    }

    @Test @DisplayName("ET-12b: /is-open returns non-trading day")
    void isOpen_nonTradingDay() throws Exception {
        when(fallback.isTradingDay(eq("US"), eq(LocalDate.of(2026, 7, 4))))
                .thenReturn(TradingCalendarResult.nonTrading("US", LocalDate.of(2026, 7, 4), "tiger", "HOLIDAY"));

        mockMvc.perform(get("/api/v1/trading-calendar/is-open")
                        .param("date", "2026-07-04")
                        .param("exchange", "XNYS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isOpen").value(false))
                .andExpect(jsonPath("$.data.source").value("tiger"));
    }

    @Test @DisplayName("ET-13: All sources unavailable -> isOpen=true (DEFAULT)")
    void isOpen_defaultTradingDay() throws Exception {
        when(fallback.isTradingDay(eq("US"), eq(LocalDate.of(2026, 6, 1))))
                .thenReturn(TradingCalendarResult.defaultTradingDay("US", LocalDate.of(2026, 6, 1)));

        mockMvc.perform(get("/api/v1/trading-calendar/is-open")
                        .param("date", "2026-06-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isOpen").value(true))
                .andExpect(jsonPath("$.data.source").value("none"));
    }

    @Test @DisplayName("ET-13b: /is-open without date defaults to today (no exception)")
    void isOpen_noDate_defaultsToday() throws Exception {
        when(fallback.isTradingDay(eq("US"), any(LocalDate.class)))
                .thenReturn(TradingCalendarResult.defaultTradingDay("US", LocalDate.now()));

        mockMvc.perform(get("/api/v1/trading-calendar/is-open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isOpen").isBoolean());
    }

    @Test @DisplayName("ET-14: Invalid date -> 400 Bad Request")
    void isOpen_invalidDate() throws Exception {
        mockMvc.perform(get("/api/v1/trading-calendar/is-open")
                        .param("date", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test @DisplayName("ET-14b: Exchange HK resolves market HK")
    void isOpen_hkExchange() throws Exception {
        when(fallback.isTradingDay(eq("HK"), eq(LocalDate.of(2026, 6, 1))))
                .thenReturn(TradingCalendarResult.nonTrading("HK", LocalDate.of(2026, 6, 1), "tiger", "HOLIDAY"));

        mockMvc.perform(get("/api/v1/trading-calendar/is-open")
                        .param("date", "2026-06-01")
                        .param("exchange", "XHKG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.market").value("HK"))
                .andExpect(jsonPath("$.data.isOpen").value(false));
    }

    @Test @DisplayName("ET-14c: Exchange CN resolves market CN")
    void isOpen_cnExchange() throws Exception {
        when(fallback.isTradingDay(eq("CN"), eq(LocalDate.of(2026, 6, 1))))
                .thenReturn(TradingCalendarResult.trading("CN", LocalDate.of(2026, 6, 1), "tiger", "TRADING"));

        mockMvc.perform(get("/api/v1/trading-calendar/is-open")
                        .param("date", "2026-06-01")
                        .param("exchange", "XSHG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.market").value("CN"))
                .andExpect(jsonPath("$.data.isOpen").value(true));
    }

}
