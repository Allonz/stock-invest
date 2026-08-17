package com.stock.invest.controller;

import com.stock.invest.service.OrchestrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = OrchestrationController.class, properties = "admin.api-key=test-admin-key")
@DisplayName("OrchestrationController — 鉴权")
class OrchestrationControllerAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrchestrationService orchestrationService;

    @Test
    @DisplayName("无 X-Admin-API-Key 返回 401")
    void stepWithoutKey_returns401() throws Exception {
        mockMvc.perform(post("/api/orchestration/step")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("正确 X-Admin-API-Key 放行进入业务校验（空 body 400）")
    void stepWithKey_passesAuth() throws Exception {
        mockMvc.perform(post("/api/orchestration/step")
                        .header("X-Admin-API-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
