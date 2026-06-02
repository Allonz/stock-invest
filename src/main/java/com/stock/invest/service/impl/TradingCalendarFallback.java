package com.stock.invest.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.stock.invest.model.TradingCalendarResult;
import com.stock.invest.service.TradingCalendarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 交易日历 Fallback 编排 + 缓存。
 * 按顺序尝试三个数据源：
 *   1. Tiger Java SDK
 *   2. TigerOpen Python
 *   3. Alpaca Markets
 * 全部不可用时返回 tradingDay=true（默认交易日）。
 *
 * 查询结果缓存 24 小时，key 格式为 "{market}:{yyyy-MM-dd}"。
 */
@Service
public class TradingCalendarFallback implements TradingCalendarService {

    private static final Logger log = LoggerFactory.getLogger(TradingCalendarFallback.class);

    /** Fallback 链：按优先级排列 */
    private final List<TradingCalendarService> sources;

    /** 内存缓存：24h TTL，最多 10000 条 */
    private final Cache<String, TradingCalendarResult> cache;

    public TradingCalendarFallback(
            TigerCalendarService tiger,
            TigerOpenCalendarService tigerOpen,
            AlpacaCalendarService alpaca) {
        this.sources = List.of(tiger, tigerOpen, alpaca);
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(24, TimeUnit.HOURS)
                .maximumSize(10000)
                .recordStats()
                .build();
    }

    @Override
    public String getSourceName() {
        return "fallback";
    }

    @Override
    public boolean isAvailable() {
        return sources.stream().anyMatch(TradingCalendarService::isAvailable);
    }

    @Override
    public TradingCalendarResult isTradingDay(String market, LocalDate date) {
        String cacheKey = buildCacheKey(market, date);

        // 1. 查缓存
        TradingCalendarResult cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            log.debug("[fallback] 缓存命中: {} -> tradingDay={}", cacheKey, cached.isTradingDay());
            return cached;
        }

        // 2. 顺序 fallback
        for (TradingCalendarService source : sources) {
            if (!source.isAvailable()) {
                log.debug("[fallback] 跳过不可用源: {}", source.getSourceName());
                continue;
            }

            log.debug("[fallback] 尝试源: {} (market={}, date={})", source.getSourceName(), market, date);
            TradingCalendarResult result = source.isTradingDay(market, date);
            if (result != null) {
                cache.put(cacheKey, result);
                log.info("[fallback] 源 {} 返回: tradingDay={}", source.getSourceName(), result.isTradingDay());
                return result;
            }
            log.warn("[fallback] 源 {} 查询失败，fallback 到下一源", source.getSourceName());
        }

        // 3. 全部失败 → 默认交易日（宁可重复，不要漏数据）
        TradingCalendarResult defaultResult = TradingCalendarResult.defaultTradingDay(market, date);
        cache.put(cacheKey, defaultResult);
        log.warn("[fallback] 所有日历数据源均不可用，默认返回 tradingDay=true (market={}, date={})", market, date);
        return defaultResult;
    }

    /** 获取缓存统计信息 */
    public CacheStats getCacheStats() {
        return cache.stats();
    }

    private static String buildCacheKey(String market, LocalDate date) {
        return market + ":" + date.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
}
