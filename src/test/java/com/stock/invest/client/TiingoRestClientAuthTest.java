package com.stock.invest.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.config.TiingoProperties;
import com.stock.invest.http.ResilientHttpExecutor;
import com.stock.invest.model.KLineData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TiingoRestClientAuthTest {

    @Mock
    private ResilientHttpExecutor http;

    private TiingoProperties props;

    private ObjectMapper objectMapper;

    private TiingoRestClient client;

    @BeforeEach
    void setUp() {
        props = new TiingoProperties();
        objectMapper = new ObjectMapper();
        client = new TiingoRestClient(http, props, objectMapper);
    }

    @Test
    void authHeadersShouldReturnCorrectAuthorization() throws Exception {
        props.setToken("my-tiingo-token-abc");

        Method authHeaders = TiingoRestClient.class.getDeclaredMethod("authHeaders");
        authHeaders.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) authHeaders.invoke(client);
        assertNotNull(headers);
        assertTrue(headers.containsKey("Authorization"));
        assertEquals("Token my-tiingo-token-abc", headers.get("Authorization"));
    }

    @Test
    void emptyTokenShouldThrowIllegalStateException() throws Exception {
        props.setToken("");

        Method requireToken = TiingoRestClient.class.getDeclaredMethod("requireToken");
        requireToken.setAccessible(true);

        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                () -> requireToken.invoke(client));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("tiingo token is missing"));
    }

    @Test
    void placeholderTokenShouldThrowIllegalStateException() throws Exception {
        props.setToken("your_tiingo_token_here");

        Method requireToken = TiingoRestClient.class.getDeclaredMethod("requireToken");
        requireToken.setAccessible(true);

        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                () -> requireToken.invoke(client));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("tiingo token is missing"));
    }

    // ---- Extended: test fetch methods ----

    @Test
    void fetchDailyBarsShouldParseResponseAndSort() throws Exception {
        props.setToken("valid-token");
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
    }

    @Test
    void fetchDailyBarsShouldHandleEmptyArray() throws Exception {
        props.setToken("valid-token");
        String json = "[]";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        KLineData data = client.fetchDailyBars("AAPL", 3);
        assertNotNull(data);
        assertTrue(data.getItems() == null || data.getItems().isEmpty());
    }

    @Test
    void fetchDailyBarsShouldHandleErrorObject() throws Exception {
        props.setToken("valid-token");
        String json = "{\"error\":\"Invalid token\"}";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        assertThrows(Exception.class, () -> client.fetchDailyBars("AAPL", 3));
    }

    @Test
    void fetchDailyBarsShouldHandleDetailError() throws Exception {
        props.setToken("valid-token");
        String json = "{\"detail\":\"Not found\"}";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        assertThrows(Exception.class, () -> client.fetchDailyBars("INVALID", 3));
    }

    @Test
    void fetchDailyBarsShouldHandleMessageWithCodeError() throws Exception {
        props.setToken("valid-token");
        String json = "{\"message\":\"Rate limit\",\"code\":429}";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        assertThrows(Exception.class, () -> client.fetchDailyBars("AAPL", 3));
    }

    @Test
    void fetchLastCloseShouldReturnNullWhenNoData() throws Exception {
        props.setToken("valid-token");
        String json = "[]";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        Double result = client.fetchLastClose("AAPL");
        assertNull(result);
    }

    @Test
    void fetchLastCloseShouldReturnClose() throws Exception {
        props.setToken("valid-token");
        String json = "["
                + "{\"date\":\"2024-01-04T00:00:00.000Z\",\"open\":152.0,\"high\":155.0,\"low\":151.0,\"close\":154.0,\"volume\":2000000}"
                + "]";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        Double result = client.fetchLastClose("AAPL");
        assertEquals(154.0, result, 0.001);
    }

    @Test
    void hasTokenShouldReturnFalseForBlank() {
        props.setToken("");
        assertFalse(props.hasToken());
        props.setToken("  ");
        assertFalse(props.hasToken());
    }

    @Test
    void hasTokenShouldReturnFalseForPlaceholder() {
        props.setToken("your_tiingo_token_here");
        assertFalse(props.hasToken());
        props.setToken("YOUR_TIINGO_TOKEN_HERE");
        assertFalse(props.hasToken());
    }

    @Test
    void hasTokenShouldReturnTrueForValidToken() {
        props.setToken("abc123validtoken");
        assertTrue(props.hasToken());
    }

    // ---- Price range test ----

    @Test
    void listUsSymbolsByPriceRangeShouldFilter() throws Exception {
        props.setToken("valid-token");
        String json = "["
                + "{\"ticker\":\"AAPL\",\"tngoLast\":150.0},"
                + "{\"ticker\":\"MSFT\",\"last\":50.0},"
                + "{\"ticker\":\"GOOGL\",\"prevClose\":200.0},"
                + "{\"ticker\":\"AMZN\",\"open\":80.0}"
                + "]";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        List<String> result = client.listUsSymbolsByPriceRange(10, 40.0, 100.0);
        // MSFT (50.0) and AMZN (80.0) should be in range
        assertTrue(result.contains("MSFT"), "MSFT at 50.0 should be in 40-100 range");
        assertTrue(result.contains("AMZN"), "AMZN at 80.0 should be in 40-100 range");
    }

    @Test
    void listUsSymbolsByPriceRangeShouldSkipInvalidSymbols() throws Exception {
        props.setToken("valid-token");
        String json = "["
                + "{\"ticker\":\"\",\"last\":50.0},"
                + "{\"ticker\":\"lowercase\",\"last\":50.0},"
                + "{\"ticker\":\"@BAD!\",\"last\":50.0}"
                + "]";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        List<String> result = client.listUsSymbolsByPriceRange(10, 40.0, 100.0);
        assertTrue(result.isEmpty());
    }

    @Test
    void listUsSymbolsByPriceRangeShouldHandleNonArrayResponse() throws Exception {
        props.setToken("valid-token");
        String json = "{\"error\":\"not found\"}";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        assertThrows(Exception.class, () -> client.listUsSymbolsByPriceRange(10, 40.0, 100.0));
    }

    @Test
    void listUsSymbolsByPriceRangeWithNoMatchingPrice() throws Exception {
        props.setToken("valid-token");
        String json = "["
                + "{\"ticker\":\"AAPL\",\"tngoLast\":500.0}"
                + "]";
        when(http.get(anyString(), anyMap())).thenReturn(json);

        List<String> result = client.listUsSymbolsByPriceRange(10, 1.0, 10.0);
        assertTrue(result.isEmpty());
    }
}
