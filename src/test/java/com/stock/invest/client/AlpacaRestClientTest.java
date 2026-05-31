package com.stock.invest.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * UT-16 ~ UT-25: AlpacaRestClient 单元测试（Mock HTTP）
 */
class AlpacaRestClientTest {

    private ObjectMapper objectMapper;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
    }

    // -- 凭证检查 --

    @Test @DisplayName("UT-16: hasCredentials=true when both keys present")
    void hasCredentials_bothPresent() {
        AlpacaRestClient client = new AlpacaRestClient("key123", "secret456", new ObjectMapper());
        assertTrue(client.hasCredentials());
    }

    @Test @DisplayName("UT-17: hasCredentials=false when both empty")
    void hasCredentials_bothEmpty() {
        AlpacaRestClient client = new AlpacaRestClient("", "", new ObjectMapper());
        assertFalse(client.hasCredentials());
    }

    @Test @DisplayName("UT-18: hasCredentials=false when keyId missing")
    void hasCredentials_keyIdMissing() {
        AlpacaRestClient client = new AlpacaRestClient("", "secret456", new ObjectMapper());
        assertFalse(client.hasCredentials());
    }

    @Test @DisplayName("UT-19: hasCredentials=false when secretKey missing")
    void hasCredentials_secretMissing() {
        AlpacaRestClient client = new AlpacaRestClient("key123", "", new ObjectMapper());
        assertFalse(client.hasCredentials());
    }

    @Test @DisplayName("UT-19b: hasCredentials=false when blank")
    void hasCredentials_blank() {
        AlpacaRestClient client = new AlpacaRestClient("  ", "secret", new ObjectMapper());
        assertFalse(client.hasCredentials());
    }

    // -- isTradingDay Mock HTTP --

    @SuppressWarnings("unchecked")
    @Test @DisplayName("UT-20: API returns trading day (non-empty list)")
    void isTradingDay_tradingDay() throws Exception {
        HttpResponse<String> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.body()).thenReturn("[{\"date\":\"2026-06-01\",\"open\":\"09:30\",\"close\":\"16:00\"}]");

        HttpClient mockClient = mock(HttpClient.class);
        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResp);

        AlpacaRestClient client = new AlpacaRestClient("key", "secret", objectMapper);
        // Use reflection to inject mock HttpClient
        injectHttpClient(client, mockClient);

        assertTrue(client.isTradingDay(LocalDate.of(2026, 6, 1)));
    }

    @SuppressWarnings("unchecked")
    @Test @DisplayName("UT-21: API returns non-trading day (empty list)")
    void isTradingDay_nonTradingDay() throws Exception {
        HttpResponse<String> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.body()).thenReturn("[]");

        HttpClient mockClient = mock(HttpClient.class);
        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResp);

        AlpacaRestClient client = new AlpacaRestClient("key", "secret", objectMapper);
        injectHttpClient(client, mockClient);

        assertFalse(client.isTradingDay(LocalDate.of(2026, 7, 4)));
    }

    @SuppressWarnings("unchecked")
    @Test @DisplayName("UT-22: API returns 401 throws IOException")
    void isTradingDay_unauthorized() throws Exception {
        HttpResponse<String> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(401);

        HttpClient mockClient = mock(HttpClient.class);
        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResp);

        AlpacaRestClient client = new AlpacaRestClient("key", "secret", objectMapper);
        injectHttpClient(client, mockClient);

        assertThrows(IOException.class, () -> client.isTradingDay(LocalDate.of(2026, 6, 1)));
    }

    @SuppressWarnings("unchecked")
    @Test @DisplayName("UT-23: API returns 429 throws IOException")
    void isTradingDay_rateLimited() throws Exception {
        HttpResponse<String> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(429);
        when(mockResp.body()).thenReturn("{\"message\":\"rate limit\"}");

        HttpClient mockClient = mock(HttpClient.class);
        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResp);

        AlpacaRestClient client = new AlpacaRestClient("key", "secret", objectMapper);
        injectHttpClient(client, mockClient);

        assertThrows(IOException.class, () -> client.isTradingDay(LocalDate.of(2026, 6, 1)));
    }

    @SuppressWarnings("unchecked")
    @Test @DisplayName("UT-24: API returns 500 throws IOException")
    void isTradingDay_serverError() throws Exception {
        HttpResponse<String> mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(500);
        when(mockResp.body()).thenReturn("{\"error\":\"internal\"}");

        HttpClient mockClient = mock(HttpClient.class);
        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResp);

        AlpacaRestClient client = new AlpacaRestClient("key", "secret", objectMapper);
        injectHttpClient(client, mockClient);

        assertThrows(IOException.class, () -> client.isTradingDay(LocalDate.of(2026, 6, 1)));
    }

    @SuppressWarnings("unchecked")
    @Test @DisplayName("UT-25: HTTP timeout throws IOException")
    void isTradingDay_timeout() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.net.http.HttpTimeoutException("timeout"));

        AlpacaRestClient client = new AlpacaRestClient("key", "secret", objectMapper);
        injectHttpClient(client, mockClient);

        assertThrows(IOException.class, () -> client.isTradingDay(LocalDate.of(2026, 6, 1)));
    }

    @Test @DisplayName("UT-25b: getKeyIdMasked short key returns empty")
    void getKeyIdMasked_short() {
        AlpacaRestClient client = new AlpacaRestClient("ab", "secret", new ObjectMapper());
        assertEquals("", client.getKeyIdMasked());
    }

    @Test @DisplayName("UT-25c: getKeyIdMasked normal key")
    void getKeyIdMasked_normal() {
        AlpacaRestClient client = new AlpacaRestClient("ABCD1234", "secret", new ObjectMapper());
        assertEquals("ABCD****", client.getKeyIdMasked());
    }

    /** Reflection helper to inject mock HttpClient */
    private void injectHttpClient(AlpacaRestClient client, HttpClient mockClient) throws Exception {
        java.lang.reflect.Field field = AlpacaRestClient.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(client, mockClient);
    }
}
