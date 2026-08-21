package com.stock.invest.service;

import com.stock.invest.util.WebhookUrlValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评审 D-5：OrchestrationService runWebhookUrls TTL + 容量上限 ——
 * Hermes 中断接力后条目不再永久滞留（缓慢内存泄漏）。
 * <p>评审 F-14：补充重试退避（成功恢复 / 耗尽失败）与 chain=false（单步不接力）场景。</p>
 */
@ExtendWith(MockitoExtension.class)
class OrchestrationServiceTest {

    @Mock private DataGapFillerService dataGapFillerService;
    @Mock private ScreeningService screeningService;
    @Mock private WebhookNotifier webhookNotifier;
    @Mock private WebhookUrlValidator webhookUrlValidator;
    @Mock(name = "scanExecutor") private Executor scanExecutor;

    private OrchestrationService service;
    private final List<Runnable> submitted = new ArrayList<>();
    private long[] savedRetryDelays;

    @BeforeEach
    void setUp() {
        service = new OrchestrationService(dataGapFillerService, screeningService,
                webhookNotifier, webhookUrlValidator, scanExecutor);
        doAnswer(inv -> {
            submitted.add(inv.getArgument(0));
            return null;
        }).when(scanExecutor).execute(any(Runnable.class));
        // 评审 F-14：重试退避替换为 1ms 短值（3 个元素对齐生产默认 3 次重试），避免真实 3s/9s/27s 拖慢 CI
        savedRetryDelays = OrchestrationService.RETRY_DELAYS_MS;
        OrchestrationService.RETRY_DELAYS_MS = new long[]{1L, 1L, 1L};
    }

    @AfterEach
    void tearDown() {
        OrchestrationService.RETRY_DELAYS_MS = savedRetryDelays;
    }

    @Test
    @DisplayName("评审 D-5: 超过容量上限时淘汰最旧回调端点，接力步骤回退为 null")
    void webhookUrls_capacityEvictsOldest() {
        when(dataGapFillerService.fillGaps()).thenReturn(true);
        // 连续触发 201 个不同 run（容量上限 200）→ 最早的 run-000 被淘汰
        for (int i = 0; i < 201; i++) {
            service.triggerStep("history_backfill", String.format("run-%03d", i), null,
                    "http://localhost:8080/hook-" + i, false);
        }
        assertEquals(201, submitted.size(), "all steps must be submitted");

        // 结束 run-000 的第一条（清 running 标记）；其回调端点已被容量淘汰
        submitted.get(0).run();
        verify(webhookNotifier).notify(eq("history_backfill"), eq("success"), eq("run-000"),
                isNull(), contains("历史失败补缺完成"), eq("none"), eq("http://localhost:8080/hook-0"));

        // 接力 run-000 且不传 webhookUrl → 端点在淘汰后不存在，回退为 null（全局配置）
        service.triggerStep("day_backfill", "run-000", null, null, false);
        assertEquals(202, submitted.size(), "relay step must be submitted");
        submitted.get(201).run();

        verify(webhookNotifier).notify(eq("day_backfill"), eq("success"), eq("run-000"),
                isNull(), contains("当天补缺完成"), eq("none"), isNull());
    }

    @Test
    @DisplayName("评审 D-5: 接力步骤沿用已记录的回调端点（未淘汰时）")
    void webhookUrls_relayStepReusesStoredUrl() {
        when(dataGapFillerService.fillGaps()).thenReturn(true);
        service.triggerStep("history_backfill", "run-A", null, "http://localhost:8080/hook-A", true);
        submitted.get(0).run(); // history_backfill → next=day_backfill（非终态，端点保留）

        service.triggerStep("day_backfill", "run-A", null, null, true); // 接力不传 URL
        submitted.get(1).run();

        verify(webhookNotifier).notify(eq("day_backfill"), eq("success"), eq("run-A"),
                isNull(), contains("当天补缺完成"), eq("screening"),
                eq("http://localhost:8080/hook-A"));
    }

    @Test
    @DisplayName("评审 D-5: 链路终态（none/report）清理回调端点，且不误伤其他 run")
    void webhookUrls_terminalStepCleansOwnEntry() {
        when(dataGapFillerService.fillGaps()).thenReturn(true);
        // run-B 用 chain=false（单步即终态），run-C 用 chain=true（接力链路）
        service.triggerStep("history_backfill", "run-B", null, "http://localhost:8080/hook-B", false);
        service.triggerStep("history_backfill", "run-C", null, "http://localhost:8080/hook-C", true);
        submitted.get(0).run(); // run-B 终态（chain=false → next=none）→ 仅清理 run-B 自身端点
        submitted.get(1).run(); // run-C 第一步（chain=true → next=day_backfill，非终态）→ 端点保留

        // run-B 接力不传 URL → 端点已被终态清理，回退 null
        service.triggerStep("day_backfill", "run-B", null, null, false);
        submitted.get(2).run();
        verify(webhookNotifier).notify(eq("day_backfill"), eq("success"), eq("run-B"),
                isNull(), contains("当天补缺完成"), eq("none"), isNull());

        // run-C 的端点不受影响，接力仍沿用 hook-C
        service.triggerStep("day_backfill", "run-C", null, null, true);
        submitted.get(3).run();
        verify(webhookNotifier).notify(eq("day_backfill"), eq("success"), eq("run-C"),
                isNull(), contains("当天补缺完成"), eq("screening"),
                eq("http://localhost:8080/hook-C"));
    }

    @Test
    @DisplayName("评审 D-5: 运行互斥仍生效（同一 run 并发触发被忽略）")
    void webhookUrls_runningRunsMutexStillWorks() {
        service.triggerStep("history_backfill", "run-X", null, "http://localhost:8080/hook-X", false);
        // 未执行第一条（running 标记仍在）→ 同 run 再次触发被忽略
        service.triggerStep("history_backfill", "run-X", null, "http://localhost:8080/hook-X", false);
        assertEquals(1, submitted.size(), "duplicate trigger while running must be ignored");
    }

    // ===================================================================
    // 评审 F-14：重试退避
    // ===================================================================

    @Test
    @DisplayName("评审 F-14: 首次失败自动重试，第二次成功 → 仅通知 success，不通知 failed")
    void retryBackoff_recoversAfterTransientFailure() {
        when(dataGapFillerService.fillGaps())
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(true);

        service.triggerStep("day_backfill", "run-R1", null, "http://localhost:8080/hook-R1", false);
        submitted.get(0).run();

        // 第 1 次失败 + 第 2 次成功 = fillGaps 共调用 2 次（重试退避后重试一次）
        verify(dataGapFillerService, times(2)).fillGaps();
        verify(webhookNotifier).notify(eq("day_backfill"), eq("success"), eq("run-R1"),
                isNull(), contains("当天补缺完成"), eq("none"), eq("http://localhost:8080/hook-R1"));
        verify(webhookNotifier, never()).notify(eq("day_backfill"), eq("failed"),
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("评审 F-14: 全部尝试失败（1 次 + 3 次重试）→ 通知 failed（含已重试 3 次）并清理端点")
    void retryBackoff_exhaustsThenFailed() {
        when(dataGapFillerService.fillGaps()).thenThrow(new RuntimeException("boom"));

        service.triggerStep("day_backfill", "run-R2", null, "http://localhost:8080/hook-R2", false);
        submitted.get(0).run();

        // 初始 1 次 + 3 次退避重试 = 共 4 次调用
        verify(dataGapFillerService, times(4)).fillGaps();
        verify(webhookNotifier).notify(eq("day_backfill"), eq("failed"), eq("run-R2"),
                isNull(), contains("已重试 3 次"), eq("none"), eq("http://localhost:8080/hook-R2"));
    }

    @Test
    @DisplayName("评审 F-14: chain=false 单步执行 → next=none，不接力后续步骤")
    void chainFalse_singleStepNoRelay() {
        // history_backfill（processRetryingTasks 为 void，默认 mock 直接成功）
        service.triggerStep("history_backfill", "run-S1", null, "http://localhost:8080/hook-S1", false);
        submitted.get(0).run();

        verify(webhookNotifier).notify(eq("history_backfill"), eq("success"), eq("run-S1"),
                isNull(), contains("历史失败补缺完成"), eq("none"), eq("http://localhost:8080/hook-S1"));
        assertEquals(1, submitted.size(), "chain=false must not schedule any relay step");
    }
}
