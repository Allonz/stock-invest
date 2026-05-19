package com.stock.invest.model;

/**
 * K线数据迭代器
 * 为解决"KLineData.KLineItem cannot be resolved to a type"问题，将KLineItem从KLineData内部类提取为独立类
 */
public class KLineIterator {
    private String symbol;
    private long time;
    private String timeString; // 用于存储时间字符串
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;
    private double amount;

    public KLineIterator() {
    }

    public KLineIterator(String symbol, long time, double open, double high, double low, double close, long volume, double amount) {
        this.symbol = symbol;
        this.time = time;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.amount = amount;
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
        }
    }
    
    public String getTimeString() {
        return timeString;
    }
    
    public void setTimeString(String timeString) {
        this.timeString = timeString;
    }

    public double getOpen() {
        return open;
    }

    public void setOpen(double open) {
        this.open = open;
    }

    public double getHigh() {
        return high;
    }

    public void setHigh(double high) {
        this.high = high;
    }

    public double getLow() {
        return low;
    }

    public void setLow(double low) {
        this.low = low;
    }

    public double getClose() {
        return close;
    }

    public void setClose(double close) {
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
                '}';
    }
}
