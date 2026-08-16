package com.stock.invest.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

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
import com.stock.invest.entity.FieldCapability;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.exception.StockDataException;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
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

    private static final ZoneId AMERICA_NY = ZoneId.of("America/New_York");


    private static final int MAX_SYMBOLS_PER_RUN = 200;
    private static final int MAX_LOOKBACK_DAYS = 7;
    private static final int MAX_MISSING_DATES_PER_SYMBOL = 5;

    /** P1-5：账户级错误（权限/配额）触发源级熔断的冷却时长 */
    private static final long SOURCE_COOLDOWN_MILLIS = 30 * 60 * 1000L;

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
    private final List<DataSourceStrategy> dataSources;
    private final GapFillProperties gapFillProperties;
    private final DataFillProgressService dataFillProgressService;
    private final RetryProgressService retryProgressService;
    private final TradingCalendarDbService tradingCalendarDbService;
    private final StockDataSourcePriorityService stockDataSourcePriorityService;
    private final SymbolBlacklistService symbolBlacklistService;
    private final FieldCapabilityService fieldCapabilityService;

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
        this.dataSources = dataSources;
        this.gapFillProperties = gapFillProperties;
        this.dataFillProgressService = dataFillProgressService;
        this.retryProgressService = retryProgressService;
        this.tradingCalendarDbService = tradingCalendarDbService;
        this.stockDataSourcePriorityService = stockDataSourcePriorityService;
        this.symbolBlacklistService = symbolBlacklistService;
        this.fieldCapabilityService = fieldCapabilityService;
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

    private GapFetcher.FetchResult fetchAndPersist(String symbol, LocalDate tradeDate) {
        return gapFetcher.fetchAndPersist(symbol, tradeDate);
    }

    private StockDailyBar persist(String symbol, LocalDate tradeDate, KLineIterator item, String source) {
        Optional<StockDailyBar> existing = stockDailyBarRepository.findBySymbolAndTradeDate(symbol, tradeDate);
        StockDailyBar bar;
        if (existing.isPresent()) {
            bar = existing.get();
        } else {
            bar = new StockDailyBar();
            bar.setSymbol(symbol);
            bar.setTradeDate(tradeDate);
        }
        bar.setOpenPrice(item.getOpen());
        bar.setHighPrice(item.getHigh());
        bar.setLowPrice(item.getLow());
        bar.setClosePrice(item.getClose());
        bar.setVolume(item.getVolume());
        // R2 P3-4：透传路径 —— 数据源侧原始值原样透传，落库时由 DECIMAL(12,4) 隐式四舍五入归一；
        // 计算型 changePercent（Tiger/Tiingo/盘后）已在计算点 setScale(4, HALF_UP)
        bar.setChangePercent(item.getChangePercent());
        bar.setAfterHours(item.getAfterHours());
        bar.setAfterHoursChangePercent(item.getAfterHoursChangePercent());
        // === fallback: 数据源未提供 changePercent 时自动计算（隔日涨跌幅） ===
        // yfinance/tiingo 的 K 线响应不含 changePercent 字段（反序列化后为 null），
        // 若此处不补算，落库后 change_percent 为 NULL（历史 532/4100、68/500 空）。
        // 恢复 29ef6c5 设计：null 时查前一个交易日 close 计算 (curr-prev)/prev*100。
        if (bar.getChangePercent() == null && bar.getClosePrice() != null
                && bar.getClosePrice().compareTo(java.math.BigDecimal.ZERO) != 0) {
            final java.math.BigDecimal currClose = bar.getClosePrice();
            stockDailyBarRepository
                    .findTopBySymbolAndTradeDateBeforeOrderByTradeDateDesc(symbol, tradeDate)
                    .ifPresent(prev -> {
                        java.math.BigDecimal prevClose = prev.getClosePrice();
                        if (prevClose != null && prevClose.compareTo(java.math.BigDecimal.ZERO) != 0) {
                            java.math.BigDecimal pct = currClose.subtract(prevClose)
                                    .divide(prevClose, 8, java.math.RoundingMode.HALF_UP)
                                    .multiply(java.math.BigDecimal.valueOf(100))
                                    .setScale(4, java.math.RoundingMode.HALF_UP);
                            bar.setChangePercent(pct);
                        }
                    });
        }
        bar.setSource(source);
        // 字段缺失标记（2026-08-14）：按能力表计算缺失字段集 + 增补状态
        applyMissingFieldsMark(bar);
        // P1-2：单次持久化独立事务，失败不回滚整批
        runInTx(() -> stockDailyBarRepository.save(bar));
        return bar;
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

    /** 价格字段缺失判定：NULL 或 0（美股价格恒 > 0） */
    private static boolean isMissingPrice(java.math.BigDecimal v) {
        return v == null || v.compareTo(java.math.BigDecimal.ZERO) == 0;
    }

    /** 成交量缺失判定：NULL 或 0（无成交异常） */
    private static boolean isMissingVolume(Long v) {
        return v == null || v <= 0L;
    }

    /**
     * 对 Tiger 截图数据源，尝试拉取盘后价并合并到已保存的日 K 线。
     */
    private void mergeAfterHoursIfAvailable(String symbol, LocalDate tradeDate, StockDailyBar bar,
                                            DataSourceStrategy source) {
        if (!supportsAfterHoursMerge(source)) {
            return;
        }
        try {
            KLineData ahData = source.getAfterHoursKLineDataByDateRange(symbol, tradeDate);
            if (isKLineDataEmpty(ahData)) {
                return;
            }
            for (KLineIterator item : ahData.getItems()) {
                LocalDate itemDate = item.getTimeString() != null && !item.getTimeString().isEmpty()
                        ? LocalDate.parse(item.getTimeString())
                        : epochMillisToLocalDate(item.getTime());
                if (!itemDate.equals(tradeDate)) {
                    continue;
                }
                java.math.BigDecimal ahClose = item.getClose();
                bar.setAfterHours(ahClose);
                java.math.BigDecimal regClose = bar.getClosePrice();
                if (regClose != null && regClose.compareTo(java.math.BigDecimal.ZERO) != 0) {
                    bar.setAfterHoursChangePercent(ahClose.subtract(regClose)
                            .divide(regClose, 8, java.math.RoundingMode.HALF_UP)
                            .multiply(java.math.BigDecimal.valueOf(100))
                            .setScale(4, java.math.RoundingMode.HALF_UP));
                }
                // 盘后合并成功后清除盘后相关缺失标记（2026-08-14）
                clearMissingFields(bar, F_AFTER_HOURS, F_AFTER_HOURS_CHANGE_PERCENT);
                runInTx(() -> stockDailyBarRepository.save(bar));
                return;
            }
        } catch (Exception e) {
            log.warn("[DataGapFiller] mergeAfterHours: failed symbol={}, date={}, error={}",
                    symbol, tradeDate, e.getMessage());
        }
    }

    private boolean supportsAfterHoursMerge(DataSourceStrategy source) {
        // 数据驱动：查能力表该源是否支持盘后字段（yfinance/tigeropen/tiger_snap 支持；
        // tiingo/twelvedata 不支持）。2026-08-14 由硬编码改为查表。
        return fieldCapabilityService.isMarkable(source.getSourceName(), F_AFTER_HOURS);
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

    /** 字段增补窗口起点（美东今天 - FILL_WINDOW_CALENDAR_DAYS） */
    private LocalDate fillWindowStart() {
        return ZonedDateTime.now(AMERICA_NY).toLocalDate().minusDays(FILL_WINDOW_CALENDAR_DAYS);
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

    /**
     * 用日 K item 更新 bar 的缺失行情字段（只更新缺失项，不覆盖已有值）。
     * 返回是否发生了更新。
     */
    private boolean applyKlineItemToBar(StockDailyBar bar, KLineIterator item, List<String> missing) {
        boolean updated = false;
        if (missing.contains(F_OPEN) && item.getOpen() != null) {
            bar.setOpenPrice(item.getOpen());
            updated = true;
        }
        if (missing.contains(F_HIGH) && item.getHigh() != null) {
            bar.setHighPrice(item.getHigh());
            updated = true;
        }
        if (missing.contains(F_LOW) && item.getLow() != null) {
            bar.setLowPrice(item.getLow());
            updated = true;
        }
        if (missing.contains(F_CLOSE) && item.getClose() != null) {
            bar.setClosePrice(item.getClose());
            updated = true;
        }
        if (missing.contains(F_VOLUME) && item.getVolume() > 0) {
            bar.setVolume(item.getVolume());
            updated = true;
        }
        if (missing.contains(F_CHANGE_PERCENT)) {
            // 优先用脚本直算值（两日窗口相邻交易日），null 才 Java DB 兜底（用户原则：源直取优先）
            if (item.getChangePercent() != null) {
                bar.setChangePercent(item.getChangePercent());
                updated = true;
            } else if (recalcChangePercent(bar)) {
                updated = true;
            }
        }
        // 已补上的字段从缺失集移除
        List<String> remaining = parseMissingFields(bar.getMissingFields());
        if (remaining.isEmpty()) {
            return updated;
        }
        remaining.removeAll(missing.stream()
                .filter(f -> {
                    switch (f) {
                        case F_OPEN: return bar.getOpenPrice() != null;
                        case F_HIGH: return bar.getHighPrice() != null;
                        case F_LOW: return bar.getLowPrice() != null;
                        case F_CLOSE: return bar.getClosePrice() != null;
                        case F_VOLUME: return bar.getVolume() != null && bar.getVolume() > 0;
                        case F_CHANGE_PERCENT: return bar.getChangePercent() != null;
                        default: return false;
                    }
                }).toList());
        if (remaining.size() != parseMissingFields(bar.getMissingFields()).size()) {
            bar.setMissingFields(remaining.isEmpty() ? null : String.join(",", remaining));
        }
        return updated;
    }

    /**
     * 重算 change_percent：(今日收盘 - 前一日收盘) / 前一日收盘 * 100。
     * 前一日数据缺失时返回 false（保留缺失标记，等前一日补上后再算）。
     */
    private boolean recalcChangePercent(StockDailyBar bar) {
        if (bar.getClosePrice() == null
                || bar.getClosePrice().compareTo(java.math.BigDecimal.ZERO) == 0) {
            return false;
        }
        Optional<StockDailyBar> prev = stockDailyBarRepository
                .findTopBySymbolAndTradeDateBeforeOrderByTradeDateDesc(bar.getSymbol(), bar.getTradeDate());
        if (prev.isEmpty() || prev.get().getClosePrice() == null
                || prev.get().getClosePrice().compareTo(java.math.BigDecimal.ZERO) == 0) {
            return false;
        }
        java.math.BigDecimal currClose = bar.getClosePrice();
        java.math.BigDecimal prevClose = prev.get().getClosePrice();
        java.math.BigDecimal pct = currClose.subtract(prevClose)
                .divide(prevClose, 8, java.math.RoundingMode.HALF_UP)
                .multiply(java.math.BigDecimal.valueOf(100))
                .setScale(4, java.math.RoundingMode.HALF_UP);
        bar.setChangePercent(pct);
        return true;
    }

    /** 从 KLineData 中找指定交易日的 item（优先 timeString，回退毫秒转美东日期） */
    private KLineIterator findItemByDate(KLineData data, LocalDate tradeDate) {
        if (data == null || data.getItems() == null) {
            return null;
        }
        for (KLineIterator item : data.getItems()) {
            LocalDate itemDate = item.getTimeString() != null && !item.getTimeString().isEmpty()
                    ? LocalDate.parse(item.getTimeString())
                    : epochMillisToLocalDate(item.getTime());
            if (itemDate.equals(tradeDate)) {
                return item;
            }
        }
        return null;
    }

    /**
     * 从缺失标记中移除指定字段；清空后置 CONFIRMED。
     */
    void clearMissingFields(StockDailyBar bar, String... fields) {
        List<String> missing = parseMissingFields(bar.getMissingFields());
        if (missing.isEmpty()) {
            return;
        }
        Set<String> toClear = new HashSet<>(java.util.Arrays.asList(fields));
        missing.removeIf(toClear::contains);
        if (missing.isEmpty()) {
            bar.setMissingFields(null);
            bar.setFieldFillStatus(STATUS_CONFIRMED);
        } else {
            bar.setMissingFields(String.join(",", missing));
        }
    }

    private static List<String> parseMissingFields(String s) {
        if (s == null || s.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(java.util.Arrays.asList(s.split(",")));
    }

    /**
     * 构建补缺查询序列：yfinance 无条件第一 → source（若 != yfinance 且 != tiger_snap）。
     * tiger_snap 特例：只查 yfinance。不再 fallback 到第三/第四源（用户设计 2026-08-14）。
     */
    private List<DataSourceStrategy> buildQuerySequence(String sourceName) {
        List<DataSourceStrategy> seq = new ArrayList<>();
        for (DataSourceStrategy d : dataSources) {
            if ("yfinance".equals(d.getSourceName()) && d.isAvailable()) {
                seq.add(d);
                break;
            }
        }
        if (!"yfinance".equals(sourceName) && !"tiger_snap".equals(sourceName)) {
            for (DataSourceStrategy d : dataSources) {
                if (sourceName.equals(d.getSourceName()) && d.isAvailable()) {
                    seq.add(d);
                    break;
                }
            }
        }
        return seq;
    }

    /**
     * change_percent 兜底计算：用 DB 前一交易日 close（真实前交易日序列）。
     * 源无当日 K 线时涨跌幅仍可计算（已有 close + 前交易日 close）；返回 null 表示算不出。
     */
    private java.math.BigDecimal calcChangePercentFromPrevClose(
            String symbol, LocalDate tradeDate, java.math.BigDecimal currClose) {
        if (currClose == null || currClose.compareTo(java.math.BigDecimal.ZERO) == 0) {
            return null;
        }
        final java.math.BigDecimal[] result = { null };
        stockDailyBarRepository
                .findTopBySymbolAndTradeDateBeforeOrderByTradeDateDesc(symbol, tradeDate)
                .ifPresent(prev -> {
                    java.math.BigDecimal prevClose = prev.getClosePrice();
                    if (prevClose != null && prevClose.compareTo(java.math.BigDecimal.ZERO) != 0) {
                        result[0] = currClose.subtract(prevClose)
                                .divide(prevClose, 8, java.math.RoundingMode.HALF_UP)
                                .multiply(java.math.BigDecimal.valueOf(100))
                                .setScale(4, java.math.RoundingMode.HALF_UP);
                    }
                });
        return result[0];
    }

    /** 按源名找数据源；找不到时回退到第一个可用源（截图等无 bean 源的数据用其他源补，用户设计）。 */
    private DataSourceStrategy findDataSource(String name) {
        if (name != null) {
            for (DataSourceStrategy ds : dataSources) {
                if (name.equals(ds.getSourceName()) && ds.isAvailable()) {
                    return ds;
                }
            }
        }
        return dataSources.stream()
                .filter(DataSourceStrategy::isAvailable)
                .findFirst()
                .orElse(null);
    }

    private void createRetryTask(String symbol, LocalDate tradeDate, String error) {
        LocalDate today = ZonedDateTime.now(AMERICA_NY).toLocalDate();
        Optional<DataFillTask> existing = dataFillTaskRepository.findBySymbolAndTradeDate(symbol, tradeDate);
        if (existing.isPresent()) {
            DataFillTask task = existing.get();
            // R2 P2-1：retryCount 递增走 JPQL 原子自增（不校验版本，杜绝乐观锁冲突丢更新）
            runInTx(() -> dataFillTaskRepository.incrementRetryCounters(task.getId(), "retrying", error));
            task.setRetryCount(task.getRetryCount() + 1);
            task.setStatus("retrying");
            task.setLastError(error);
            log.info("[DataGapFiller] createRetryTask: updated symbol={}, date={}, retryCount={}, error={}",
                    symbol, tradeDate, task.getRetryCount(), error);
            return;
        }
        DataFillTask task = new DataFillTask();
        task.setSymbol(symbol);
        task.setTradeDate(tradeDate);
        task.setStatus("retrying");
        task.setRetryCount(1);
        task.setRetryDate(today);
        task.setDayCount(1);
        task.setLastError(error);
        saveTaskWithOptimisticLock(task);
        log.info("[DataGapFiller] createRetryTask: created symbol={}, date={}, error={}",
                symbol, tradeDate, error);
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

    private void processRetryingTasksInternal() {
        retryTaskProcessor.processRetryingTasksInternal();
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

    private static LocalDate epochMillisToLocalDate(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis)
                .atZone(AMERICA_NY)
                .toLocalDate();
    }

    // ---- Fallback chain ----

    @FunctionalInterface
    private interface KLineFetcher {
        KLineData fetch(String symbol, LocalDate tradeDate) throws Exception;
    }

    private record FallbackSource(String name, KLineFetcher fetcher, DataSourceStrategy ds) {}

    /**
     * 构建某支股票专属的 fallback 链。
     * <ul>
     *   <li>有历史成功记录 → 按 last_success_time DESC 优先</li>
     *   <li>无历史记录 → 使用默认顺序 yfinance → twelvedata → tiingo → tigeropen → tiger</li>
     *   <li>Tiger 截图数据源不参与优先级排序</li>
     * </ul>
     */
        private List<FallbackSource> buildFallbackChainForSymbol(String symbol) {
        List<String> priorityOrder;
        if (symbol != null) {
            priorityOrder = stockDataSourcePriorityService.getPriorityList(symbol);
        } else {
            priorityOrder = StockDataSourcePriorityService.DEFAULT_DATA_SOURCE_ORDER;
        }

        // 按优先顺序构建可用的数据源链
        Map<String, Integer> priorityMap = new java.util.HashMap<>();
        for (int i = 0; i < priorityOrder.size(); i++) {
            priorityMap.put(priorityOrder.get(i), i);
        }

        return dataSources.stream()
                .filter(Objects::nonNull)
                .filter(DataSourceStrategy::isAvailable)
                // P1-5：熔断冷却期内的源直接跳过（账户级错误后 30 分钟内不再打该源）
                .filter(ds -> !isSourceCooledDown(ds.getSourceName()))
                .sorted(Comparator.comparingInt(s -> priorityMap.getOrDefault(s.getSourceName(), 99)))
                .map(ds -> new FallbackSource(ds.getSourceName(),
                        (sym, date) -> ds.getDailyKLineDataByDateRange(sym, date), ds))
                .collect(Collectors.toList());
    }

    /**
     * 数据源是否处于熔断冷却期（P1-5）。
     */
    private boolean isSourceCooledDown(String sourceName) {
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
     * 判断异常消息是否明确匹配 not-found 关键词（P1-3 路径 B 白名单）。
     * <p>仅在数据源未抛带分类的 {@link StockDataException} 时兜底使用；成功但空结果一律不计黑名单。</p>
     * <p>R2 P3-2：原 klineData 参数（路径 A 空结果判定移除后遗留）已删除，仅保留 errorMessage。</p>
     */
    private boolean isNotFoundError(String errorMessage) {
        if (errorMessage != null && !errorMessage.isEmpty()) {
            return StockDataException.isNotFoundMessage(errorMessage.toLowerCase());
        }
        return false;
    }

    /**
     * 检查 KLineData 是否返回了空结果（空列表）。
     */
    private boolean isKLineDataEmpty(KLineData klineData) {
        return klineData == null
                || klineData.getItems() == null
                || klineData.getItems().isEmpty();
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

    /**
     * R2 P2-1：乐观锁兜底保存 —— 终态类保存（status/lastError/retryDate）冲突时
     * 重读 + 重放一次：终态字段以本次意图为准直接覆盖；若本次携带日计数重置
     * （retryDate 变更 → dayCount 置 0）则一并重放。再次冲突 → error + 原子计数，可观测。
     * <p>计数递增（retryCount/dayCount）已改走 JPQL 原子自增（incrementRetryCounters），
     * 不再经过本方法，从根上消除计数丢失。</p>
     */
    private final java.util.concurrent.atomic.AtomicInteger optimisticLockConflicts =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private void saveTaskWithOptimisticLock(DataFillTask task) {
        try {
            runInTx(() -> dataFillTaskRepository.save(task));
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            DataFillTask latest = dataFillTaskRepository.findById(task.getId()).orElse(null);
            if (latest == null) {
                log.error("[DataGapFiller] optimistic lock conflict on taskId={} but row not found, update dropped: {}",
                        task.getId(), e.getMessage());
                return;
            }
            log.warn("[DataGapFiller] optimistic lock conflict on taskId={}, re-read and replay once: {}",
                    task.getId(), e.getMessage());
            // 终态字段以本次意图为准直接覆盖（status/lastError/retryDate）
            latest.setStatus(task.getStatus());
            latest.setLastError(task.getLastError());
            latest.setRetryDate(task.getRetryDate());
            // 日计数重置意图（retryDate 变更 → dayCount 置 0）一并重放
            if (!java.util.Objects.equals(task.getRetryDate(), latest.getRetryDate())) {
                latest.setDayCount(task.getDayCount());
            }
            try {
                runInTx(() -> dataFillTaskRepository.save(latest));
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e2) {
                optimisticLockConflicts.incrementAndGet();
                log.error("[DataGapFiller] optimistic lock conflict on taskId={} after replay, update dropped (conflictTotal={}): {}",
                        task.getId(), optimisticLockConflicts.get(), e2.getMessage());
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
