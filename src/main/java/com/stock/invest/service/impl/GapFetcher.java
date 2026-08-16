package com.stock.invest.service.impl;

import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.exception.StockDataException;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.repository.DataFillTaskRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.StockDataSourcePriorityService;
import com.stock.invest.service.SymbolBlacklistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 单日补缺执行器。
 *
 * <p>负责按 fallback 链逐源拉取指定交易日的 K 线，
 * 并在成功后持久化、合并盘后价、更新数据源优先级和黑名单状态。</p>
 */
class GapFetcher {

    private static final Logger log = LoggerFactory.getLogger(GapFetcher.class);

    private static final ZoneId AMERICA_NY = ZoneId.of("America/New_York");
    private static final long SOURCE_COOLDOWN_MILLIS = 30 * 60 * 1000L;

    private final StockDailyBarRepository stockDailyBarRepository;
    private final DataFillTaskRepository dataFillTaskRepository;
    private final StockDataSourcePriorityService stockDataSourcePriorityService;
    private final SymbolBlacklistService symbolBlacklistService;
    private final MissingFieldFiller missingFieldFiller;
    private final FallbackChainBuilder fallbackChainBuilder;
    private final TransactionTemplate transactionTemplate;

    GapFetcher(StockDailyBarRepository stockDailyBarRepository,
               DataFillTaskRepository dataFillTaskRepository,
               StockDataSourcePriorityService stockDataSourcePriorityService,
               SymbolBlacklistService symbolBlacklistService,
               MissingFieldFiller missingFieldFiller,
               FallbackChainBuilder fallbackChainBuilder,
               PlatformTransactionManager transactionManager) {
        this.stockDailyBarRepository = stockDailyBarRepository;
        this.dataFillTaskRepository = dataFillTaskRepository;
        this.stockDataSourcePriorityService = stockDataSourcePriorityService;
        this.symbolBlacklistService = symbolBlacklistService;
        this.missingFieldFiller = missingFieldFiller;
        this.fallbackChainBuilder = fallbackChainBuilder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    record FetchResult(boolean succeeded, boolean skipRetry) {
        static FetchResult ok() {
            return new FetchResult(true, false);
        }

        static FetchResult blacklisted() {
            return new FetchResult(false, true);
        }

        static FetchResult retryableFailure() {
            return new FetchResult(false, false);
        }
    }

    FetchResult fetchAndPersist(String symbol, LocalDate tradeDate) {
        log.info("");
        log.info("[GapFetcher] ================================================");
        log.info("[GapFetcher] === 补缺 {}，日期 {} ===", symbol, tradeDate);
        log.info("[GapFetcher] ================================================");
        log.info("");

        List<FallbackChainBuilder.FallbackSource> fallbacks = fallbackChainBuilder.buildForSymbol(symbol);

        Map<String, Boolean> sourceNotFoundResults = new LinkedHashMap<>();
        boolean accountLevelAbort = false;

        for (FallbackChainBuilder.FallbackSource source : fallbacks) {
            log.info("");
            log.info("[GapFetcher] {} source start", source.name());
            log.info("[GapFetcher] {} source now send request: dateRange symbol={}, TradeDate={}", source.name(), symbol, tradeDate);

            KLineData klineData = null;
            try {
                klineData = source.fetcher().fetch(symbol, tradeDate);
                if (isKLineDataEmpty(klineData)) {
                    sourceNotFoundResults.put(source.name(), true);
                    log.warn("[GapFetcher] {} source then received response: returned empty result for symbol={} (counted as not-found)",
                            source.name(), symbol);
                    log.info("[GapFetcher] {} source end", source.name());
                    log.info("");
                    continue;
                }
                log.info("[GapFetcher] {} source then received response: itemsCount={}", source.name(), klineData.getItems().size());
                for (KLineIterator item : klineData.getItems()) {
                    LocalDate itemDate = item.getTimeString() != null && !item.getTimeString().isEmpty()
                            ? LocalDate.parse(item.getTimeString())
                            : epochMillisToLocalDate(item.getTime());
                    log.info("[GapFetcher] {} source item: symbol={}, epochTime={}, timeString='{}', parsedDate={}, open={}, close={}",
                            source.name(), item.getSymbol(), item.getTime(), item.getTimeString(), itemDate,
                            item.getOpen(), item.getClose());
                    if ((item.getOpen() == null || item.getOpen().compareTo(java.math.BigDecimal.ZERO) == 0)
                            && (item.getClose() == null || item.getClose().compareTo(java.math.BigDecimal.ZERO) == 0)) {
                        log.warn("[GapFetcher] {} source item: skip zero-price placeholder symbol={}, date={}",
                                source.name(), item.getSymbol(), itemDate);
                        continue;
                    }
                    if (itemDate.equals(tradeDate)) {
                        log.info("[GapFetcher] {} source then received response: matched targetDate={}", source.name(), tradeDate);
                        StockDailyBar bar = persist(symbol, tradeDate, item, source.name());
                        missingFieldFiller.mergeAfterHoursIfAvailable(symbol, tradeDate, bar, source.ds());
                        final String sourceName = source.name();
                        runInTx(() -> stockDataSourcePriorityService.updatePriority(
                                symbol, sourceName, java.time.LocalDateTime.now()));
                        log.info("[GapFetcher] fillWithFallback: success symbol={}, source={}", symbol, source.name());
                        log.info("[GapFetcher] {} source end", source.name());
                        log.info("");
                        symbolBlacklistService.resetCount(symbol);
                        return FetchResult.ok();
                    }
                }
                log.warn("[GapFetcher] fillWithFallback: date mismatch symbol={}, source={}, targetDate={}",
                        symbol, source.name(), tradeDate);
                log.info("[GapFetcher] {} source end", source.name());
                log.info("");
            } catch (StockDataException e) {
                switch (e.getCategory()) {
                    case CONFIRMED_NOT_FOUND -> {
                        sourceNotFoundResults.put(source.name(), true);
                        log.warn("[GapFetcher] fillWithFallback: confirmed not-found symbol={}, source={}, error={}",
                                symbol, source.name(), e.getMessage());
                    }
                    case ACCOUNT_LEVEL -> {
                        long until = System.currentTimeMillis() + SOURCE_COOLDOWN_MILLIS;
                        fallbackChainBuilder.isSourceCooledDown(source.name());
                        // 冷却写入仍由调用方持有的 map 完成；这里通过 builder 的包级访问能力写入。
                        fallbackChainBuilder.putCooldown(source.name(), until);
                        accountLevelAbort = true;
                        log.error("[GapFetcher] fillWithFallback: account-level error symbol={}, source={}, " +
                                        "circuit open until={}, stop fallback chain — error={}",
                                symbol, source.name(), until, e.getMessage());
                    }
                    default -> log.warn("[GapFetcher] fillWithFallback: transient failure symbol={}, source={}, " +
                                    "not counted for blacklist — error={}",
                            symbol, source.name(), e.getMessage());
                }
                log.info("[GapFetcher] {} source end", source.name());
                log.info("");
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                boolean isNotFound = isNotFoundError(errorMsg);
                if (isNotFound) {
                    sourceNotFoundResults.put(source.name(), true);
                }
                log.error("[GapFetcher] fillWithFallback: error symbol={}, source={}, notFound={}, error={}",
                        symbol, source.name(), isNotFound, errorMsg);
                log.info("[GapFetcher] {} source end", source.name());
                log.info("");
            }

            if (accountLevelAbort) {
                break;
            }
        }

        long notFoundCount = sourceNotFoundResults.values().stream()
                .filter(Boolean.TRUE::equals)
                .count();

        if (notFoundCount >= 2) {
            Map<String, String> sourceErrors = new LinkedHashMap<>();
            for (Map.Entry<String, Boolean> entry : sourceNotFoundResults.entrySet()) {
                if (entry.getValue()) {
                    sourceErrors.put(entry.getKey(), "not_found");
                }
            }

            runInTx(() -> {
                symbolBlacklistService.recordNotFound(symbol, sourceErrors);
                dataFillTaskRepository.updateStatusBySymbolAndStatusIn(
                        symbol,
                        java.util.List.of("pending", "retrying"),
                        "stopped",
                        "双数据源以上报 404，已进黑名单"
                );
            });

            log.warn("[GapFetcher] [blacklist] symbol={} added to blacklist: {} sources returned not-found",
                    symbol, notFoundCount);
            log.warn("[GapFetcher] fillWithFallback: all sources failed symbol={}, date={}, notFoundCount={}",
                    symbol, tradeDate, notFoundCount);
            return FetchResult.blacklisted();
        }

        log.warn("[GapFetcher] fillWithFallback: all sources failed symbol={}, date={}, notFoundCount={}",
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
        bar.setChangePercent(item.getChangePercent());
        bar.setAfterHours(item.getAfterHours());
        bar.setAfterHoursChangePercent(item.getAfterHoursChangePercent());
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
        missingFieldFiller.applyMissingFieldsMark(bar);
        runInTx(() -> stockDailyBarRepository.save(bar));
        return bar;
    }

    private static boolean isKLineDataEmpty(KLineData klineData) {
        return klineData == null || klineData.getItems() == null || klineData.getItems().isEmpty();
    }

    private static boolean isNotFoundError(String errorMessage) {
        if (errorMessage != null && !errorMessage.isEmpty()) {
            return StockDataException.isNotFoundMessage(errorMessage.toLowerCase());
        }
        return false;
    }

    private static LocalDate epochMillisToLocalDate(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis)
                .atZone(AMERICA_NY)
                .toLocalDate();
    }

    private void runInTx(Runnable action) {
        transactionTemplate.executeWithoutResult(status -> action.run());
    }
}
