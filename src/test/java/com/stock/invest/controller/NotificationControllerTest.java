package com.stock.invest.controller;

import com.stock.invest.entity.ScreeningMatch;
import com.stock.invest.repository.ScreeningMatchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NotificationController.class)
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

        ScreeningMatch m1 = new ScreeningMatch();
        m1.setBatchId(batchId);
        m1.setTradeDate(tradeDate);
        m1.setSymbol("AAPL");
        m1.setWindowDays(7);
        m1.setAlgorithm("increasing_volume");

        ScreeningMatch m2 = new ScreeningMatch();
        m2.setBatchId(batchId);
        m2.setTradeDate(tradeDate);
        m2.setSymbol("MSFT");
        m2.setWindowDays(7);
        m2.setAlgorithm("increasing_volume");

        when(screeningMatchRepository.findTopByOrderByTradeDateDescIdDesc())
                .thenReturn(Optional.of(m1));
        when(screeningMatchRepository.findByBatchIdOrderByIdAsc(batchId))
                .thenReturn(List.of(m1, m2));

        mockMvc.perform(get("/api/notification/latest")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.batchId").value(batchId))
                .andExpect(jsonPath("$.data.screenDate").value("2026-05-18"))
                .andExpect(jsonPath("$.data.results.increasing_volume['7d']").value(2));
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
                .andExpect(jsonPath("$.data.message").value("暂无筛选数据"));
    }
}
