package com.stock.invest.service.impl;

import com.stock.invest.entity.DataFillTask;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.repository.DataFillTaskRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.DataGapFillerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.stock.invest.config.GapFillProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据补全服务实现。
 * <p>
 * Fallback 链（按优先级从高到低）：
 * Tiger Java SDK ({@link TigerStockServiceImpl}) →
 * Tiger Python Bridge (PythonScriptExecutor) →
 * YFinance ({@link YFinanceStockServiceImpl}) →
 * TwelveData ({@link TwelveDataStockServiceImpl}) →
 * Tiingo ({@link TiingoDataSourceStrategy})
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
    private final TigerStockServiceImpl tigerStockService;
    private final YFinanceStockServiceImpl yFinanceStockService;
    private final TwelveDataStockServiceImpl twelveDataStockService;
    private final TiingoDataSourceStrategy tiingoDataSourceStrategy;
    private final GapFillProperties gapFillProperties;

    public DataGapFillerServiceImpl(
            StockDailyBarRepository stockDailyBarRepository,
            DataFillTaskRepository dataFillTaskRepository,
            @Qualifier("tigerStockService") TigerStockServiceImpl tigerStockService,
            @Qualifier("yFinanceStockService") YFinanceStockServiceImpl yFinanceStockService,
            TwelveDataStockServiceImpl twelveDataStockService,
            TiingoDataSourceStrategy tiingoDataSourceStrategy,
            GapFillProperties gapFillProperties) {
        this.stockDailyBarRepository = stockDailyBarRepository;
        this.dataFillTaskRepository = dataFillTaskRepository;
        this.tigerStockService = tigerStockService;
        this.yFinanceStockService = yFinanceStockService;
        this.twelveDataStockService = twelveDataStockService;
        this.tiingoDataSourceStrategy = tiingoDataSourceStrategy;
        this.gapFillProperties = gapFillProperties;
    }

    @Override
    @Transactional
    public void fillGaps() {
        Instant batchStart = Instant.now();
        log.info("[DataGapFiller] fillGaps: === BEGIN ===");

        List<String> allSymbols = stockDailyBarRepository.findAllSymbols();
        if (allSymbols.isEmpty()) {
            log.info("[DataGapFiller] fillGaps: no symbols found in stock_daily_bars");
            return;
        }
        log.info("[DataGapFiller] fillGaps: scanning — totalSymbols={}", allSymbols.size());

        int processed = 0;
        int totalGapsFound = 0;
        int totalFilled = 0;
        int totalFailed = 0;

        for (String symbol : allSymbols) {
            if (processed >= MAX_SYMBOLS_PER_RUN) {
                log.info("[DataGapFiller] fillGaps: reached max symbols per run ({})", MAX_SYMBOLS_PER_RUN);
                break;
            }

            FillResult result = fillGapsForSymbol(symbol);
            processed += result.symbolsProcessed;
            totalGapsFound += result.gapsFound;
            totalFilled += result.filled;
            totalFailed += result.failed;
        }

        log.info("[DataGapFiller] fillGaps: === COMPLETED === " +
                "totalSymbols={}, gapsFound={}, filled={}, failed={}, elapsedMs={}",
                allSymbols.size(), totalGapsFound, totalFilled, totalFailed,
                Duration.between(batchStart, Instant.now()).toMillis());
    }

    private FillResult fillGapsForSymbol(String symbol) {
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

        List<LocalDate> missingDates = findMissingTradeDates(bars);
        if (missingDates.isEmpty()) {
            return FillResult.empty();
        }

        log.info("[DataGapFiller] fillGaps: symbol={} — gapsFound={}, dates={}, latestClose={}",
                symbol, missingDates.size(), missingDates, latest.getClosePrice());

        int filled = 0;
        int failed = 0;
        for (LocalDate date : missingDates) {
            log.info("[DataGapFiller] fillGaps: filling — symbol={}, date={}", symbol, date);
            boolean success = fetchAndPersist(symbol, date);
            if (success) {
                filled++;
            } else {
                log.warn("[DataGapFiller] fillGaps: all sources exhausted — symbol={}, date={}", symbol, date);
                createRetryTask(symbol, date, "all fallbacks failed");
                failed++;
            }
        }
        return new FillResult(1, missingDates.size(), filled, failed);
    }

    static List<LocalDate> findMissingTradeDates(List<StockDailyBar> existingBars) {
        if (existingBars.isEmpty()) {
            return Collections.emptyList();
        }
        LocalDate first = existingBars.get(0).getTradeDate();
        LocalDate last = existingBars.get(existingBars.size() - 1).getTradeDate();

        Set<LocalDate> existingDates = existingBars.stream()
                .map(StockDailyBar::getTradeDate)
                .collect(Collectors.toCollection(HashSet::new));

        List<LocalDate> missing = new ArrayList<>();
        LocalDate cursor = first;
        while (!cursor.isAfter(last)) {
            if (cursor.getDayOfWeek().getValue() <= 5) {
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
        log.info("[DataGapFiller] fillWithFallback: begin — symbol={}, date={}", symbol, tradeDate);

        List<FallbackSource> fallbacks = buildFallbackChain();
        for (FallbackSource source : fallbacks) {
            log.info("[DataGapFiller] fillWithFallback: trying — symbol={}, source={}", symbol, source.name);
            try {
                KLineData klineData = source.fetcher.fetch(symbol);
                if (klineData == null || klineData.getItems() == null || klineData.getItems().isEmpty()) {
                    log.warn("[DataGapFiller] fillWithFallback: no data — symbol={}, source={}", symbol, source.name);
                    continue;
                }
                for (KLineIterator item : klineData.getItems()) {
                    LocalDate itemDate = epochMillisToLocalDate(item.getTime());
                    if (tradeDate.equals(itemDate)) {
                        persist(symbol, tradeDate, item, source.name);
                        log.info("[DataGapFiller] fillWithFallback: success — symbol={}, source={}", symbol, source.name);
                        return true;
                    }
                }
                log.warn("[DataGapFiller] fillWithFallback: date mismatch — symbol={}, source={}, targetDate={}",
                        symbol, source.name, tradeDate);
            } catch (Exception e) {
                log.error("[DataGapFiller] fillWithFallback: error — symbol={}, source={}, error={}",
                        symbol, source.name, e.getMessage(), e);
            }
        }

        log.warn("[DataGapFiller] fillWithFallback: all sources failed — symbol={}, date={}", symbol, tradeDate);
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
            // dayCount 和 retryDate 保持不变，由 processRetryingTasks 在遍历时根据日期重置
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

            // createdAt + 7天 <= now? → status = "stopped"，跳过
            Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
            if (!task.getCreatedAt().isAfter(weekAgo)) {
                task.setStatus("stopped");
                dataFillTaskRepository.save(task);
                log.info("[DataGapFiller] processRetryingTasks: task expired — taskId={}, symbol={}, date={}",
                        task.getId(), symbol, tradeDate);
                continue;
            }

            // retryDate = today 且 dayCount >= 5？ → 跳过（今天满了）
            if (today.equals(task.getRetryDate()) && task.getDayCount() != null && task.getDayCount() >= 5) {
                log.info("[DataGapFiller] processRetryingTasks: daily limit reached — taskId={}, symbol={}, date={}, dayCount={}",
                        task.getId(), symbol, tradeDate, task.getDayCount());
                continue;
            }

            // retryDate ≠ today → dayCount 重置为 0，retryDate = today
            if (!today.equals(task.getRetryDate())) {
                task.setDayCount(0);
                task.setRetryDate(today);
            }

            // sameDay 且 updatedAt + 30分钟 > now？ → 跳过
            if (task.getUpdatedAt() != null) {
                Instant cooldownEnd = task.getUpdatedAt().plus(30, ChronoUnit.MINUTES);
                if (cooldownEnd.isAfter(Instant.now())) {
                    log.info("[DataGapFiller] processRetryingTasks: cooldown — taskId={}, symbol={}, date={}, updatedAt={}",
                            task.getId(), symbol, tradeDate, task.getUpdatedAt());
                    continue;
                }
            }

            log.info("[DataGapFiller] processRetryingTasks: retrying — taskId={}, symbol={}, date={}, attempt={}/{}",
                    task.getId(), symbol, tradeDate, task.getRetryCount() + 1, task.getMaxRetries());

            boolean success = fetchAndPersist(symbol, tradeDate);
            if (success) {
                task.setStatus("completed");
                dataFillTaskRepository.save(task);
                log.info("[DataGapFiller] processRetryingTasks: retry success — taskId={}, symbol={}, date={}",
                        task.getId(), symbol, tradeDate);
                retried++;
            } else {
                task.setRetryCount(task.getRetryCount() + 1);
                task.setDayCount(task.getDayCount() + 1);
                task.setStatus("retrying");
                task.setLastError("retry attempt failed again");
                dataFillTaskRepository.save(task);
                log.warn("[DataGapFiller] processRetryingTasks: retry failed — taskId={}, symbol={}, date={}, retryCount={}, dayCount={}",
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

    private record FallbackSource(String name, KLineFetcher fetcher) {}

    private List<FallbackSource> buildFallbackChain() {
        List<FallbackSource> chain = new ArrayList<>();
        chain.add(new FallbackSource("tiger_java", symbol -> {
            try { return tigerStockService.getDailyKLineDataAsObject(symbol); }
            catch (Exception e) { log.warn("[DataGapFiller] fallback tiger_java failed for {}: {}", symbol, e.getMessage()); return null; }
        }));
        chain.add(new FallbackSource("tiger_python", symbol -> {
            try { return tigerStockService.getDailyKLine(symbol); }
            catch (Exception e) { log.warn("[DataGapFiller] fallback tiger_python failed for {}: {}", symbol, e.getMessage()); return null; }
        }));
        chain.add(new FallbackSource("yfinance", symbol -> {
            try { return yFinanceStockService.getDailyKLine(symbol); }
            catch (Exception e) { log.warn("[DataGapFiller] fallback yfinance failed for {}: {}", symbol, e.getMessage()); return null; }
        }));
        chain.add(new FallbackSource("twelvedata", symbol -> {
            try { return twelveDataStockService.getDailyKLineDataAsObject(symbol); }
            catch (Exception e) { log.warn("[DataGapFiller] fallback twelvedata failed for {}: {}", symbol, e.getMessage()); return null; }
        }));
        chain.add(new FallbackSource("tiingo", symbol -> {
            try { return tiingoDataSourceStrategy.getDailyKLine(symbol); }
            catch (Exception e) { log.warn("[DataGapFiller] fallback tiingo failed for {}: {}", symbol, e.getMessage()); return null; }
        }));
        return chain;
    }

    // ---- Internal result holder ----

    private record FillResult(int symbolsProcessed, int gapsFound, int filled, int failed) {
        static FillResult empty() {
            return new FillResult(0, 0, 0, 0);
        }
    }
}
