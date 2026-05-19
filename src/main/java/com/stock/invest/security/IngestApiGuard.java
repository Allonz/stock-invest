package com.stock.invest.security;

import com.stock.invest.config.IngestProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

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
        if (headerValue == null || !ingestProperties.getApiKey().equals(headerValue.trim())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or missing X-INGEST-API-KEY");
        }
    }
}
