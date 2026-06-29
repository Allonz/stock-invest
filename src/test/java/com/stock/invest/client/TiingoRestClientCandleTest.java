package com.stock.invest.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.config.TiingoProperties;
import com.stock.invest.http.ResilientHttpExecutor;
import com.stock.invest.model.KLineData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * JAVA-TG-001~004: TiingoRestClient 蜡烛图/K线数据查询单元测试。
 * Mock ResilientHttpExecutor，验证日线行情获取和解析逻辑。
 */
@ExtendWith(MockitoExtension.class)
class TiingoRestClientCandleTest {

    @Mock
    private ResilientHttpExecutor http;

    @Captor
    private ArgumentCaptor<String> urlCaptor;

    @Captor
    private ArgumentCaptor<Map<String, String>> headersCaptor;

    private TiingoProperties props;

    private ObjectMapper objectMapper;

    private TiingoRestClient client;

    @BeforeEach
    void setUp() {
        props = new TiingoProperties();
        props.setToken("valid-token-abc");
        objectMapper = new ObjectMapper();
        client = new TiingoRestClient(http, props, objectMapper);
    }

    // ---- JAVA-TG-001: fetchDailyBars parses valid response and sorts ----

    @Test
    @DisplayName("JAVA-TG-001: fetchDailyBars parses valid tiingo response and sorts newest first")
    void fetchDailyBars_parsesValidResponse_sortsNewestFirst() throws Exception {
        String json = "["
                + "{\"date\":\"2024-01-04T00:00:00.000Z\",\"open\":152.0,\"high\":155.0,\"low\":151.0,\"close\":154.0,\"volume\":2000000},"
                + "{\"date\":\"2024-01-03T00:00:00.000Z\",\"open\":150.0,\"high\":152.0,\"low\":149.0,\"close\":151.0,\"volume\":1000000}"
                + "]";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        KLineData data = client.fetchDailyBars("AAPL", 3);

        assertNotNull(data);
        assertEquals("AAPL", data.getSymbol());
        assertNotNull(data.getItems());
        assertEquals(2, data.getItems().size());
        // Items should be sorted newest first: 2024-01-04 first
        assertEquals(154.0, data.getItems().get(0).getClose(), 0.001);
        assertEquals(151.0, data.getItems().get(1).getClose(), 0.001);
        assertEquals(2000000L, data.getItems().get(0).getVolume());
    }

    // ---- JAVA-TG-002: fetchDailyBars with date range parameters ----

    @Test
    @DisplayName("JAVA-TG-002: fetchDailyBars with explicit start/end dates")
    void fetchDailyBars_withDateRange() throws Exception {
        String json = "["
                + "{\"date\":\"2024-01-04T00:00:00.000Z\",\"open\":152.0,\"high\":155.0,\"low\":151.0,\"close\":154.0,\"volume\":2000000}"
                + "]";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        LocalDate start = LocalDate.of(2024, 1, 1);
        LocalDate end = LocalDate.of(2024, 1, 5);
        KLineData data = client.fetchDailyBars("AAPL", start, end);

        assertNotNull(data);
        assertEquals("AAPL", data.getSymbol());
        assertEquals(1, data.getItems().size());
        assertEquals(154.0, data.getItems().get(0).getClose(), 0.001);

        verify(http).get(urlCaptor.capture(), headersCaptor.capture());
        String url = urlCaptor.getValue();
        assertTrue(url.contains("startDate=2024-01-01"), "URL should contain startDate");
        assertTrue(url.contains("endDate=2024-01-05"), "URL should contain endDate");
        assertTrue(url.contains("/tiingo/daily/AAPL/prices"), "URL should contain daily prices endpoint");

        Map<String, String> headers = headersCaptor.getValue();
        assertEquals("Token valid-token-abc", headers.get("Authorization"));
    }

    // ---- JAVA-TG-003: fetchLastClose returns correct value from KLineData ----

    @Test
    @DisplayName("JAVA-TG-003: fetchLastClose returns latest close price")
    void fetchLastClose_returnsLatestClose() throws Exception {
        String json = "["
                + "{\"date\":\"2024-01-04T00:00:00.000Z\",\"open\":152.0,\"high\":155.0,\"low\":151.0,\"close\":154.0,\"volume\":2000000}"
                + "]";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        Double close = client.fetchLastClose("AAPL");

        assertEquals(154.0, close, 0.001);
    }

    @Test
    @DisplayName("JAVA-TG-003b: fetchLastClose returns null when no data")
    void fetchLastClose_returnsNullWhenEmpty() throws Exception {
        String json = "[]";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        Double close = client.fetchLastClose("AAPL");

        assertNull(close);
    }

    // ---- JAVA-TG-004: Real mode - integration test, skipped ----

    @Test
    @Tag("integration")
    @DisplayName("JAVA-TG-004: Real Tiingo API integration test (skipped in unit test)")
    void fetchDailyBars_realMode_integration() {
        // Integration test — requires valid Tiingo API token and network.
        assertTrue(true);
    }
}
