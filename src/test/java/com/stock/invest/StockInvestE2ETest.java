package com.stock.invest;

import com.stock.invest.enums.dto.TigerWatchlistRowDto;
import com.stock.invest.enums.dto.TigerWatchlistIngestRequestDto;
import com.stock.invest.enums.dto.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StockInvest E2E 端点测试。
 * 验证关键 REST 端点返回状态码和基本结构。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("StockInvest E2E — 端点集成测试")
@SuppressWarnings("rawtypes")
class StockInvestE2ETest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ──────────────────────────────────────────────
    // POST /api/ingest/tiger-watchlist -> 200
    // ──────────────────────────────────────────────
    @Test
    @DisplayName("POST /api/ingest/tiger-watchlist -> 200 with valid data")
    void ingestWatchlist_returns200() {
        TigerWatchlistRowDto row = new TigerWatchlistRowDto("AAPL", "Apple Inc.", 0.15, "1.2M");
        TigerWatchlistIngestRequestDto body = new TigerWatchlistIngestRequestDto(
                LocalDate.now().toString(),
                List.of(row)
        );

        ResponseEntity<ApiResponse> resp = rest.postForEntity(
                url("/api/ingest/tiger-watchlist"),
                body,
                ApiResponse.class
        );

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().success());
    }

    // ──────────────────────────────────────────────
    // POST /api/ingest/tiger-watchlist with [] -> 200, imported=0
    // ──────────────────────────────────────────────
    @Test
    @DisplayName("POST /api/ingest/tiger-watchlist with empty array -> 400 (rows empty)")
    void ingestWatchlist_emptyArray_returns400() {
        // When rows is empty the service returns 400
        TigerWatchlistIngestRequestDto body = new TigerWatchlistIngestRequestDto(
                LocalDate.now().toString(),
                List.of()
        );

        ResponseEntity<ApiResponse> resp = rest.postForEntity(
                url("/api/ingest/tiger-watchlist"),
                body,
                ApiResponse.class
        );

        assertEquals(400, resp.getStatusCode().value());
    }

    // ──────────────────────────────────────────────
    // GET /api/screening/latest -> 200
    // ──────────────────────────────────────────────
    @Test
    @DisplayName("GET /api/screening/latest -> 200")
    void getLatestScreening_returns200() {
        ResponseEntity<ApiResponse> resp = rest.getForEntity(
                url("/api/screening/latest"),
                ApiResponse.class
        );

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().success());
    }

    // ──────────────────────────────────────────────
    // GET /api/notification/latest -> 200
    // ──────────────────────────────────────────────
    @Test
    @DisplayName("GET /api/notification/latest -> 200")
    void getNotification_returns200() {
        ResponseEntity<ApiResponse> resp = rest.getForEntity(
                url("/api/notification/latest"),
                ApiResponse.class
        );

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().success());
    }

    // ──────────────────────────────────────────────
    // GET /api/notification/latest -> 200 + "no screening data available"
    // ──────────────────────────────────────────────
    @Test
    @DisplayName("GET /api/notification/latest -> 200 with no-data message")
    void getNotification_noData_returns200() {
        // H2 is empty so should return "no screening data available"
        ResponseEntity<ApiResponse> resp = rest.getForEntity(
                url("/api/notification/latest"),
                ApiResponse.class
        );

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().success());
    }
}
