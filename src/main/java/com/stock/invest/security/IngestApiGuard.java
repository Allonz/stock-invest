package com.stock.invest.security;

import com.stock.invest.config.IngestProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class IngestApiGuard {

    private final IngestProperties ingestProperties;

    public IngestApiGuard(IngestProperties ingestProperties) {
        this.ingestProperties = ingestProperties;
    }

    /**
     * 校验截图导入 API Key（必选，fail closed）。
     * 服务端未配置 key 时返回 503，缺失/错误 key 返回 401。
     */
    public void verifyOptionalKey(String headerValue) {
        if (!ingestProperties.isKeyRequired()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "INGEST_API_KEY is not configured on server");
        }
        if (headerValue == null || headerValue.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or missing X-INGEST-API-KEY");
        }
        String configured = ingestProperties.getApiKey();
        String provided = headerValue.trim();
        if (!MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or missing X-INGEST-API-KEY");
        }
    }
}
