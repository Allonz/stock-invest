package com.stock.invest.service;

import com.stock.invest.entity.StockDailyBar;

import java.util.List;

/**
 * 独立的缓存层 Service，用于支持 {@code @Cacheable} 通过 AOP 代理生效。
 * <p>
 * 从 PriceVolumeCacheServiceImpl 中拆分而来，避免 self-invocation 绕过缓存代理的问题。
 */
public interface LatestBarsCacheService {

    /**
     * 获取指定股票最近 N 天的 K 线数据（走缓存）。
     */
    List<StockDailyBar> getLatestBars(String symbol, int windowDays);
}
