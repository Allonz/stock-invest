package com.stock.invest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ingest")
public class IngestProperties {

    /**
     * 非空时，导入与基于快照的筛选接口需在请求头携带相同值：X-INGEST-API-KEY
     */
    private String apiKey = "";

    public boolean isKeyRequired() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }
}
