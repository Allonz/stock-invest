package com.stock.invest.service.impl;

import com.stock.invest.model.KLineData;
import com.stock.invest.service.DataSourceStrategy;
import com.stock.invest.service.StockDataSourcePriorityService;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 数据源 fallback 链构建器。
 *
 * <p>负责按默认优先级或个股历史成功记录排序可用数据源，
 * 并跳过处于账户级错误冷却期内的数据源。</p>
 */
class FallbackChainBuilder {

    @FunctionalInterface
    interface KLineFetcher {
        KLineData fetch(String symbol, LocalDate tradeDate) throws Exception;
    }

    record FallbackSource(String name, KLineFetcher fetcher, DataSourceStrategy ds) {}

    private final List<DataSourceStrategy> dataSources;
    private final StockDataSourcePriorityService stockDataSourcePriorityService;
    private final Map<String, Long> sourceCooldownUntil;

    FallbackChainBuilder(List<DataSourceStrategy> dataSources,
                         StockDataSourcePriorityService stockDataSourcePriorityService,
                         Map<String, Long> sourceCooldownUntil) {
        this.dataSources = dataSources;
        this.stockDataSourcePriorityService = stockDataSourcePriorityService;
        this.sourceCooldownUntil = sourceCooldownUntil;
    }

    /**
     * 构建某支股票专属的 fallback 链。
     * 有历史成功记录 → 按 last_success_time DESC 优先；
     * 无历史记录 → 默认顺序 yfinance → twelvedata → tiingo → tigeropen。
     */
    List<FallbackSource> buildForSymbol(String symbol) {
        List<String> priorityOrder = symbol != null
                ? stockDataSourcePriorityService.getPriorityList(symbol)
                : StockDataSourcePriorityService.DEFAULT_DATA_SOURCE_ORDER;

        Map<String, Integer> priorityMap = new HashMap<>();
        for (int i = 0; i < priorityOrder.size(); i++) {
            priorityMap.put(priorityOrder.get(i), i);
        }

        return dataSources.stream()
                .filter(Objects::nonNull)
                .filter(DataSourceStrategy::isAvailable)
                .filter(ds -> !isSourceCooledDown(ds.getSourceName()))
                .sorted(Comparator.comparingInt(s -> priorityMap.getOrDefault(s.getSourceName(), 99)))
                .map(ds -> new FallbackSource(
                        ds.getSourceName(),
                        (sym, date) -> ds.getDailyKLineDataByDateRange(sym, date),
                        ds))
                .collect(Collectors.toList());
    }

    /**
     * 数据源是否处于熔断冷却期。
     */
    boolean isSourceCooledDown(String sourceName) {
        Long until = sourceCooldownUntil.get(sourceName);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            sourceCooldownUntil.remove(sourceName, until);
            return false;
        }
        return true;
    }

    /**
     * 写入数据源冷却截止时间（账户级错误触发）。
     */
    void putCooldown(String sourceName, long until) {
        sourceCooldownUntil.put(sourceName, until);
    }
}
