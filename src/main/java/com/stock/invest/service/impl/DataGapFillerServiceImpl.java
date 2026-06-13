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

/**
 * 数据补缺服务 —— 通过 fallback 链补全缺失的日 K 线数据。
 * <p>
 * Fallback 链通过自动收集所有 {@link com.stock.invest.service.DataSourceStrategy} bean 构建，
 * 按优先级（tiger -> tigeropen -> yfinance -> twelvedata -> tiingo）排序，
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

    public DataGapFillerServiceImpl(
            StockDailyBarRepository stockDailyBarRepository,
            DataFillTaskRepository dataFillTaskRepository,
            List<DataSourceStrategy> dataSources,
            GapFillProperties gapFillProperties,
            DataFillProgressService dataFillProgressService,
            TradingCalendarDbService tradingCalendarDbService,
            StockDataSourcePriorityService stockDataSourcePriorityService) {
        this.stockDailyBarRepository = stockDailyBarRepository;
        this.dataFillTaskRepository = dataFillTaskRepository;
        this.dataSources = dataSources;
        this.gapFillProperties = gapFillProperties;
        this.dataFillProgressService = dataFillProgressService;
        this.tradingCalendarDbService = tradingCalendarDbService;
        this.stockDataSourcePriorityService = stockDataSourcePriorityService;
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
        log.info("[DataGapFiller] fillGaps: scanning totalSymbols={}", allSymbols.size());

        if (progress != null) {
            progress.setTotalSymbols(allSymbols.size());
            progress.setStage("SCANNING");
        }

        int processed = 0;
        int totalGapsFound = 0;
        int totalFilled = 0;
        int totalFailed = 0;

        for (String symbol : allSymbols) {
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

        log.info("[DataGapFiller] fillGaps: symbol={} gapsFound={}, dates={}, latestClose={}",
                symbol, missingDates.size(), missingDates, latest.getClosePrice());

        int filled = 0;
        int failed = 0;
        for (LocalDate date : missingDates) {
            log.info("[DataGapFiller] fillGaps: filling symbol={}, date={}", symbol, date);
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
        log.info("[DataGapFiller] fillWithFallback: begin symbol={}, date={}", symbol, tradeDate);

        // 使用该股票专属的数据源优先级列表（含历史成功记录排序 + fallback）
        List<FallbackSource> fallbacks = buildFallbackChainForSymbol(symbol);
        for (FallbackSource source : fallbacks) {
            log.info("[DataGapFiller] fillWithFallback: trying symbol={}, source={}", symbol, source.name);
            try {
                KLineData klineData = null;
                // yfinance 优先使用精确日期范围查询
                if ("yfinance".equals(source.name) && source.ds() instanceof YFinanceStockServiceImpl) {
                    klineData = ((YFinanceStockServiceImpl) source.ds()).getDailyKLineDataByDateRange(symbol, tradeDate, tradeDate.plusDays(1));
                }
                if (klineData == null || klineData.getItems() == null || klineData.getItems().isEmpty()) {
                    klineData = source.fetcher.fetch(symbol);
                }
                if (klineData == null || klineData.getItems() == null || klineData.getItems().isEmpty()) {
                    log.warn("[DataGapFiller] fillWithFallback: no data symbol={}, source={}", symbol, source.name);
                    continue;
                }
                for (KLineIterator item : klineData.getItems()) {
                    LocalDate itemDate = epochMillisToLocalDate(item.getTime());
                    if (tradeDate.equals(itemDate)) {
                        persist(symbol, tradeDate, item, source.name);
                        // 更新该股票的该数据源优先级（最近成功时间），先删旧记录再写新记录
                        stockDataSourcePriorityService.updatePriority(
                                symbol, source.name, java.time.LocalDateTime.now());
                        log.info("[DataGapFiller] fillWithFallback: success symbol={}, source={}", symbol, source.name);
                        return true;
                    }
                }
                log.warn("[DataGapFiller] fillWithFallback: date mismatch symbol={}, source={}, targetDate={}",
                        symbol, source.name, tradeDate);
            } catch (Exception e) {
                log.error("[DataGapFiller] fillWithFallback: error symbol={}, source={}, error={}",
                        symbol, source.name, e.getMessage(), e);
            }
        }

        log.warn("[DataGapFiller] fillWithFallback: all sources failed symbol={}, date={}", symbol, tradeDate);
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
    }

    @Override
    @Transactional
    public void processRetryingTasks() {
        log.info("[DataGapFiller] processRetryingTasks: === BEGIN ===");

        List<DataFillTask> retryable = dataFillTaskRepository.findRetryableTasks();
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

            log.info("[DataGapFiller] processRetryingTasks: retrying taskId={}, symbol={}, date={}, attempt={}/{}",
                    task.getId(), symbol, tradeDate, task.getRetryCount() + 1, task.getMaxRetries());

            boolean success = fetchAndPersist(symbol, tradeDate);
            if (success) {
                task.setStatus("completed");
                dataFillTaskRepository.save(task);
                log.info("[DataGapFiller] processRetryingTasks: retry success taskId={}, symbol={}, date={}",
                        task.getId(), symbol, tradeDate);
                retried++;
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
        KLineData fetch(String symbol) throws Exception;
    }

    private record FallbackSource(String name, KLineFetcher fetcher, DataSourceStrategy ds) {}

    /**
     * 构建某支股票专属的 fallback 链。
     * <ul>
     *   <li>有历史成功记录 → 按 last_success_time DESC 优先</li>
     *   <li>无历史记录 → 使用默认顺序 yfinance → tiingo → tiger → twelvedata → tigeropen</li>
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
                        sym -> ds.getDailyKLineDataAsObject(sym), ds))
                .collect(Collectors.toList());
    }

    // ---- Internal result holder ----

    private record FillResult(int symbolsProcessed, int gapsFound, int filled, int failed) {
        static FillResult empty() {
            return new FillResult(0, 0, 0, 0);
        }
    }
}
