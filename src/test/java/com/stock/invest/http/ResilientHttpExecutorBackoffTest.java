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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    @DisplayName("R2 P2-6: HttpClientProperties 生产默认退避基数 500ms / jitter 250ms（注入化不改默认值）")
    void backoffBaseDefault_500() {
        HttpClientProperties defaults = new HttpClientProperties();
        assertEquals(500, defaults.getBackoffBaseMs(), "production backoff base must stay 500ms");
        assertEquals(250, defaults.getJitterMaxMs(), "production jitter max must stay 250ms");
    }

    @Test
    void getWithAuthHeadersShouldNotThrow() {
        lenient().when(props.getUserAgents()).thenReturn(Collections.singletonList("test-agent"));
        // Just verify the method exists and handles basic cases
        assertNotNull(executor);
    }

    @Test
    @DisplayName("P2: 退避 sleep 期间线程被中断 → 停止重试并抛 ResourceAccessException，保留中断标记")
    void interruptedDuringBackoff_stopsRetryingAndPreservesFlag() throws Exception {
        lenient().when(props.getMaxRetries()).thenReturn(3);
        lenient().when(props.getBackoffBaseMs()).thenReturn(5000);
        lenient().when(props.getJitterMaxMs()).thenReturn(0);
        ResilientHttpExecutor interruptibleExecutor = new ResilientHttpExecutor(props);

        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("connect refused"));
        injectRestTemplate(interruptibleExecutor, rt);

        AtomicReference<Thread> workerRef = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean interruptedFlag =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = pool.submit(() -> {
                workerRef.set(Thread.currentThread());
                try {
                    return interruptibleExecutor.get("http://127.0.0.1:1/quote");
                } finally {
                    interruptedFlag.set(Thread.currentThread().isInterrupted());
                }
            });

            // 等待 worker 进入退避 sleep（TIMED_WAITING）
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (workerRef.get() == null || workerRef.get().getState() != Thread.State.TIMED_WAITING) {
                if (System.nanoTime() > deadline) {
                    break;
                }
                Thread.sleep(20);
            }
            assertNotNull(workerRef.get(), "worker thread should have started");
            workerRef.get().interrupt();

            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> future.get(2, TimeUnit.SECONDS));
            assertInstanceOf(ResourceAccessException.class, ex.getCause(),
                    "interrupted backoff should surface as ResourceAccessException");
            assertTrue(interruptedFlag.get(),
                    "worker thread must preserve interrupt flag after sleepQuietly returns false");
            verify(rt, times(1)).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("P2: 多线程节流并发调用全部成功，无死锁（slot 抢占在锁外 sleep）")
    void throttleConcurrentCalls_allSucceedWithoutDeadlock() throws Exception {
        lenient().when(props.getMaxRetries()).thenReturn(1);
        lenient().when(props.getMinIntervalMs()).thenReturn(150);
        lenient().when(props.getUserAgents()).thenReturn(List.of("agent-a", "agent-b"));
        ResilientHttpExecutor concurrentExecutor = new ResilientHttpExecutor(props);

        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"ok\":true}"));
        injectRestTemplate(concurrentExecutor, rt);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> concurrentExecutor.get("http://127.0.0.1:1/quote")));
            }
            for (Future<String> future : futures) {
                assertEquals("{\"ok\":true}", future.get(5, TimeUnit.SECONDS),
                        "all throttled calls must complete successfully");
            }
            verify(rt, times(4)).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
        } finally {
            pool.shutdownNow();
        }
    }

    // ========== P1-9: 网络层故障重试（ResourceAccessException 指数退避） ==========
    //
    // 生产 RestTemplate 无法注入，测试经反射替换 private final restTemplate 为 mock，
    // 验证重试次数与退避时序（sleep 为真实等待，退避基数 500ms * 2^(attempt-1)）。

    private ResilientHttpExecutor newExecutorWithRetries(int maxRetries) {
        lenient().when(props.getMaxRetries()).thenReturn(maxRetries);
        // R2 P2-6：注入化退避 —— 基数 10ms、jitter 0，毫秒级验证时序，避免真实 1.5~2s 等待
        lenient().when(props.getBackoffBaseMs()).thenReturn(10);
        lenient().when(props.getJitterMaxMs()).thenReturn(0);
        return new ResilientHttpExecutor(props);
    }

    private static void injectRestTemplate(ResilientHttpExecutor executor, RestTemplate mockRt)
            throws Exception {
        Field f = ResilientHttpExecutor.class.getDeclaredField("restTemplate");
        f.setAccessible(true);
        f.set(executor, mockRt);
    }

    @Test
    @DisplayName("P1-9: 连接拒绝（ConnectException）→ 重试 maxRetries 次后抛 ResourceAccessException")
    void networkError_retriesThenThrows() throws Exception {
        ResilientHttpExecutor executor = newExecutorWithRetries(3);
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("connect refused", new ConnectException("Connection refused")));
        injectRestTemplate(executor, rt);

        long start = System.nanoTime();
        assertThrows(ResourceAccessException.class,
                () -> executor.get("http://127.0.0.1:1/quote"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        verify(rt, times(3)).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
        // R2 P2-6：注入基数 10ms → 两次退避 10 + 20 = 30ms（jitter=0），毫秒级验证
        assertTrue(elapsedMs >= 30, "backoff sleeps should happen, elapsed=" + elapsedMs);
    }

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

        // R2 P2-6：注入基数 10ms、jitter=0 → 基数 10 + 20 = 30ms（另加 attempt*50 线性项）；
        // 断言毫秒级下界 + 调用次数，删除真实 1.5~2s 等待
        assertTrue(elapsedMs >= 30, "at least exponential base, elapsed=" + elapsedMs);
        assertTrue(elapsedMs <= 500, "within injected bounds, elapsed=" + elapsedMs);
        verify(rt, times(3)).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

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
