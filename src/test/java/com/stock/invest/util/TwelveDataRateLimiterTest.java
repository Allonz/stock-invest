package com.stock.invest.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评审 C-1：TwelveDataRateLimiter 单元测试 —— Java 侧跨进程最小间隔限速。
 * <p>验证：同一 key 连续请求按最小间隔节流；不同 key 互不阻塞（多 key 轮换互补）。</p>
 */
class TwelveDataRateLimiterTest {

    @Test
    @DisplayName("同一 key 连续两次 acquire 被最小间隔节流")
    void sameKey_consecutiveCallsAreThrottled() throws Exception {
        long intervalMillis = 150;
        TwelveDataRateLimiter limiter = new TwelveDataRateLimiter(intervalMillis);

        long t0 = System.nanoTime();
        limiter.acquire("key-A");
        limiter.acquire("key-A");
        long elapsedMs = java.time.Duration.ofNanos(System.nanoTime() - t0).toMillis();

        // 第二次调用必须等到 ≥ interval（留 10% 计时余量）
        assertTrue(elapsedMs >= intervalMillis - 15,
                "second call to the same key must wait for the min interval, elapsed=" + elapsedMs);
    }

    @Test
    @DisplayName("不同 key 互不阻塞（多 key 轮换时各 key 独立计时）")
    void differentKeys_doNotBlockEachOther() throws Exception {
        long intervalMillis = 5_000; // 大间隔：若跨 key 误共享限速，测试会等待 5s
        TwelveDataRateLimiter limiter = new TwelveDataRateLimiter(intervalMillis);

        long t0 = System.nanoTime();
        limiter.acquire("key-A");
        limiter.acquire("key-B");
        limiter.acquire("key-C");
        long elapsedMs = java.time.Duration.ofNanos(System.nanoTime() - t0).toMillis();

        // 三个不同 key 的请求应立即放行（远小于间隔）
        assertTrue(elapsedMs < 500,
                "different keys must not share the throttle, elapsed=" + elapsedMs);
    }

    @Test
    @DisplayName("间隔过后同一 key 再次 acquire 立即放行")
    void sameKey_afterIntervalPassesAllowedImmediately() throws Exception {
        long intervalMillis = 100;
        TwelveDataRateLimiter limiter = new TwelveDataRateLimiter(intervalMillis);

        limiter.acquire("key-A");
        Thread.sleep(intervalMillis + 50);
        long t0 = System.nanoTime();
        limiter.acquire("key-A");
        long elapsedMs = java.time.Duration.ofNanos(System.nanoTime() - t0).toMillis();

        assertTrue(elapsedMs < 80, "call after the interval must not wait, elapsed=" + elapsedMs);
    }
}
