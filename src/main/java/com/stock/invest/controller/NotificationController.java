package com.stock.invest.controller;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stock.invest.enums.dto.ApiResponse;
import com.stock.invest.service.ScreeningService;

/**
 * 通知查询控制器。
 * 所有查询逻辑委托给 ScreeningService。
 */
@RestController
@RequestMapping("/api/notification")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final ScreeningService screeningService;

    public NotificationController(ScreeningService screeningService) {
        this.screeningService = screeningService;
    }

    /**
     * GET /api/notification/latest — 最新筛选结果通知（按 algorithm + windowDays 分组）
     */
    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLatestNotification(
            @RequestParam(value = "windows", required = false) String windows) {
        try {
            // P3-3：两分支等价（均原样返回 result），删除死分支
            Map<String, Object> result = screeningService.getLatestNotificationGrouped(windows);
            return ResponseEntity.ok(ApiResponse.ok(result));
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
            List<Map<String, Object>> history = screeningService.getScreeningHistory();
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
            Map<String, Object> result = screeningService.getBatchDetail(batchId);
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (Exception e) {
            log.error("notification batchDetail failed batchId={}", batchId, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve batch detail for " + batchId));
        }
    }
}
