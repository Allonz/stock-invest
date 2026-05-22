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

    /**
     * 放量突破模式：
     * 窗口内前 (windowDays-1) 天的平均成交量 × 5 &lt; 最后一天的成交量
     *
     * @param barsOldestFirst K线数据（oldest-first 排序），v1最远，vN最近
     * @param windowDays 窗口天数（2-7）
     * @return 是否命中
     */
    boolean matchesVolumeSpikePattern(List<StockDailyBar> barsOldestFirst, int windowDays);
}
