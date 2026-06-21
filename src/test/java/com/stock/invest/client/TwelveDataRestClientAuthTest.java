package com.stock.invest.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.config.TwelveDataProperties;
import com.stock.invest.http.ResilientHttpExecutor;
import com.stock.invest.model.KLineData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TwelveDataRestClientAuthTest {

    @Mock
    private ResilientHttpExecutor http;

    private TwelveDataProperties props;

    private ObjectMapper objectMapper;

    private TwelveDataRestClient client;

    @BeforeEach
    void setUp() {
        props = new TwelveDataProperties();
        objectMapper = new ObjectMapper();
        client = new TwelveDataRestClient(http, props, objectMapper);
    }

    @Test
    void buildUrlShouldNotContainApiKey() throws Exception {
        Method buildUrl = TwelveDataRestClient.class.getDeclaredMethod("buildUrl", String.class, String.class);
        buildUrl.setAccessible(true);

        String url = (String) buildUrl.invoke(client, "/stocks", "?country=United%20States");
        assertNotNull(url);
        assertFalse(url.contains("apikey"), "buildUrl() should not contain 'apikey' parameter");
        assertTrue(url.contains("/stocks?country=United%20States"), url);
    }

    @Test
    void authHeadersShouldReturnCorrectAuthorization() throws Exception {
        props.setApiKey("test-key-123");

        Method authHeaders = TwelveDataRestClient.class.getDeclaredMethod("authHeaders");
        authHeaders.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) authHeaders.invoke(client);

        assertNotNull(headers);
        assertTrue(headers.containsKey("Authorization"));
        assertEquals("apikey test-key-123", headers.get("Authorization"));
    }

    @Test
    void multiKeyRoundRobinShouldRotateCorrectly() throws Exception {
        props.setApiKeys("key1,key2,key3");

        Method authHeaders = TwelveDataRestClient.class.getDeclaredMethod("authHeaders");
        authHeaders.setAccessible(true);

        String[] expectedSequence = {"key1", "key2", "key3", "key1", "key2", "key3"};
        for (int i = 0; i < 6; i++) {
            @SuppressWarnings("unchecked")
            Map<String, String> headers = (Map<String, String>) authHeaders.invoke(client);
            assertEquals("apikey " + expectedSequence[i], headers.get("Authorization"),
                    "Round-robin call " + i + " should use key " + expectedSequence[i]);
        }
    }

    @Test
    void multiKeyRoundRobinShouldIncludeSingleKey() throws Exception {
        props.setApiKey("single-key");

        Method authHeaders = TwelveDataRestClient.class.getDeclaredMethod("authHeaders");
        authHeaders.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) authHeaders.invoke(client);
        assertEquals("apikey single-key", headers.get("Authorization"));
    }

    // ---- Extended: test fetch methods with mock HTTP ----

    @Test
    void listUsStockSymbolsShouldParseResponse() throws Exception {
        String jsonResponse = "{"
                + "\"data\":["
                + "  {\"symbol\":\"AAPL\"},"
                + "  {\"symbol\":\"MSFT\"},"
                + "  {\"symbol\":\"GOOGL\"}"
                + "]}";
        when(http.get(anyString(), anyMap())).thenReturn(jsonResponse);

        List<String> symbols = client.listUsStockSymbols(2);
        assertEquals(2, symbols.size());
        assertEquals("AAPL", symbols.get(0));
        assertEquals("MSFT", symbols.get(1));
    }

    @Test
    void listUsStockSymbolsShouldHandleApiError() throws Exception {
        String jsonResponse = "{\"code\":\"429\",\"message\":\"Rate limit exceeded\"}";
        when(http.get(anyString(), anyMap())).thenReturn(jsonResponse);

        List<String> symbols = client.listUsStockSymbols(10);
        assertTrue(symbols.isEmpty());
    }

    @Test
    void fetchLastCloseShouldReturnValue() throws Exception {
        String jsonResponse = "{\"close\":\"150.25\"}";
        when(http.get(anyString(), anyMap())).thenReturn(jsonResponse);

        Double close = client.fetchLastClose("AAPL");
        assertEquals(150.25, close, 0.001);
    }

    @Test
    void fetchLastCloseShouldReturnNullOnError() throws Exception {
        String jsonResponse = "{\"code\":\"error\",\"status\":\"error\"}";
        when(http.get(anyString(), anyMap())).thenReturn(jsonResponse);

        Double close = client.fetchLastClose("AAPL");
        assertNull(close);
    }

    @Test
    void fetchDailyBarsShouldParseResponse() throws Exception {
        String jsonResponse = "{"
                + "\"values\":["
                + "  {\"datetime\":\"2024-01-03\",\"open\":\"150\",\"high\":\"152\",\"low\":\"149\",\"close\":\"151\",\"volume\":\"1000000\"},"
                + "  {\"datetime\":\"2024-01-02\",\"open\":\"149\",\"high\":\"151\",\"low\":\"148\",\"close\":\"150\",\"volume\":\"900000\"}"
                + "]}";
        when(http.get(anyString(), anyMap())).thenReturn(jsonResponse);

        KLineData data = client.fetchDailyBars("AAPL", 2);
        assertNotNull(data);
        assertEquals("AAPL", data.getSymbol());
        assertNotNull(data.getItems());
        assertEquals(2, data.getItems().size());
        assertEquals(151.0, data.getItems().get(0).getClose(), 0.001);
        assertEquals(150.0, data.getItems().get(1).getClose(), 0.001);
    }

    @Test
    void fetchDailyBarsShouldReturnEmptyWhenNoValues() throws Exception {
        String jsonResponse = "{}";
        when(http.get(anyString(), anyMap())).thenReturn(jsonResponse);

        KLineData data = client.fetchDailyBars("AAPL", 2);
        assertNotNull(data);
        assertTrue(data.getItems() == null || data.getItems().isEmpty());
    }

    @Test
    void fetchDailyBarsShouldHandleApiError() throws Exception {
        String jsonResponse = "{\"status\":\"error\",\"message\":\"Invalid symbol\"}";
        when(http.get(anyString(), anyMap())).thenReturn(jsonResponse);

        KLineData data = client.fetchDailyBars("INVALID", 2);
        assertNull(data, "API error should return null");
    }

    @Test
    void buildUrlShouldUseCustomBaseUrl() throws Exception {
        props.setBaseUrl("https://custom.example.com");
        Method buildUrl = TwelveDataRestClient.class.getDeclaredMethod("buildUrl", String.class, String.class);
        buildUrl.setAccessible(true);

        String url = (String) buildUrl.invoke(client, "/stocks", "?country=US");
        assertTrue(url.startsWith("https://custom.example.com/stocks?country=US"));
    }

    @Test
    void resolvedKeysShouldCombineApiKeysAndApiKey() {
        props.setApiKeys("key1,key2");
        props.setApiKey("fallback-key");
        List<String> keys = props.resolvedKeys();
        assertEquals(3, keys.size());
        assertEquals("key1", keys.get(0));
        assertEquals("key2", keys.get(1));
        assertEquals("fallback-key", keys.get(2));
    }

    @Test
    void resolvedKeysShouldExcludePlaceholderApiKey() {
        props.setApiKey("your_api_key_here");
        List<String> keys = props.resolvedKeys();
        assertTrue(keys.isEmpty());
    }

    @Test
    void resolvedKeysShouldReturnEmptyWhenNothingConfigured() {
        List<String> keys = props.resolvedKeys();
        assertTrue(keys.isEmpty());
    }
}
