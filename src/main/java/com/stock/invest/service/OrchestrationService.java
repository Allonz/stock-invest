package com.stock.invest.service;

import com.stock.invest.service.DataGapFillerService;
import com.stock.invest.service.ScreeningService;
import com.stock.invest.service.WebhookNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 串行编排服务：tiger 导入完成后，Hermes 逐步骤触发本服务执行：
 *   history_backfill（历史失败补缺）→ day_backfill（当天补缺）→ screening（筛选）
 * 每步异步执行，完成后经 WebhookNotifier 回调 Hermes 并告知 next_step。
 * 单步失败自动重试 3 次（间隔 3s/9s/27s），3 次全败则回调 status=failed 终止链路。
 */
@Service
public class OrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationService.class);
    private static final long[] RETRY_DELAYS_MS = {3_000L, 9_000L, 27_000L};

    private final DataGapFillerService dataGapFillerService;
    private final ScreeningService screeningService;
    private final WebhookNotifier webhookNotifier;
    private final Executor scanExecutor;
    private final Map<String, Boolean> runningRuns = new ConcurrentHashMap<>();

    public OrchestrationService(DataGapFillerService dataGapFillerService,
                                ScreeningService screeningService,
                                WebhookNotifier webhookNotifier,
                                @Qualifier("scanExecutor") Executor scanExecutor) {
        this.dataGapFillerService = dataGapFillerService;
        this.screeningService = screeningService;
        this.webhookNotifier = webhookNotifier;
        this.scanExecutor = scanExecutor;
    }

    /**
     * 触发编排步骤（异步）。调用方（Controller）立即返回。
     *
     * @param step      history_backfill / day_backfill / screening
     * @param runId     链路标识（如 20260812-01）
     * @param tradeDate 交易日（ISO，可空）
     */
    public void triggerStep(String step, String runId, String tradeDate) {
        if (runningRuns.putIfAbsent(runId, Boolean.TRUE) != null) {
            log.warn("[Orchestration] run {} already in progress, ignore step {}", runId, step);
            return;
        }
        try {
            scanExecutor.execute(() -> executeStepWithRetry(step, runId, tradeDate));
        } catch (Exception e) {
            runningRuns.remove(runId);
            log.error("[Orchestration] submit step {} failed for run {}", step, runId, e);
            webhookNotifier.notify(step, "failed", runId, tradeDate,
                    "提交失败: " + e.getMessage(), "none");
        }
    }

    private void executeStepWithRetry(String step, String runId, String tradeDate) {
        boolean ok = false;
        String lastErr = "";
        for (int attempt = 0; attempt <= RETRY_DELAYS_MS.length; attempt++) {
            if (attempt > 0) {
                log.info("[Orchestration] retry {} attempt {}/{} after {}ms",
                        step, attempt, RETRY_DELAYS_MS.length, RETRY_DELAYS_MS[attempt - 1]);
                try {
                    Thread.sleep(RETRY_DELAYS_MS[attempt - 1]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                String msg = executeStep(step, runId, tradeDate);
                ok = true;
                String next = nextStep(step);
                String fullMsg = step + " 完成" + (msg != null ? "：" + msg : "");
                log.info("[Orchestration] step {} OK for run {} (attempt {}), next={}",
                        step, runId, attempt + 1, next);
                webhookNotifier.notify(step, "success", runId, tradeDate, fullMsg, next);
                break;
            } catch (Exception e) {
                lastErr = e.getMessage() == null ? e.toString() : e.getMessage();
                log.warn("[Orchestration] step {} failed attempt {}/{}: {}",
                        step, attempt + 1, RETRY_DELAYS_MS.length + 1, lastErr);
            }
        }
        if (!ok) {
            log.error("[Orchestration] step {} FAILED after {} attempts for run {}, err={}",
                    step, RETRY_DELAYS_MS.length + 1, runId, lastErr);
            webhookNotifier.notify(step, "failed", runId, tradeDate,
                    "执行失败（已重试 3 次）: " + lastErr, "none");
        }
        runningRuns.remove(runId);
    }

    /**
     * 执行单个编排步骤，返回结果描述。
     */
    private String executeStep(String step, String runId, String tradeDate) throws Exception {
        switch (step) {
            case "history_backfill":
                log.info("[Orchestration] executing history_backfill (fillGaps), run={}", runId);
                boolean ok = dataGapFillerService.fillGaps();
                if (!ok) {
                    return "补缺未实际执行（可能已被调度器/其他入口抢占）";
                }
                return "历史失败补缺完成";
            case "day_backfill":
                log.info("[Orchestration] executing day_backfill (processRetryingTasks), run={}", runId);
                dataGapFillerService.processRetryingTasks();
                return "当天补缺完成";
            case "screening": {
                log.info("[Orchestration] executing screening, run={}, tradeDate={}", runId, tradeDate);
                LocalDate date = tradeDate != null && !tradeDate.isEmpty()
                        ? LocalDate.parse(tradeDate)
                        : LocalDate.now(java.time.ZoneId.of("America/New_York"));
                String batchId = screeningService.runScreening(date, null, null);
                if (batchId == null) {
                    return "筛选已在运行，本次跳过";
                }
                return "筛选完成 batchId=" + batchId;
            }
            default:
                throw new IllegalArgumentException("unknown step: " + step);
        }
    }

    private String nextStep(String step) {
        switch (step) {
            case "history_backfill":
                return "day_backfill";
            case "day_backfill":
                return "screening";
            case "screening":
                return "report";
            default:
                return "none";
        }
    }
}
