package com.stock.invest.service;

import com.stock.invest.model.KLineData;
import com.stock.invest.model.StockInfo;
import com.tigerbrokers.stock.openapi.client.struct.enums.Market;

import java.util.List;
import java.util.Map;

/**
 * 扩展数据源策略接口 — 包含扫描/批量/股票信息等完整能力。
 * <p>
 * 各数据源实现类应实现此接口。DataGapFillerServiceImpl 只依赖 {@link DataSourceStrategy}。
 * </p>
 */
public interface StockScannerStrategy extends DataSourceStrategy {

    /** 获取股票的每日K线数据（字符串格式） */
    String getDailyKLineData(String symbol);

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
}
