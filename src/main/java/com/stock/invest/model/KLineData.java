package com.stock.invest.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * K线数据模型
 */
@Getter
public class KLineData {
    @Setter private String symbol;
    private long time;
    @Setter private double open;
    @Setter private double high;
    @Setter private double low;
    @Setter private double close;
    @Setter private long volume;
    @Setter private double amount;

    // 存储K线数据对象的列表
    private List<KLineIterator> items = new ArrayList<>();

    public List<KLineIterator> getItems() {
        return items != null ? Collections.unmodifiableList(items) : Collections.emptyList();
    }

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

    public void setTime(long time) {
        this.time = time;
    }

    public void setItems(List<KLineIterator> items) {
        this.items = items != null ? items : new ArrayList<KLineIterator>();
    }


}
