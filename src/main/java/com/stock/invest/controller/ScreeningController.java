package com.stock.invest.controller;

import com.stock.invest.entity.ScreeningMatch;
import com.stock.invest.enums.dto.ApiResponse;
import com.stock.invest.repository.ScreeningMatchRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/screening")
public class ScreeningController {

    private static final Logger log = LoggerFactory.getLogger(ScreeningController.class);

    private final ScreeningMatchRepository screeningMatchRepository;
    private final StockDailyBarRepository stockDailyBarRepository;

    public ScreeningController(ScreeningMatchRepository screeningMatchRepository, StockDailyBarRepository stockDailyBarRepository) {
        this.screeningMatchRepository = screeningMatchRepository;
        this.stockDailyBarRepository = stockDailyBarRepository;
    }

    /**
     * GET /api/screening/latest — 最新一次筛选结果
     */
    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<Map<String, Object>>> latest() {
        try {
        Optional<ScreeningMatch> top = screeningMatchRepository.findTopByOrderByTradeDateDescIdDesc();
        if (top.isEmpty()) {
            Map<String, Object> emptyResult = new HashMap<>();
            emptyResult.put("batchId", null);
            emptyResult.put("tradeDate", null);
            emptyResult.put("matches", List.of());
            return ResponseEntity.ok(ApiResponse.ok(emptyResult));
        }

        ScreeningMatch latest = top.get();
        String batchId = latest.getBatchId();
        List<ScreeningMatch> matches = screeningMatchRepository.findByBatchIdOrderByIdAsc(batchId);

        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);
        result.put("tradeDate", latest.getTradeDate().toString());
        result.put("totalMatches", matches.size());
        // 批量查询 stock name
        var symbols = matches.stream().map(ScreeningMatch::getSymbol).distinct().toList();
        var nameMap = stockDailyBarRepository.findBySymbolInAndNameIsNotNull(symbols)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        bar -> bar.getSymbol(),
                        bar -> bar.getName(),
                        (a, b) -> a
                ));

        var matchesWithName = matches.stream().map(m -> {
            var item = new java.util.LinkedHashMap<String, Object>();
            item.put("id", m.getId());
            item.put("symbol", m.getSymbol());
            item.put("name", nameMap.getOrDefault(m.getSymbol(), ""));
            item.put("lastClose", m.getLastClose());
            item.put("rise", m.getRise());
            item.put("windowDays", m.getWindowDays());
            item.put("algorithm", m.getAlgorithm());
            return item;
        }).toList();

        result.put("matches", matchesWithName);
        return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (Exception e) {
            log.error("screening latest failed", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve latest screening result"));
        }
    }

    /**
     * GET /api/screening/history — 历史筛选批次列表
     */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> history() {
        try {
        List<Object[]> batchSummaries = screeningMatchRepository.findBatchSummary();
        List<Map<String, Object>> history = new ArrayList<>();

        for (Object[] row : batchSummaries) {
            Map<String, Object> item = new HashMap<>();
            item.put("batchId", row[0]);
            item.put("matchCount", row[1]);
            item.put("lastTradeDate", row[2] != null ? row[2].toString() : null);
            history.add(item);
        }
        return ResponseEntity.ok(ApiResponse.ok(history));
        } catch (Exception e) {
            log.error("screening history failed", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve screening history"));
        }
    }

    /**
     * GET /api/screening/batch/{batchId} — 某批次详情
     */
    @GetMapping("/batch/{batchId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchDetail(@PathVariable String batchId) {
        try {
        List<ScreeningMatch> matches = screeningMatchRepository.findByBatchIdOrderByIdAsc(batchId);
        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);
        result.put("totalMatches", matches.size());
        // 批量查询 stock name
        var symbols = matches.stream().map(ScreeningMatch::getSymbol).distinct().toList();
        var nameMap = stockDailyBarRepository.findBySymbolInAndNameIsNotNull(symbols)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        bar -> bar.getSymbol(),
                        bar -> bar.getName(),
                        (a, b) -> a
                ));

        var matchesWithName = matches.stream().map(m -> {
            var item = new java.util.LinkedHashMap<String, Object>();
            item.put("id", m.getId());
            item.put("symbol", m.getSymbol());
            item.put("name", nameMap.getOrDefault(m.getSymbol(), ""));
            item.put("lastClose", m.getLastClose());
            item.put("rise", m.getRise());
            item.put("windowDays", m.getWindowDays());
            item.put("algorithm", m.getAlgorithm());
            return item;
        }).toList();

        result.put("matches", matchesWithName);
        return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (Exception e) {
            log.error("screening batchDetail failed batchId={}", batchId, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve batch detail for " + batchId));
        }
    }
}
