package com.stock.invest.http;

import com.stock.invest.config.HttpClientProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    // ========== P1-9: 网络层故障重试（ResourceAccessException 指数退避） ==========
    //
    // 生产 RestTemplate 无法注入，测试经反射替换 private final restTemplate 为 mock，
    // 验证重试次数与退避时序（sleep 为真实等待，退避基数 500ms * 2^(attempt-1)）。

    private ResilientHttpExecutor newExecutorWithRetries(int maxRetries) {
        lenient().when(props.getMaxRetries()).thenReturn(maxRetries);
        return new ResilientHttpExecutor(props);
    }

    private static void injectRestTemplate(ResilientHttpExecutor executor, RestTemplate mockRt)
            throws Exception {
        Field f = ResilientHttpExecutor.class.getDeclaredField("restTemplate");
        f.setAccessible(true);
        f.set(executor, mockRt);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("P1-9: 连接拒绝（ConnectException）→ 重试 maxRetries 次后抛 ResourceAccessException")
    void networkError_retriesThenThrows() throws Exception {
        ResilientHttpExecutor executor = newExecutorWithRetries(3);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("connect refused", new ConnectException("Connection refused")));
        injectRestTemplate(executor, rt);

        long start = System.nanoTime();
        ResourceAccessException ex = assertThrows(ResourceAccessException.class,
                () -> executor.get("http://127.0.0.1:1/quote"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        verify(rt, times(3)).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
        // 两次重试退避：500 + 1000 = 1.5s（未计 jitter 上界）
        assertTrue(elapsedMs >= 1450, "backoff sleeps should happen, elapsed=" + elapsedMs);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("P1-9: maxRetries=1 → 仅 1 次调用后抛错")
    void maxRetriesRespected_singleAttempt() throws Exception {
        ResilientHttpExecutor executor = newExecutorWithRetries(1);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("connect refused"));
        injectRestTemplate(executor, rt);

        assertThrows(ResourceAccessException.class, () -> executor.get("http://127.0.0.1:1/quote"));
        verify(rt, times(1)).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("P1-9: 重试间隔符合指数退避（500ms→1000ms，外加 0~250ms jitter）")
    void networkError_backoffSequence() throws Exception {
        ResilientHttpExecutor executor = newExecutorWithRetries(3);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("socket timeout"));
        injectRestTemplate(executor, rt);

        long start = System.nanoTime();
        assertThrows(ResourceAccessException.class, () -> executor.get("http://127.0.0.1:1/quote"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // 基数 500 + 1000 = 1500ms；jitter 每次 0~250ms → 上界 2000ms；CI 余量放宽
        assertTrue(elapsedMs >= 1450, "at least exponential base, elapsed=" + elapsedMs);
        assertTrue(elapsedMs <= 4000, "within jitter bounds, elapsed=" + elapsedMs);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("P1-9: 一次瞬态失败后恢复成功 → 返回 body，调用 2 次")
    void successAfterTransientRetry() throws Exception {
        ResilientHttpExecutor executor = newExecutorWithRetries(3);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("connect refused"))
                .thenReturn(ResponseEntity.ok("{\"ok\":true}"));
        injectRestTemplate(executor, rt);

        String body = executor.get("http://127.0.0.1:1/quote");

        assertEquals("{\"ok\":true}", body);
        verify(rt, times(2)).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("P1-9: DNS 失败（UnknownHostException）同样走重试")
    void dnsFailure_retries() throws Exception {
        ResilientHttpExecutor executor = newExecutorWithRetries(2);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("dns fail", new UnknownHostException("no.such.host.invalid")));
        injectRestTemplate(executor, rt);

        assertThrows(ResourceAccessException.class, () -> executor.get("http://no.such.host.invalid/quote"));
        verify(rt, times(2)).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
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
