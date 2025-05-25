package com.stock.invest.service;

import com.stock.invest.model.KLineData;
import com.stock.invest.model.StockInfo;
import com.tigerbrokers.stock.openapi.client.struct.enums.Market;

import java.util.List;
import java.util.Map;

/**
 * 股票服务接口
 */
public interface StockService {
    
    /**
     * 时间周期枚举
     */
    enum Period {
        DAY,   // 日K线
        WEEK,  // 周K线
        MONTH  // 月K线
    }
    
    /**
     * 获取股票的每日K线数据（字符串格式）
     * @param symbol 股票代码
     * @return K线数据字符串
     */
    String getDailyKLineData(String symbol);
    
    /**
     * 获取股票的每日K线数据（对象格式）
     * @param symbol 股票代码
     * @return K线数据对象
     */
    KLineData getDailyKLineDataAsObject(String symbol);
    
    /**
     * 获取股票信息
     * @param symbol 股票代码
     * @return 股票信息对象
     */
    StockInfo getStockInfo(String symbol);
    
    /**
     * 获取股票列表
     * @return 股票代码列表
     */
    List<String> getStockList();
    
    /**
     * 获取股票每日K线数据
     * @param symbol 股票代码
     * @return K线数据对象
     */
    KLineData getDailyKLine(String symbol);
    
    /**
     * 批量获取K线数据（枚举周期）
     * @param symbols 股票代码列表
     * @param period 时间周期枚举
     * @param count 数据的条数
     * @return K线数据列表
     */
    List<KLineData> getBatchKlineData(List<String> symbols, Period period, int count);
    
    /**
     * 批量获取K线数据
     * @param symbols 股票代码列表
     * @param period 时间周期
     * @param count 数据的条数
     * @return K线数据列表
     */
    List<KLineData> getBatchKline(List<String> symbols, String period, int count);
    
    /**
     * 自定义筛选股票 (Market 枚举版本)
     * 
     * @param market 市场: US, HK, CN
     * @param limit 返回结果的最大数
     * @param minPrice 最低股价
     * @param maxPrice 最高股价
     * @return 股票列表
     */
    List<String> scanStocks(Market market, int limit, Double minPrice, Double maxPrice);
    
    /**
     * 筛选股票 (字符串版本)
     * @param market 市场
     * @param limit 返回结果的最大数
     * @param minPrice 最低股价
     * @param maxPrice 最高股价
     * @return 股票列表
     */
    List<String> scanStocks(String market, int limit, String minPrice, String maxPrice);
    
    /**
     * 查询预设置的低价股票列表，并根据成交量筛选
     * @return 符合条件的股票及其K线数据
     */
    Map<String, Object> scanLowPriceStocksWithVolumePattern();
    
    /**
     * 查询低价股票并根据成交量筛选（带数量限制）
     * @param limit 限制返回结果数
     * @return 符合条件的股票及其K线数据
     */
    Map<String, Object> scanLowPriceStocksWithVolumePattern(int limit);
} 