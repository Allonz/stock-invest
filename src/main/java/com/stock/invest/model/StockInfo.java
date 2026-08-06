package com.stock.invest.model;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 股票信息模型
 */
@Getter
@Setter
public class StockInfo {

    private String symbol;
    private String name;
    private BigDecimal currentPrice;
    private BigDecimal openPrice;
    private long volume;
    private BigDecimal change;
    private BigDecimal changePercent;
}
