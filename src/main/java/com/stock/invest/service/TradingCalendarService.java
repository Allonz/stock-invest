package com.stock.invest.service;

import com.stock.invest.model.TradingCalendarResult;

import java.time.LocalDate;

/**
 * 交易日历查询接口。
 * 每个数据源实现此接口，返回单日查询结果。
 * 返回 null = 该源不可用/超时/失败，触发 fallback 到下一源。
 */
public interface TradingCalendarService {

    /**
     * 判断指定日期是否为指定市场的交易日。
     *
     * @param market 市场代码，如 "US", "HK", "CN"
     * @param date   查询日期
     * @return TradingCalendarResult，或 null（触发 fallback）
     */
    TradingCalendarResult isTradingDay(String market, LocalDate date);

    /** 数据源名称，如 "tiger", "tigeropen", "alpaca" */
    String getSourceName();

    /** 该源是否已配置可用（凭证齐全） */
    boolean isAvailable();
}
