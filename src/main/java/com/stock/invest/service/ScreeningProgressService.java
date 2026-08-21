package com.stock.invest.service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 筛选进度服务。
 * <p>评审 D-6：与 {@link DataFillProgressService} 统一进度语义 ——
 * 任务完成后条目不立即删除（保留 DONE 状态供前端最后一轮轮询读取），
 * 由 TTL（24h）+ 每小时定时清理收敛；读路径做惰性 TTL 清理，防内存泄漏。</p>
 */
@org.springframework.stereotype.Service
public class ScreeningProgressService {

    /** 进度条目保留时长（与 DataFillProgressService 对齐） */
    private static final long TTL_MILLIS = 24 * 60 * 60 * 1000L;

    private final ConcurrentHashMap<String, ScreeningProgress> progressMap = new ConcurrentHashMap<>();

    public String startScreening(List<Integer> windows, int limit) {
        sweepExpired();
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ScreeningProgress p = new ScreeningProgress();
        p.setRunning(true);
        p.setStartTime(System.currentTimeMillis());
        p.setTotalWindows(windows.size());
        List<WindowProgress> windowList = new ArrayList<>();
        for (int days : windows) {
            WindowProgress wp = new WindowProgress();
            wp.setDays(days);
            wp.setStatus("WAITING");
            wp.setMatched(0);
            windowList.add(wp);
        }
        p.setWindows(windowList);
        if (!windowList.isEmpty()) {
            windowList.get(0).setStatus("RUNNING");
        }
        progressMap.put(taskId, p);
        return taskId;
    }

    /** 读路径先做惰性 TTL 清理，再返回进度 */
    public ScreeningProgress getProgress(String taskId) {
        sweepExpired();
        return progressMap.get(taskId);
    }

    public void removeProgress(String taskId) {
        progressMap.remove(taskId);
    }

    /**
     * 惰性 TTL 清理：移除超过 24h 的已完成条目（运行中条目不清，防误删长任务）。
     * <p>评审 D-6：startScreening/getProgress 读路径与每小时定时兜底都会触发，
     * 保证"完成后短暂保留 + 定时清理"收敛，无 TTL 兜底导致条目永久滞留。</p>
     */
    private void sweepExpired() {
        long cutoff = System.currentTimeMillis() - TTL_MILLIS;
        Iterator<Map.Entry<String, ScreeningProgress>> it = progressMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ScreeningProgress> e = it.next();
            ScreeningProgress p = e.getValue();
            if (!p.isRunning() && p.getStartTime() > 0 && p.getStartTime() < cutoff) {
                it.remove();
            }
        }
    }

    /** 每小时定时兜底清理（与 DataFillProgressService.sweepExpiredHourly 对齐） */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3_600_000)
    public void sweepExpiredHourly() {
        sweepExpired();
    }

    public static class ScreeningProgress {
        private volatile boolean running;
        private volatile long startTime;
        private volatile List<WindowProgress> windows = new ArrayList<>();
        private volatile int totalWindows;
        private volatile int completedWindows;

        public boolean isRunning() { return running; }
        public void setRunning(boolean running) { this.running = running; }
        public long getStartTime() { return startTime; }
        public void setStartTime(long startTime) { this.startTime = startTime; }
        public List<WindowProgress> getWindows() { return windows; }
        public void setWindows(List<WindowProgress> windows) { this.windows = windows; }
        public int getTotalWindows() { return totalWindows; }
        public void setTotalWindows(int totalWindows) { this.totalWindows = totalWindows; }
        public int getCompletedWindows() { return completedWindows; }
        public void setCompletedWindows(int completedWindows) { this.completedWindows = completedWindows; }

        public long getElapsedSeconds() {
            if (startTime == 0) return 0;
            return (System.currentTimeMillis() - startTime) / 1000;
        }

        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("running", running);
            m.put("windows", windows);
            m.put("totalWindows", totalWindows);
            m.put("completedWindows", completedWindows);
            m.put("elapsedSeconds", getElapsedSeconds());
            m.put("startTime", startTime);
            return m;
        }
    }

    public static class WindowProgress {
        /** P2-12：跨线程可见性 —— 异步筛选线程写入、HTTP 线程读取 */
        private volatile int days;
        private volatile String status;
        private volatile int matched;

        public int getDays() { return days; }
        public void setDays(int days) { this.days = days; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getMatched() { return matched; }
        public void setMatched(int matched) { this.matched = matched; }
    }
}
