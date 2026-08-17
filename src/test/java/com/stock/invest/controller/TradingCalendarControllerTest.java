package com.stock.invest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
@WebMvcTest(value = TradingCalendarController.class, properties = "admin.api-key=test-admin-key")
class TradingCalendarControllerTest {

    private static final String ADMIN_API_KEY = "test-admin-key";

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


    @Test @DisplayName("fetch-full-year 无 X-Admin-API-Key 返回 401")
    void fetchFullYearWithoutKey_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/trading-calendar/fetch-full-year").param("year", "2026"))
                .andExpect(status().isUnauthorized());
    }

    // ---- R2 P2-8: fetch-full-year 冷却只写成功、失败不冷却 ----

    @Test @DisplayName("R2 P2-8: 成功 → 冷却写入，窗口内二次触发 429 SYNC_IN_PROGRESS")
    void fetchFullYear_successThenCooldown() throws Exception {
        when(tradingCalendarDbService.fetchAndStoreFullYear(eq("US"), eq(2026))).thenReturn(250);

        mockMvc.perform(post("/api/v1/trading-calendar/fetch-full-year").header("X-Admin-API-Key", ADMIN_API_KEY)
                        .param("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fetched").value(250));

        // 冷却窗口（30 分钟）内二次触发 → 429
        mockMvc.perform(post("/api/v1/trading-calendar/fetch-full-year").header("X-Admin-API-Key", ADMIN_API_KEY)
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

        mockMvc.perform(post("/api/v1/trading-calendar/fetch-full-year").header("X-Admin-API-Key", ADMIN_API_KEY)
                        .param("year", "2025"))
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.success").value(false));

        // 失败不冷却 → 立即重试成功（无需等 30 分钟）
        mockMvc.perform(post("/api/v1/trading-calendar/fetch-full-year").header("X-Admin-API-Key", ADMIN_API_KEY)
                        .param("year", "2025"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fetched").value(250));
    }

    @Test @DisplayName("R2 P2-8: 并发双请求 → 仅一份执行，另一份 429（putIfAbsent 原子抢占）")
    void fetchFullYear_concurrentDualRequest_onlyOneExecutes() throws Exception {
        // 用 2027 年隔离冷却状态；同步服务仅成功返回一次
        when(tradingCalendarDbService.fetchAndStoreFullYear(eq("US"), eq(2027))).thenReturn(251);

        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Integer> f1 = pool.submit(() -> {
                start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/v1/trading-calendar/fetch-full-year").header("X-Admin-API-Key", ADMIN_API_KEY)
                                .param("year", "2027"))
                        .andReturn().getResponse().getStatus();
            });
            java.util.concurrent.Future<Integer> f2 = pool.submit(() -> {
                start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/v1/trading-calendar/fetch-full-year").header("X-Admin-API-Key", ADMIN_API_KEY)
                                .param("year", "2027"))
                        .andReturn().getResponse().getStatus();
            });

            start.countDown();
            int s1 = f1.get(10, java.util.concurrent.TimeUnit.SECONDS);
            int s2 = f2.get(10, java.util.concurrent.TimeUnit.SECONDS);

            // 一份 200、一份 429，恰好一次外部同步
            assertEquals(java.util.Set.of(200, 429), java.util.Set.of(s1, s2),
                    "one request must execute (200), the other must be rate-limited (429), got: " + s1 + "," + s2);
            verify(tradingCalendarDbService, times(1)).fetchAndStoreFullYear(eq("US"), eq(2027));
        } finally {
            pool.shutdownNow();
        }
    }
}
