package com.stock.invest.controller;

import com.stock.invest.enums.dto.StockDailyBarDto;
import com.stock.invest.service.StockDailyBarService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BarsController.class)
@DisplayName("BarsController /single/query — symbol 参数校验")
class BarsControllerQueryTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StockDailyBarService stockDailyBarService;

    @Test
    @DisplayName("缺少 symbol 参数返回 400")
    void missingSymbol_returns400() throws Exception {
        mockMvc.perform(get("/api/bars/single/query"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("空白 symbol 返回 400，不产生 NPE")
    void blankSymbol_returns400() throws Exception {
        mockMvc.perform(get("/api/bars/single/query").param("symbol", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorType").value("BadRequest"))
                .andExpect(jsonPath("$.message").value("symbol is required"));
    }

    @Test
    @DisplayName("合法 symbol 转大写并返回 200")
    void validSymbol_uppercasesAndReturns200() throws Exception {
        when(stockDailyBarService.getBarsBySymbol(anyString())).thenReturn(List.of(new StockDailyBarDto(
                null, "AAPL", null, null, null, null, null, null, null, null, null, null, null)));

        mockMvc.perform(get("/api/bars/single/query").param("symbol", " aapl "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.total").value(1));
    }
}
