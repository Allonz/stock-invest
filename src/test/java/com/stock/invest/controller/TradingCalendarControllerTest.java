package com.stock.invest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.stock.invest.service.TradingCalendarDbService;

/**
 * ET-12 ~ ET-15: TradingCalendarController 端点测试
 * Controller 仅委托 TradingCalendarDbService。
 */
@WebMvcTest(TradingCalendarController.class)
class TradingCalendarControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TradingCalendarDbService tradingCalendarDbService;

    @Test @DisplayName("ET-12: /is-open with date param returns trading day")
    void isOpen_tradingDay() throws Exception {
        when(tradingCalendarDbService.isTradingDay(eq("US"), eq(LocalDate.of(2026, 6, 1))))
                .thenReturn(true);

        mockMvc.perform(get("/api/v1/trading-calendar/is-open")
                        .param("date", "2026-06-01")
                        .param("exchange", "XNYS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.isOpen").value(true))
                .andExpect(jsonPath("$.data.date").value("2026-06-01"));
    }

    @Test @DisplayName("ET-13: /is-open returns non-trading day")
    void isOpen_nonTradingDay() throws Exception {
        when(tradingCalendarDbService.isTradingDay(eq("US"), eq(LocalDate.of(2026, 7, 4))))
                .thenReturn(false);

        mockMvc.perform(get("/api/v1/trading-calendar/is-open")
                        .param("date", "2026-07-04")
                        .param("exchange", "XNYS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.isOpen").value(false));
    }

    @Test @DisplayName("ET-14: /is-open defaults to true when dbService returns null")
    void isOpen_defaultTrueWhenNull() throws Exception {
        when(tradingCalendarDbService.isTradingDay(any(), any()))
                .thenReturn(null);

        mockMvc.perform(get("/api/v1/trading-calendar/is-open")
                        .param("date", "2026-12-25")
                        .param("exchange", "XNYS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.isOpen").value(true));
    }

    @Test @DisplayName("ET-15: /is-open with invalid date returns 400")
    void isOpen_invalidDateFormat() throws Exception {
        mockMvc.perform(get("/api/v1/trading-calendar/is-open")
                        .param("date", "invalid-date"))
                .andExpect(status().isBadRequest());
    }

    // ---- R2 P2-8: fetch-full-year 冷却只写成功、失败不冷却 ----

    @Test @DisplayName("R2 P2-8: 成功 → 冷却写入，窗口内二次触发 429 SYNC_IN_PROGRESS")
    void fetchFullYear_successThenCooldown() throws Exception {
        when(tradingCalendarDbService.fetchAndStoreFullYear(eq("US"), eq(2026))).thenReturn(250);

        mockMvc.perform(post("/api/v1/trading-calendar/fetch-full-year")
                        .param("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fetched").value(250));

        // 冷却窗口（30 分钟）内二次触发 → 429
        mockMvc.perform(post("/api/v1/trading-calendar/fetch-full-year")
                        .param("year", "2026"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorType").value("SYNC_IN_PROGRESS"));
    }

    @Test @DisplayName("R2 P2-8: 失败 → 冷却未写入，立即重试成功")
    void fetchFullYear_failureThenImmediateRetrySucceeds() throws Exception {
        // 用 2025 年隔离冷却状态（成功用例使用 2026，避免测试间耦合）
        when(tradingCalendarDbService.fetchAndStoreFullYear(eq("US"), eq(2025)))
                .thenThrow(new RuntimeException("external api down"))
                .thenReturn(250);

        mockMvc.perform(post("/api/v1/trading-calendar/fetch-full-year")
                        .param("year", "2025"))
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.success").value(false));

        // 失败不冷却 → 立即重试成功（无需等 30 分钟）
        mockMvc.perform(post("/api/v1/trading-calendar/fetch-full-year")
                        .param("year", "2025"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fetched").value(250));
    }
}
