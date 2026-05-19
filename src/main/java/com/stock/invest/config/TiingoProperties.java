package com.stock.invest.config;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@ToString
@Component
@ConfigurationProperties(prefix = "tiingo.api")
public class TiingoProperties {

    private String baseUrl = "https://api.tiingo.com";
    @ToString.Exclude
    private String token = "";

    public boolean hasToken() {
        return token != null && !token.trim().isEmpty()
                && !"your_tiingo_token_here".equalsIgnoreCase(token.trim());
    }
}
