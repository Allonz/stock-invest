package com.stock.invest.controller;

import com.stock.invest.entity.ScreeningMatch;
import com.stock.invest.enums.dto.ApiResponse;
import com.stock.invest.repository.ScreeningMatchRepository;
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

    public ScreeningController(ScreeningMatchRepository screeningMatchRepository) {
        this.screeningMatchRepository = screeningMatchRepository;
    }

    /**
     * GET /api/screening/latest — 最新一次筛选结果
     */
    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<Map<String, Object>>> latest() {
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
        result.put("matches", matches);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * GET /api/screening/history — 历史筛选批次列表
     */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> history() {
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
    }

    /**
     * GET /api/screening/batch/{batchId} — 某批次详情
     */
    @GetMapping("/batch/{batchId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchDetail(@PathVariable String batchId) {
        List<ScreeningMatch> matches = screeningMatchRepository.findByBatchIdOrderByIdAsc(batchId);
        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);
        result.put("totalMatches", matches.size());
        result.put("matches", matches);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
