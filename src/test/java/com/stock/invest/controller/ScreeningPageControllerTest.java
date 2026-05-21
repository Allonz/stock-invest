package com.stock.invest.controller;

import com.stock.invest.enums.dto.ScreenerRunResponseDto;
import com.stock.invest.enums.dto.SnapshotGridViewDto;
import com.stock.invest.repository.ScreeningMatchRepository;
import com.stock.invest.service.DataGapFillerService;
import com.stock.invest.service.ScanOrchestratorService;
import com.stock.invest.service.TigerSnapshotGridService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ScreeningPageController.class)
class ScreeningPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ScanOrchestratorService scanOrchestratorService;

    @MockBean
    private ScreeningMatchRepository screeningMatchRepository;

    @MockBean
    private TigerSnapshotGridService tigerSnapshotGridService;

    @MockBean
    private DataGapFillerService dataGapFillerService;

    // ======= TC01-TC04: sanitizeWindowDays =======

    @Test
    void sanitize_null_returnsDefault() {
        assertEquals(7, ScreeningPageController.sanitizeWindowDays(null));
    }

    @Test
    void sanitize_below3_returns3() {
        assertEquals(3, ScreeningPageController.sanitizeWindowDays(1));
    }

    @Test
    void sanitize_above7_returns7() {
        assertEquals(7, ScreeningPageController.sanitizeWindowDays(10));
    }

    @Test
    void sanitize_normal_returnsInput() {
        assertEquals(5, ScreeningPageController.sanitizeWindowDays(5));
    }

    // ======= TC05: GET / =======

    @Test
    void home_redirectsToDaily() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/screener/daily"));
    }

    // ======= TC06: GET /page/screener =======

    @Test
    void pageScreener_redirectsToDaily() throws Exception {
        mockMvc.perform(get("/page/screener"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/screener/daily"));
    }

    // ======= TC07-TC09: GET /screener/daily =======

    @Test
    void daily_withTradeDate_returns200() throws Exception {
        when(tigerSnapshotGridService.buildGrid(48))
                .thenReturn(new SnapshotGridViewDto(List.of(), List.of()));
        mockMvc.perform(get("/screener/daily")
                        .param("tradeDate", "2026-05-20"))
                .andExpect(status().isOk())
                .andExpect(view().name("screener-daily"));
    }

    @Test
    void daily_withoutTradeDate_usesToday() throws Exception {
        when(tigerSnapshotGridService.buildGrid(48))
                .thenReturn(new SnapshotGridViewDto(List.of(), List.of()));
        mockMvc.perform(get("/screener/daily"))
                .andExpect(status().isOk())
                .andExpect(view().name("screener-daily"));
    }

    @Test
    void daily_withWindowDays_usesProvidedValue() throws Exception {
        when(tigerSnapshotGridService.buildGrid(48))
                .thenReturn(new SnapshotGridViewDto(List.of(), List.of()));
        mockMvc.perform(get("/screener/daily")
                        .param("windowDays", "5"))
                .andExpect(status().isOk())
                .andExpect(view().name("screener-daily"));
    }

    // ======= TC10-TC14: POST /screener/run =======

    @Test
    void run_withoutTradeDate_usesToday() throws Exception {
        ScreenerRunResponseDto mockResult = new ScreenerRunResponseDto(
                "batch-1", LocalDate.now(), 100, 10, 3);
        when(scanOrchestratorService.runDailyScanFromSnapshotImport(
                any(LocalDate.class), eq(20), eq(7)))
                .thenReturn(mockResult);

        mockMvc.perform(post("/screener/run"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/screener/daily**"));
    }

    @Test
    void run_withTradeDate_parsesAndRedirects() throws Exception {
        ScreenerRunResponseDto mockResult = new ScreenerRunResponseDto(
                "batch-2", LocalDate.of(2026, 5, 20), 100, 5, 1);
        when(scanOrchestratorService.runDailyScanFromSnapshotImport(
                eq(LocalDate.of(2026, 5, 20)), eq(20), eq(7)))
                .thenReturn(mockResult);

        mockMvc.perform(post("/screener/run")
                        .param("tradeDate", "2026-05-20"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/screener/daily**"))
                .andExpect(redirectedUrlPattern("/screener/daily**"));
    }

    @Test
    void run_withCustomLimit() throws Exception {
        ScreenerRunResponseDto mockResult = new ScreenerRunResponseDto(
                "batch-3", LocalDate.now(), 100, 10, 5);
        when(scanOrchestratorService.runDailyScanFromSnapshotImport(
                any(LocalDate.class), eq(50), eq(7)))
                .thenReturn(mockResult);

        mockMvc.perform(post("/screener/run")
                        .param("limit", "50"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void run_withWindowDays3_uses3() throws Exception {
        ScreenerRunResponseDto mockResult = new ScreenerRunResponseDto(
                "batch-4", LocalDate.now(), 100, 10, 2);
        when(scanOrchestratorService.runDailyScanFromSnapshotImport(
                any(LocalDate.class), eq(20), eq(3)))
                .thenReturn(mockResult);

        mockMvc.perform(post("/screener/run")
                        .param("windowDays", "3"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void run_withWindowDays1_sanitizesTo3() throws Exception {
        ScreenerRunResponseDto mockResult = new ScreenerRunResponseDto(
                "batch-5", LocalDate.now(), 100, 0, 0);
        when(scanOrchestratorService.runDailyScanFromSnapshotImport(
                any(LocalDate.class), eq(20), eq(3)))
                .thenReturn(mockResult);

        mockMvc.perform(post("/screener/run")
                        .param("windowDays", "1"))
                .andExpect(status().is3xxRedirection());
    }

    // ======= TC15: GET /page/screening =======

    @Test
    void screeningPage_returns200() throws Exception {
        when(screeningMatchRepository.findBatchSummary()).thenReturn(List.of());
        mockMvc.perform(get("/page/screening"))
                .andExpect(status().isOk())
                .andExpect(view().name("screening"));
    }

    // ======= TC16: POST /api/debug/trigger-data-fill =======

    @Test
    void triggerDataFill_returnsOk() throws Exception {
        mockMvc.perform(post("/api/debug/trigger-data-fill"))
                .andExpect(status().isOk());
    }
}
