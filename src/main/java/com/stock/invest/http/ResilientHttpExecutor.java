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
import java.util.concurrent.atomic.AtomicInteger;

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
    private volatile long lastRequestAtMs = 0L;

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
        log.debug("[ResilientHttp] get: begin — url={}", url);
        throttle();
        int attempts = 0;
        int max = Math.max(1, props.getMaxRetries());
        while (true) {
            attempts++;
            log.debug("[ResilientHttp] get: attempt — url={}, attempt={}/{}", url, attempts, max);
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.add(HttpHeaders.USER_AGENT, nextUserAgent());
                headers.add(HttpHeaders.ACCEPT, "*/*");
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
                log.debug("[ResilientHttp] get: success — url={}, status={}", url, response.getStatusCodeValue());
                return response.getBody();
            } catch (HttpStatusCodeException ex) {
                if (ex.getRawStatusCode() == 429 && attempts < max) {
                    long backoffMs = parseRetryAfterMs(ex) + jitter(attempts);
                    log.warn("[ResilientHttp] get: HTTP 429, backing off {} ms (attempt {}/{})", backoffMs, attempts, max);
                    rotateUserAgentFor429();
                    sleepQuietly(backoffMs);
                    continue;
                }
                if ((ex.getRawStatusCode() >= 500 || ex.getRawStatusCode() == 408) && attempts < max) {
                    long backoffMs = (long) (500 * Math.pow(2, attempts - 1)) + jitter(attempts);
                    log.warn("[ResilientHttp] get: HTTP {} retry in {} ms (attempt {}/{})",
                            ex.getRawStatusCode(), backoffMs, attempts, max);
                    sleepQuietly(backoffMs);
                    continue;
                }
                log.error("[ResilientHttp] get: HTTP {} non-retryable — url={}", ex.getRawStatusCode(), url);
                throw ex;
            }
        }
    }

    private void throttle() {
        int min = Math.max(0, props.getMinIntervalMs());
        if (min <= 0) {
            return;
        }
        synchronized (this) {
            long now = System.currentTimeMillis();
            long wait = min - (now - lastRequestAtMs);
            if (wait > 0) {
                log.debug("[ResilientHttp] throttle: waiting {} ms", wait);
                sleepQuietly(wait);
            }
            lastRequestAtMs = System.currentTimeMillis();
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

    private void rotateUserAgentFor429() {
        userAgentIndex.incrementAndGet();
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
                return Math.min(60_000, Math.max(1_000, seconds * 1000L));
            } catch (NumberFormatException e) {
                // ignore - field not applicable
            }
        }
        return 2_000L;
    }

    private static long jitter(int attempt) {
        return (long) (Math.random() * 250) + (attempt * 50L);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
