package com.stock.invest.config;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@Setter
@ToString
@Component
@ConfigurationProperties(prefix = "twelvedata.api")
public class TwelveDataProperties {

    private String baseUrl = "https://api.twelvedata.com";
    @ToString.Exclude
    private String apiKey = "";
    /**
     * 逗号分隔的多密钥，用于在限流时轮换（仍受套餐配额约束）。
     */
    @ToString.Exclude
    private String apiKeys = "";

    public List<String> resolvedKeys() {
        List<String> keys = new ArrayList<>();
        if (apiKeys != null && !apiKeys.trim().isEmpty()) {
            keys.addAll(Arrays.stream(apiKeys.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList()));
        }
        if (apiKey != null && !apiKey.trim().isEmpty()
                && !"your_api_key_here".equals(apiKey.trim())) {
            keys.add(apiKey.trim());
        }
        return keys;
    }
}
