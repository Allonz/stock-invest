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
    private List<String> userAgents = new ArrayList<>();
    private String proxyHost = "";
    private int proxyPort = 0;
}
