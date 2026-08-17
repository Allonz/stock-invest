package com.stock.invest.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP 端点 HEAD 探测：MCP Streamable HTTP 传输只注册 GET/POST，
 * 客户端（Hermes 等）用 HEAD 探测 /api/mcp 时应返回 200 + Content-Type。
 */
@WebMvcTest(value = McpEndpointHeadProbeController.class, properties = "admin.api-key=test-admin-key")
@DisplayName("McpEndpointHeadProbeController — HEAD /api/mcp")
class McpEndpointHeadProbeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("HEAD /api/mcp 返回 200 且带 application/json Content-Type")
    void headProbe_returns200WithContentType() throws Exception {
        mockMvc.perform(head("/api/mcp").header("X-Admin-API-Key", "test-admin-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")));
    }
}
