package com.stock.invest.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.HttpURLConnection;

/**
 * Webhook 通知专用 HTTP 配置。
 *
 * <p>安全与健壮性：</p>
 * <ul>
 *   <li>禁用 HttpURLConnection 的自动重定向 —— 防止白名单 host 通过 302 将请求导向内网地址（SSRF 绕过）；</li>
 *   <li>设置 connect/read 超时 —— 避免回调端点不可达时通知线程无限挂起。</li>
 * </ul>
 */
@Configuration
public class WebhookHttpConfig {

    @Value("${orchestration.webhook-connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${orchestration.webhook-read-timeout-ms:10000}")
    private int readTimeoutMs;

    @Bean("webhookRestTemplate")
    public RestTemplate webhookRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                // 关键：禁止跟随 3xx 重定向，防止 SSRF 绕过 WebhookUrlValidator 的白名单校验
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }
}
