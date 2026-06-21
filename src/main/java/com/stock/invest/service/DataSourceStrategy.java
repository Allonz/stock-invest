package com.stock.invest.service;

import com.stock.invest.model.KLineData;
import com.stock.invest.model.StockInfo;
import com.tigerbrokers.stock.openapi.client.struct.enums.Market;

import java.util.List;
import java.time.LocalDate;
import java.util.Map;

/**
 * 统一数据源策略接口 - 每种数据源实现此接口的所有操作。
 * 各实现通过 @Order 注解定义优先级，PriorityStockServiceImpl 按顺序依次尝试。
 */
public interface DataSourceStrategy {

    /** 数据源名称标识，如 "tigeropen", "tiger", "yfinance" */
    String getSourceName();

    /** 是否为该数据源配置了有效凭证 */
    boolean isAvailable();

    /** 获取股票的每日K线数据（字符串格式） */
    String getDailyKLineData(String symbol);

    /** 获取股票的每日K线数据（对象格式） */
    KLineData getDailyKLineDataAsObject(String symbol);

    /** 获取股票信息 */
    StockInfo getStockInfo(String symbol);

    /** 获取股票列表 */
    List<String> getStockList();

    /** 获取股票每日K线数据 */
    KLineData getDailyKLine(String symbol);

    /** 批量获取K线数据 */
    List<KLineData> getBatchKline(List<String> symbols, String period, int count);

    /** 筛选股票（Market 枚举版本） */
    List<String> scanStocks(Market market, int limit, Double minPrice, Double maxPrice);

    /** 筛选股票（字符串版本） */
    List<String> scanStocks(String market, int limit, String minPrice, String maxPrice);

    /** 查询低价股票并根据成交量筛选（带数量限制） */
    Map<String, Object> scanLowPriceStocksWithVolumePattern(int limit);

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
}
