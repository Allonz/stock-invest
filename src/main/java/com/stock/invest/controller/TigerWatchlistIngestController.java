package com.stock.invest.controller;

import com.stock.invest.enums.dto.ApiResponse;
import com.stock.invest.enums.dto.TigerWatchlistIngestRequestDto;
import com.stock.invest.enums.dto.TigerWatchlistIngestResponseDto;
import com.stock.invest.security.IngestApiGuard;
import com.stock.invest.service.TigerWatchlistIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ingest")
public class TigerWatchlistIngestController {

    private static final Logger log = LoggerFactory.getLogger(TigerWatchlistIngestController.class);

    private final TigerWatchlistIngestService tigerWatchlistIngestService;
    private final IngestApiGuard ingestApiGuard;

    public TigerWatchlistIngestController(
            TigerWatchlistIngestService tigerWatchlistIngestService,
            IngestApiGuard ingestApiGuard) {
        this.tigerWatchlistIngestService = tigerWatchlistIngestService;
        this.ingestApiGuard = ingestApiGuard;
    }

    @PostMapping(value = "/tiger-watchlist", consumes = "application/json", produces = "application/json")
    public ResponseEntity<ApiResponse<TigerWatchlistIngestResponseDto>> ingest(
            @RequestHeader(value = "X-INGEST-API-KEY", required = false) String apiKey,
            @RequestBody TigerWatchlistIngestRequestDto body
    ) {
        try {
            ingestApiGuard.verifyOptionalKey(apiKey);
            TigerWatchlistIngestResponseDto result = tigerWatchlistIngestService.ingest(body);
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid watchlist ingest request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(e.getMessage(), "VALIDATION_ERROR"));
        } catch (Exception e) {
            log.error("Watchlist ingest failed", e);
            throw new com.stock.invest.exception.StockDataException(
                    "ingest", "ingest", "Watchlist ingest failed: " + e.getMessage());
        }
    }
}
