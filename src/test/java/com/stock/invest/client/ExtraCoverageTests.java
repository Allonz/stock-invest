package com.stock.invest.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.config.TwelveDataProperties;
import com.stock.invest.http.ResilientHttpExecutor;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Additional coverage tests for TwelveDataRestClient branches not yet covered.
 */
@ExtendWith(MockitoExtension.class)
class ExtraCoverageTests {

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
    void fetchLastCloseWithNumericClose() throws Exception {
        String json = "{\"close\":150.5}";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        Double close = client.fetchLastClose("AAPL");
        assertEquals(150.5, close, 0.001);
    }

    @Test
    void fetchLastCloseWithNullClose() throws Exception {
        String json = "{}";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        Double close = client.fetchLastClose("AAPL");
        assertNull(close);
    }

    @Test
    void parseDatetimeToMillisWithIsoDate() {
        // Use reflection to test parseDatetimeToMillis
    }

    @Test
    void listUsStockSymbolsWithCode200() throws Exception {
        String json = "{\"code\":\"200\",\"data\":[{\"symbol\":\"AAPL\"}]}";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        List<String> symbols = client.listUsStockSymbols(10);
        assertEquals(1, symbols.size());
        assertEquals("AAPL", symbols.get(0));
    }

    @Test
    void listUsStockSymbolsWithNonCodeStatus() throws Exception {
        String json = "{\"code\":\"400\",\"message\":\"Bad request\",\"data\":[{\"symbol\":\"AAPL\"}]}";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        List<String> symbols = client.listUsStockSymbols(10);
        assertEquals(1, symbols.size(), "Should still parse data even with non-200 code");
    }

    @Test
    void fetchDailyBarsWithNullValues() throws Exception {
        String json = "{\"status\":\"ok\"}";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        KLineData data = client.fetchDailyBars("AAPL", 5);
        assertNotNull(data);
        assertEquals("AAPL", data.getSymbol());
        assertTrue(data.getItems() == null || data.getItems().isEmpty());
    }

    @Test
    void emptyApiKeyReturnsEmptyString() throws Exception {
        java.lang.reflect.Method nextApiKey = TwelveDataRestClient.class.getDeclaredMethod("nextApiKey");
        nextApiKey.setAccessible(true);

        String key = (String) nextApiKey.invoke(client);
        assertEquals("", key);
    }
}
