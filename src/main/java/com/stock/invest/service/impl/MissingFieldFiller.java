package com.stock.invest.service.impl;

import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.DataSourceStrategy;
import com.stock.invest.service.FieldCapabilityService;
import com.stock.invest.exception.StockDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 字段增补模块。
 *
 * <p>负责对已有日 K 记录做缺失字段发现、标记和增补：
 * 日线 OHLCV、change_percent、盘后价等由能力表驱动。</p>
 */
class MissingFieldFiller {

    private static final Logger log = LoggerFactory.getLogger(MissingFieldFiller.class);

    private static final ZoneId AMERICA_NY = ZoneId.of("America/New_York");
    private static final int FILL_WINDOW_CALENDAR_DAYS = 45;
    /** 发现阶段单批最大记录数，避免一次性把存量未检查记录全部载入内存 */
    private static final int DISCOVER_BATCH_SIZE = 1000;

    private final StockDailyBarRepository stockDailyBarRepository;
    private final List<DataSourceStrategy> dataSources;
    private final FieldCapabilityService fieldCapabilityService;
    private final TransactionTemplate transactionTemplate;

    MissingFieldFiller(StockDailyBarRepository stockDailyBarRepository,
                       List<DataSourceStrategy> dataSources,
                       FieldCapabilityService fieldCapabilityService,
                       PlatformTransactionManager transactionManager) {
        this.stockDailyBarRepository = stockDailyBarRepository;
        this.dataSources = dataSources;
        this.fieldCapabilityService = fieldCapabilityService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    void applyMissingFieldsMark(StockDailyBar bar) {
        List<String> missing = new ArrayList<>();
        String source = bar.getSource();
        if (isMissingPrice(bar.getOpenPrice()) && fieldCapabilityService.isMarkable(source, DataGapFillerServiceImpl.F_OPEN)) {
            missing.add(DataGapFillerServiceImpl.F_OPEN);
        }
        if (isMissingPrice(bar.getHighPrice()) && fieldCapabilityService.isMarkable(source, DataGapFillerServiceImpl.F_HIGH)) {
            missing.add(DataGapFillerServiceImpl.F_HIGH);
        }
        if (isMissingPrice(bar.getLowPrice()) && fieldCapabilityService.isMarkable(source, DataGapFillerServiceImpl.F_LOW)) {
            missing.add(DataGapFillerServiceImpl.F_LOW);
        }
        if (isMissingPrice(bar.getClosePrice()) && fieldCapabilityService.isMarkable(source, DataGapFillerServiceImpl.F_CLOSE)) {
            missing.add(DataGapFillerServiceImpl.F_CLOSE);
        }
        if (isMissingVolume(bar.getVolume()) && fieldCapabilityService.isMarkable(source, DataGapFillerServiceImpl.F_VOLUME)) {
            missing.add(DataGapFillerServiceImpl.F_VOLUME);
        }
        if (bar.getChangePercent() == null && fieldCapabilityService.isMarkable(source, DataGapFillerServiceImpl.F_CHANGE_PERCENT)) {
            missing.add(DataGapFillerServiceImpl.F_CHANGE_PERCENT);
        }
        if (bar.getAfterHours() == null && fieldCapabilityService.isMarkable(source, DataGapFillerServiceImpl.F_AFTER_HOURS)) {
            missing.add(DataGapFillerServiceImpl.F_AFTER_HOURS);
        }
        if (bar.getAfterHoursChangePercent() == null
                && fieldCapabilityService.isMarkable(source, DataGapFillerServiceImpl.F_AFTER_HOURS_CHANGE_PERCENT)) {
            missing.add(DataGapFillerServiceImpl.F_AFTER_HOURS_CHANGE_PERCENT);
        }
        if (missing.isEmpty()) {
            bar.setMissingFields(null);
            bar.setFieldFillStatus(DataGapFillerServiceImpl.STATUS_CONFIRMED);
        } else {
            bar.setMissingFields(String.join(",", missing));
            bar.setFieldFillStatus(DataGapFillerServiceImpl.STATUS_PENDING);
        }
    }

    int discoverMissingFields() {
        List<StockDailyBar> unchecked = stockDailyBarRepository.findUnchecked(
                org.springframework.data.domain.PageRequest.of(0, DISCOVER_BATCH_SIZE));
        LocalDate windowStart = fillWindowStart();
        int discovered = 0;
        for (StockDailyBar bar : unchecked) {
            try {
                if (bar.getTradeDate().isBefore(windowStart)) {
                    if (bar.getFieldFillStatus() == null || bar.getMissingFields() != null) {
                        bar.setMissingFields(null);
                        bar.setFieldFillStatus(DataGapFillerServiceImpl.STATUS_CONFIRMED);
                        runInTx(() -> stockDailyBarRepository.save(bar));
                    }
                } else {
                    applyMissingFieldsMark(bar);
                    runInTx(() -> stockDailyBarRepository.save(bar));
                }
                discovered++;
            } catch (Exception e) {
                log.warn("[MissingFieldFiller] discoverMissingFields failed for {} {}: {}",
                        bar.getSymbol(), bar.getTradeDate(), e.getMessage());
            }
        }
        if (!unchecked.isEmpty()) {
            log.info("[MissingFieldFiller] discoverMissingFields: scanned {} unchecked records, marked {} (window={}~today)",
                    unchecked.size(), discovered, windowStart);
        }
        return discovered;
    }

    int fillMissingFields() {
        LocalDate windowStart = fillWindowStart();
        AtomicInteger staleRef = new AtomicInteger();
        runInTx(() -> staleRef.set(stockDailyBarRepository.confirmStalePending(windowStart)));
        int stale = staleRef.get();
        if (stale > 0) {
            log.info("[MissingFieldFiller] fillMissingFields: confirmed {} stale pending (tradeDate < {}) as terminal",
                    stale, windowStart);
        }
        List<StockDailyBar> pending = stockDailyBarRepository.findByFieldFillStatus(
                DataGapFillerServiceImpl.STATUS_PENDING,
                org.springframework.data.domain.PageRequest.of(0, DataGapFillerServiceImpl.MAX_FILL_FIELDS_PER_RUN));
        int completed = 0;
        for (StockDailyBar bar : pending) {
            try {
                if (fillMissingFieldsForBar(bar)) {
                    completed++;
                }
            } catch (Exception e) {
                log.warn("[MissingFieldFiller] fillMissingFields failed for {} {}: {}",
                        bar.getSymbol(), bar.getTradeDate(), e.getMessage());
            }
        }
        if (!pending.isEmpty()) {
            log.info("[MissingFieldFiller] fillMissingFields: processed {} pending records, completed {}",
                    pending.size(), completed);
        }
        return completed;
    }

    boolean fillMissingFieldsForBar(StockDailyBar bar) {
        List<String> missing = parseMissingFields(bar.getMissingFields());
        if (missing.isEmpty()) {
            bar.setFieldFillStatus(DataGapFillerServiceImpl.STATUS_CONFIRMED);
            runInTx(() -> stockDailyBarRepository.save(bar));
            return true;
        }

        String sourceName = bar.getSource();
        List<DataSourceStrategy> querySequence = buildQuerySequence(sourceName);
        if (querySequence.isEmpty()) {
            log.warn("[MissingFieldFiller] fillMissingFields: no available source for sourceName={}, symbol={}",
                    sourceName, bar.getSymbol());
            return false;
        }

        boolean transientFailure = false;
        boolean anyUpdated = false;
        String updaterSource = null;

        List<String> klineFields = missing.stream()
                .filter(f -> !DataGapFillerServiceImpl.F_AFTER_HOURS.equals(f)
                        && !DataGapFillerServiceImpl.F_AFTER_HOURS_CHANGE_PERCENT.equals(f))
                .toList();
        if (!klineFields.isEmpty()) {
            KLineIterator matchedItem = null;
            DataSourceStrategy matchedDs = null;
            for (DataSourceStrategy ds : querySequence) {
                try {
                    KLineData data = ds.getDailyKLineDataByDateRange(bar.getSymbol(), bar.getTradeDate());
                    KLineIterator item = findItemByDate(data, bar.getTradeDate());
                    if (item != null) {
                        matchedItem = item;
                        matchedDs = ds;
                        break;
                    }
                } catch (StockDataException e) {
                    if (e.getCategory() == StockDataException.ErrorCategory.CONFIRMED_NOT_FOUND) {
                        continue;
                    }
                    transientFailure = true;
                } catch (Exception e) {
                    transientFailure = true;
                }
            }
            if (matchedItem != null) {
                boolean updated = applyKlineItemToBar(bar, matchedItem, klineFields);
                anyUpdated |= updated;
                if (updated && matchedDs != null) {
                    updaterSource = matchedDs.getSourceName();
                }
            } else {
                List<String> nonCalcFields = klineFields.stream()
                        .filter(f -> !DataGapFillerServiceImpl.F_CHANGE_PERCENT.equals(f)).toList();
                if (!nonCalcFields.isEmpty()) {
                    clearMissingFields(bar, "source-has-no-kline-item", nonCalcFields.toArray(new String[0]));
                }
                if (klineFields.contains(DataGapFillerServiceImpl.F_CHANGE_PERCENT)) {
                    java.math.BigDecimal pct = calcChangePercentFromPrevClose(
                            bar.getSymbol(), bar.getTradeDate(), bar.getClosePrice());
                    if (pct != null) {
                        bar.setChangePercent(pct);
                        clearMissingFields(bar, "calculated-from-prev-close", DataGapFillerServiceImpl.F_CHANGE_PERCENT);
                        anyUpdated = true;
                    }
                }
            }
        }

        boolean hasAhMissing = missing.contains(DataGapFillerServiceImpl.F_AFTER_HOURS)
                || missing.contains(DataGapFillerServiceImpl.F_AFTER_HOURS_CHANGE_PERCENT);
        if (hasAhMissing) {
            boolean ahResolved = false;
            for (DataSourceStrategy ds : querySequence) {
                if (!supportsAfterHoursMerge(ds)) {
                    continue;
                }
                try {
                    KLineData ahData = ds.getAfterHoursKLineDataByDateRange(bar.getSymbol(), bar.getTradeDate());
                    if (isKLineDataEmpty(ahData)) {
                        continue;
                    }
                    KLineIterator ahItem = findItemByDate(ahData, bar.getTradeDate());
                    if (ahItem != null && ahItem.getClose() != null) {
                        bar.setAfterHours(ahItem.getClose());
                        if (ahItem.getAfterHoursChangePercent() != null) {
                            bar.setAfterHoursChangePercent(ahItem.getAfterHoursChangePercent());
                        } else {
                            java.math.BigDecimal regClose = bar.getClosePrice();
                            if (regClose != null && regClose.compareTo(java.math.BigDecimal.ZERO) != 0) {
                                bar.setAfterHoursChangePercent(ahItem.getClose().subtract(regClose)
                                        .divide(regClose, 8, java.math.RoundingMode.HALF_UP)
                                        .multiply(java.math.BigDecimal.valueOf(100))
                                        .setScale(4, java.math.RoundingMode.HALF_UP));
                            }
                        }
                        clearMissingFields(bar, "after-hours-value-resolved", DataGapFillerServiceImpl.F_AFTER_HOURS,
                                DataGapFillerServiceImpl.F_AFTER_HOURS_CHANGE_PERCENT);
                        anyUpdated = true;
                        updaterSource = ds.getSourceName();
                        ahResolved = true;
                        break;
                    }
                } catch (Exception e) {
                    log.warn("[MissingFieldFiller] fillMissingFields after-hours failed for {} {} via {}: {}",
                            bar.getSymbol(), bar.getTradeDate(), ds.getSourceName(), e.getMessage());
                    transientFailure = true;
                }
            }
            if (!ahResolved && !transientFailure) {
                clearMissingFields(bar, "after-hours-confirmed-unavailable", DataGapFillerServiceImpl.F_AFTER_HOURS,
                        DataGapFillerServiceImpl.F_AFTER_HOURS_CHANGE_PERCENT);
            }
        }

        if (updaterSource != null && !"tiger_snap".equals(sourceName)) {
            log.info("[MissingFieldFiller] fillMissingFields: source update {} -> {} for {} {}",
                    sourceName, updaterSource, bar.getSymbol(), bar.getTradeDate());
            bar.setSource(updaterSource);
        }

        boolean stillMissing = !parseMissingFields(bar.getMissingFields()).isEmpty();
        if (transientFailure && stillMissing) {
            if (anyUpdated) {
                runInTx(() -> stockDailyBarRepository.save(bar));
            }
            return false;
        }
        bar.setMissingFields(null);
        bar.setFieldFillStatus(DataGapFillerServiceImpl.STATUS_CONFIRMED);
        runInTx(() -> stockDailyBarRepository.save(bar));
        return true;
    }

    void mergeAfterHoursIfAvailable(String symbol, LocalDate tradeDate, StockDailyBar bar,
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
                clearMissingFields(bar, "after-hours-merged", DataGapFillerServiceImpl.F_AFTER_HOURS,
                        DataGapFillerServiceImpl.F_AFTER_HOURS_CHANGE_PERCENT);
                runInTx(() -> stockDailyBarRepository.save(bar));
                return;
            }
        } catch (Exception e) {
            log.warn("[MissingFieldFiller] mergeAfterHours: failed symbol={}, date={}, error={}",
                    symbol, tradeDate, e.getMessage());
        }
    }

    boolean supportsAfterHoursMerge(DataSourceStrategy source) {
        return fieldCapabilityService.isMarkable(source.getSourceName(), DataGapFillerServiceImpl.F_AFTER_HOURS);
    }

    private LocalDate fillWindowStart() {
        return ZonedDateTime.now(AMERICA_NY).toLocalDate().minusDays(FILL_WINDOW_CALENDAR_DAYS);
    }

    private boolean applyKlineItemToBar(StockDailyBar bar, KLineIterator item, List<String> missing) {
        boolean updated = false;
        if (missing.contains(DataGapFillerServiceImpl.F_OPEN) && item.getOpen() != null) {
            bar.setOpenPrice(item.getOpen());
            updated = true;
        }
        if (missing.contains(DataGapFillerServiceImpl.F_HIGH) && item.getHigh() != null) {
            bar.setHighPrice(item.getHigh());
            updated = true;
        }
        if (missing.contains(DataGapFillerServiceImpl.F_LOW) && item.getLow() != null) {
            bar.setLowPrice(item.getLow());
            updated = true;
        }
        if (missing.contains(DataGapFillerServiceImpl.F_CLOSE) && item.getClose() != null) {
            bar.setClosePrice(item.getClose());
            updated = true;
        }
        if (missing.contains(DataGapFillerServiceImpl.F_VOLUME) && item.getVolume() > 0) {
            bar.setVolume(item.getVolume());
            updated = true;
        }
        if (missing.contains(DataGapFillerServiceImpl.F_CHANGE_PERCENT)) {
            if (item.getChangePercent() != null) {
                bar.setChangePercent(item.getChangePercent());
                updated = true;
            } else if (recalcChangePercent(bar)) {
                updated = true;
            }
        }
        List<String> remaining = parseMissingFields(bar.getMissingFields());
        if (remaining.isEmpty()) {
            return updated;
        }
        remaining.removeAll(missing.stream()
                .filter(f -> {
                    switch (f) {
                        case DataGapFillerServiceImpl.F_OPEN: return bar.getOpenPrice() != null;
                        case DataGapFillerServiceImpl.F_HIGH: return bar.getHighPrice() != null;
                        case DataGapFillerServiceImpl.F_LOW: return bar.getLowPrice() != null;
                        case DataGapFillerServiceImpl.F_CLOSE: return bar.getClosePrice() != null;
                        case DataGapFillerServiceImpl.F_VOLUME: return bar.getVolume() != null && bar.getVolume() > 0;
                        case DataGapFillerServiceImpl.F_CHANGE_PERCENT: return bar.getChangePercent() != null;
                        default: return false;
                    }
                }).toList());
        if (remaining.size() != parseMissingFields(bar.getMissingFields()).size()) {
            bar.setMissingFields(remaining.isEmpty() ? null : String.join(",", remaining));
        }
        return updated;
    }

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
     * 将指定字段从缺失集中移除。
     * reason 明确记录本次移除语义：
     * 源返回了值 / 源确认无值 / 本地计算得出 / 盘后合并，避免“清标记”语义混淆。
     */
    void clearMissingFields(StockDailyBar bar, String reason, String... fields) {
        List<String> missing = parseMissingFields(bar.getMissingFields());
        if (missing.isEmpty()) {
            return;
        }
        Set<String> toClear = new HashSet<>(java.util.Arrays.asList(fields));
        missing.removeIf(toClear::contains);
        log.debug("[MissingFieldFiller] clearMissingFields symbol={}, date={}, fields={}, reason={}",
                bar.getSymbol(), bar.getTradeDate(), toClear, reason);
        if (missing.isEmpty()) {
            bar.setMissingFields(null);
            bar.setFieldFillStatus(DataGapFillerServiceImpl.STATUS_CONFIRMED);
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

    private static boolean isMissingPrice(java.math.BigDecimal v) {
        return v == null || v.compareTo(java.math.BigDecimal.ZERO) == 0;
    }

    private static boolean isMissingVolume(Long v) {
        return v == null || v <= 0L;
    }

    private static boolean isKLineDataEmpty(KLineData klineData) {
        return klineData == null || klineData.getItems() == null || klineData.getItems().isEmpty();
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
