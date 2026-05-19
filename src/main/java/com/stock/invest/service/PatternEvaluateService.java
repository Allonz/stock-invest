package com.stock.invest.service;

import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.model.KLineIterator;

import java.util.List;

public interface PatternEvaluateService {

    boolean matchesIncreasingVolumePattern(List<StockDailyBar> sevenBarsOldestFirst);

    boolean matchesIncreasingVolumePattern(List<StockDailyBar> barsOldestFirst, int windowDays);

    /**
     * Evaluate volume pattern using {@link KLineIterator} list (oldest-first).
     */
    boolean matchesIncreasingVolumePatternFromKLine(List<KLineIterator> barsOldestFirst, int windowDays);
}
