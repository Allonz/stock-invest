package com.stock.invest.service;

import com.stock.invest.model.KLineData;

import java.time.LocalDate;

/**
 * 核心数据源策略接口 — 只包含数据补缺所需的最小方法集。
 * <p>
 * DataGapFillerServiceImpl 只依赖此接口，无需关心扫描/批量等能力。
 * </p>
 */
public interface DataSourceStrategy {

    /** 数据源名称标识，如 "tigeropen", "tiger", "yfinance" */
    String getSourceName();

    /** 是否为该数据源配置了有效凭证 */
    boolean isAvailable();

    /**
     * 按指定交易日获取 K 线数据（精确查询）。
     * <p>数据源支持精确日期则高效实现，不支持则走默认的 {@link #getDailyKLineDataAsObject} 全量拉取。</p>
     * <p>注意：不要在实现内做「精确查不到→换范围再查」的 fallback 逻辑。</p>
     * <p>各数据源实现应根据自身 API 的日期边界语义在内部膨胀日期范围。</p>
     *
     * @param symbol    股票代码
     * @param tradeDate 需要查询的交易日（含）
     * @return K线数据
     */
    default KLineData getDailyKLineDataByDateRange(String symbol, LocalDate tradeDate) {
        return getDailyKLineDataAsObject(symbol);
    }

    /**
     * 按指定交易日获取盘后价 K 线数据（精确查询）。
     * <p>默认实现回退到 {@link #getDailyKLineDataByDateRange}，不支持盘后价的数据源直接使用默认值。</p>
     *
     * @param symbol    股票代码
     * @param tradeDate 需要查询的交易日（含）
     * @return K线数据
     */
    default KLineData getAfterHoursKLineDataByDateRange(String symbol, LocalDate tradeDate) {
        return getDailyKLineDataByDateRange(symbol, tradeDate);
    }

    /**
     * 获取股票的每日K线数据（对象格式）。
     * <p>核心接口需要此方法作为 getDailyKLineDataByDateRange 的默认回退实现。</p>
     */
    KLineData getDailyKLineDataAsObject(String symbol);
}
