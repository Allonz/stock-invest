package com.stock.invest.service;

import java.time.LocalDate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.stock.invest.entity.DataFillTask;

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

    /**
     * 补缺/重试批次是否正在运行（P1-2 互斥状态，供管理端点识别"已在运行"）。
     */
    boolean isRunning();

    // ---- DataFillTask 查询封装（供 AdminController 使用，避免 Controller 直接注入 Repository） ----

    /**
     * 多条件筛选分页查询补缺任务。
     */
    Page<DataFillTask> findFillTasks(String symbol, LocalDate tradeDate, String status, Pageable pageable);

    /**
     * 补缺任务总数。
     */
    long countFillTasks();

    /**
     * 按状态统计补缺任务数量。
     */
    long countFillTasksByStatus(String status);
}
