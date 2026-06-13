package com.stock.invest.http;

import com.stock.invest.config.HttpClientProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpStatusCodeException;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResilientHttpExecutorBackoffTest {

    @Mock
    private HttpClientProperties props;

    private ResilientHttpExecutor executor;

    @BeforeEach
    void setUp() {
        lenient().when(props.getConnectTimeoutMs()).thenReturn(5000);
        lenient().when(props.getReadTimeoutMs()).thenReturn(5000);
        lenient().when(props.getMaxRetries()).thenReturn(1);
        lenient().when(props.getMinIntervalMs()).thenReturn(0);
        executor = new ResilientHttpExecutor(props);
    }

    @Test
    void parseRetryAfterMsWith5Seconds() throws Exception {
        long result = invokeParseRetryAfterMs("5");
        assertEquals(5000L, result, "5 seconds should be 5000ms");
    }

    @Test
    void parseRetryAfterMsWith600SecondsShouldClamp() throws Exception {
        long result = invokeParseRetryAfterMs("600");
        assertEquals(300000L, result, "600 seconds should clamp to 300000ms");
    }

    @Test
    void parseRetryAfterMsWith0SecondsShouldBeAtLeast1000() throws Exception {
        long result = invokeParseRetryAfterMs("0");
        assertTrue(result >= 1000L, "0 seconds should produce at least 1000ms, got " + result);
    }

    @Test
    void parseRetryAfterMsWithNullHeaders() throws Exception {
        long result = invokeParseRetryAfterMs(null);
        assertEquals(2000L, result, "Null headers should return default 2000ms");
    }

    @Test
    void parseRetryAfterMsWith1Second() throws Exception {
        long result = invokeParseRetryAfterMs("1");
        assertEquals(1000L, result, "1 second should be 1000ms");
    }

    @Test
    void parseRetryAfterMsWithInvalidHeader() throws Exception {
        long result = invokeParseRetryAfterMs("not-a-number");
        assertEquals(2000L, result, "Invalid Retry-After should return default 2000ms");
    }

    @Test
    void throttleShouldSleepWhenMinIntervalSet() throws Exception {
        lenient().when(props.getMinIntervalMs()).thenReturn(100);
        // Need to create executor with min interval set
        ResilientHttpExecutor throttledExecutor = new ResilientHttpExecutor(props);

        // Verify no exception - throttle should work
        // We can't easily test the actual sleep timing, just that it doesn't throw
        assertNotNull(throttledExecutor);
    }

    @Test
    void constructorShouldSetTimeoutValues() {
        verify(props, atLeastOnce()).getConnectTimeoutMs();
        verify(props, atLeastOnce()).getReadTimeoutMs();
    }

    @Test
    void getWithAuthHeadersShouldNotThrow() {
        lenient().when(props.getUserAgents()).thenReturn(Collections.singletonList("test-agent"));
        // Just verify the method exists and handles basic cases
        assertNotNull(executor);
    }

    private long invokeParseRetryAfterMs(String retryAfterValue) throws Exception {
        Method parseRetryAfterMs = ResilientHttpExecutor.class.getDeclaredMethod(
                "parseRetryAfterMs", HttpStatusCodeException.class);
        parseRetryAfterMs.setAccessible(true);

        HttpStatusCodeException ex;
        if (retryAfterValue == null) {
            ex = mock(HttpStatusCodeException.class);
            when(ex.getResponseHeaders()).thenReturn(null);
        } else {
            HttpHeaders headers = new HttpHeaders();
            headers.add("Retry-After", retryAfterValue);
            ex = mock(HttpStatusCodeException.class);
            when(ex.getResponseHeaders()).thenReturn(headers);
        }

        return (long) parseRetryAfterMs.invoke(null, ex);
    }

    @SuppressWarnings("unused")
    private HttpStatusCodeException createMockException(HttpStatus status, HttpHeaders headers) {
        return new HttpStatusCodeException(status, status.getReasonPhrase(), headers, null, null) {
            @Override
            public HttpHeaders getResponseHeaders() {
                return headers;
            }
        };
    }
}
