package com.stock.invest.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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

    /**
     * 对指定交易日执行筛选（P1-7：支持窗口/数量参数生效）。
     *
     * @param tradeDate  交易日
     * @param windowDays 窗口天数；null 或小于最小窗口时使用全部窗口 2~7 天
     * @param limit      最多评估的候选 symbol 数；null 或 &lt;=0 表示不限
     * @return 生成的 batchId；若已有筛选在运行则返回 null
     */
    String runScreening(LocalDate tradeDate, Integer windowDays, Integer limit);

    /**
     * 获取最新一次筛选结果（含 stock name）。
     *
     * @return 结果 Map，包含 batchId / tradeDate / totalMatches / matches
     */
    Map<String, Object> getLatestScreening();

    /**
     * 获取历史筛选批次列表。
     *
     * @return 每个批次的摘要 Map 列表
     */
    List<Map<String, Object>> getScreeningHistory();

    /**
     * 获取某批次筛选详情（含 stock name）。
     *
     * @param batchId 批次 ID
     * @return 结果 Map，包含 batchId / totalMatches / matches
     */
    Map<String, Object> getBatchDetail(String batchId);

    /**
     * 按批次 ID 分组统计每个 windowDays 的匹配数。
     *
     * @param batchId 批次 ID
     * @return 每行 Object[]: [windowDays, count]
     */
    List<Object[]> countByBatchIdGroupByWindowDays(String batchId);

    /**
     * 获取最新筛选结果，按 algorithm + windowDays 分组统计（通知用）。
     *
     * @return 包含 batchId / screenDate / results 的 Map
     */
    Map<String, Object> getLatestNotificationGrouped();

    /**
     * 获取最新筛选结果，按 algorithm + windowDays 分组统计（通知用），支持窗口过滤。
     *
     * @param windows 逗号分隔的窗口列表，如 "2d,3d,4d,5d"；null 或空表示返回全部窗口
     * @return 包含 batchId / screenDate / results 的 Map
     */
    Map<String, Object> getLatestNotificationGrouped(String windows);
}
