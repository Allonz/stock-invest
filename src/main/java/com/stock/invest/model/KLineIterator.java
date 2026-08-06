package com.stock.invest.model;

/**
 * K线数据迭代器
 * 为解决"KLineData.KLineItem cannot be resolved to a type"问题，将KLineItem从KLineData内部类提取为独立类
 */
public class KLineIterator {
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

    public KLineIterator() {
    }

    /**
     * 8 参数构造器（兼容旧代码），新增字段默认 BigDecimal.ZERO
     */
    public KLineIterator(String symbol, long time, java.math.BigDecimal open, java.math.BigDecimal high, java.math.BigDecimal low, java.math.BigDecimal close, long volume, double amount) {
        this(symbol, time, open, high, low, close, volume, amount, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);
    }

    public KLineIterator(String symbol, long time, java.math.BigDecimal open, java.math.BigDecimal high, java.math.BigDecimal low, java.math.BigDecimal close, long volume, double amount,
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
        this.timeString = timeStr;
        try {
            if (timeStr != null && !timeStr.isEmpty()) {
                // 尝试解析为数字时间戳
                this.time = Long.parseLong(timeStr);
            }
        } catch (NumberFormatException e) {
            this.time = 0L;
            // 使用 java.lang.System.Logger（model 层不应依赖 SLF4J）
            System.Logger logger = System.getLogger(KLineIterator.class.getName());
            logger.log(System.Logger.Level.WARNING, "KLineIterator: failed to parse timeStr='" + timeStr + "'");
        }
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
        return "KLineIterator{" +
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
