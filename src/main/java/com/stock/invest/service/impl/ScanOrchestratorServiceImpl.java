package com.stock.invest.service.impl;

import com.stock.invest.config.ScannerProperties;
import com.stock.invest.entity.ScreeningMatch;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.enums.dto.ScreenerRunResponseDto;
import com.stock.invest.enums.dto.ScreeningMatchProjection;
import com.stock.invest.enums.dto.ScreeningResultDto;
import com.stock.invest.repository.ScreeningMatchRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.MarketDataSourceRouter;
import com.stock.invest.service.PatternEvaluateService;
import com.stock.invest.service.PriceVolumeCacheService;
import com.stock.invest.service.ScanOrchestratorService;
import com.stock.invest.service.TigerWatchlistIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class ScanOrchestratorServiceImpl implements ScanOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(ScanOrchestratorServiceImpl.class);
    private static final int MIN_WINDOW_DAYS = 3;
    private static final int MAX_WINDOW_DAYS = 7;

    private final ScannerProperties scannerProperties;
    private final MarketDataSourceRouter marketDataSourceRouter;
    private final PriceVolumeCacheService priceVolumeCacheService;
    private final PatternEvaluateService patternEvaluateService;
    private final ScreeningMatchRepository screeningMatchRepository;
    private final StockDailyBarRepository stockDailyBarRepository;
    private final Executor scanExecutor;

    /**
     * 统一使用构造函数注入 - 符合 Spring 最佳实践
     */
    public ScanOrchestratorServiceImpl(
            ScannerProperties scannerProperties,
            MarketDataSourceRouter marketDataSourceRouter,
            PriceVolumeCacheService priceVolumeCacheService,
            PatternEvaluateService patternEvaluateService,
            ScreeningMatchRepository screeningMatchRepository,
            StockDailyBarRepository stockDailyBarRepository,
            @Qualifier("scanExecutor") Executor scanExecutor) {
        this.scannerProperties = scannerProperties;
        this.marketDataSourceRouter = marketDataSourceRouter;
        this.priceVolumeCacheService = priceVolumeCacheService;
        this.patternEvaluateService = patternEvaluateService;
        this.screeningMatchRepository = screeningMatchRepository;
        this.stockDailyBarRepository = stockDailyBarRepository;
        this.scanExecutor = scanExecutor;
    }

    @Override
    @Transactional
    public ScreenerRunResponseDto runDailyScan(LocalDate tradeDate, int limit) {
        return runDailyScan(tradeDate, limit, MAX_WINDOW_DAYS);
    }

    @Override
    @Transactional
    public ScreenerRunResponseDto runDailyScan(LocalDate tradeDate, int limit, int windowDays) {
        LocalDate targetDate = tradeDate == null ? LocalDate.now() : tradeDate;
        int target = Math.max(1, limit);
        int days = sanitizeWindowDays(windowDays);
        String batchId = UUID.randomUUID().toString();

        List<String> candidates = marketDataSourceRouter.loadCandidates(
                Math.max(target * 3, scannerProperties.getMaxCandidates() / 2),
                scannerProperties.getMinPrice(),
                scannerProperties.getMaxPrice()
        );

        final int matchLimit = target;
        AtomicInteger processed = new AtomicInteger(0);
        List<ScreeningMatch> matches = Collections.synchronizedList(new ArrayList<ScreeningMatch>());
        List<CompletableFuture<Void>> futures = candidates.stream()
                .limit(candidates.size())
                .map(sym -> CompletableFuture.supplyAsync(() -> {
                    List<StockDailyBar> bars = priceVolumeCacheService.refreshBarsForSymbol(sym, "tigeropen", targetDate, days);
                    return new CandidateResult(sym, bars);
                }, scanExecutor).thenAccept(cr -> {
                    List<StockDailyBar> bars = cr.bars;
                    String crSymbol = cr.symbol;
                    if (bars.size() < days) {
                        return;
                    }
                    if (!patternEvaluateService.matchesIncreasingVolumePattern(bars, days)) {
                        return;
                    }
                    StockDailyBar latest = bars.get(bars.size() - 1);
                    if (latest.getClosePrice() == null) {
                        return;
                    }
                    if (latest.getClosePrice() < scannerProperties.getMinPrice()
                            || latest.getClosePrice() > scannerProperties.getMaxPrice()) {
                        return;
                    }
                    synchronized (matches) {
                        if (matches.size() >= matchLimit) {
                            return;
                        }
                        ScreeningMatch row = new ScreeningMatch();
                        row.setBatchId(batchId);
                        row.setDataSource(latest.getSource());
                        row.setSymbol(crSymbol);
                        row.setLastClose(latest.getClosePrice());
                        row.setTradeDate(targetDate);
                        row.setPrice(latest.getClosePrice());
                        row.setRise(latest.getClosePrice() > latest.getOpenPrice());
                        row.setWindowDays(days);
                        matches.add(row);
                    }
                    processed.incrementAndGet();
                }).exceptionally(ex -> {
                    log.warn("scan candidate failed symbol={}, error={}", sym, ex.getMessage());
                    return null;
                }))
                .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .join();

        for (ScreeningMatch match : matches) {
            screeningMatchRepository.save(match);
        }

        log.info("daily scan done tradeDate={}, windowDays={}, candidates={}, processed={}, matched={}",
                targetDate, days, candidates.size(), processed.get(), matches.size());
        return new ScreenerRunResponseDto(batchId, targetDate, candidates.size(), processed.get(), matches.size());
    }

    /**
     * Simple holder for a symbol and its bars.
     */
    private static final class CandidateResult {
        final String symbol;
        final List<StockDailyBar> bars;

        CandidateResult(String symbol, List<StockDailyBar> bars) {
            this.symbol = symbol;
            this.bars = bars;
        }
    }

    @Override
    @Transactional
    public ScreenerRunResponseDto runDailyScanFromSnapshotImport(LocalDate tradeDate, int limit) {
        return runDailyScanFromSnapshotImport(tradeDate, limit, MAX_WINDOW_DAYS);
    }

    @Override
    @Transactional
    public ScreenerRunResponseDto runDailyScanFromSnapshotImport(LocalDate tradeDate, int limit, int windowDays) {
        LocalDate targetDate = tradeDate == null ? LocalDate.now() : tradeDate;
        int target = Math.max(1, limit);
        int days = sanitizeWindowDays(windowDays);
        String batchId = UUID.randomUUID().toString();
        List<String> candidates = stockDailyBarRepository.findDistinctSymbolsByTradeDateAndSourceAndClosePriceBetween(
                targetDate,
                TigerWatchlistIngestService.SNAPSHOT_SOURCE,
                scannerProperties.getMinPrice(),
                scannerProperties.getMaxPrice());
        Collections.sort(candidates);

        if (candidates.isEmpty()) {
            log.info("snapshot-import scan done tradeDate={}, windowDays={}, candidates=0, processed=0, matched=0",
                    targetDate, days);
            return new ScreenerRunResponseDto(batchId, targetDate, 0, 0, 0);
        }

        List<StockDailyBar> allBars = stockDailyBarRepository.findBySymbolInAndSourceOrderBySymbolAscTradeDateDesc(
                candidates, TigerWatchlistIngestService.SNAPSHOT_SOURCE);

        Map<String, List<StockDailyBar>> barsBySymbol = new LinkedHashMap<>();
        for (StockDailyBar bar : allBars) {
            barsBySymbol.computeIfAbsent(bar.getSymbol(), k -> new ArrayList<>()).add(bar);
        }

        int processed = 0;
        int matched = 0;
        for (String symbol : candidates) {
            if (matched >= target) {
                break;
            }
            processed++;
            List<StockDailyBar> bars = barsBySymbol.getOrDefault(symbol, Collections.emptyList());
            if (bars.size() < days) {
                continue;
            }
            List<StockDailyBar> window = new ArrayList<>(bars.subList(0, Math.min(days, bars.size())));
            window.sort(Comparator.comparing(StockDailyBar::getTradeDate));
            if (window.size() < days) {
                continue;
            }
            StockDailyBar latest = window.get(window.size() - 1);
            if (!targetDate.equals(latest.getTradeDate())) {
                continue;
            }
            if (!patternEvaluateService.matchesIncreasingVolumePattern(window, days)) {
                continue;
            }
            if (latest.getClosePrice() == null) {
                continue;
            }
            if (latest.getClosePrice() < scannerProperties.getMinPrice() || latest.getClosePrice() > scannerProperties.getMaxPrice()) {
                continue;
            }
            ScreeningMatch row = new ScreeningMatch();
            row.setBatchId(batchId);
            row.setDataSource(TigerWatchlistIngestService.SNAPSHOT_SOURCE);
            row.setSymbol(symbol);
            row.setLastClose(latest.getClosePrice());
            row.setTradeDate(targetDate);
            row.setPrice(latest.getClosePrice());
            row.setRise(latest.getClosePrice() > latest.getOpenPrice());
            row.setWindowDays(days);
            screeningMatchRepository.save(row);
            matched++;
        }
        log.info("snapshot-import scan done tradeDate={}, windowDays={}, candidates={}, processed={}, matched={}",
                targetDate, days, candidates.size(), processed, matched);
        return new ScreenerRunResponseDto(batchId, targetDate, candidates.size(), processed, matched);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScreeningResultDto> queryByDate(LocalDate tradeDate, Double minPrice, Double maxPrice) {
        LocalDate date = tradeDate == null ? LocalDate.now() : tradeDate;
        double min = minPrice == null ? scannerProperties.getMinPrice() : minPrice;
        double max = maxPrice == null ? scannerProperties.getMaxPrice() : maxPrice;
        List<ScreeningMatchProjection> rows = screeningMatchRepository.findProjectedByTradeDateAndPriceBetweenOrderByPriceDesc(date, min, max);
        return toDto(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScreeningResultDto> queryLatest(Double minPrice, Double maxPrice) {
        Optional<ScreeningMatch> latest = screeningMatchRepository.findTopByOrderByTradeDateDescIdDesc();
        if (!latest.isPresent() || latest.get().getTradeDate() == null) {
            return new ArrayList<>();
        }
        return queryByDate(latest.get().getTradeDate(), minPrice, maxPrice);
    }

    private static List<ScreeningResultDto> toDto(List<? extends ScreeningMatchProjection> rows) {
        List<ScreeningResultDto> out = new ArrayList<>();
        for (ScreeningMatchProjection row : rows) {
            out.add(new ScreeningResultDto(
                    row.getSymbol(),
                    row.getPrice(),
                    row.getRise(),
                    row.getDataSource(),
                    row.getTradeDate()
            ));
        }
        return out;
    }

    private static int sanitizeWindowDays(int windowDays) {
        if (windowDays < MIN_WINDOW_DAYS) {
            return MIN_WINDOW_DAYS;
        }
        if (windowDays > MAX_WINDOW_DAYS) {
            return MAX_WINDOW_DAYS;
        }
        return windowDays;
    }
}
