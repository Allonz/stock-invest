package com.stock.invest.controller;

import com.stock.invest.service.SymbolBlacklistService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = BlacklistController.class, properties = "admin.api-key=test-admin-key")
@DisplayName("BlacklistController — 鉴权")
class BlacklistControllerAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SymbolBlacklistService symbolBlacklistService;

    @Test
    @DisplayName("无 X-Admin-API-Key 清除黑名单返回 401")
    void clearWithoutKey_returns401() throws Exception {
        mockMvc.perform(post("/api/blacklist/clear").param("symbol", "AAPL"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("正确 X-Admin-API-Key 清除黑名单放行")
    void clearWithKey_passesAuth() throws Exception {
        mockMvc.perform(post("/api/blacklist/clear")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .param("symbol", "AAPL"))
                .andExpect(status().isOk());
    }
}
