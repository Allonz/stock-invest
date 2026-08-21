package com.stock.invest.model;

/**
 * 单根 K 线数据项。
 *
 * <p>承载单根 K 线的 OHLCV、涨跌幅、盘后价等字段，由各数据源（Python 脚本 JSON 反序列化
 * 或服务实现手工构造）填充。</p>
 *
 * <p>评审 E-4：类名为数据项而非迭代器，已由 {@code KLineIterator} 更名回 {@code KLineItem}
 * （该名与最初从 {@code KLineData} 内部类提取时的命名一致）。</p>
 */
public class KLineItem {
    private String symbol;
    private long time;
    private String timeString; // 用于存储时间字符串
    private java.math.BigDecimal open;
    private java.math.BigDecimal high;
    private java.math.BigDecimal low;
    private java.math.BigDecimal close;
    private long volume;
    private double amount;
    private java.math.BigDecimal changePercent;           // 涨跌幅（%）
    private java.math.BigDecimal afterHours;               // 盘后价
    private java.math.BigDecimal afterHoursChangePercent;  // 盘后涨跌幅（%）

    public KLineItem() {
    }

    /**
     * 8 参数构造器（兼容旧代码），新增字段默认 BigDecimal.ZERO
     */
    public KLineItem(String symbol, long time, java.math.BigDecimal open, java.math.BigDecimal high, java.math.BigDecimal low, java.math.BigDecimal close, long volume, double amount) {
        this(symbol, time, open, high, low, close, volume, amount, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);
    }

    public KLineItem(String symbol, long time, java.math.BigDecimal open, java.math.BigDecimal high, java.math.BigDecimal low, java.math.BigDecimal close, long volume, double amount,
                         java.math.BigDecimal changePercent, java.math.BigDecimal afterHours, java.math.BigDecimal afterHoursChangePercent) {
        this.symbol = symbol;
        this.time = time;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.amount = amount;
        this.changePercent = changePercent;
        this.afterHours = afterHours;
        this.afterHoursChangePercent = afterHoursChangePercent;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }
    
    // 支持字符串格式的时间设置
    public void setTime(String timeStr) {
        if (timeStr != null && !timeStr.isEmpty()) {
            // 尝试解析为数字时间戳；解析失败直接抛 NumberFormatException，
            // 避免坏数据静默置 0 后在链路中无声传播（评审 E-4）。
            // 先解析后赋值：失败时不留下部分修改的状态。
            long parsed = Long.parseLong(timeStr);
            this.time = parsed;
        }
        this.timeString = timeStr;
    }
    
    public String getTimeString() {
        return timeString;
    }
    
    public void setTimeString(String timeString) {
        this.timeString = timeString;
    }

    public java.math.BigDecimal getOpen() {
        return open;
    }

    public void setOpen(java.math.BigDecimal open) {
        this.open = open;
    }

    public java.math.BigDecimal getHigh() {
        return high;
    }

    public void setHigh(java.math.BigDecimal high) {
        this.high = high;
    }

    public java.math.BigDecimal getLow() {
        return low;
    }

    public void setLow(java.math.BigDecimal low) {
        this.low = low;
    }

    public java.math.BigDecimal getClose() {
        return close;
    }

    public void setClose(java.math.BigDecimal close) {
        this.close = close;
    }

    public long getVolume() {
        return volume;
    }

    public void setVolume(long volume) {
        this.volume = volume;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public java.math.BigDecimal getChangePercent() {
        return changePercent;
    }

    public void setChangePercent(java.math.BigDecimal changePercent) {
        this.changePercent = changePercent;
    }

    public java.math.BigDecimal getAfterHours() {
        return afterHours;
    }

    public void setAfterHours(java.math.BigDecimal afterHours) {
        this.afterHours = afterHours;
    }

    public java.math.BigDecimal getAfterHoursChangePercent() {
        return afterHoursChangePercent;
    }

    public void setAfterHoursChangePercent(java.math.BigDecimal afterHoursChangePercent) {
        this.afterHoursChangePercent = afterHoursChangePercent;
    }

    @Override
    public String toString() {
        return "KLineItem{" +
                "symbol='" + symbol + '\'' +
                ", time=" + time +
                ", timeString='" + timeString + '\'' +
                ", open=" + open +
                ", high=" + high +
                ", low=" + low +
                ", close=" + close +
                ", volume=" + volume +
                ", amount=" + amount +
                ", changePercent=" + changePercent +
                ", afterHours=" + afterHours +
                ", afterHoursChangePercent=" + afterHoursChangePercent +
                '}';
    }
}
