package com.stock.invest.service;

/**
 * 数据补全服务：检查 stock_daily_bars 的数据连续性，
 * 对缺失日期或低价股票通过 fallback 链补查。
 */
public interface DataGapFillerService {

    /**
     * 执行全量数据补全：
     * 1. 读取所有 symbol，按 tradeDate 降序排列
     * 2. 检查日期连续性，发现缺失日期
     * 3. 检查最新价，> $1.00 跳过
     * 4. 按 fallback 链补查
     */
    void fillGaps();

    /**
     * 处理 data_fill_tasks 中 status='retrying' 的任务。
     * 每天最多重试5次，超过7天标记为 stopped。
     */
    void processRetryingTasks();
}
