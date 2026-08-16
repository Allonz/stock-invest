package com.stock.invest.controller;

import com.stock.invest.enums.dto.TigerWatchlistIngestRequestDto;
import com.stock.invest.enums.dto.TigerWatchlistIngestResponseDto;
import com.stock.invest.security.IngestApiGuard;
import com.stock.invest.service.TigerWatchlistIngestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TigerWatchlistIngestController.class)
@DisplayName("TigerWatchlistIngestController — 截图导入接口")
class TigerWatchlistIngestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TigerWatchlistIngestService tigerWatchlistIngestService;

    @MockitoBean
    private IngestApiGuard ingestApiGuard;

    @Test
    @DisplayName("Service 抛 IllegalArgumentException 时返回 400 VALIDATION_ERROR")
    void illegalArgument_returns400ValidationError() throws Exception {
        when(tigerWatchlistIngestService.ingest(any(TigerWatchlistIngestRequestDto.class)))
                .thenThrow(new IllegalArgumentException("tradeDate must be yyyy-MM-dd"));

        mockMvc.perform(post("/api/ingest/tiger-watchlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tradeDate\":\"2026-13-40\",\"rows\":[{\"symbol\":\"AAPL\",\"lastPrice\":10.0,\"openPrice\":9.5,\"volume\":\"100\"}]}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorType").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("正常导入返回 200 与响应结构")
    void validRequest_returns200() throws Exception {
        when(tigerWatchlistIngestService.ingest(any(TigerWatchlistIngestRequestDto.class)))
                .thenReturn(new TigerWatchlistIngestResponseDto(
                        "batch-1", LocalDate.of(2026, 8, 16), 1, 0, List.of()));

        mockMvc.perform(post("/api/ingest/tiger-watchlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tradeDate\":\"2026-08-16\",\"rows\":[{\"symbol\":\"AAPL\",\"lastPrice\":10.0,\"openPrice\":9.5,\"volume\":\"100\"}]}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.batchId").value("batch-1"))
                .andExpect(jsonPath("$.data.imported").value(1));
    }
}
