package com.stock.invest.controller;

import com.stock.invest.service.ScreeningService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScreeningService screeningService;

    @Test
    @DisplayName("有筛选结果 → 返回完整 JSON")
    void test_latest_withResults() throws Exception {
        Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        Map<String, Object> windowGroup = new LinkedHashMap<>();
        windowGroup.put("count", 2L);
        windowGroup.put("stocks", List.of(
                Map.of("symbol", "AAPL", "lastClose", 150.0, "rise", 5.0),
                Map.of("symbol", "MSFT", "lastClose", 300.0, "rise", 10.0)
        ));
        Map<String, Object> algoGroup = new LinkedHashMap<>();
        algoGroup.put("7d", windowGroup);
        results.put("increasing_volume", algoGroup);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchId", "screen-20260518-abc123");
        payload.put("screenDate", "2026-05-18");
        payload.put("results", results);

        // controller 已改调 getLatestNotificationGrouped(String windows) 重载（windows 缺省为 null）
        when(screeningService.getLatestNotificationGrouped(isNull())).thenReturn(payload);

        mockMvc.perform(get("/api/notification/latest")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.batchId").value("screen-20260518-abc123"))
                .andExpect(jsonPath("$.data.screenDate").value("2026-05-18"))
                .andExpect(jsonPath("$.data.results.increasing_volume['7d'].count").value(2));
    }

    @Test
    @DisplayName("无筛选结果 → 空返回")
    void test_latest_noResults() throws Exception {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("message", "暂无筛选数据");
        when(screeningService.getLatestNotificationGrouped(isNull())).thenReturn(empty);

        mockMvc.perform(get("/api/notification/latest")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.message").value("暂无筛选数据"));
    }
}
