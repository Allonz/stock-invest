package com.stock.invest.controller;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stock.invest.entity.ScreeningMatch;
import com.stock.invest.enums.dto.ApiResponse;
import com.stock.invest.repository.ScreeningMatchRepository;

/**
 * 通知查询控制器
 */
@RestController
@RequestMapping("/api/notification")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final ScreeningMatchRepository screeningMatchRepository;

    public NotificationController(ScreeningMatchRepository screeningMatchRepository) {
        this.screeningMatchRepository = screeningMatchRepository;
    }

    /**
     * GET /api/notification/latest — 最新筛选结果通知
     */
    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLatestNotification() {
        try {
            Optional<ScreeningMatch> latest = screeningMatchRepository.findTopByOrderByTradeDateDescIdDesc();
            if (!latest.isPresent()) {
                return ResponseEntity.ok(ApiResponse.ok(Map.of("message", "暂无筛选数据")));
            }

            String latestBatchId = latest.get().getBatchId();
            LocalDate screenDate = latest.get().getTradeDate();

            // 按 batchId 查询所有匹配记录，按 algorithm + windowDays 分组统计（含具体代码）
            List<ScreeningMatch> allMatches = screeningMatchRepository
                    .findByBatchIdOrderByIdAsc(latestBatchId);
            Map<String, Map<String, Object>> resultByAlgo = new LinkedHashMap<>();
            for (ScreeningMatch m : allMatches) {
                String algo = m.getAlgorithm();
                int wd = m.getWindowDays();
                String windowKey = wd + "d";

                Map<String, Object> windowData = resultByAlgo
                        .computeIfAbsent(algo, k -> new LinkedHashMap<>());
                @SuppressWarnings("unchecked")
                Map<String, Object> windowGroup = (Map<String, Object>) windowData
                        .computeIfAbsent(windowKey, k -> {
                            Map<String, Object> g = new LinkedHashMap<>();
                            g.put("count", 0L);
                            g.put("stocks", new ArrayList<Map<String, Object>>());
                            return g;
                        });

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> stocks = (List<Map<String, Object>>) windowGroup.get("stocks");
                windowGroup.put("count", ((Long) windowGroup.get("count")) + 1L);

                Map<String, Object> stockInfo = new LinkedHashMap<>();
                stockInfo.put("symbol", m.getSymbol());
                stockInfo.put("lastClose", m.getLastClose());
                stockInfo.put("rise", m.getRise());
                stocks.add(stockInfo);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("batchId", latestBatchId);
            payload.put("screenDate", screenDate.toString());
            payload.put("results", resultByAlgo);

            log.info("[Notification] latest: batchId={}, screenDate={}, totalHits={}",
                    latestBatchId, screenDate, resultByAlgo);
            return ResponseEntity.ok(ApiResponse.ok(payload));
        } catch (Exception e) {
            log.error("getLatestNotification failed", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve notification data"));
        }
    }

    /**
     * GET /api/notification/history — 历史通知批次列表
     */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> history() {
        try {
            List<Object[]> batchSummaries = screeningMatchRepository.findBatchSummary();
            List<Map<String, Object>> history = new ArrayList<>();
            for (Object[] row : batchSummaries) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("batchId", row[0]);
                item.put("screenDate", row[2] != null ? row[2].toString() : null);
                item.put("matchCount", row[1]);
                history.add(item);
            }
            return ResponseEntity.ok(ApiResponse.ok(history));
        } catch (Exception e) {
            log.error("notification history failed", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve notification history"));
        }
    }

    /**
     * GET /api/notification/batch/{batchId} — 某批次通知详情
     */
    @GetMapping("/batch/{batchId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchDetail(@PathVariable String batchId) {
        try {
            List<ScreeningMatch> matches = screeningMatchRepository.findByBatchIdOrderByIdAsc(batchId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("batchId", batchId);
            result.put("totalMatches", matches.size());
            result.put("matches", matches);
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (Exception e) {
            log.error("notification batchDetail failed batchId={}", batchId, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve batch detail for " + batchId));
        }
    }
}
