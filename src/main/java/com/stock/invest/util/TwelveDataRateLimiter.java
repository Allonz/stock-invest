package com.stock.invest.util;

import java.util.HashMap;
import java.util.Map;

/**
 * TwelveData 跨进程最小请求间隔限速器（评审 C-1）。
 *
 * <p>问题背景：Python 脚本 {@code stock_info_twelvedata.py} 内的 {@code rate_limit()}
 * 依赖进程内全局变量 {@code last_request_time}，而 Java 每次请求都会新起一个 Python
 * 子进程 —— 限速状态随进程销毁而丢失，elapsed 恒为巨大值，限速从不生效，逐 symbol
 * 补缺循环会直接打爆 TwelveData 分钟配额（Free tier 8 req/min → 429）。</p>
 *
 * <p>本类在 Java 侧（服务进程内单例）按 API Key 维护最近请求时间：所有 Python
 * 子进程调用都经由同一个 Spring 单例服务发起，因此限速状态天然跨进程共享；
 * 并发调用通过 synchronized 串行化"检查-等待-记录"，保证同一 key 任意两次
 * 实际 API 请求的间隔不小于 {@link #minIntervalMillis}。多 key 轮换（评审 F-2）
 * 与限速互补：每个 key 独立计时，N 个 key 的总体吞吐约为 N × 配额。</p>
 *
 * <p>Python 脚本内原有的 rate_limit() 保留作为单进程内兜底（多命令脚本内部多次
 * API 调用场景），两者不冲突。</p>
 */
public class TwelveDataRateLimiter {

    /** 保守默认间隔：10s/key → 每 key ≤ 6 req/min（Free tier 为 8 req/min，留余量） */
    private static final long DEFAULT_MIN_INTERVAL_MILLIS = 10_000L;

    private final long minIntervalMillis;
    /** key -> 该 key 最近一次请求的毫秒时间戳 */
    private final Map<String, Long> lastRequestAtByKey = new HashMap<>();

    public TwelveDataRateLimiter() {
        this(DEFAULT_MIN_INTERVAL_MILLIS);
    }

    /** 测试/调参用：显式指定最小间隔。 */
    public TwelveDataRateLimiter(long minIntervalMillis) {
        this.minIntervalMillis = minIntervalMillis;
    }

    /**
     * 按 key 限速：距该 key 上一次请求不足 {@code minIntervalMillis} 时阻塞等待，
     * 直到满足最小间隔后记录本次请求时间。
     *
     * @param apiKey 本次请求使用的 API Key（轮换时各 key 独立计时）
     * @throws InterruptedException 等待期间被中断
     */
    public void acquire(String apiKey) throws InterruptedException {
        synchronized (this) {
            long now = System.currentTimeMillis();
            long last = lastRequestAtByKey.getOrDefault(apiKey, 0L);
            long waitMillis = minIntervalMillis - (now - last);
            if (waitMillis > 0) {
                Thread.sleep(waitMillis);
            }
            lastRequestAtByKey.put(apiKey, System.currentTimeMillis());
        }
    }
}
