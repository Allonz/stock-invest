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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stock.invest.config.GapFillProperties;
import com.stock.invest.entity.DataFillTask;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.repository.DataFillTaskRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.DataFillProgressService;
import com.stock.invest.service.DataGapFillerService;
import com.stock.invest.service.DataSourceStrategy;
import com.stock.invest.service.TradingCalendarDbService;
import com.stock.invest.service.StockDataSourcePriorityService;
import com.stock.invest.service.SymbolBlacklistService;
import com.stock.invest.entity.SymbolBlacklist;

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

    private final StockDailyBarRepository stockDailyBarRepository;
    private final DataFillTaskRepository dataFillTaskRepository;
    private final List<DataSourceStrategy> dataSources;
    private final GapFillProperties gapFillProperties;
    private final DataFillProgressService dataFillProgressService;
    private final TradingCalendarDbService tradingCalendarDbService;
    private final StockDataSourcePriorityService stockDataSourcePriorityService;
    private final SymbolBlacklistService symbolBlacklistService;

    public DataGapFillerServiceImpl(
            StockDailyBarRepository stockDailyBarRepository,
            DataFillTaskRepository dataFillTaskRepository,
            List<DataSourceStrategy> dataSources,
            GapFillProperties gapFillProperties,
            DataFillProgressService dataFillProgressService,
            TradingCalendarDbService tradingCalendarDbService,
            StockDataSourcePriorityService stockDataSourcePriorityService,
            SymbolBlacklistService symbolBlacklistService) {
        this.stockDailyBarRepository = stockDailyBarRepository;
        this.dataFillTaskRepository = dataFillTaskRepository;
        this.dataSources = dataSources;
        this.gapFillProperties = gapFillProperties;
        this.dataFillProgressService = dataFillProgressService;
        this.tradingCalendarDbService = tradingCalendarDbService;
        this.stockDataSourcePriorityService = stockDataSourcePriorityService;
        this.symbolBlacklistService = symbolBlacklistService;
    }

    @Override
    @Transactional
    public void fillGaps() {
        Instant batchStart = Instant.now();
        log.info("[DataGapFiller] fillGaps: === BEGIN ===");

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
            // 清理已入黑 symbol 的 retry 任务
            for (String s : blacklistedSymbols) {
                dataFillTaskRepository.updateStatusBySymbolAndStatusIn(
                        s,
                        java.util.List.of("pending", "retrying"),
                        "stopped",
                        "symbol is blacklisted, stop retry"
                );
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

            FillResult result = fillGapsForSymbol(symbol, progress);
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
        List<StockDailyBar> bars = stockDailyBarRepository
                .findBySymbolOrderByTradeDateDesc(symbol, PageRequest.of(0, MAX_LOOKBACK_DAYS));
        if (bars.isEmpty()) {
            return FillResult.empty();
        }
        Collections.reverse(bars);

        StockDailyBar latest = bars.get(bars.size() - 1);
        if (latest.getClosePrice() != null && latest.getClosePrice() > gapFillProperties.getMinPriceThreshold()) {
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
        log.info("\033[31m[DataGapFiller] {}\033[0m", msg);
        log.info("[DataGapFiller] {}", sep);

        int filled = 0;
        int failed = 0;
        for (LocalDate date : missingDates) {
            // 进入 fetchAndPersist 会打印分隔线和补缺信息
            boolean success = fetchAndPersist(symbol, date);
            if (success) {
                filled++;
                if (progress != null) {
                    progress.incrementFilled();
                }
            } else {
                log.warn("[DataGapFiller] fillGaps: all sources exhausted symbol={}, date={}", symbol, date);
                createRetryTask(symbol, date, "all fallbacks failed");
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
     * <p>existingBars 按 tradeDate DESC 排序传入。</p>
     * <p>通过 TradingCalendarDbService 查询交易日历，跳过非开盘日（节假日）。</p>
     */
    static List<LocalDate> findMissingTradeDates(List<StockDailyBar> existingBars,
                                                  TradingCalendarDbService calendarDbService) {
        if (existingBars.isEmpty()) {
            return Collections.emptyList();
        }

        // bars 按 tradeDate DESC 传回
        LocalDate newestInBars = existingBars.get(0).getTradeDate();
        LocalDate oldestInBars = existingBars.get(existingBars.size() - 1).getTradeDate();

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
                .map(StockDailyBar::getTradeDate)
                .collect(Collectors.toSet());

        List<LocalDate> missing = new ArrayList<>();
        LocalDate cursor = rangeStart;
        while (!cursor.isAfter(rangeEnd)) {
            if (cursor.getDayOfWeek().getValue() <= 5) {         // 周一到周五
                // 查交易日历 → 非开盘日跳过（节假日）
                if (calendarDbService != null) {
                    Boolean isOpen = calendarDbService.isTradingDay("US", cursor);
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

        if (missing.size() > MAX_MISSING_DATES_PER_SYMBOL) {
            return missing.subList(missing.size() - MAX_MISSING_DATES_PER_SYMBOL, missing.size());
        }
        return missing;
    }

    private boolean fetchAndPersist(String symbol, LocalDate tradeDate) {
        log.info("");
        log.info("[DataGapFiller] ================================================");
        log.info("[DataGapFiller] === 补缺 {}，日期 {} ===", symbol, tradeDate);
        log.info("[DataGapFiller] ================================================");
        log.info("");

        // 使用该股票专属的数据源优先级列表（含历史成功记录排序 + fallback）
        List<FallbackSource> fallbacks = buildFallbackChainForSymbol(symbol);

        // 记录每个数据源的"不存在"判定结果
        Map<String, Boolean> sourceNotFoundResults = new LinkedHashMap<>();

        for (FallbackSource source : fallbacks) {
            log.info("");
            log.info("\033[32m[DataGapFiller] {} source start\033[0m", source.name);
            log.info("\033[34m[DataGapFiller] {} source now send request:\033[0m dateRange symbol={}, TradeDate={}", source.name, symbol, tradeDate);

            KLineData klineData = null;
            try {
                klineData = source.fetcher.fetch(symbol, tradeDate);
                if (isKLineDataEmpty(klineData)) {
                    sourceNotFoundResults.put(source.name, true);
                    log.warn("\033[34m[DataGapFiller] {} source then received response:\033[0m returned empty result for symbol={}",
                            source.name, symbol);
                    log.info("\033[32m[DataGapFiller] {} source end\033[0m", source.name);
                    log.info("");
                    continue;
                }
                log.info("\033[34m[DataGapFiller] {} source then received response:\033[0m itemsCount={}", source.name, klineData.getItems().size());
                for (KLineIterator item : klineData.getItems()) {
                    // 优先使用 timeString 解析日期（数据源自身返回的），避免 epoch 时区转换不统一的问题
                    LocalDate itemDate = item.getTimeString() != null && !item.getTimeString().isEmpty()
                            ? LocalDate.parse(item.getTimeString())
                            : epochMillisToLocalDate(item.getTime());
                    log.info("[DataGapFiller] {} source item: symbol={}, epochTime={}, timeString='{}', parsedDate={}, open={}, close={}",
                            source.name, item.getSymbol(), item.getTime(), item.getTimeString(), itemDate,
                            item.getOpen(), item.getClose());
                    // 跳过零价格无效数据
                    if (item.getOpen() == 0.0 && item.getClose() == 0.0) {
                        log.warn("[DataGapFiller] {} source item: skip zero-price placeholder symbol={}, date={}",
                                source.name, item.getSymbol(), itemDate);
                        continue;
                    }
                    if (itemDate.equals(tradeDate)) {
                        log.info("\033[34m[DataGapFiller] {} source then received response:\033[0m matched targetDate={}", source.name, tradeDate);
                        persist(symbol, tradeDate, item, source.name);
                        // 更新该股票的该数据源优先级
                        stockDataSourcePriorityService.updatePriority(
                                symbol, source.name, java.time.LocalDateTime.now());
                        log.info("[DataGapFiller] fillWithFallback: success symbol={}, source={}", symbol, source.name);
                        log.info("\033[32m[DataGapFiller] {} source end\033[0m", source.name);
                        log.info("");
                        // 补缺成功，重置黑名单计数
                        symbolBlacklistService.resetCount(symbol);
                        return true;
                    }
                }
                log.warn("[DataGapFiller] fillWithFallback: date mismatch symbol={}, source={}, targetDate={}",
                        symbol, source.name, tradeDate);
                log.info("\033[32m[DataGapFiller] {} source end\033[0m", source.name);
                log.info("");
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                boolean isNotFound = isNotFoundError(klineData, errorMsg);
                sourceNotFoundResults.put(source.name, isNotFound);

                log.error("[DataGapFiller] fillWithFallback: error symbol={}, source={}, error={}",
                        symbol, source.name, errorMsg);
                log.info("\033[32m[DataGapFiller] {} source end\033[0m", source.name);
                log.info("");
            }
        }

        // 所有数据源都失败了，检查"不存在"数量
        long notFoundCount = sourceNotFoundResults.values().stream()
                .filter(Boolean::booleanValue)
                .count();

        if (notFoundCount >= 2) {
            // 获取具体的错误信息用于记录
            Map<String, String> sourceErrors = new LinkedHashMap<>();
            for (Map.Entry<String, Boolean> entry : sourceNotFoundResults.entrySet()) {
                if (entry.getValue()) {
                    sourceErrors.put(entry.getKey(), "not_found");
                }
            }

            // 记入黑名单
            symbolBlacklistService.recordNotFound(symbol, sourceErrors);

            // 将 data_fill_task 中该 symbol 的 pending/retrying 任务改为 stopped
            dataFillTaskRepository.updateStatusBySymbolAndStatusIn(
                    symbol,
                    java.util.List.of("pending", "retrying"),
                    "stopped",
                    "双数据源以上报 404，已进黑名单"
            );

            log.warn("[DataGapFiller] [blacklist] symbol={} added to blacklist: {} sources returned not-found",
                    symbol, notFoundCount);
        }

        log.warn("[DataGapFiller] fillWithFallback: all sources failed symbol={}, date={}, notFoundCount={}",
                symbol, tradeDate, notFoundCount);

        // 只有在 notFoundCount < 2 时才创建 retrying 任务
        if (notFoundCount < 2) {
            createRetryTask(symbol, tradeDate, "all fallbacks failed");
        }

        return false;
    }

    private void persist(String symbol, LocalDate tradeDate, KLineIterator item, String source) {
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
        bar.setClosePrice(item.getClose());
        bar.setVolume(item.getVolume());
        bar.setSource(source);
        stockDailyBarRepository.save(bar);
    }

    private void createRetryTask(String symbol, LocalDate tradeDate, String error) {
        LocalDate today = ZonedDateTime.now(AMERICA_NY).toLocalDate();
        Optional<DataFillTask> existing = dataFillTaskRepository.findBySymbolAndTradeDate(symbol, tradeDate);
        if (existing.isPresent()) {
            DataFillTask task = existing.get();
            task.setRetryCount(task.getRetryCount() + 1);
            task.setStatus("retrying");
            task.setLastError(error);
            // dayCount 和 retryDate 由 processRetryingTasks 统一管理
            dataFillTaskRepository.save(task);
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
        dataFillTaskRepository.save(task);
        log.info("[DataGapFiller] createRetryTask: created symbol={}, date={}, error={}",
                symbol, tradeDate, error);
    }

    @Override
    @Transactional
    public void processRetryingTasks() {
        log.info("");
        log.info("\033[31m[DataGapFiller] processRetryingTasks: === BEGIN ===\033[0m");

        List<DataFillTask> retryable = dataFillTaskRepository.findRetryableTasks();
        log.info("\033[31m[DataGapFiller] processRetryingTasks: found retryingTasks={}\033[0m", retryable.size());

        LocalDate today = ZonedDateTime.now(AMERICA_NY).toLocalDate();
        int retried = 0;
        for (DataFillTask task : retryable) {
            String symbol = task.getSymbol();
            LocalDate tradeDate = task.getTradeDate();

            // createdAt + 7天 <= now? 则 status = "stopped" 放弃
            Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
            if (!task.getCreatedAt().isAfter(weekAgo)) {
                task.setStatus("stopped");
                dataFillTaskRepository.save(task);
                log.info("[DataGapFiller] processRetryingTasks: task expired taskId={}, symbol={}, date={}",
                        task.getId(), symbol, tradeDate);
                continue;
            }

            // retryDate = today 且 dayCount >= 5? 当天已达上限
            if (today.equals(task.getRetryDate()) && task.getDayCount() != null && task.getDayCount() >= 5) {
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
                    log.info("[DataGapFiller] processRetryingTasks: cooldown taskId={}, symbol={}, date={}, updatedAt={}",
                            task.getId(), symbol, tradeDate, task.getUpdatedAt());
                    continue;
                }
            }

            // 跳过已进黑名单的符号
            if (symbolBlacklistService.isBlacklisted(symbol)) {
                task.setStatus("stopped");
                task.setLastError("symbol is blacklisted");
                dataFillTaskRepository.save(task);
                log.info("[DataGapFiller] processRetryingTasks: task stopped (blacklisted) taskId={}, symbol={}, date={}",
                        task.getId(), symbol, tradeDate);
                continue;
            }

            String retryMsg = String.format("processRetryingTasks: retrying taskId=%d, symbol=%s, date=%s, attempt=%d/%d",
                    task.getId(), symbol, tradeDate, task.getRetryCount() + 1, task.getMaxRetries());
            String retrySep = "=".repeat(retryMsg.length());
            log.info("");
            log.info("[DataGapFiller] {}", retrySep);
            log.info("\033[31m[DataGapFiller] {}\033[0m", retryMsg);
            log.info("[DataGapFiller] {}", retrySep);

            boolean success = fetchAndPersist(symbol, tradeDate);
            if (success) {
                task.setStatus("completed");
                dataFillTaskRepository.save(task);
                log.info("[DataGapFiller] processRetryingTasks: retry success taskId={}, symbol={}, date={}",
                        task.getId(), symbol, tradeDate);
                retried++;
            } else {
                // fetchAndPersist 内部可能已将 symbol 加入黑名单并 stop 了 retry 任务
                // 但 processRetryingTasks 持有的 task 对象未更新，需重新检查
                if (symbolBlacklistService.isBlacklisted(symbol)) {
                    task.setStatus("stopped");
                    task.setLastError("blacklisted after all sources exhausted");
                    dataFillTaskRepository.save(task);
                    log.info("[DataGapFiller] processRetryingTasks: task stopped (newly blacklisted) taskId={}, symbol={}, date={}",
                            task.getId(), symbol, tradeDate);
                } else {
                    task.setRetryCount(task.getRetryCount() + 1);
                    task.setDayCount(task.getDayCount() + 1);
                    task.setStatus("retrying");
                    task.setLastError("retry attempt failed again");
                    dataFillTaskRepository.save(task);
                    log.warn("[DataGapFiller] processRetryingTasks: retry failed taskId={}, symbol={}, date={}, retryCount={}, dayCount={}",
                            task.getId(), symbol, tradeDate, task.getRetryCount(), task.getDayCount());
                }
            }
        }

        log.info("[DataGapFiller] processRetryingTasks: === COMPLETED === retried={}, total={}",
                retried, retryable.size());
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
                .filter(DataSourceStrategy::isAvailable)
                .sorted(Comparator.comparingInt(s -> priorityMap.getOrDefault(s.getSourceName(), 99)))
                .map(ds -> new FallbackSource(ds.getSourceName(),
                        (sym, date) -> ds.getDailyKLineDataByDateRange(sym, date), ds))
                .collect(Collectors.toList());
    }

    /**
     * 综合判断该数据源是否返回了"股票不存在"。
     * 同时覆盖两条失败路径：
     *   路径 A：fetch 返回空结果（items 为空）
     *   路径 B：异常消息匹配 404/not found 关键词
     *
     * @param klineData   fetch 返回的数据对象（可为 null）
     * @param errorMessage catch 块中的异常消息（可为 null）
     * @return true = 该数据源判定为"不存在"
     */
    private boolean isNotFoundError(KLineData klineData, String errorMessage) {
        // 路径 A：空结果路径
        if (klineData == null
                || klineData.getItems() == null
                || klineData.getItems().isEmpty()) {
            return true;
        }
        // 路径 B：异常消息路径
        if (errorMessage != null && !errorMessage.isEmpty()) {
            String lower = errorMessage.toLowerCase();
            return lower.contains("404")
                    || lower.contains("not found")
                    || lower.contains("no data")
                    || lower.contains("no historical data")
                    || lower.contains("no results")
                    || lower.contains("invalid symbol")
                    || lower.contains("is missing or invalid")
                    || lower.contains("grow or venture")
                    || lower.contains("not_found");
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
    }
}
