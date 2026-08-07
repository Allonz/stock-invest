package com.stock.invest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "http.client")
public class HttpClientProperties {

    private int connectTimeoutMs = 10_000;
    private int readTimeoutMs = 20_000;
    private int minIntervalMs = 250;
    private int maxRetries = 4;
    /** R2 P2-6：指数退避基数（ms）—— 注入化便于测试短退避，生产默认 500 */
    private int backoffBaseMs = 500;
    /** R2 P2-6：随机 jitter 上界（ms）—— 注入化便于测试确定性，生产默认 250 */
    private int jitterMaxMs = 250;
    private List<String> userAgents = new ArrayList<>();
    private String proxyHost = "";
    private int proxyPort = 0;
}
