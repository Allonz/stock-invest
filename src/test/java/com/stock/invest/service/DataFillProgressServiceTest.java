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
}
