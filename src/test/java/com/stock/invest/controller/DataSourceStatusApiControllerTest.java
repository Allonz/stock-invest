package com.stock.invest.controller;

import com.stock.invest.datasource.DataSourceAvailabilityChecker;
import com.stock.invest.datasource.DataSourceCapability;
import com.stock.invest.datasource.SourceRequirement;
import com.stock.invest.datasource.SourceStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ET-01 ~ ET-11: DataSourceStatusApiController 端点测试
 */
@WebMvcTest(DataSourceStatusApiController.class)
class DataSourceStatusApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DataSourceAvailabilityChecker checker;

    private SourceStatus makeStatus(String name, boolean available, Set<DataSourceCapability> caps) {
        return new SourceStatus(name, available,
                available ? null : "unavailable",
                SourceRequirement.REQUIRED, available, caps);
    }

    @Test @DisplayName("ET-01: GET /api/datasource/status includes Tiger capabilities")
    void status_tigerCapabilities() throws Exception {
        Map<String, SourceStatus> mockData = new LinkedHashMap<>();
        mockData.put("tiger", makeStatus("tiger", true,
                Set.of(DataSourceCapability.STOCK_QUOTE, DataSourceCapability.TRADING_CALENDAR)));
        when(checker.getAllStatus()).thenReturn(mockData);

        mockMvc.perform(get("/api/datasource/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sources").isArray())
                .andExpect(jsonPath("$.data.sources[0].name").value("tiger"))
                .andExpect(jsonPath("$.data.sources[0].available").value(true))
                .andExpect(jsonPath("$.data.sources[0].capabilities[0]").isString());
    }

    @Test @DisplayName("ET-02: Alpaca capabilities = [TRADING_CALENDAR]")
    void status_alpacaCapabilities() throws Exception {
        Map<String, SourceStatus> mockData = new LinkedHashMap<>();
        mockData.put("alpaca", makeStatus("alpaca", true, Set.of(DataSourceCapability.TRADING_CALENDAR)));
        when(checker.getAllStatus()).thenReturn(mockData);

        mockMvc.perform(get("/api/datasource/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sources[0].name").value("alpaca"))
                .andExpect(jsonPath("$.data.sources[0].capabilities.length()").value(1));
    }

    @Test @DisplayName("ET-03: TwelveData capabilities = [STOCK_QUOTE]")
    void status_twelvedataCapabilities() throws Exception {
        Map<String, SourceStatus> mockData = new LinkedHashMap<>();
        mockData.put("twelvedata", makeStatus("twelvedata", true, Set.of(DataSourceCapability.STOCK_QUOTE)));
        when(checker.getAllStatus()).thenReturn(mockData);

        mockMvc.perform(get("/api/datasource/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sources[0].capabilities.length()").value(1));
    }

    @Test @DisplayName("ET-04: Unavailable source still has capabilities")
    void status_unavailableWithCapabilities() throws Exception {
        Map<String, SourceStatus> mockData = new LinkedHashMap<>();
        mockData.put("tiingo", makeStatus("tiingo", false, Set.of(DataSourceCapability.STOCK_QUOTE)));
        when(checker.getAllStatus()).thenReturn(mockData);

        mockMvc.perform(get("/api/datasource/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sources[0].available").value(false))
                .andExpect(jsonPath("$.data.sources[0].capabilities.length()").value(1));
    }

    @Test @DisplayName("ET-05: GET /api/datasource/status - availableCount + totalCount")
    void status_counts() throws Exception {
        Map<String, SourceStatus> mockData = new LinkedHashMap<>();
        mockData.put("tiger", makeStatus("tiger", true, Set.of(DataSourceCapability.STOCK_QUOTE, DataSourceCapability.TRADING_CALENDAR)));
        mockData.put("alpaca", makeStatus("alpaca", false, Set.of(DataSourceCapability.TRADING_CALENDAR)));
        when(checker.getAllStatus()).thenReturn(mockData);

        mockMvc.perform(get("/api/datasource/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableCount").value(1))
                .andExpect(jsonPath("$.data.totalCount").value(2));
    }

    @Test @DisplayName("ET-06: GET /api/datasource/health returns capabilities too")
    void health_includesCapabilities() throws Exception {
        Map<String, SourceStatus> mockData = new LinkedHashMap<>();
        mockData.put("tiger", makeStatus("tiger", true, Set.of(DataSourceCapability.STOCK_QUOTE, DataSourceCapability.TRADING_CALENDAR)));
        when(checker.getAllStatus()).thenReturn(mockData);

        mockMvc.perform(get("/api/datasource/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sources[0].name").value("tiger"))
                .andExpect(jsonPath("$.data.sources[0].capabilities").isArray());
    }

    @Test @DisplayName("ET-07: Response structure backward compatibility")
    void status_backwardCompatible() throws Exception {
        Map<String, SourceStatus> mockData = new LinkedHashMap<>();
        mockData.put("tiingo", makeStatus("tiingo", false, Set.of(DataSourceCapability.STOCK_QUOTE)));
        when(checker.getAllStatus()).thenReturn(mockData);

        mockMvc.perform(get("/api/datasource/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sources[0].name").exists())
                .andExpect(jsonPath("$.data.sources[0].available").exists())
                .andExpect(jsonPath("$.data.sources[0].reason").exists())
                .andExpect(jsonPath("$.data.sources[0].hasApiKey").exists())
                .andExpect(jsonPath("$.data.sources[0].capabilities").exists());
    }

    @Test @DisplayName("ET-08: Empty capabilities returns empty array, not null")
    void status_emptyCapabilities() throws Exception {
        Map<String, SourceStatus> mockData = new LinkedHashMap<>();
        mockData.put("unknown", makeStatus("unknown", true, Set.of()));
        when(checker.getAllStatus()).thenReturn(mockData);

        mockMvc.perform(get("/api/datasource/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sources[0].capabilities").isArray())
                .andExpect(jsonPath("$.data.sources[0].capabilities.length()").value(0));
    }

    @Test @DisplayName("ET-09: Content-Type is application/json")
    void status_contentType() throws Exception {
        Map<String, SourceStatus> mockData = new LinkedHashMap<>();
        mockData.put("tiger", makeStatus("tiger", true, Set.of()));
        when(checker.getAllStatus()).thenReturn(mockData);

        mockMvc.perform(get("/api/datasource/status"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    @Test @DisplayName("ET-10: HTTP status is 200")
    void status_http200() throws Exception {
        when(checker.getAllStatus()).thenReturn(new LinkedHashMap<>());
        mockMvc.perform(get("/api/datasource/status"))
                .andExpect(status().isOk());
    }
}
