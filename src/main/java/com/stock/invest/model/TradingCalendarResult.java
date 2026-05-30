package com.stock.invest.model;

import java.time.LocalDate;

/**
 * 交易日历查询结果，不可变对象。
 * 每次查询一个日期的交易日状态。
 */
public class TradingCalendarResult {

    private final String market;
    private final LocalDate date;
    private final boolean tradingDay;
    private final String source;
    private final String type;
    private final String detail;

    public TradingCalendarResult(String market, LocalDate date, boolean tradingDay,
                                 String source, String type, String detail) {
        this.market = market;
        this.date = date;
        this.tradingDay = tradingDay;
        this.source = source;
        this.type = type;
        this.detail = detail;
    }

    /** 交易日 */
    public static TradingCalendarResult trading(String market, LocalDate date,
                                                String source, String type) {
        return new TradingCalendarResult(market, date, true, source, type, null);
    }

    /** 非交易日 */
    public static TradingCalendarResult nonTrading(String market, LocalDate date,
                                                   String source, String type) {
        return new TradingCalendarResult(market, date, false, source, type, null);
    }

    /** 非交易日（带详细说明） */
    public static TradingCalendarResult nonTrading(String market, LocalDate date,
                                                   String source, String type, String detail) {
        return new TradingCalendarResult(market, date, false, source, type, detail);
    }

    /** 全部数据源不可用时返回默认 true（宁可重复，不要漏数据） */
    public static TradingCalendarResult defaultTradingDay(String market, LocalDate date) {
        return new TradingCalendarResult(market, date, true, "none", "DEFAULT",
                "所有数据源均不可用，默认交易日");
    }

    // --- getters ---

    public String getMarket() { return market; }
    public LocalDate getDate() { return date; }
    public boolean isTradingDay() { return tradingDay; }
    public String getSource() { return source; }
    public String getType() { return type; }
    public String getDetail() { return detail; }

    @Override
    public String toString() {
        return "TradingCalendarResult{" +
                "market='" + market + '\'' +
                ", date=" + date +
                ", tradingDay=" + tradingDay +
                ", source='" + source + '\'' +
                ", type='" + type + '\'' +
                '}';
    }
}
