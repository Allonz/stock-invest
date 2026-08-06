package com.stock.invest.service;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 补缺进度服务（P2-12）。
 * <p>startFill() 生成 UUID taskId（与筛选进度对齐），多次触发不再互相覆盖；
 * 无参 getProgress() 返回最近一次任务进度，兼容旧端点；条目超过 24h 自动清除（惰性 TTL）。</p>
 */
@org.springframework.stereotype.Service
public class DataFillProgressService {

    /** 进度条目保留时长 */
    private static final long TTL_MILLIS = 24 * 60 * 60 * 1000L;

    private final ConcurrentHashMap<String, FillProgress> progressMap = new ConcurrentHashMap<>();
    /** 最近一次任务的 key（兼容无参 getProgress） */
    private volatile String latestKey;

    /** 启动一次补缺任务，返回 taskId */
    public String startFill() {
        sweepExpired();
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        FillProgress p = new FillProgress();
        progressMap.put(taskId, p);
        latestKey = taskId;
        return taskId;
    }

    /** 最近一次任务的进度（兼容旧端点） */
    public FillProgress getProgress() {
        return latestKey == null ? null : progressMap.get(latestKey);
    }

    /** 按 taskId 查进度 */
    public FillProgress getProgress(String taskId) {
        return taskId == null ? null : progressMap.get(taskId);
    }

    /** 移除某任务进度（任务结束后调用） */
    public void removeProgress(String taskId) {
        if (taskId != null) {
            progressMap.remove(taskId);
            if (taskId.equals(latestKey)) {
                latestKey = null;
            }
        }
    }

    /** 惰性 TTL 清理：移除超过 24h 的条目，防止内存泄漏 */
    private void sweepExpired() {
        long cutoff = System.currentTimeMillis() - TTL_MILLIS;
        Iterator<Map.Entry<String, FillProgress>> it = progressMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, FillProgress> e = it.next();
            FillProgress p = e.getValue();
            if (!p.isRunning() && p.getStartTime() > 0 && p.getStartTime() < cutoff) {
                it.remove();
            }
        }
    }

    public static class FillProgress {
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicLong startTime = new AtomicLong(0);
        private volatile String stage = "SCANNING";
        private final AtomicInteger totalSymbols = new AtomicInteger(0);
        private final AtomicInteger processedSymbols = new AtomicInteger(0);
        private final AtomicInteger gapsFound = new AtomicInteger(0);
        private final AtomicInteger filled = new AtomicInteger(0);
        private final AtomicInteger failed = new AtomicInteger(0);

        public boolean isRunning() { return running.get(); }
        public void setRunning(boolean v) { running.set(v); }
        public String getStage() { return stage; }
        public void setStage(String s) { this.stage = s; }
        public long getStartTime() { return startTime.get(); }
        public void setStartTime(long t) { startTime.set(t); }
        public int getTotalSymbols() { return totalSymbols.get(); }
        public void setTotalSymbols(int v) { totalSymbols.set(v); }
        public int getProcessedSymbols() { return processedSymbols.get(); }
        public void incrementProcessedSymbols() { processedSymbols.incrementAndGet(); }
        public int getGapsFound() { return gapsFound.get(); }
        public void addGapsFound(int v) { gapsFound.addAndGet(v); }
        public int getFilled() { return filled.get(); }
        public void incrementFilled() { filled.incrementAndGet(); }
        public int getFailed() { return failed.get(); }
        public void incrementFailed() { failed.incrementAndGet(); }
        public long getElapsedSeconds() {
            long start = startTime.get();
            if (start == 0) return 0;
            return (System.currentTimeMillis() - start) / 1000;
        }
        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("running", isRunning());
            m.put("stage", getStage());
            m.put("totalSymbols", getTotalSymbols());
            m.put("processedSymbols", getProcessedSymbols());
            m.put("gapsFound", getGapsFound());
            m.put("filled", getFilled());
            m.put("failed", getFailed());
            m.put("elapsedSeconds", getElapsedSeconds());
            m.put("startTime", getStartTime());
            return m;
        }
    }
}
