package com.stock.invest.service.impl;

import com.stock.invest.config.ScannerProperties;
import com.stock.invest.constant.WindowConstants;
import com.stock.invest.entity.ScreeningMatch;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.repository.ScreeningMatchRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.PatternEvaluateService;
import com.stock.invest.service.ScreeningService;
import com.stock.invest.service.TradingCalendarDbService;
import com.stock.invest.entity.TradingCalendarEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 模式筛选服务实现。
 * <p>
 * 从 stock_daily_bars 读取最近 7 天数据，按 symbol 分组后传入
 * {@link PatternEvaluateServiceImpl} 做模式评估，结果写入 screening_match 表。
 * </p>
 * <p>
 * 每个 symbol 同时对 2/3/4/5/6/7 天窗口做并行评估，分别记录结果。
 * </p>
 */
@Service
public class ScreeningServiceImpl implements ScreeningService {

    private static final Logger log = LoggerFactory.getLogger(ScreeningServiceImpl.class);

    private final StockDailyBarRepository stockDailyBarRepository;
    private final ScreeningMatchRepository screeningMatchRepository;
    private final PatternEvaluateService patternEvaluateService;
    private final ScannerProperties scannerProperties;
    private final TradingCalendarDbService tradingCalendarDbService;

    public ScreeningServiceImpl(
            StockDailyBarRepository stockDailyBarRepository,
            ScreeningMatchRepository screeningMatchRepository,
            PatternEvaluateService patternEvaluateService,
            ScannerProperties scannerProperties,
            TradingCalendarDbService tradingCalendarDbService) {
        this.stockDailyBarRepository = stockDailyBarRepository;
        this.screeningMatchRepository = screeningMatchRepository;
        this.patternEvaluateService = patternEvaluateService;
        this.scannerProperties = scannerProperties;
        this.tradingCalendarDbService = tradingCalendarDbService;
    }

    @Override
    @Transactional
    public String runScreening(LocalDate tradeDate) {
        LocalDate targetDate = tradeDate == null ? ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate() : tradeDate;
        String batchId = UUID.randomUUID().toString();

        log.info("ScreeningServiceImpl: start batchId={}, date={}", batchId, targetDate);

        // 获取最近 MAX_SEARCH_DAYS 天的数据
        LocalDate startDate = targetDate.minusDays(WindowConstants.MAX_WINDOW_DAYS + 2);
        List<StockDailyBar> allBars = stockDailyBarRepository
                .findByTradeDateBetweenOrderByTradeDateDesc(startDate, targetDate);

        if (allBars == null || allBars.isEmpty()) {
            log.warn("ScreeningServiceImpl: no bars found for date={}", targetDate);
            return batchId;
        }

        // 按 symbol 分组
        Map<String, List<StockDailyBar>> barsBySymbol = new LinkedHashMap<>();
        LocalDate latestTradeDate = allBars.get(0).getTradeDate();
        for (StockDailyBar bar : allBars) {
            barsBySymbol.computeIfAbsent(bar.getSymbol(), k -> new ArrayList<>()).add(bar);
        }

        // 对每个 symbol 并行评估 6 个窗口 (2d~7d)
        List<ScreeningMatch> allRows = new ArrayList<>();
        int processed = 0;
        int totalMatchedRows = 0;

        for (Map.Entry<String, List<StockDailyBar>> entry : barsBySymbol.entrySet()) {
            String symbol = entry.getKey();
            List<StockDailyBar> bars = entry.getValue();

            // 升序排列（PatternEvaluateService 要求 oldest-first）
            bars.sort(Comparator.comparing(StockDailyBar::getTradeDate));

            StockDailyBar latest = bars.get(bars.size() - 1);

            // 以库里实际最新日期作为筛选基准，targetDate 仅作批次标记
            if (!latestTradeDate.equals(latest.getTradeDate())) {
                continue;
            }
            if (latest.getClosePrice() == null) {
                continue;
            }

            processed++;

            // 多窗口并行评估：数据够哪个窗口就评估哪个
            for (int windowDays : WindowConstants.ALL_WINDOW_DAYS) {
                if (bars.size() < windowDays) {
                    continue;
                }
                // 取对应窗口长度的数据
                List<StockDailyBar> windowSlice = bars.subList(bars.size() - windowDays, bars.size());

                // 连续开盘日校验
                if (!isWindowConsecutiveTradingDays(windowSlice, windowDays)) {
                    log.debug("[Screening] skip symbol={} window={}d: data not on consecutive trading days",
                            symbol, windowDays);
                    continue;
                }

                // 算法1: 递增成交量
                if (patternEvaluateService.matchesIncreasingVolumePattern(windowSlice, windowDays)) {
                    ScreeningMatch row = buildMatch(batchId, latest, symbol, targetDate, windowDays, "increasing_volume");
                    allRows.add(row);
                    totalMatchedRows++;
                }

                // 算法2: 放量突破
                if (patternEvaluateService.matchesVolumeSpikePattern(windowSlice, windowDays)) {
                    ScreeningMatch row = buildMatch(batchId, latest, symbol, targetDate, windowDays, "volume_spike");
                    allRows.add(row);
                    totalMatchedRows++;
                }
            }
        }

        // 批量写入
        if (!allRows.isEmpty()) {
            screeningMatchRepository.saveAll(allRows);
        }

        log.info("ScreeningServiceImpl: done batchId={}, tradeDate={}, symbols={}, processed={}, matchedRows={}",
                batchId, targetDate, barsBySymbol.size(), processed, totalMatchedRows);
        return batchId;
    }

    private ScreeningMatch buildMatch(String batchId, StockDailyBar latest,
                                      String symbol, LocalDate targetDate,
                                      int windowDays, String algorithm) {
        ScreeningMatch row = new ScreeningMatch();
        row.setBatchId(batchId);
        row.setDataSource(latest.getSource());
        row.setSymbol(symbol);
        row.setLastClose(latest.getClosePrice());
        row.setTradeDate(targetDate);
        row.setPrice(latest.getClosePrice());
        row.setRise(latest.getClosePrice() > latest.getOpenPrice());
        row.setWindowDays(windowDays);
        row.setAlgorithm(algorithm);
        return row;
    }

    /**
     * 判断窗口数据是否覆盖连续开盘日（无缺失）。
     * <p>
     * 取窗口首尾日期范围，查 trading_calendar，统计该范围内
     * 实际开盘日列表，与窗口数据的日期列表做 equals 比较。
     * 完全一致 = 无缺口，放行。
     * </p>
     */
    private boolean isWindowConsecutiveTradingDays(List<StockDailyBar> windowSlice, int windowDays) {
        if (windowSlice == null || windowSlice.isEmpty()) {
            return false;
        }

        LocalDate firstDate = windowSlice.get(0).getTradeDate();
        LocalDate lastDate = windowSlice.get(windowSlice.size() - 1).getTradeDate();

        // 从日历获取 range 内的开盘日（已升序）
        List<TradingCalendarEntity> calEntries = tradingCalendarDbService.getRange("US", firstDate, lastDate);
        List<LocalDate> openDaysInRange = calEntries.stream()
                .filter(TradingCalendarEntity::getIsOpen)
                .map(TradingCalendarEntity::getTradeDate)
                .sorted()
                .toList();

        // 窗口数据的日期（已升序，因为 bars 在外部已排序）
        List<LocalDate> actualDates = windowSlice.stream()
                .map(StockDailyBar::getTradeDate)
                .toList();

        return openDaysInRange.equals(actualDates);
    }
}
