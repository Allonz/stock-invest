package com.stock.invest.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.stock.invest.config.GapFillProperties;
import com.stock.invest.entity.DataFillTask;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.repository.DataFillTaskRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.DataFillProgressService;
import com.stock.invest.service.DataGapFillerService;
import com.stock.invest.service.DataSourceStrategy;
import com.stock.invest.service.FieldCapabilityService;
import com.stock.invest.service.RetryProgressService;
import com.stock.invest.service.StockDataSourcePriorityService;
import com.stock.invest.service.SymbolBlacklistService;
import com.stock.invest.service.TradingCalendarDbService;

/**
 * 数据补缺服务 —— 通过 fallback 链补全缺失的日 K 线数据。
 * <p>
 * Fallback 链通过自动收集所有 {@link com.stock.invest.service.DataSourceStrategy} bean 构建，
 * 按优先级（yfinance -> twelvedata -> tiingo -> tigeropen -> tiger）排序，
 * 过滤掉不可用的数据源。
 * </p>
 * <p>
 * 在查找缺失日期时会通过 {@link TradingCalendarDbService} 查询交易日历，
 * 跳过非开盘日（节假日、周末），避免不必要的 API 调用。
 * </p>
 */
@Service
public class DataGapFillerServiceImpl implements DataGapFillerService {

    private static final Logger log = LoggerFactory.getLogger(DataGapFillerServiceImpl.class);

    private static final int MAX_SYMBOLS_PER_RUN = 200;
    private static final int MAX_LOOKBACK_DAYS = 7;

    /** 字段增补单次上限：防止存量 PENDING 过多时一次性打爆外部 API（2026-08-14） */
    static final int MAX_FILL_FIELDS_PER_RUN = 100;

    /** 字段增补窗口：只补最近 30 个交易日内的记录（≈45 日历日，含周末/节假日宽松覆盖）。
     *  用户 2026-08-14：历史太久的不补（yfinance 盘后分钟数据 30 天窗口外不可得）。 */
    static final int FILL_WINDOW_CALENDAR_DAYS = 45;

    // ---- 字段增补（2026-08-14）----
    /** 字段名常量 */
    static final String F_OPEN = "open_price";
    static final String F_HIGH = "high_price";
    static final String F_LOW = "low_price";
    static final String F_CLOSE = "close_price";
    static final String F_VOLUME = "volume";
    static final String F_CHANGE_PERCENT = "change_percent";
    static final String F_AFTER_HOURS = "after_hours";
    static final String F_AFTER_HOURS_CHANGE_PERCENT = "after_hours_change_percent";
    /** 字段增补状态 */
    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_CONFIRMED = "CONFIRMED";

    private final StockDailyBarRepository stockDailyBarRepository;
    private final DataFillTaskRepository dataFillTaskRepository;
    private final GapFillProperties gapFillProperties;
    private final DataFillProgressService dataFillProgressService;
    private final TradingCalendarDbService tradingCalendarDbService;
    private final SymbolBlacklistService symbolBlacklistService;

    private final FallbackChainBuilder fallbackChainBuilder;
    private final MissingFieldFiller missingFieldFiller;
    private final GapFetcher gapFetcher;
    private final RetryTaskProcessor retryTaskProcessor;

    /** P1-2：批次运行互斥 —— 定时、REST、MCP 三路共用同一 Service 实例，天然互斥 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** P1-5：数据源熔断冷却表，key=数据源名，value=冷却截止 epochMillis */
    private final Map<String, Long> sourceCooldownUntil = new ConcurrentHashMap<>();

    /** P1-2：事务边界收缩 —— 单次持久化用独立事务，失败不回滚整批 */
    private final TransactionTemplate transactionTemplate;

    public DataGapFillerServiceImpl(
            StockDailyBarRepository stockDailyBarRepository,
            DataFillTaskRepository dataFillTaskRepository,
            List<DataSourceStrategy> dataSources,
            GapFillProperties gapFillProperties,
            DataFillProgressService dataFillProgressService,
            RetryProgressService retryProgressService,
            TradingCalendarDbService tradingCalendarDbService,
            StockDataSourcePriorityService stockDataSourcePriorityService,
            SymbolBlacklistService symbolBlacklistService,
            PlatformTransactionManager transactionManager,
            FieldCapabilityService fieldCapabilityService) {
        this.stockDailyBarRepository = stockDailyBarRepository;
        this.dataFillTaskRepository = dataFillTaskRepository;
        this.gapFillProperties = gapFillProperties;
        this.dataFillProgressService = dataFillProgressService;
        this.tradingCalendarDbService = tradingCalendarDbService;
        this.symbolBlacklistService = symbolBlacklistService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.fallbackChainBuilder = new FallbackChainBuilder(
                dataSources, stockDataSourcePriorityService, sourceCooldownUntil);
        this.missingFieldFiller = new MissingFieldFiller(
                stockDailyBarRepository, dataSources, fieldCapabilityService, transactionManager);
        this.gapFetcher = new GapFetcher(
                stockDailyBarRepository,
                dataFillTaskRepository,
                stockDataSourcePriorityService,
                symbolBlacklistService,
                missingFieldFiller,
                fallbackChainBuilder,
                transactionManager);
        this.retryTaskProcessor = new RetryTaskProcessor(
                dataFillTaskRepository,
                symbolBlacklistService,
                retryProgressService,
                gapFetcher,
                transactionManager);
    }

    @Override
    public boolean fillGaps() {
        // P1-2：运行互斥 —— 已有一份补缺在跑则直接跳过，避免定时/手动/MCP 并发重复补缺、双倍配额
        if (!running.compareAndSet(false, true)) {
            log.warn("[DataGapFiller] fillGaps: already running, skip concurrent trigger");
            return false;
        }
        Instant batchStart = Instant.now();
        log.info("[DataGapFiller] fillGaps: === BEGIN ===");
        try {
            fillGapsInternal(batchStart);
            return true;
        } finally {
            running.set(false);
        }
    }

    private void fillGapsInternal(Instant batchStart) {
        // 获取进度对象，如果没有（scheduler 触发）则忽略
        DataFillProgressService.FillProgress progress = dataFillProgressService.getProgress();

        List<String> allSymbols = stockDailyBarRepository.findAllSymbols();
        if (allSymbols.isEmpty()) {
            log.info("[DataGapFiller] fillGaps: no symbols found in stock_daily_bars");
            if (progress != null) {
                progress.setStage("COMPLETED");
                progress.setRunning(false);
            }
            return;
        }
        Set<String> blacklistedSymbols = new HashSet<>(symbolBlacklistService.getBlacklistedSymbols());
        List<String> filteredSymbols = allSymbols.stream()
                .filter(s -> !blacklistedSymbols.contains(s))
                .collect(java.util.stream.Collectors.toList());
        if (!blacklistedSymbols.isEmpty()) {
            log.info("[DataGapFiller] [blacklist] filtered symbols: {}, count={}", blacklistedSymbols, blacklistedSymbols.size());
            // 清理已入黑 symbol 的 retry 任务（独立事务，避免整批回滚）
            for (String s : blacklistedSymbols) {
                final String symbol = s;
                runInTx(() -> dataFillTaskRepository.updateStatusBySymbolAndStatusIn(
                        symbol,
                        java.util.List.of("pending", "retrying"),
                        "stopped",
                        "symbol is blacklisted, stop retry"
                ));
            }
        }
        log.info("[DataGapFiller] fillGaps: scanning totalSymbols={}, afterBlacklistFilter={}, skipped={}",
                allSymbols.size(), filteredSymbols.size(), allSymbols.size() - filteredSymbols.size());

        if (progress != null) {
            progress.setTotalSymbols(allSymbols.size());
            progress.setStage("SCANNING");
        }

        int processed = 0;
        int totalGapsFound = 0;
        int totalFilled = 0;
        int totalFailed = 0;

        for (String symbol : filteredSymbols) {
            if (processed >= MAX_SYMBOLS_PER_RUN) {
                log.info("[DataGapFiller] fillGaps: reached max symbols per run ({})", MAX_SYMBOLS_PER_RUN);
                break;
            }

            // 更新进度：切换到 FILLING 阶段
            if (progress != null) {
                progress.setStage("FILLING");
            }

            // P1-2：单 symbol 失败不中断批次
            FillResult result;
            try {
                result = fillGapsForSymbol(symbol, progress);
            } catch (Exception e) {
                log.error("[DataGapFiller] fillGaps: symbol={} failed, continue batch — error={}", symbol, e.getMessage(), e);
                result = FillResult.failedSymbol();
            }
            processed += result.symbolsProcessed;
            totalGapsFound += result.gapsFound;
            totalFilled += result.filled;
            totalFailed += result.failed;

            // 更新进度
            if (progress != null) {
                progress.incrementProcessedSymbols();
                progress.addGapsFound(result.gapsFound);
            }
        }

        // 字段增补：发现 + 增补已有记录的缺失字段（2026-08-14，开关 gap-fill.field-fill-enabled）
        int discovered = 0;
        int filledFields = 0;
        if (gapFillProperties.isFieldFillEnabled()) {
            try {
                discovered = missingFieldFiller.discoverMissingFields();
                filledFields = missingFieldFiller.fillMissingFields();
            } catch (Exception e) {
                log.error("[DataGapFiller] fillMissingFields failed, continue — error={}", e.getMessage(), e);
            }
            log.info("[DataGapFiller] fillMissingFields: discovered={}, filledFields={}", discovered, filledFields);
        }

        log.info("[DataGapFiller] fillGaps: === COMPLETED === " +
                "totalSymbols={}, gapsFound={}, filled={}, failed={}, elapsedMs={}",
                allSymbols.size(), totalGapsFound, totalFilled, totalFailed,
                Duration.between(batchStart, Instant.now()).toMillis());

        if (progress != null) {
            progress.setStage("COMPLETED");
            progress.setRunning(false);
        }
    }

    private FillResult fillGapsForSymbol(String symbol, DataFillProgressService.FillProgress progress) {
        // 查询结果按 tradeDate DESC（最新在前）
        List<StockDailyBar> bars = stockDailyBarRepository
                .findBySymbolOrderByTradeDateDesc(symbol, PageRequest.of(0, MAX_LOOKBACK_DAYS));
        if (bars.isEmpty()) {
            return FillResult.empty();
        }

        StockDailyBar latest = bars.get(0);
        if (latest.getClosePrice() != null
                && latest.getClosePrice().compareTo(gapFillProperties.getMinPriceThreshold()) > 0) {
            return FillResult.empty();
        }

        List<LocalDate> missingDates = findMissingTradeDates(bars, tradingCalendarDbService);
        if (missingDates.isEmpty()) {
            return FillResult.empty();
        }

        String msg = String.format("fillGaps: symbol=%s gapsFound=%d, dates=%s, latestClose=%s",
                symbol, missingDates.size(), missingDates, latest.getClosePrice());
        String sep = "=".repeat(msg.length());
        log.info("");
        log.info("[DataGapFiller] {}", sep);
        log.info("[DataGapFiller] {}", msg);
        log.info("[DataGapFiller] {}", sep);

        int filled = 0;
        int failed = 0;
        for (LocalDate date : missingDates) {
            // 进入 fetchAndPersist 会打印分隔线和补缺信息
            GapFetcher.FetchResult result = gapFetcher.fetchAndPersist(symbol, date);
            if (result.succeeded()) {
                filled++;
                if (progress != null) {
                    progress.incrementFilled();
                }
            } else {
                log.warn("[DataGapFiller] fillGaps: all sources exhausted symbol={}, date={}", symbol, date);
                if (!result.skipRetry()) {
                    retryTaskProcessor.createRetryTask(symbol, date, "all fallbacks failed");
                }
                failed++;
                if (progress != null) {
                    progress.incrementFailed();
                }
            }
        }
        return new FillResult(1, missingDates.size(), filled, failed);
    }

    /**
     * 计算 [max(oldestBar, today-30d), today(NY)] 范围内的缺失交易日。
     * <p>P1-4：方法内部显式按 tradeDate ASC 排序，不依赖调用方的传入顺序
     * （此前依赖调用方传 DESC 序，而调用方经 {@code Collections.reverse} 实际传 ASC，
     * 导致 newest/oldest 取反、窗口内部历史空洞永不发现）。</p>
     * <p>通过 TradingCalendarDbService 查询交易日历，跳过非开盘日（节假日）。</p>
     */
        static List<LocalDate> findMissingTradeDates(List<StockDailyBar> existingBars,
                                                  TradingCalendarDbService calendarDbService) {
        return GapDateScanner.findMissingTradeDates(existingBars, calendarDbService);
    }

    /**
     * 计算并设置记录的字段缺失标记（missingFields + fieldFillStatus）。
     * <p>
     * 规则（由能力表驱动）：字段缺失（NULL/0，按字段类型）且 markable(source, field) → 加入缺失集。
     * 有缺失 → PENDING；无缺失 → CONFIRMED。
     * </p>
     */
    void applyMissingFieldsMark(StockDailyBar bar) {
        missingFieldFiller.applyMissingFieldsMark(bar);
    }

    // ==================== 字段增补：发现 + 增补（2026-08-14） ====================

    /**
     * 发现阶段：扫描 field_fill_status 为 NULL 的记录（存量），按能力表计算缺失字段并标记。
     * 仅最近 {@link #FILL_WINDOW_CALENDAR_DAYS} 天内（≈30 交易日）的记录参与增补标记；
     * 超窗记录直接 CONFIRMED（终态，不补，用户 2026-08-14）。
     *
     * @return 处理的记录数
     */
    int discoverMissingFields() {
        return missingFieldFiller.discoverMissingFields();
    }

    /**
     * 增补阶段：对 field_fill_status=PENDING 的记录逐条增补缺失字段。
     * <ul>
     *   <li>增补成功 / 源确认无值 → 清标记 + CONFIRMED</li>
     *   <li>瞬态失败 → 保留 PENDING + 缺失标记（下次再试）</li>
     * </ul>
     *
     * @return 本次处理完成的记录数
     */
    int fillMissingFields() {
        return missingFieldFiller.fillMissingFields();
    }

    /**
     * 单条 PENDING 记录字段增补。返回 true = 本次处理完成（成功或确认无值，状态置 CONFIRMED）；
     * false = 存在瞬态失败，保留 PENDING 待下次。
     */
    boolean fillMissingFieldsForBar(StockDailyBar bar) {
        return missingFieldFiller.fillMissingFieldsForBar(bar);
    }

    @Override
    public void processRetryingTasks() {
        // P1-2：运行互斥 —— 与 fillGaps 共用同一把锁，补缺/重试不得并发
        if (!running.compareAndSet(false, true)) {
            log.warn("[DataGapFiller] processRetryingTasks: already running, skip concurrent trigger");
            return;
        }
        try {
            retryTaskProcessor.processRetryingTasksInternal();
        } finally {
            running.set(false);
        }
    }

    @Override
    public Page<DataFillTask> findFillTasks(String symbol, LocalDate tradeDate, String status, Pageable pageable) {
        return dataFillTaskRepository.findByFilters(symbol, tradeDate, status, pageable);
    }

    @Override
    public long countFillTasks() {
        return dataFillTaskRepository.count();
    }

    @Override
    public long countFillTasksByStatus(String status) {
        return dataFillTaskRepository.countByStatus(status);
    }

    // ---- Internal result holder ----

    private record FillResult(int symbolsProcessed, int gapsFound, int filled, int failed) {
        static FillResult empty() {
            return new FillResult(0, 0, 0, 0);
        }

        static FillResult failedSymbol() {
            return new FillResult(1, 0, 0, 1);
        }
    }

    // ---- P1-2 辅助：独立事务 + 互斥状态 ----

    /**
     * 以独立事务执行单次持久化，失败只回滚当前操作，不影响批次其他已落库数据。
     */
    private void runInTx(Runnable action) {
        transactionTemplate.executeWithoutResult(status -> action.run());
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
