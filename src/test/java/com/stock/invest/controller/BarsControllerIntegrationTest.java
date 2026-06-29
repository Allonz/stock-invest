package com.stock.invest.controller;

import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.repository.StockDailyBarRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成测试：BarsController 连接真实 MySQL 验证。
 * <p>
 * 测试 INT-CTRL-001~005。
 * 使用 @Tag("integration") 标记，不干扰 CI 的 Mock 测试。
 * 连接真实 MySQL，只读查询，不修改数据库。
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@Tag("integration")
class BarsControllerIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BarsControllerIntegrationTest.class);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private StockDailyBarRepository repository;

    // ---- INT-CTRL-001: GET /api/bars/AAPL/candles?days=7 → 200, 返回 date/open/high/low/close/changePercent ----

    @Test
    @DisplayName("INT-CTRL-001: GET /api/bars/AAPL/candles?days=7 → 200, 返回 candle 字段")
    void testGetCandles_returnsCandleData() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/bars/AAPL/candles?days=7", Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map body = response.getBody();
        assertNotNull(body);
        assertTrue((Boolean) body.get("success"), "success should be true");

        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        assertNotNull(data, "data should not be null");
        assertTrue(data.size() <= 7, "data length should be ≤ 7");

        if (!data.isEmpty()) {
            Map<String, Object> first = data.get(0);
            assertNotNull(first.get("date"), "date should not be null");
            assertNotNull(first.get("open"), "open should not be null");
            assertNotNull(first.get("high"), "high should not be null");
            assertNotNull(first.get("low"), "low should not be null");
            assertNotNull(first.get("close"), "close should not be null");
            assertNotNull(first.get("changePercent"), "changePercent should not be null");
            assertNotNull(first.get("volume"), "volume should not be null");
        }
    }

    // ---- INT-CTRL-002: 查询 DB 对比 changePercent 一致性 ----

    @Test
    @DisplayName("INT-CTRL-002: 查询 DB 对比 changePercent 一致性")
    void testChangePercentMatchesDb() {
        String symbol = findSymbolWithChangePercent();
        if (symbol == null) {
            log.warn("No symbol with change_percent found in DB, skipping INT-CTRL-002");
            return;
        }

        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/bars/" + symbol + "/candles?days=7", Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map body = response.getBody();
        assertNotNull(body);

        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        assertNotNull(data);

        for (Map<String, Object> candle : data) {
            String dateStr = (String) candle.get("date");
            LocalDate tradeDate = LocalDate.parse(dateStr);
            Double apiChangePercent = (Double) candle.get("changePercent");

            // Query DB for the same row
            List<StockDailyBar> bars = repository
                    .findBySymbolAndTradeDate(symbol, tradeDate)
                    .stream().toList();

            if (!bars.isEmpty()) {
                Double dbChangePercent = bars.get(0).getChangePercent();
                if (dbChangePercent != null && apiChangePercent != null) {
                    assertEquals(dbChangePercent, apiChangePercent, 0.0001,
                            "changePercent mismatch for " + symbol + " on " + dateStr);
                }
            }
        }
    }

    // ---- INT-CTRL-003: GET /api/bars/ZZZZZ/candles → 200, data=[] ----

    @Test
    @DisplayName("INT-CTRL-003: GET /api/bars/ZZZZZ/candles → 200, data=[]")
    void testGetCandles_unknownSymbol() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/bars/ZZZZZ/candles", Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map body = response.getBody();
        assertNotNull(body);
        assertTrue((Boolean) body.get("success"));

        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        assertNotNull(data, "data should be empty array, not null");
        assertTrue(data.isEmpty(), "data should be empty for unknown symbol");
    }

    // ---- INT-CTRL-004: GET /api/bars/AAPL/candles?days=3 → data.length ≤ 3 ----

    @Test
    @DisplayName("INT-CTRL-004: GET /api/bars/AAPL/candles?days=3 → data.length ≤ 3")
    void testGetCandles_respectsDaysParam() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/bars/AAPL/candles?days=3", Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map body = response.getBody();
        assertNotNull(body);

        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        assertNotNull(data);
        assertTrue(data.size() <= 3,
                "data.length should be ≤ 3 but was " + data.size());
    }

    // ---- INT-CTRL-005: 查询 after_hours=null 的行 → JSON 中 afterHours=null ----

    @Test
    @DisplayName("INT-CTRL-005: 查询 after_hours=null 的行 → JSON 中 afterHours=null")
    void testAfterHoursNullInResponse() {
        String symbol = findSymbolWithAfterHoursNull();
        if (symbol == null) {
            log.warn("No symbol with after_hours=null found in DB, skipping INT-CTRL-005");
            return;
        }

        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/bars/" + symbol + "/candles?days=7", Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map body = response.getBody();
        assertNotNull(body);

        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        assertNotNull(data);

        // ApiResponse uses @JsonInclude(NON_NULL), so null fields are omitted from JSON
        boolean foundNullAfterHours = false;
        for (Map<String, Object> candle : data) {
            if (!candle.containsKey("afterHours") || candle.get("afterHours") == null) {
                foundNullAfterHours = true;
                break;
            }
        }
        assertTrue(foundNullAfterHours,
                "Expected at least one candle with afterHours=null (or absent from JSON)");
    }

    // ========== Helper methods ==========

    private String findSymbolWithChangePercent() {
        List<String> symbols = repository.findAllSymbols();
        for (String sym : symbols) {
            List<StockDailyBar> bars = repository
                    .findTop7BySymbolOrderByTradeDateDesc(sym);
            for (StockDailyBar bar : bars) {
                if (bar.getChangePercent() != null) {
                    return sym;
                }
            }
        }
        return null;
    }

    private String findSymbolWithAfterHoursNull() {
        List<String> symbols = repository.findAllSymbols();
        for (String sym : symbols) {
            List<StockDailyBar> bars = repository
                    .findTop7BySymbolOrderByTradeDateDesc(sym);
            for (StockDailyBar bar : bars) {
                if (bar.getAfterHours() == null) {
                    return sym;
                }
            }
        }
        return null;
    }
}
