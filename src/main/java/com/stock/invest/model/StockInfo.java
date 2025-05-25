package com.stock.invest.model;

import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 股票信息数据模型
 */
public class StockInfo {
    private String symbol;
    private String name;
    
    @JsonProperty("currentPrice")
    private double currentPrice;
    
    @JsonProperty("openPrice")
    private double openPrice;
    
    @JsonProperty("highPrice")
    private double highPrice;
    
    @JsonProperty("lowPrice")
    private double lowPrice;
    
    private int volume;
    private double change;
    
    @JsonProperty("changePercent")
    private double changePercent;

    private double latestPrice;
    private double changePrice;
    private double changeRate;
    private long latestVolume;
    private long marketCap;
    private double pe;
    private double week52High;
    private double week52Low;
    private int dividendYield;
    
    // 相关K线数据项列表
    private List<KLineIterator> kLineItems = new ArrayList<>();

    public StockInfo() {
    }

    /**
     * 根据股票代码和名称创建一个StockInfo对象
     * 
     * @param symbol 股票代码
     * @param name 股票名称
     */
    public StockInfo(String symbol, String name) {
        this.symbol = symbol;
        this.name = name;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getCurrentPrice() {
        return currentPrice;
    }

    public void setCurrentPrice(double currentPrice) {
        this.currentPrice = currentPrice;
    }

    public double getOpenPrice() {
        return openPrice;
    }

    public void setOpenPrice(double openPrice) {
        this.openPrice = openPrice;
    }

    public double getHighPrice() {
        return highPrice;
    }

    public void setHighPrice(double highPrice) {
        this.highPrice = highPrice;
    }

    public double getLowPrice() {
        return lowPrice;
    }

    public void setLowPrice(double lowPrice) {
        this.lowPrice = lowPrice;
    }

    public int getVolume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = volume;
    }

    public double getChange() {
        return change;
    }

    public void setChange(double change) {
        this.change = change;
    }

    public double getChangePercent() {
        return changePercent;
    }

    public void setChangePercent(double changePercent) {
        this.changePercent = changePercent;
    }

    public double getLatestPrice() {
        return latestPrice;
    }

    public void setLatestPrice(double latestPrice) {
        this.latestPrice = latestPrice;
    }

    public double getChangePrice() {
        return changePrice;
    }

    public void setChangePrice(double changePrice) {
        this.changePrice = changePrice;
    }

    public double getChangeRate() {
        return changeRate;
    }

    public void setChangeRate(double changeRate) {
        this.changeRate = changeRate;
    }

    public long getLatestVolume() {
        return latestVolume;
    }

    public void setLatestVolume(long latestVolume) {
        this.latestVolume = latestVolume;
    }

    public long getMarketCap() {
        return marketCap;
    }

    public void setMarketCap(long marketCap) {
        this.marketCap = marketCap;
    }

    public double getPe() {
        return pe;
    }

    public void setPe(double pe) {
        this.pe = pe;
    }

    public double getWeek52High() {
        return week52High;
    }

    public void setWeek52High(double week52High) {
        this.week52High = week52High;
    }

    public double getWeek52Low() {
        return week52Low;
    }

    public void setWeek52Low(double week52Low) {
        this.week52Low = week52Low;
    }

    public int getDividendYield() {
        return dividendYield;
    }

    public void setDividendYield(int dividendYield) {
        this.dividendYield = dividendYield;
    }

    /**
     * 获取K线数据项列表
     * @return K线数据项列表
     */
    public List<KLineIterator> getKLineItems() {
        return kLineItems;
    }
    
    /**
     * 设置K线数据项列表
     * @param kLineItems K线数据项列表
     */
    public void setKLineItems(List<KLineIterator> kLineItems) {
        this.kLineItems = kLineItems;
    }
    
    /**
     * 添加单个K线数据项
     * @param item K线数据项
     */
    public void addKLineItem(KLineIterator item) {
        this.kLineItems.add(item);
    }

    @Override
    public String toString() {
        return "StockInfo{" +
                "symbol='" + symbol + '\'' +
                ", name='" + name + '\'' +
                ", latestPrice=" + latestPrice +
                ", changePrice=" + changePrice +
                ", changeRate=" + changeRate +
                ", latestVolume=" + latestVolume +
                ", marketCap=" + marketCap +
                ", kLineItems=" + kLineItems.size() +
                '}';
    }
} 