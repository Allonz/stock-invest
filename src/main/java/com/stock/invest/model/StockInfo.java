package com.stock.invest.model;

import lombok.Getter;
import lombok.Setter;

/**
 * 股票信息模型
 */
@Getter
@Setter
public class StockInfo {

    private String symbol;
    private String name;
    private double currentPrice;
    private double openPrice;
    private int volume;
    private double change;
    private double changePercent;
}
