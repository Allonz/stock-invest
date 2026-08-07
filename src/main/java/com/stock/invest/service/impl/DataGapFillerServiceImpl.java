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
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.exception.StockDataException;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.repository.DataFillTaskRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.DataFillProgressService;
import com.stock.invest.service.DataGapFillerService;
import com.stock.invest.service.DataSourceStrategy;
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
    private static final int MAX_LOOKBACK_DAYS = 30;
    private static final int MAX_MISSING_DATES_PER_SYMBOL = 5;

    /** P1-5：账户级错误（权限/配额）触发源级熔断的冷却时长 */
    private static final long SOURCE_COOLDOWN_MILLIS = 30 * 60 * 1000L;

    private final StockDailyBarRepository stockDailyBarRepository;
    private final DataFillTaskRepository dataFillTaskRepository;
    private final List<DataSourceStrategy> dataSources;
    private final GapFillProperties gapFillProperties;
    private final DataFillProgressService dataFillProgressService;
    private final RetryProgressService retryProgressService;
    private final TradingCalendarDbService tradingCalendarDbService;
    private final StockDataSourcePriorityService stockDataSourcePriorityService;
    private final SymbolBlacklistService symbolBlacklistService;

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
            PlatformTransactionManager transactionManager) {
        this.stockDailyBarRepository = stockDailyBarRepository;
        this.dataFillTaskRepository = dataFillTaskRepository;
        this.dataSources = dataSources;
        this.gapFillProperties = gapFillProperties;
        this.dataFillProgressService = dataFillProgressService;
        this.retryProgressService = retryProgressService;
        this.tradingCalendarDbService = tradingCalendarDbService;
        this.stockDataSourcePriorityService = stockDataSourcePriorityService;
        this.symbolBlacklistService = symbolBlacklistService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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
            FetchResult result = fetchAndPersist(symbol, date);
            if (result.success()) {
                filled++;
                if (progress != null) {
                    progress.incrementFilled();
                }
            } else {
                log.warn("[DataGapFiller] fillGaps: all sources exhausted symbol={}, date={}", symbol, date);
                if (!result.skipRetry()) {
                    createRetryTask(symbol, date, "all fallbacks failed");
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
        if (existingBars.isEmpty()) {
            return Collections.emptyList();
        }

        // P1-4：显式升序排序，消除对调用方顺序的隐式依赖
        List<StockDailyBar> sorted = existingBars.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(StockDailyBar::getTradeDate))
                .toList();
        LocalDate newestInBars = sorted.get(sorted.size() - 1).getTradeDate();
        LocalDate oldestInBars = sorted.get(0).getTradeDate();

        // 以纽约时间为基准的"今天"
        LocalDate today = ZonedDateTime.now(AMERICA_NY).toLocalDate();

        // 只考察最近 MAX_LOOKBACK_DAYS 天——取 bar 最旧日期和 today-MAX_LOOKBACK_DAYS 中较晚者
        LocalDate lookbackLimit = today.minusDays(MAX_LOOKBACK_DAYS);
        LocalDate rangeStart = oldestInBars.isAfter(lookbackLimit) ? oldestInBars : lookbackLimit;

        // 范围上界：按时间段决定是否排除当天
        // 00:00~16:00 ET → 排除当天（盘中数据不完整）
        // 16:00~23:59 ET → 包含当天（收盘后可补当天数据）
        LocalTime nowTime = LocalTime.now(AMERICA_NY);
        LocalDate rangeEnd;
        if (nowTime.isBefore(LocalTime.of(16, 0))) {
            LocalDate yesterday = today.minusDays(1);
            rangeEnd = newestInBars.isAfter(yesterday) ? newestInBars : yesterday;
        } else {
            rangeEnd = newestInBars.isAfter(today) ? newestInBars : today;
        }

        Set<LocalDate> existingDates = existingBars.stream()
                .filter(Objects::nonNull)
                .map(StockDailyBar::getTradeDate)
                .collect(Collectors.toSet());

        List<LocalDate> missing = new ArrayList<>();
        int unknownSkipped = 0;
        LocalDate cursor = rangeStart;
        while (!cursor.isAfter(rangeEnd)) {
            if (cursor.getDayOfWeek().getValue() <= 5) {         // 周一到周五
                // 查交易日历 → 非开盘日跳过（节假日）
                if (calendarDbService != null) {
                    Boolean isOpen = calendarDbService.isTradingDay("US", cursor);
                    if (isOpen == null) {
                        // P2-11：日历数据源全挂（未知态）→ 跳过该日期，
                        // 宁可漏一天，不可按错误日历白打配额
                        unknownSkipped++;
                        log.warn("[DataGapFiller] calendar unknown, skip date: {}", cursor);
                        cursor = cursor.plusDays(1);
                        continue;
                    }
                    if (Boolean.FALSE.equals(isOpen)) {
                        log.debug("[DataGapFiller] skip non-trading day: {}", cursor);
                        cursor = cursor.plusDays(1);
                        continue;
                    }
                }
                if (!existingDates.contains(cursor)) {
                    missing.add(cursor);
                }
            }
            cursor = cursor.plusDays(1);
        }
        if (unknownSkipped > 0) {
            log.warn("[DataGapFiller] findMissingTradeDates: {} date(s) skipped due to unknown calendar state",
                    unknownSkipped);
        }

        if (missing.size() > MAX_MISSING_DATES_PER_SYMBOL) {
            return missing.subList(missing.size() - MAX_MISSING_DATES_PER_SYMBOL, missing.size());
        }
        return missing;
    }

    private FetchResult fetchAndPersist(String symbol, LocalDate tradeDate) {
        log.info("");
        log.info("[DataGapFiller] ================================================");
        log.info("[DataGapFiller] === 补缺 {}，日期 {} ===", symbol, tradeDate);
        log.info("[DataGapFiller] ================================================");
        log.info("");

        // 使用该股票专属的数据源优先级列表（含历史成功记录排序 + fallback）
        List<FallbackSource> fallbacks = buildFallbackChainForSymbol(symbol);

        // 记录每个数据源的"确认不存在"判定结果（P1-3：仅 CONFIRMED_NOT_FOUND 计入黑名单计数）
        Map<String, Boolean> sourceNotFoundResults = new LinkedHashMap<>();
        // P1-5：账户级错误终止本 symbol 的 fallback 链
        boolean accountLevelAbort = false;

        for (FallbackSource source : fallbacks) {
            log.info("");
            log.info("[DataGapFiller] {} source start", source.name);
            log.info("[DataGapFiller] {} source now send request: dateRange symbol={}, TradeDate={}", source.name, symbol, tradeDate);

            KLineData klineData = null;
            try {
                klineData = source.fetcher.fetch(symbol, tradeDate);
                if (isKLineDataEmpty(klineData)) {
                    // P1-3：成功但无数据（EMPTY）——默认不计黑名单，避免限流/超时/解析失败
                    // 被数据源"包装成空结果"后误伤真实股票
                    log.warn("[DataGapFiller] {} source then received response: returned empty result for symbol={}",
                            source.name, symbol);
                    log.info("[DataGapFiller] {} source end", source.name);
                    log.info("");
                    continue;
                }
                log.info("[DataGapFiller] {} source then received response: itemsCount={}", source.name, klineData.getItems().size());
                for (KLineIterator item : klineData.getItems()) {
                    // 优先使用 timeString 解析日期（数据源自身返回的），避免 epoch 时区转换不统一的问题
                    LocalDate itemDate = item.getTimeString() != null && !item.getTimeString().isEmpty()
                            ? LocalDate.parse(item.getTimeString())
                            : epochMillisToLocalDate(item.getTime());
                    log.info("[DataGapFiller] {} source item: symbol={}, epochTime={}, timeString='{}', parsedDate={}, open={}, close={}",
                            source.name, item.getSymbol(), item.getTime(), item.getTimeString(), itemDate,
                            item.getOpen(), item.getClose());
                    // 跳过零价格无效数据
                    if (item.getOpen() != null && item.getClose() != null
                            && item.getOpen().compareTo(java.math.BigDecimal.ZERO) == 0
                            && item.getClose().compareTo(java.math.BigDecimal.ZERO) == 0) {
                        log.warn("[DataGapFiller] {} source item: skip zero-price placeholder symbol={}, date={}",
                                source.name, item.getSymbol(), itemDate);
                        continue;
                    }
                    if (itemDate.equals(tradeDate)) {
                        log.info("[DataGapFiller] {} source then received response: matched targetDate={}", source.name, tradeDate);
                        StockDailyBar bar = persist(symbol, tradeDate, item, source.name);
                        mergeAfterHoursIfAvailable(symbol, tradeDate, bar, source.ds);
                        // 更新该股票的该数据源优先级（独立事务）
                        final String sourceName = source.name;
                        runInTx(() -> stockDataSourcePriorityService.updatePriority(
                                symbol, sourceName, java.time.LocalDateTime.now()));
                        log.info("[DataGapFiller] fillWithFallback: success symbol={}, source={}", symbol, source.name);
                        log.info("[DataGapFiller] {} source end", source.name);
                        log.info("");
                        // 补缺成功，重置黑名单计数
                        symbolBlacklistService.resetCount(symbol);
                        return FetchResult.ok();
                    }
                }
                log.warn("[DataGapFiller] fillWithFallback: date mismatch symbol={}, source={}, targetDate={}",
                        symbol, source.name, tradeDate);
                log.info("[DataGapFiller] {} source end", source.name);
                log.info("");
            } catch (StockDataException e) {
                // P1-3/P1-5：带分类的异常 —— 三态判定
                switch (e.getCategory()) {
                    case CONFIRMED_NOT_FOUND -> {
                        sourceNotFoundResults.put(source.name, true);
                        log.warn("[DataGapFiller] fillWithFallback: confirmed not-found symbol={}, source={}, error={}",
                                symbol, source.name, e.getMessage());
                    }
                    case ACCOUNT_LEVEL -> {
                        // P1-5：账户级错误 → 熔断该源（冷却期内跳过），并终止本 symbol 的 fallback 链
                        long until = System.currentTimeMillis() + SOURCE_COOLDOWN_MILLIS;
                        sourceCooldownUntil.put(source.name, until);
                        accountLevelAbort = true;
                        log.error("[DataGapFiller] fillWithFallback: account-level error symbol={}, source={}, " +
                                        "circuit open until={}, stop fallback chain — error={}",
                                symbol, source.name, until, e.getMessage());
                    }
                    default -> log.warn("[DataGapFiller] fillWithFallback: transient failure symbol={}, source={}, " +
                                    "not counted for blacklist — error={}",
                            symbol, source.name, e.getMessage());
                }
                log.info("[DataGapFiller] {} source end", source.name);
                log.info("");
            } catch (Exception e) {
                // P1-3：未知异常（非 StockDataException）——按关键词判 not-found，否则视为瞬态，不计黑名单
                String errorMsg = e.getMessage();
                boolean isNotFound = isNotFoundError(errorMsg);
                if (isNotFound) {
                    sourceNotFoundResults.put(source.name, true);
                }
                log.error("[DataGapFiller] fillWithFallback: error symbol={}, source={}, notFound={}, error={}",
                        symbol, source.name, isNotFound, errorMsg);
                log.info("[DataGapFiller] {} source end", source.name);
                log.info("");
            }

            if (accountLevelAbort) {
                break;
            }
        }

        // 所有数据源都失败了，检查"不存在"数量
                long notFoundCount = sourceNotFoundResults.values().stream()
                .filter(Boolean.TRUE::equals)
                .count();

        if (notFoundCount >= 2) {
            // 获取具体的错误信息用于记录
            Map<String, String> sourceErrors = new LinkedHashMap<>();
            for (Map.Entry<String, Boolean> entry : sourceNotFoundResults.entrySet()) {
                if (entry.getValue()) {
                    sourceErrors.put(entry.getKey(), "not_found");
                }
            }

            // 记入黑名单 + 停止 retry 任务（P1-2：独立事务，失败不回滚批次）
            runInTx(() -> {
                symbolBlacklistService.recordNotFound(symbol, sourceErrors);
                dataFillTaskRepository.updateStatusBySymbolAndStatusIn(
                        symbol,
                        java.util.List.of("pending", "retrying"),
                        "stopped",
                        "双数据源以上报 404，已进黑名单"
                );
            });

            log.warn("[DataGapFiller] [blacklist] symbol={} added to blacklist: {} sources returned not-found",
                    symbol, notFoundCount);

            log.warn("[DataGapFiller] fillWithFallback: all sources failed symbol={}, date={}, notFoundCount={}",
                    symbol, tradeDate, notFoundCount);
            return FetchResult.blacklisted();
        }

        log.warn("[DataGapFiller] fillWithFallback: all sources failed symbol={}, date={}, notFoundCount={}",
                symbol, tradeDate, notFoundCount);

        return FetchResult.retryableFailure();
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
        bar.setSource(source);
        // P1-2：单次持久化独立事务，失败不回滚整批
        runInTx(() -> stockDailyBarRepository.save(bar));
        return bar;
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
                runInTx(() -> stockDailyBarRepository.save(bar));
                return;
            }
        } catch (Exception e) {
            log.warn("[DataGapFiller] mergeAfterHours: failed symbol={}, date={}, error={}",
                    symbol, tradeDate, e.getMessage());
        }
    }

    private boolean supportsAfterHoursMerge(DataSourceStrategy source) {
        if (source instanceof TigerStockServiceImpl) {
            return true;
        }
        String name = source.getSourceName();
        return "tiger".equals(name) || "tigeropen".equals(name);
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
            processRetryingTasksInternal();
        } finally {
            running.set(false);
        }
    }

    private void processRetryingTasksInternal() {
        log.info("");
        log.info("[DataGapFiller] processRetryingTasks: === BEGIN ===");

        // P3-4：接入真实进度追踪（GET /api/admin/retry-progress 读取）
        RetryProgressService.RetryProgress progress = retryProgressService.startRetry();
        progress.setStage("SCANNING");

        try {
            List<DataFillTask> retryable = dataFillTaskRepository.findRetryableTasks();
            progress.setTotal(retryable.size());
            progress.setStage("RETRYING");
            log.info("[DataGapFiller] processRetryingTasks: found retryingTasks={}", retryable.size());

            LocalDate today = ZonedDateTime.now(AMERICA_NY).toLocalDate();
            int retried = 0;
            for (DataFillTask task : retryable) {
                String symbol = task.getSymbol();
                LocalDate tradeDate = task.getTradeDate();

                // createdAt + 7天 <= now? 则 status = "stopped" 放弃
                Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
                if (!task.getCreatedAt().isAfter(weekAgo)) {
                    task.setStatus("stopped");
                    saveTaskWithOptimisticLock(task);
                    progress.incrementProcessed();
                    log.info("[DataGapFiller] processRetryingTasks: task expired taskId={}, symbol={}, date={}",
                            task.getId(), symbol, tradeDate);
                    continue;
                }

                // retryDate = today 且 dayCount >= 5? 当天已达上限
                if (today.equals(task.getRetryDate()) && task.getDayCount() != null && task.getDayCount() >= 5) {
                    progress.incrementProcessed();
                    log.info("[DataGapFiller] processRetryingTasks: daily limit reached taskId={}, symbol={}, date={}, dayCount={}",
                            task.getId(), symbol, tradeDate, task.getDayCount());
                    continue;
                }

                // retryDate 非 today？重置 dayCount 并将 retryDate = today
                if (!today.equals(task.getRetryDate())) {
                    task.setDayCount(0);
                    task.setRetryDate(today);
                }

                // sameDay 冷却：updatedAt + 30分钟 > now？跳过
                if (task.getUpdatedAt() != null) {
                    Instant cooldownEnd = task.getUpdatedAt().plus(30, ChronoUnit.MINUTES);
                    if (cooldownEnd.isAfter(Instant.now())) {
                        progress.incrementProcessed();
                        log.info("[DataGapFiller] processRetryingTasks: cooldown taskId={}, symbol={}, date={}, updatedAt={}",
                                task.getId(), symbol, tradeDate, task.getUpdatedAt());
                        continue;
                    }
                }

                // 跳过已进黑名单的符号
                if (symbolBlacklistService.isBlacklisted(symbol)) {
                    task.setStatus("stopped");
                    task.setLastError("symbol is blacklisted");
                    saveTaskWithOptimisticLock(task);
                    progress.incrementProcessed();
                    log.info("[DataGapFiller] processRetryingTasks: task stopped (blacklisted) taskId={}, symbol={}, date={}",
                            task.getId(), symbol, tradeDate);
                    continue;
                }

                String retryMsg = String.format("processRetryingTasks: retrying taskId=%d, symbol=%s, date=%s, attempt=%d/%d",
                        task.getId(), symbol, tradeDate, task.getRetryCount() + 1, task.getMaxRetries());
                String retrySep = "=".repeat(retryMsg.length());
                log.info("");
                log.info("[DataGapFiller] {}", retrySep);
                log.info("[DataGapFiller] {}", retryMsg);
                log.info("[DataGapFiller] {}", retrySep);

                boolean success = fetchAndPersist(symbol, tradeDate).success();
                progress.incrementProcessed();
                if (success) {
                    task.setStatus("completed");
                    saveTaskWithOptimisticLock(task);
                    progress.incrementSucceeded();
                    log.info("[DataGapFiller] processRetryingTasks: retry success taskId={}, symbol={}, date={}",
                            task.getId(), symbol, tradeDate);
                    retried++;
                } else {
                    // fetchAndPersist 内部可能已将 symbol 加入黑名单并 stop 了 retry 任务
                    // 但 processRetryingTasks 持有的 task 对象未更新，需重新检查
                    if (symbolBlacklistService.isBlacklisted(symbol)) {
                        task.setStatus("stopped");
                        task.setLastError("blacklisted after all sources exhausted");
                        saveTaskWithOptimisticLock(task);
                        progress.incrementFailed();
                        log.info("[DataGapFiller] processRetryingTasks: task stopped (newly blacklisted) taskId={}, symbol={}, date={}",
                                task.getId(), symbol, tradeDate);
                    } else {
                        // R2 P2-1：计数递增走 JPQL 原子自增（retryCount/dayCount +1、状态/错误一并更新，
                        // 版本无关）—— 读-改-写 + 乐观锁在此路径废弃，冲突不再可能丢更新；
                        // retryDate 非 today 时的日计数重置也由原子条件更新承接（下方第 1 条语句）
                        final long taskId = task.getId();
                        runInTx(() -> {
                            dataFillTaskRepository.resetDailyCounterIfDateChanged(taskId, today);
                            dataFillTaskRepository.incrementRetryCounters(taskId, "retrying", "retry attempt failed again");
                        });
                        task.setRetryCount(task.getRetryCount() + 1);
                        task.setDayCount(task.getDayCount() + 1);
                        task.setStatus("retrying");
                        task.setLastError("retry attempt failed again");
                        progress.incrementFailed();
                        log.warn("[DataGapFiller] processRetryingTasks: retry failed taskId={}, symbol={}, date={}, retryCount={}, dayCount={}",
                                task.getId(), symbol, tradeDate, task.getRetryCount(), task.getDayCount());
                    }
                }
            }

            log.info("[DataGapFiller] processRetryingTasks: === COMPLETED === retried={}, total={}",
                    retried, retryable.size());
        } finally {
            progress.setRunning(false);
            progress.setStage("COMPLETED");
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

    private record FetchResult(boolean succeeded, boolean skipRetry) {
        static FetchResult ok() {
            return new FetchResult(true, false);
        }

        static FetchResult retryableFailure() {
            return new FetchResult(false, false);
        }

        static FetchResult blacklisted() {
            return new FetchResult(false, true);
        }

        boolean success() {
            return succeeded;
        }
    }

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
