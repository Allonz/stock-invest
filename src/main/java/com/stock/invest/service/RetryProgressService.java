package com.stock.invest.service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@org.springframework.stereotype.Service
public class RetryProgressService {

    private RetryProgress current;

    public RetryProgress startRetry() {
        RetryProgress p = new RetryProgress();
        p.setStage("RECEIVING");
        p.setRunning(true);
        p.setStartTime(System.currentTimeMillis());
        current = p;
        return p;
    }

    public RetryProgress getProgress() {
        return current;
    }

    public static class RetryProgress {
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicLong startTime = new AtomicLong(0);
        private volatile String stage = "IDLE";
        private final AtomicInteger total = new AtomicInteger(0);
        private final AtomicInteger processed = new AtomicInteger(0);
        private final AtomicInteger succeeded = new AtomicInteger(0);
        private final AtomicInteger failed = new AtomicInteger(0);

        public boolean isRunning() { return running.get(); }
        public void setRunning(boolean v) { running.set(v); }
        public String getStage() { return stage; }
        public void setStage(String s) { this.stage = s; }
        public long getStartTime() { return startTime.get(); }
        public void setStartTime(long t) { startTime.set(t); }
        public int getTotal() { return total.get(); }
        public void setTotal(int v) { total.set(v); }
        public void incrementTotal() { total.incrementAndGet(); }
        public int getProcessed() { return processed.get(); }
        public void incrementProcessed() { processed.incrementAndGet(); }
        public int getSucceeded() { return succeeded.get(); }
        public void incrementSucceeded() { succeeded.incrementAndGet(); }
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
            m.put("total", getTotal());
            m.put("processed", getProcessed());
            m.put("succeeded", getSucceeded());
            m.put("failed", getFailed());
            m.put("elapsedSeconds", getElapsedSeconds());
            m.put("startTime", getStartTime());
            return m;
        }
    }
}
