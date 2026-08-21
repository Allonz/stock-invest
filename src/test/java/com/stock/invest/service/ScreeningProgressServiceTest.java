package com.stock.invest.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评审 D-6：ScreeningProgressService TTL 清理 —— 与 DataFillProgressService 统一语义：
 * 完成后条目不立即删除（保留 DONE 状态），由 TTL（24h）+ 每小时定时清理收敛。
 */
class ScreeningProgressServiceTest {

    private static final long TTL_PLUS = 25L * 60 * 60 * 1000; // 25h > TTL 24h

    @Test
    @DisplayName("评审 D-6: 超过 TTL 的已完成条目在 getProgress 读路径被清除")
    void expiredEntryRemovedOnRead() {
        ScreeningProgressService svc = new ScreeningProgressService();
        String taskId = svc.startScreening(List.of(2, 3, 4), 10);
        ScreeningProgressService.ScreeningProgress p = svc.getProgress(taskId);
        assertNotNull(p, "fresh entry must exist");
        p.setRunning(false);
        p.setStartTime(System.currentTimeMillis() - TTL_PLUS);

        assertNull(svc.getProgress(taskId), "expired entry should be swept on read");
    }

    @Test
    @DisplayName("评审 D-6: 未过期/运行中条目在读路径清理后保留")
    void freshEntrySurvivesReadSweep() {
        ScreeningProgressService svc = new ScreeningProgressService();
        String taskId = svc.startScreening(List.of(2), 10);
        assertNotNull(svc.getProgress(taskId), "fresh entry must survive read sweep");
    }

    @Test
    @DisplayName("评审 D-6: 运行中条目即使超龄也不被清除")
    void runningEntryNotSwept() {
        ScreeningProgressService svc = new ScreeningProgressService();
        String taskId = svc.startScreening(List.of(2), 10);
        ScreeningProgressService.ScreeningProgress p = svc.getProgress(taskId);
        p.setRunning(true);
        p.setStartTime(System.currentTimeMillis() - TTL_PLUS);

        assertNotNull(svc.getProgress(taskId), "running entry must not be swept");
    }

    @Test
    @DisplayName("评审 D-6: 每小时定时兜底 sweepExpiredHourly 清除过期条目")
    void scheduledSweep_removesExpired() {
        ScreeningProgressService svc = new ScreeningProgressService();
        String expiredId = svc.startScreening(List.of(2), 10);
        ScreeningProgressService.ScreeningProgress expired = svc.getProgress(expiredId);
        expired.setRunning(false);
        expired.setStartTime(System.currentTimeMillis() - TTL_PLUS);

        String freshId = svc.startScreening(List.of(3), 10); // 触发一次前置清理，但 fresh 未过期
        assertNotNull(svc.getProgress(freshId));

        svc.sweepExpiredHourly();

        assertNull(svc.getProgress(expiredId), "expired entry must be removed by hourly sweep");
        assertNotNull(svc.getProgress(freshId), "fresh entry must survive hourly sweep");
    }

    @Test
    @DisplayName("评审 D-6: startScreening 前置清理行为保持（过期条目被清，新条目保留）")
    void startScreening_sweepsToo() {
        ScreeningProgressService svc = new ScreeningProgressService();
        String oldId = svc.startScreening(List.of(2), 10);
        ScreeningProgressService.ScreeningProgress old = svc.getProgress(oldId);
        old.setRunning(false);
        old.setStartTime(System.currentTimeMillis() - TTL_PLUS);

        String newId = svc.startScreening(List.of(4), 10);

        assertNull(svc.getProgress(oldId), "startScreening must sweep expired entries first");
        assertNotNull(svc.getProgress(newId), "new task progress must be queryable");
    }

    @Test
    @DisplayName("评审 D-6: 完成后条目保留（running=false 但可查询到 DONE 状态）")
    void completedEntryRetainedForPolling() {
        ScreeningProgressService svc = new ScreeningProgressService();
        String taskId = svc.startScreening(List.of(2, 3), 10);
        ScreeningProgressService.ScreeningProgress p = svc.getProgress(taskId);
        p.setRunning(false); // 模拟任务完成：只清 running 标记，不删条目
        p.getWindows().forEach(w -> w.setStatus("DONE"));

        ScreeningProgressService.ScreeningProgress after = svc.getProgress(taskId);
        assertNotNull(after, "completed entry must remain queryable (DONE state visible to frontend)");
        assertFalse(after.isRunning());
        assertTrue(after.getWindows().stream().allMatch(w -> "DONE".equals(w.getStatus())));
    }
}
