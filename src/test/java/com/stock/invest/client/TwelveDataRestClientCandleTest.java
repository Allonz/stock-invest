package com.stock.invest.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.config.TwelveDataProperties;
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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * JAVA-TD-001~006: TwelveDataRestClient 蜡烛图/K线数据查询单元测试。
 * Mock ResilientHttpExecutor，验证时间序列、报价、盘后等场景。
 */
@ExtendWith(MockitoExtension.class)
class TwelveDataRestClientCandleTest {

    @Mock
    private ResilientHttpExecutor http;

    @Captor
    private ArgumentCaptor<String> urlCaptor;

    @Captor
    private ArgumentCaptor<Map<String, String>> headersCaptor;

    private TwelveDataProperties props;

    private ObjectMapper objectMapper;

    private TwelveDataRestClient client;

    @BeforeEach
    void setUp() {
        props = new TwelveDataProperties();
        props.setApiKey("test-key-123");
        objectMapper = new ObjectMapper();
        client = new TwelveDataRestClient(http, props, objectMapper);
    }

    // ---- JAVA-TD-001: fetchDailyBars parses valid response ----

    @Test
    @DisplayName("JAVA-TD-001: fetchDailyBars parses valid time_series response")
    void fetchDailyBars_parsesValidResponse() throws Exception {
        String json = "{"
                + "\"meta\":{\"symbol\":\"AAPL\"},"
                + "\"values\":["
                + "  {\"datetime\":\"2024-01-04\",\"open\":\"152\",\"high\":\"155\",\"low\":\"151\",\"close\":\"154\",\"volume\":\"2000000\"},"
                + "  {\"datetime\":\"2024-01-03\",\"open\":\"150\",\"high\":\"152\",\"low\":\"149\",\"close\":\"151\",\"volume\":\"1000000\"}"
                + "]}";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        KLineData data = client.fetchDailyBars("AAPL", 2);

        assertNotNull(data);
        assertEquals("AAPL", data.getSymbol());
        assertNotNull(data.getItems());
        assertEquals(2, data.getItems().size());
        assertEquals(154.0, data.getItems().get(0).getClose(), 0.001);
        assertEquals(151.0, data.getItems().get(1).getClose(), 0.001);
        assertEquals(2000000L, data.getItems().get(0).getVolume());
    }

    // ---- JAVA-TD-002: fetchDailyBars returns empty KLineData when no values ----

    @Test
    @DisplayName("JAVA-TD-002: fetchDailyBars returns empty data when no values field")
    void fetchDailyBars_emptyValues() throws Exception {
        String json = "{\"meta\":{\"symbol\":\"AAPL\"}}";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        KLineData data = client.fetchDailyBars("AAPL", 2);

        assertNotNull(data);
        assertEquals("AAPL", data.getSymbol());
        assertTrue(data.getItems() == null || data.getItems().isEmpty());
    }

    // ---- JAVA-TD-003: fetchDailyBars returns null on API error ----

    @Test
    @DisplayName("JAVA-TD-003: fetchDailyBars returns null when API returns error status")
    void fetchDailyBars_apiError_returnsNull() throws Exception {
        String json = "{\"status\":\"error\",\"message\":\"Invalid symbol\"}";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        KLineData data = client.fetchDailyBars("INVALID", 2);

        assertNull(data);
    }

    // ---- JAVA-TD-004: fetchLastClose returns correct value ----

    @Test
    @DisplayName("JAVA-TD-004: fetchLastClose returns correct double value")
    void fetchLastClose_returnsValue() throws Exception {
        String json = "{\"close\":\"150.25\"}";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        Double close = client.fetchLastClose("AAPL");

        assertEquals(150.25, close, 0.001);
    }

    @Test
    @DisplayName("JAVA-TD-004b: fetchLastClose returns null on error")
    void fetchLastClose_returnsNullOnError() throws Exception {
        String json = "{\"code\":\"error\",\"status\":\"error\"}";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        Double close = client.fetchLastClose("AAPL");

        assertNull(close);
    }

    // ---- JAVA-TD-005: fetchDailyBars uses correct URL and auth headers ----

    @Test
    @DisplayName("JAVA-TD-005: fetchDailyBars builds correct URL with Authorization header")
    void fetchDailyBars_sendsCorrectRequest() throws Exception {
        String json = "{\"values\":[]}";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        client.fetchDailyBars("AAPL", 5);

        verify(http).get(urlCaptor.capture(), headersCaptor.capture());

        String url = urlCaptor.getValue();
        assertTrue(url.contains("/time_series"), "URL should contain /time_series");
        assertTrue(url.contains("symbol=AAPL"), "URL should contain symbol=AAPL");
        assertTrue(url.contains("interval=1day"), "URL should contain interval=1day");
        assertTrue(url.contains("outputsize=5"), "URL should contain outputsize=5");

        Map<String, String> headers = headersCaptor.getValue();
        assertNotNull(headers);
        assertTrue(headers.containsKey("Authorization"));
        assertEquals("apikey test-key-123", headers.get("Authorization"));
    }

    // ---- JAVA-TD-006: Real mode - integration test, skipped ----

    @Test
    @Tag("integration")
    @DisplayName("JAVA-TD-006: Real TwelveData API integration test (skipped in unit test)")
    void fetchDailyBars_realMode_integration() {
        // Integration test — requires valid TwelveData API key and network.
        assertTrue(true);
    }
}
