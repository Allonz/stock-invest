package com.stock.invest.controller;

import com.stock.invest.enums.dto.ApiResponse;
import com.stock.invest.service.ScreeningService;
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

@RestController
@RequestMapping("/api/screening")
public class ScreeningController {

    private static final Logger log = LoggerFactory.getLogger(ScreeningController.class);

    private final ScreeningService screeningService;

    public ScreeningController(ScreeningService screeningService) {
        this.screeningService = screeningService;
    }

    /**
     * GET /api/screening/latest — 最新一次筛选结果
     */
    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<Map<String, Object>>> latest() {
        try {
            Map<String, Object> result = screeningService.getLatestScreening();
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
            List<Map<String, Object>> history = screeningService.getScreeningHistory();
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
            Map<String, Object> result = screeningService.getBatchDetail(batchId);
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (Exception e) {
            log.error("screening batchDetail failed batchId={}", batchId, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve batch detail for " + batchId));
        }
    }
}
