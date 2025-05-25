package com.stock.invest.model;

import java.util.ArrayList;
import java.util.List;

/**
 * K线数据模型
 */
public class KLineData {
    private String symbol;
    private long time;
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;
    private double amount;
    
    // 分页相关字段
    private String nextPageToken;
    private String errorCode;
    private String errorMessage;
    private String code;
    private String message;
    
    // 存储K线数据对象的列表
    private List<KLineIterator> items = new ArrayList<>();

    public KLineData() {
    }

    public KLineData(String symbol, long time, double open, double high, double low, double close, long volume, double amount) {
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
    
    public void setTime(Long time) {
        this.time = time != null ? time : 0;
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

    public String getNextPageToken() {
        return nextPageToken;
    }

    public void setNextPageToken(String nextPageToken) {
        this.nextPageToken = nextPageToken;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public List<KLineIterator> getItems() {
        return items;
    }

    public void setItems(List<KLineIterator> items) {
        this.items = items;
    }

    public void addItem(KLineIterator item) {
        this.items.add(item);
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public String toString() {
        return "KLineData{" +
                "symbol='" + symbol + '\'' +
                ", time=" + time +
                ", open=" + open +
                ", high=" + high +
                ", low=" + low +
                ", close=" + close +
                ", volume=" + volume +
                ", amount=" + amount +
                '}';
    }
}