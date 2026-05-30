package com.stock.invest.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@org.springframework.stereotype.Service
public class DataFillProgressService {

    private final ConcurrentHashMap<String, FillProgress> progressMap = new ConcurrentHashMap<>();

    public FillProgress startFill() {
        FillProgress p = new FillProgress();
        progressMap.put("manual", p);
        return p;
    }

    public FillProgress getProgress() {
        return progressMap.get("manual");
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
