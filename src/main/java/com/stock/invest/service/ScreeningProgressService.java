package com.stock.invest.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@org.springframework.stereotype.Service
public class ScreeningProgressService {

    private final ConcurrentHashMap<String, ScreeningProgress> progressMap = new ConcurrentHashMap<>();

    public String startScreening(List<Integer> windows, int limit) {
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

    public ScreeningProgress getProgress(String taskId) {
        return progressMap.get(taskId);
    }

    public void removeProgress(String taskId) {
        progressMap.remove(taskId);
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
        private int days;
        private String status;
        private int matched;

        public int getDays() { return days; }
        public void setDays(int days) { this.days = days; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getMatched() { return matched; }
        public void setMatched(int matched) { this.matched = matched; }
    }
}
