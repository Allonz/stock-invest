package com.stock.invest.model;

import java.math.BigDecimal;

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
    
    // 支持BigDecimal类型 - 为TigerStockServiceImpl提供支持
    private BigDecimal openBD;
    private BigDecimal highBD;
    private BigDecimal lowBD;
    private BigDecimal closeBD;

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
        
        // 同时初始化BigDecimal值
        this.openBD = BigDecimal.valueOf(open);
        this.highBD = BigDecimal.valueOf(high);
        this.lowBD = BigDecimal.valueOf(low);
        this.closeBD = BigDecimal.valueOf(close);
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
            // 非数字格式的time设默认值
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
        this.openBD = BigDecimal.valueOf(open);
    }
    
    public BigDecimal getOpenBD() {
        return openBD;
    }
    
    public void setOpen(BigDecimal open) {
        this.openBD = open;
        this.open = open != null ? open.doubleValue() : 0.0;
    }

    public double getHigh() {
        return high;
    }

    public void setHigh(double high) {
        this.high = high;
        this.highBD = BigDecimal.valueOf(high);
    }
    
    public BigDecimal getHighBD() {
        return highBD;
    }
    
    public void setHigh(BigDecimal high) {
        this.highBD = high;
        this.high = high != null ? high.doubleValue() : 0.0;
    }

    public double getLow() {
        return low;
    }

    public void setLow(double low) {
        this.low = low;
        this.lowBD = BigDecimal.valueOf(low);
    }
    
    public BigDecimal getLowBD() {
        return lowBD;
    }
    
    public void setLow(BigDecimal low) {
        this.lowBD = low;
        this.low = low != null ? low.doubleValue() : 0.0;
    }

    public double getClose() {
        return close;
    }

    public void setClose(double close) {
        this.close = close;
        this.closeBD = BigDecimal.valueOf(close);
    }
    
    public BigDecimal getCloseBD() {
        return closeBD;
    }
    
    public void setClose(BigDecimal close) {
        this.closeBD = close;
        this.close = close != null ? close.doubleValue() : 0.0;
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