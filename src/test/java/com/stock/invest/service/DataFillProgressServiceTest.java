package com.stock.invest.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * R2 P2-7：DataFillProgressService TTL 清理 —— 读路径惰性清除过期条目。
 */
class DataFillProgressServiceTest {

    private static final long TTL_PLUS = 25L * 60 * 60 * 1000; // 25h > TTL 24h

    @Test
    @DisplayName("R2 P2-7: 超过 TTL 的已完成条目在 getProgress 读路径被清除")
    void expiredEntryRemovedOnRead() {
        DataFillProgressService svc = new DataFillProgressService();
        String taskId = svc.startFill();
        DataFillProgressService.FillProgress p = svc.getProgress(taskId);
        p.setStartTime(System.currentTimeMillis() - TTL_PLUS);

        assertNull(svc.getProgress(taskId), "expired entry should be swept on read");
        // latestKey 指向已过期条目时无参读返回 null 且不抛错
        assertNull(svc.getProgress(), "no-arg read with expired latestKey must return null without throwing");
    }

    @Test
    @DisplayName("R2 P2-7: 未过期条目在读路径清理后保留")
    void freshEntrySurvivesReadSweep() {
        DataFillProgressService svc = new DataFillProgressService();
        String taskId = svc.startFill();
        assertNotNull(svc.getProgress(taskId), "fresh entry must survive read sweep");
        assertNotNull(svc.getProgress(), "no-arg read must return the fresh entry");
    }

    @Test
    @DisplayName("R2 P2-7: 运行中条目即使超龄也不被清除")
    void runningEntryNotSwept() {
        DataFillProgressService svc = new DataFillProgressService();
        String taskId = svc.startFill();
        DataFillProgressService.FillProgress p = svc.getProgress(taskId);
        p.setRunning(true);
        p.setStartTime(System.currentTimeMillis() - TTL_PLUS);

        assertNotNull(svc.getProgress(taskId), "running entry must not be swept");
    }

    @Test
    @DisplayName("R2 P2-7: 每小时定时兜底 sweepExpiredHourly 清除过期条目")
    void scheduledSweep_removesExpired() {
        DataFillProgressService svc = new DataFillProgressService();
        String expiredId = svc.startFill();
        DataFillProgressService.FillProgress expired = svc.getProgress(expiredId);
        expired.setRunning(false);
        expired.setStartTime(System.currentTimeMillis() - TTL_PLUS);

        String freshId = svc.startFill(); // 触发一次前置清理，但 fresh 未过期
        assertNotNull(svc.getProgress(freshId));

        // 定时兜底（生产每小时触发）—— 过期条目清除、未过期保留
        svc.sweepExpiredHourly();

        assertNull(svc.getProgress(expiredId), "expired entry must be removed by hourly sweep");
        assertNotNull(svc.getProgress(freshId), "fresh entry must survive hourly sweep");
    }

    @Test
    @DisplayName("R2 P2-7: startFill 前置清理行为保持（过期条目被清，新条目保留）")
    void startFill_sweepsToo() {
        DataFillProgressService svc = new DataFillProgressService();
        String oldId = svc.startFill();
        DataFillProgressService.FillProgress old = svc.getProgress(oldId);
        old.setRunning(false);
        old.setStartTime(System.currentTimeMillis() - TTL_PLUS);

        String newId = svc.startFill();

        assertNull(svc.getProgress(oldId), "startFill must sweep expired entries first");
        assertNotNull(svc.getProgress(newId), "new task progress must be queryable");
        // latestKey 指向新任务 → 无参读返回新任务进度（不为 null）
        assertNotNull(svc.getProgress(), "no-arg read returns the latest (new) task");
    }
}
