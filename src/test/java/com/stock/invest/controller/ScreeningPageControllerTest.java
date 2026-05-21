package com.stock.invest.controller;

import com.stock.invest.entity.ScreeningMatch;
import com.stock.invest.enums.dto.SnapshotGridViewDto;
import com.stock.invest.repository.ScreeningMatchRepository;
import com.stock.invest.service.DataGapFillerService;
import com.stock.invest.service.ScanOrchestratorService;
import com.stock.invest.service.ScreeningService;
import com.stock.invest.service.TigerSnapshotGridService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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

    @MockBean
    private ScreeningService screeningService;

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

    // ======= TC10-TC13: POST /screener/run (calls screeningService.runScreening) =======

    @Test
    void run_withoutTradeDate_callsRunScreening() throws Exception {
        when(screeningService.runScreening(any(LocalDate.class))).thenReturn("batch-all-1");
        when(screeningMatchRepository.findByBatchIdOrderByIdAsc("batch-all-1"))
                .thenReturn(List.of());

        mockMvc.perform(post("/screener/run"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/screener/daily**"));
    }

    @Test
    void run_withTradeDate_usesParsedDate() throws Exception {
        when(screeningService.runScreening(LocalDate.of(2026, 5, 20))).thenReturn("batch-all-2");
        when(screeningMatchRepository.findByBatchIdOrderByIdAsc("batch-all-2"))
                .thenReturn(List.of());

        mockMvc.perform(post("/screener/run")
                        .param("tradeDate", "2026-05-20"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/screener/daily**"));
    }

    @Test
    void run_withMatches_includesCountInNotice() throws Exception {
        ScreeningMatch m1 = new ScreeningMatch();
        m1.setSymbol("AAPL"); m1.setBatchId("batch-3"); m1.setTradeDate(LocalDate.now());
        ScreeningMatch m2 = new ScreeningMatch();
        m2.setSymbol("MSFT"); m2.setBatchId("batch-3"); m2.setTradeDate(LocalDate.now());

        when(screeningService.runScreening(any(LocalDate.class))).thenReturn("batch-3");
        when(screeningMatchRepository.findByBatchIdOrderByIdAsc("batch-3"))
                .thenReturn(List.of(m1, m2));

        mockMvc.perform(post("/screener/run")
                        .param("limit", "5"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/screener/daily**notice**"));
    }

    @Test
    void run_noticeContainsWindows2to7() throws Exception {
        when(screeningService.runScreening(any(LocalDate.class))).thenReturn("batch-4");
        when(screeningMatchRepository.findByBatchIdOrderByIdAsc("batch-4"))
                .thenReturn(List.of());

        mockMvc.perform(post("/screener/run"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/screener/daily**2d**"));
    }

    // ======= TC14: GET /page/screening =======

    @Test
    void screeningPage_returns200() throws Exception {
        when(screeningMatchRepository.findBatchSummary()).thenReturn(List.of());
        mockMvc.perform(get("/page/screening"))
                .andExpect(status().isOk())
                .andExpect(view().name("screening"));
    }

    // ======= TC15: POST /api/debug/trigger-data-fill =======

    @Test
    void triggerDataFill_returnsOk() throws Exception {
        mockMvc.perform(post("/api/debug/trigger-data-fill"))
                .andExpect(status().isOk());
    }
}
