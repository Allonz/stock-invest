package com.stock.invest.service;

import com.stock.invest.util.WebhookUrlValidator;
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
 *
 * 回调端点支持按 run 动态绑定（webhook_url）：首次触发时记录该 run 的回调端点，
 * 后续接力步骤沿用；未指定则回退全局配置。
 */
@Service
public class OrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationService.class);
    /** 重试退避间隔（评审 F-14：测试类可在 @BeforeEach 替换为短值加速验证，生产默认 3s/9s/27s） */
    static long[] RETRY_DELAYS_MS = {3_000L, 9_000L, 27_000L};
    /** 评审 D-5：runWebhookUrls 条目 TTL（24h）—— Hermes 中断接力后条目不再永久滞留 */
    private static final long WEBHOOK_URL_TTL_MILLIS = 24 * 60 * 60 * 1000L;
    /** 评审 D-5：runWebhookUrls 容量上限（TTL 之外的兜底，防缓慢内存泄漏） */
    private static final int MAX_WEBHOOK_URL_ENTRIES = 200;

    private final DataGapFillerService dataGapFillerService;
    private final ScreeningService screeningService;
    private final WebhookNotifier webhookNotifier;
    private final WebhookUrlValidator webhookUrlValidator;
    private final Executor scanExecutor;
    private final Map<String, Boolean> runningRuns = new ConcurrentHashMap<>();
    /** run_id → 回调端点（首次触发记录，接力沿用；链路结束/超时/超容清理） */
    private final Map<String, WebhookUrlEntry> runWebhookUrls = new ConcurrentHashMap<>();

    /** 回调端点条目：url + 记录时间（用于 TTL 与容量淘汰） */
    private record WebhookUrlEntry(String url, long createdAt) {}

    public OrchestrationService(DataGapFillerService dataGapFillerService,
                                ScreeningService screeningService,
                                WebhookNotifier webhookNotifier,
                                WebhookUrlValidator webhookUrlValidator,
                                @Qualifier("scanExecutor") Executor scanExecutor) {
        this.dataGapFillerService = dataGapFillerService;
        this.screeningService = screeningService;
        this.webhookNotifier = webhookNotifier;
        this.webhookUrlValidator = webhookUrlValidator;
        this.scanExecutor = scanExecutor;
    }

    /**
     * 触发编排步骤（异步）。调用方（Controller）立即返回。
     *
     * @param step       history_backfill / day_backfill / screening
     * @param runId      链路标识（如 20260812-01）
     * @param tradeDate  交易日（ISO，可空）
     * @param webhookUrl 本 run 的回调端点（可空，null 回退全局配置）
     * @param chain      true=按 nextStep 自动接力后续步骤；false=单步执行，完成后不触发下一步
     */
    public void triggerStep(String step, String runId, String tradeDate, String webhookUrl, boolean chain) {
        // SSRF 防护：先校验动态 webhook_url，非法直接拒绝
        webhookUrlValidator.validate(webhookUrl);

        // 记录本 run 的回调端点（首次触发记录，后续接力沿用）
        if (webhookUrl != null && !webhookUrl.isBlank()) {
            rememberWebhookUrl(runId, webhookUrl);
        }
        String effUrl = resolveWebhookUrl(runId, webhookUrl);

        if (runningRuns.putIfAbsent(runId, Boolean.TRUE) != null) {
            log.warn("[Orchestration] run {} already in progress, ignore step {}", runId, step);
            return;
        }
        try {
            scanExecutor.execute(() -> executeStepWithRetry(step, runId, tradeDate, effUrl, chain));
        } catch (Exception e) {
            runningRuns.remove(runId);
            log.error("[Orchestration] submit step {} failed for run {}", step, runId, e);
            webhookNotifier.notify(step, "failed", runId, tradeDate,
                    "提交失败: " + e.getMessage(), "none", effUrl);
        }
    }

    /**
     * 记录 run 的回调端点：先做惰性 TTL 清理，超容量时淘汰最旧条目（评审 D-5）。
     */
    private void rememberWebhookUrl(String runId, String webhookUrl) {
        sweepExpiredWebhookUrls();
        if (runWebhookUrls.size() >= MAX_WEBHOOK_URL_ENTRIES) {
            evictOldestWebhookUrl();
        }
        runWebhookUrls.put(runId, new WebhookUrlEntry(webhookUrl, System.currentTimeMillis()));
    }

    /**
     * 解析 run 的有效回调端点：TTL 过期条目视为不存在，回退到入参/全局配置。
     */
    private String resolveWebhookUrl(String runId, String fallback) {
        WebhookUrlEntry entry = runWebhookUrls.get(runId);
        if (entry == null) {
            return fallback;
        }
        if (System.currentTimeMillis() - entry.createdAt() >= WEBHOOK_URL_TTL_MILLIS) {
            runWebhookUrls.remove(runId, entry);
            return fallback;
        }
        return entry.url();
    }

    /** 惰性 TTL 清理：移除超过 24h 的回调端点条目 */
    private void sweepExpiredWebhookUrls() {
        long cutoff = System.currentTimeMillis() - WEBHOOK_URL_TTL_MILLIS;
        runWebhookUrls.entrySet().removeIf(e -> e.getValue().createdAt() < cutoff);
    }

    /** 容量上限兜底：淘汰最旧一条（ConcurrentHashMap 弱一致迭代，单次 O(n) 可接受） */
    private void evictOldestWebhookUrl() {
        String oldestKey = null;
        long oldestCreated = Long.MAX_VALUE;
        for (Map.Entry<String, WebhookUrlEntry> e : runWebhookUrls.entrySet()) {
            if (e.getValue().createdAt() < oldestCreated) {
                oldestCreated = e.getValue().createdAt();
                oldestKey = e.getKey();
            }
        }
        if (oldestKey != null) {
            runWebhookUrls.remove(oldestKey);
        }
    }

    private void executeStepWithRetry(String step, String runId, String tradeDate, String webhookUrl, boolean chain) {
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
                // chain=false（单步触发）时 next 固定为 none，webhook 收到即链路结束，不接力下一步
                String next = chain ? nextStep(step) : "none";
                String fullMsg = step + " 完成" + (msg != null ? "：" + msg : "");
                log.info("[Orchestration] step {} OK for run {} (attempt {}), next={}",
                        step, runId, attempt + 1, next);
                webhookNotifier.notify(step, "success", runId, tradeDate, fullMsg, next, webhookUrl);
                // 链路结束（report/none 为终态）清理回调端点记录
                if ("report".equals(next) || "none".equals(next)) {
                    runWebhookUrls.remove(runId);
                }
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
                    "执行失败（已重试 3 次）: " + lastErr, "none", webhookUrl);
            runWebhookUrls.remove(runId);
        }
        runningRuns.remove(runId);
    }

    /**
     * 执行单个编排步骤，返回结果描述。
     */
    private String executeStep(String step, String runId, String tradeDate) throws Exception {
        switch (step) {
            case "history_backfill":
                log.info("[Orchestration] executing history_backfill (processRetryingTasks), run={}", runId);
                dataGapFillerService.processRetryingTasks();
                return "历史失败补缺完成";
            case "day_backfill":
                log.info("[Orchestration] executing day_backfill (fillGaps), run={}", runId);
                boolean ok = dataGapFillerService.fillGaps();
                if (!ok) {
                    return "补缺未实际执行（可能已被调度器/其他入口抢占）";
                }
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
