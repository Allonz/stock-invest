package com.stock.invest.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.ArrayList;

/**
 * K线请求参数模型类
 */
public class KLineRequest {
    
    private List<String> symbols;
    private String period;
    
    @JsonProperty("begin_time")
    private String beginTime;
    
    @JsonProperty("end_time")
    private String endTime;
    
    private Integer limit;
    
    @JsonProperty("page_token")
    private String pageToken;
    
    private String right;
    
    // 构造器
    public KLineRequest() {
    }
    
    // 创建一个用于获取日K线的请求构造器
    public static Builder dailyKLineBuilder() {
        return new Builder().period("day");
    }
    
    // 构建器模式
    public static class Builder {
        private KLineRequest request;
        
        public Builder() {
            request = new KLineRequest();
        }
        
        public Builder symbols(List<String> symbols) {
            request.setSymbols(symbols);
            return this;
        }
        
        public Builder symbol(String symbol) {
            List<String> symbols = new ArrayList<>();
            symbols.add(symbol);
            request.setSymbols(symbols);
            return this;
        }
        
        public Builder period(String period) {
            request.setPeriod(period);
            return this;
        }
        
        public Builder beginTime(String beginTime) {
            request.setBeginTime(beginTime);
            return this;
        }
        
        public Builder endTime(String endTime) {
            request.setEndTime(endTime);
            return this;
        }
        
        public Builder limit(Integer limit) {
            request.setLimit(limit);
            return this;
        }
        
        public Builder pageToken(String pageToken) {
            request.setPageToken(pageToken);
            return this;
        }
        
        public Builder right(String right) {
            request.setRight(right);
            return this;
        }
        
        public KLineRequest build() {
            return request;
        }
    }
    
    // Getters and Setters
    public List<String> getSymbols() {
        return symbols;
    }
    
    public void setSymbols(List<String> symbols) {
        this.symbols = symbols;
    }
    
    public String getPeriod() {
        return period;
    }
    
    public void setPeriod(String period) {
        this.period = period;
    }
    
    public String getBeginTime() {
        return beginTime;
    }
    
    public void setBeginTime(String beginTime) {
        this.beginTime = beginTime;
    }
    
    public String getEndTime() {
        return endTime;
    }
    
    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }
    
    public Integer getLimit() {
        return limit;
    }
    
    public void setLimit(Integer limit) {
        this.limit = limit;
    }
    
    public String getPageToken() {
        return pageToken;
    }
    
    public void setPageToken(String pageToken) {
        this.pageToken = pageToken;
    }
    
    public String getRight() {
        return right;
    }
    
    public void setRight(String right) {
        this.right = right;
    }
} 