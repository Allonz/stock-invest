package com.stock.invest.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Webhook URL 安全校验器。
 *
 * <p>防止编排接口接收任意 webhook_url 后向内网/云元数据等地址发起 SSRF 请求。
 * 只允许 http/https，且 host 必须命中白名单。</p>
 */
@Component
public class WebhookUrlValidator {

    private final Set<String> allowedHosts;

    public WebhookUrlValidator(
            @Value("${orchestration.allowed-webhook-hosts:localhost,127.0.0.1,::1}") String allowedHosts,
            @Value("${orchestration.webhook-url:}") String defaultWebhookUrl) {
        Set<String> hosts = new LinkedHashSet<>();
        if (allowedHosts != null && !allowedHosts.isBlank()) {
            Arrays.stream(allowedHosts.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(this::normalizeHost)
                    .forEach(hosts::add);
        }
        if (defaultWebhookUrl != null && !defaultWebhookUrl.isBlank()) {
            String defaultHost = extractHost(defaultWebhookUrl);
            if (defaultHost != null) {
                hosts.add(defaultHost);
            }
        }
        this.allowedHosts = hosts.stream()
                .map(this::normalizeHost)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 校验 webhook URL。
     *
     * @param url 待校验 URL
     * @throws IllegalArgumentException URL 非法或 host 不在白名单时抛出
     */
    public void validate(String url) {
        if (url == null || url.isBlank()) {
            return;
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid webhook URL: " + url);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("webhook URL must be http or https");
        }

        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("webhook URL must contain a valid host");
        }

        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            throw new IllegalArgumentException("webhook URL must not contain user info");
        }

        String host = normalizeHost(uri.getHost());
        if (!allowedHosts.contains(host)) {
            throw new IllegalArgumentException("webhook URL host is not allowed: " + uri.getHost());
        }
    }

    private String extractHost(String url) {
        try {
            URI uri = new URI(url);
            return uri.getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        // 兼容 IPv6 方括号写法
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return h;
    }
}
