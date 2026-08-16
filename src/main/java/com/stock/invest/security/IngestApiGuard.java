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

    public void verifyOptionalKey(String headerValue) {
        if (!ingestProperties.isKeyRequired()) {
            return;
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
