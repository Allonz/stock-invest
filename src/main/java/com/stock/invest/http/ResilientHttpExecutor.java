package com.stock.invest.http;

import com.stock.invest.config.HttpClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 带简单节流、429 退避与 User-Agent 轮换的 HTTP GET 执行器。
 * 代理用于合规的多出口/企业网络场景，由配置注入而非绕过服务商条款。
 */
@Component
public class ResilientHttpExecutor {

    private static final Logger log = LoggerFactory.getLogger(ResilientHttpExecutor.class);

    private final HttpClientProperties props;
    private final RestTemplate restTemplate;
    private final AtomicInteger userAgentIndex = new AtomicInteger(0);
    /** 下一个可发起请求的时间槽（epoch ms），用于节流间隔控制 */
    private final AtomicLong nextRequestSlotMs = new AtomicLong(0L);

    public ResilientHttpExecutor(HttpClientProperties props) {
        this.props = props;
        this.restTemplate = buildRestTemplate(props);
    }

    private static RestTemplate buildRestTemplate(HttpClientProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());
        if (props.getProxyHost() != null && !props.getProxyHost().trim().isEmpty() && props.getProxyPort() > 0) {
            factory.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(props.getProxyHost().trim(), props.getProxyPort())));
        }
        return new RestTemplate(factory);
    }

    public String get(String url) {
        return get(url, new java.util.HashMap<>());
    }

    public String get(String url, Map<String, String> headers) {
        log.debug("[ResilientHttp] get: begin — url={}", url);
        throttle();
        int attempts = 0;
        int max = Math.max(1, props.getMaxRetries());
        while (true) {
            attempts++;
            log.debug("[ResilientHttp] get: attempt — url={}, attempt={}/{}", url, attempts, max);
            try {
                HttpHeaders httpHeaders = new HttpHeaders();
                httpHeaders.add(HttpHeaders.USER_AGENT, nextUserAgent());
                httpHeaders.add(HttpHeaders.ACCEPT, "*/*");
                if (headers != null) {
                    for (Map.Entry<String, String> entry : headers.entrySet()) {
                        httpHeaders.add(entry.getKey(), entry.getValue());
                    }
                }
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(httpHeaders), String.class);
                log.debug("[ResilientHttp] get: success — url={}, status={}", url, response.getStatusCode().value());
                return response.getBody();
            } catch (HttpStatusCodeException ex) {
                if (ex.getStatusCode().value() == 429 && attempts < max) {
                    long backoffMs = parseRetryAfterMs(ex) + jitter(attempts);
                    log.warn("[ResilientHttp] get: HTTP 429, backing off {} ms (attempt {}/{})", backoffMs, attempts, max);
                    if (!sleepQuietly(backoffMs)) {
                        throw new org.springframework.web.client.ResourceAccessException(
                                "Interrupted while backing off for HTTP 429");
                    }
                    continue;
                }
                if ((ex.getStatusCode().value() >= 500 || ex.getStatusCode().value() == 408) && attempts < max) {
                    long backoffMs = backoffForAttempt(attempts);
                    log.warn("[ResilientHttp] get: HTTP {} retry in {} ms (attempt {}/{})",
                            ex.getStatusCode().value(), backoffMs, attempts, max);
                    if (!sleepQuietly(backoffMs)) {
                        throw new org.springframework.web.client.ResourceAccessException(
                                "Interrupted while backing off for HTTP " + ex.getStatusCode().value());
                    }
                    continue;
                }
                log.error("[ResilientHttp] get: HTTP {} non-retryable — url={}", ex.getStatusCode().value(), url);
                throw ex;
            } catch (org.springframework.web.client.ResourceAccessException ex) {
                // P1-9：网络层故障（连接拒绝、SocketTimeout、DNS 失败）——最常见的瞬时故障，按指数退避重试
                if (attempts < max) {
                    long backoffMs = backoffForAttempt(attempts);
                    log.warn("[ResilientHttp] get: network error, retry in {} ms (attempt {}/{}) — url={}, error={}",
                            backoffMs, attempts, max, url, ex.getMessage());
                    if (!sleepQuietly(backoffMs)) {
                        throw new org.springframework.web.client.ResourceAccessException(
                                "Interrupted while backing off for network error");
                    }
                    continue;
                }
                log.error("[ResilientHttp] get: network error, retries exhausted — url={}", url, ex);
                throw ex;
            }
        }
    }

    private void throttle() {
        int min = Math.max(0, props.getMinIntervalMs());
        if (min <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        // 原子抢占下一个时间槽，等待在锁外进行，避免持锁 sleep 阻塞其他调用方
        long slot = nextRequestSlotMs.getAndUpdate(last -> Math.max(now, last + min));
        long wait = slot - now;
        if (wait > 0) {
            log.debug("[ResilientHttp] throttle: waiting {} ms", wait);
            if (!sleepQuietly(wait)) {
                throw new org.springframework.web.client.ResourceAccessException(
                        "Interrupted while throttling HTTP request");
            }
        }
    }

    private String nextUserAgent() {
        List<String> agents = props.getUserAgents();
        if (agents == null || agents.isEmpty()) {
            return "stock-invest/1.0";
        }
        int idx = Math.floorMod(userAgentIndex.getAndIncrement(), agents.size());
        return agents.get(idx);
    }


    private static long parseRetryAfterMs(HttpStatusCodeException ex) {
        HttpHeaders respHeaders = ex.getResponseHeaders();
        if (respHeaders == null) {
            return 2_000L;
        }
        List<String> ra = respHeaders.get("Retry-After");
        if (ra != null && !ra.isEmpty()) {
            try {
                long seconds = Long.parseLong(ra.get(0).trim());
                return Math.min(300_000, Math.max(1_000, seconds * 1000L));
            } catch (NumberFormatException e) {
                // ignore - field not applicable
            }
        }
        return 2_000L;
    }

    /**
     * R2 P2-6：指数退避 = 基数 × 2^(attempt-1) + jitter；基数/抖动上界均可注入（HttpClientProperties）。
     */
    private long backoffForAttempt(int attempt) {
        long base = Math.max(1, props.getBackoffBaseMs());
        return (long) (base * Math.pow(2, attempt - 1)) + jitter(attempt);
    }

    private long jitter(int attempt) {
        long max = Math.max(0, props.getJitterMaxMs());
        return (long) (Math.random() * (max + 1)) + (attempt * 50L);
    }

    /**
     * 中断安全的 sleep。
     *
     * @return true=正常睡完；false=线程被中断（已恢复中断标记，调用方应停止重试）
     */
    private static boolean sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
