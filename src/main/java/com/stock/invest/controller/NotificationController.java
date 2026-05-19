package com.stock.invest.controller;

import com.stock.invest.entity.ScreeningMatch;
import com.stock.invest.enums.dto.ApiResponse;
import com.stock.invest.repository.ScreeningMatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 通知相关接口。
 */
@RestController
@RequestMapping("/api/notification")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private static final List<Integer> WINDOW_DAYS = List.of(2, 3, 4, 5, 6, 7);

    private final ScreeningMatchRepository screeningMatchRepository;

    public NotificationController(ScreeningMatchRepository screeningMatchRepository) {
        this.screeningMatchRepository = screeningMatchRepository;
    }

    /**
     * 查询最新一批筛选结果（按窗口天数分组）。
     * <p>
     * 返回格式：
     * <pre>
     * {
     *   "data": {
     *     "batchId": "screen-20260518-xxxx",
     *     "screenDate": "2026-05-18",
     *     "results": {
     *       "2d": [...],
     *       "3d": [...],
     *       ...
     *       "7d": [...]
     *     },
     *     "totalHits": {"2d":3,"3d":5,...},
     *     "generatedAt": "2026-05-18T05:00:00"
     *   }
     * }
     * </pre>
     */
    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLatestNotification() {
        Optional<ScreeningMatch> latest = screeningMatchRepository.findTopByOrderByTradeDateDescIdDesc();
        if (!latest.isPresent()) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of("message", "no screening data available")));
        }

        String latestBatchId = latest.get().getBatchId();
        LocalDate screenDate = latest.get().getTradeDate();

        // 按 windowDays 分组查询
        Map<String, List<Map<String, Object>>> groupedResults = new LinkedHashMap<>();
        Map<String, Integer> totalHits = new LinkedHashMap<>();

        for (int wd : WINDOW_DAYS) {
            String key = wd + "d";
            List<ScreeningMatch> matches = screeningMatchRepository
                    .findByBatchIdAndWindowDaysOrderByIdAsc(latestBatchId, wd);

            List<Map<String, Object>> items = new ArrayList<>();
            for (ScreeningMatch match : matches) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("symbol", match.getSymbol());
                item.put("lastClose", match.getLastClose());
                items.add(item);
            }

            groupedResults.put(key, items);
            totalHits.put(key, items.size());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchId", latestBatchId);
        payload.put("screenDate", screenDate != null ? screenDate.toString() : null);
        payload.put("results", groupedResults);
        payload.put("totalHits", totalHits);
        payload.put("generatedAt", Instant.now().toString());

        log.info("NotificationController: latest batchId={}, screenDate={}, totalHits={}",
                latestBatchId, screenDate, totalHits);
        return ResponseEntity.ok(ApiResponse.ok(payload));
    }
}
