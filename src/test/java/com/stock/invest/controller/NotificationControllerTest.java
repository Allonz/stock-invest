package com.stock.invest.controller;

import com.stock.invest.entity.ScreeningMatch;
import com.stock.invest.repository.ScreeningMatchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NotificationController.class)
@ActiveProfiles("test")
@DisplayName("NotificationController — 通知接口测试")
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ScreeningMatchRepository screeningMatchRepository;

    @Test
    @DisplayName("有筛选结果 → 返回完整 JSON")
    void test_latest_withResults() throws Exception {
        String batchId = "screen-20260518-abc123";
        LocalDate tradeDate = LocalDate.of(2026, 5, 18);

        ScreeningMatch latest = new ScreeningMatch();
        latest.setBatchId(batchId);
        latest.setTradeDate(tradeDate);
        latest.setSymbol("AAPL");
        latest.setLastClose(0.15);
        latest.setPrice(0.15);
        latest.setRise(true);
        latest.setDataSource("tiger");
        latest.setWindowDays(7);

        ScreeningMatch match2 = new ScreeningMatch();
        match2.setBatchId(batchId);
        match2.setTradeDate(tradeDate);
        match2.setSymbol("MSFT");
        match2.setLastClose(0.20);
        match2.setPrice(0.20);
        match2.setRise(false);
        match2.setDataSource("tiger");
        match2.setWindowDays(7);

        when(screeningMatchRepository.findTopByOrderByTradeDateDescIdDesc())
                .thenReturn(Optional.of(latest));
        when(screeningMatchRepository.findByBatchIdAndWindowDaysOrderByIdAsc(batchId, 7))
                .thenReturn(List.of(latest, match2));

        mockMvc.perform(get("/api/notification/latest")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.batchId").value(batchId))
                .andExpect(jsonPath("$.data.totalHits.7d").value(2))
                .andExpect(jsonPath("$.data.results.7d[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$.data.results.7d[1].symbol").value("MSFT"));
    }

    @Test
    @DisplayName("无筛选结果 → 空返回")
    void test_latest_noResults() throws Exception {
        when(screeningMatchRepository.findTopByOrderByTradeDateDescIdDesc())
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/notification/latest")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.message").value("no screening data available"));
    }
}
