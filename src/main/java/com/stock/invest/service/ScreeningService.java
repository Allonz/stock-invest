package com.stock.invest.service;

import java.time.LocalDate;

/**
 * 模式筛选服务：从 stock_daily_bars 读取最近数据，
 * 分组按 symbol 做模式评估，结果写入 screening_match 表。
 */
public interface ScreeningService {

    /**
     * 对指定交易日执行筛选。
     *
     * @param tradeDate 交易日
     * @return 生成的 batchId
     */
    String runScreening(LocalDate tradeDate);
}
