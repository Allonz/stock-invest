package com.stock.invest.datasource;

/**
 * 数据源能力枚举。
 * 每种数据源可能支持一种或多种能力。
 * 前端根据能力标识展示不同的用途标签。
 */
public enum DataSourceCapability {

    /** 可以查询股票行情（K 线、报价、筛选等） */
    STOCK_QUOTE,

    /** 可以查询交易日历 */
    TRADING_CALENDAR
}
