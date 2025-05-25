package com.stock.invest.enums;

/**
 * 市场扫描类别枚举
 * 自定义枚举，对应Tiger Open API中的筛选项
 */
public enum MarketScannerCategory {
    /**
     * 成交量排序
     */
    TOP_VOLUME,
    
    /**
     * 涨幅排序
     */
    TOP_GAINER,
    
    /**
     * 跌幅排序
     */
    TOP_LOSER,
    
    /**
     * 市值排序
     */
    TOP_MARKETCAP,
    
    /**
     * 股息率排序
     */
    TOP_DIVIDEND,
    
    /**
     * 自定义筛选
     */
    CUSTOM
} 