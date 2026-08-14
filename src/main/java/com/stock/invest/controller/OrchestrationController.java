package com.stock.invest.controller;

import com.stock.invest.enums.dto.ApiResponse;
import com.stock.invest.service.OrchestrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 串行编排入口：接收 Hermes 的步骤触发，转交 OrchestrationService 异步执行。
 *
 * POST /api/orchestration/step
 *   body: {"step": "history_backfill|day_backfill|screening",
 *          "run_id": "20260812-01",
 *          "trade_date": "2026-08-12",
 *          "webhook_url": "http://localhost:8645/webhooks/tiger-orch"}   // 可选：本 run 的回调端点
 *
 * webhook_url 支持"谁触发就回调谁"：Windows Hermes 触发传 8644，WSL Hermes 触发传 8645，
 * 不传则回退到全局配置 orchestration.webhook-url（向后兼容）。
 */
@RestController
@RequestMapping("/api/orchestration")
public class OrchestrationController {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationController.class);

    private final OrchestrationService orchestrationService;

    public OrchestrationController(OrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @PostMapping("/step")
    public ResponseEntity<ApiResponse<?>> triggerStep(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("body is required"));
        }
        String step = body.get("step") == null ? "" : String.valueOf(body.get("step"));
        String runId = body.get("run_id") == null ? "" : String.valueOf(body.get("run_id"));
        String tradeDate = body.get("trade_date") == null ? null : String.valueOf(body.get("trade_date"));
        String webhookUrl = body.get("webhook_url") == null ? null : String.valueOf(body.get("webhook_url"));
        // chain: true（默认）=按 nextStep 接力；false=单步执行不接力
        boolean chain = body.get("chain") == null || Boolean.parseBoolean(String.valueOf(body.get("chain")));

        if (step.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("step is required"));
        }
        if (!isValidStep(step)) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.error("step must be one of: history_backfill, day_backfill, screening"));
        }
        if (runId.isEmpty()) {
            runId = "manual-" + System.currentTimeMillis();
        }

        log.info("[Orchestration] trigger step={}, runId={}, tradeDate={}, webhookUrl={}, chain={}",
                step, runId, tradeDate, webhookUrl, chain);
        orchestrationService.triggerStep(step, runId, tradeDate, webhookUrl, chain);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("step", step);
        data.put("run_id", runId);
        data.put("accepted", true);
        data.put("message", "步骤已受理，异步执行中");
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    private boolean isValidStep(String step) {
        return "history_backfill".equals(step)
                || "day_backfill".equals(step)
                || "screening".equals(step);
    }
}
