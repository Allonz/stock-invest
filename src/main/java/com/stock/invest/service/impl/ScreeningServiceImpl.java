package com.stock.invest.service.impl;

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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 模式筛选服务实现。
 * <p>
 * 从 stock_daily_bars 读取最近 7 天数据，按 symbol 分组后传入
 * {@link PatternEvaluateServiceImpl} 做模式评估，结果写入 screening_match 表。
 * </p>
 * <p>
 * 每个 symbol 对 2/3/4/5/6/7 天窗口依次评估（P3-2：原 javadoc 声称"并行评估"，
 * 实际为串行双层循环，注释与实现保持一致），分别记录结果。
 * </p>
 */
@Service
public class ScreeningServiceImpl implements ScreeningService {

    private static final Logger log = LoggerFactory.getLogger(ScreeningServiceImpl.class);

    private final StockDailyBarRepository stockDailyBarRepository;
    private final ScreeningMatchRepository screeningMatchRepository;
    private final PatternEvaluateService patternEvaluateService;
    private final TradingCalendarDbService tradingCalendarDbService;

    /** P1-2：筛选运行互斥 —— 同步/异步/定时多路触发共用同一实例，重复触发直接跳过 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ScreeningServiceImpl(
            StockDailyBarRepository stockDailyBarRepository,
            ScreeningMatchRepository screeningMatchRepository,
            PatternEvaluateService patternEvaluateService,
            TradingCalendarDbService tradingCalendarDbService) {
        this.stockDailyBarRepository = stockDailyBarRepository;
        this.screeningMatchRepository = screeningMatchRepository;
        this.patternEvaluateService = patternEvaluateService;
        this.tradingCalendarDbService = tradingCalendarDbService;
    }

    @Override
    @Transactional
    public String runScreening(LocalDate tradeDate) {
        return runScreening(tradeDate, null, null);
    }

    @Override
    @Transactional
    public String runScreening(LocalDate tradeDate, Integer windowDays, Integer limit) {
        // P1-2：互斥 —— 已有一份筛选在跑则跳过，避免重复触发插入重复行、双倍计算
        if (!running.compareAndSet(false, true)) {
            log.warn("[Screening] runScreening: already running, skip concurrent trigger");
            return null;
        }
        try {
            return runScreeningInternal(tradeDate, windowDays, limit);
        } finally {
            running.set(false);
        }
    }

    private String runScreeningInternal(LocalDate tradeDate, Integer windowDays, Integer limit) {
        LocalDate targetDate = tradeDate == null ? ZonedDateTime.now(ZoneId.of("America/New_York")).toLocalDate() : tradeDate;
        String batchId = UUID.randomUUID().toString();

        // P1-7：windowDays 生效 —— null 或小于最小窗口时使用全部窗口 2~7 天
        List<Integer> windows = (windowDays == null || windowDays < WindowConstants.MIN_WINDOW_DAYS)
                ? WindowConstants.ALL_WINDOW_DAYS
                : List.of(windowDays);

        log.info("ScreeningServiceImpl: start batchId={}, date={}, windowDays={}, limit={}",
                batchId, targetDate, windows, limit);

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
            for (int w : windows) {
                if (bars.size() < w) {
                    continue;
                }
                // 取对应窗口长度的数据
                List<StockDailyBar> windowSlice = bars.subList(bars.size() - w, bars.size());

                // 连续开盘日校验
                if (!isWindowConsecutiveTradingDays(windowSlice, w)) {
                    log.debug("[Screening] skip symbol={} window={}d: data not on consecutive trading days",
                            symbol, w);
                    continue;
                }

                // 算法1: 递增成交量
                if (patternEvaluateService.matchesIncreasingVolumePattern(windowSlice, w)) {
                    ScreeningMatch row = buildMatch(batchId, latest, symbol, targetDate, w, "increasing_volume");
                    allRows.add(row);
                    totalMatchedRows++;
                }

                // 算法2: 放量突破
                if (patternEvaluateService.matchesVolumeSpikePattern(windowSlice, w)) {
                    ScreeningMatch row = buildMatch(batchId, latest, symbol, targetDate, w, "volume_spike");
                    allRows.add(row);
                    totalMatchedRows++;
                }
            }

            // P1-7：limit 生效 —— 当前 symbol 已完整评估后，再判断是否达到上限
            if (limit != null && limit > 0 && processed >= limit) {
                log.info("[Screening] limit={} reached, stop evaluating more symbols (processed={})", limit, processed);
                break;
            }
        }

        // 批量写入（P2-5：防重 —— 同交易日同股票同窗口同算法已存在的行跳过，
        // 重复触发不再插入重复行；DB 唯一约束 uk_screening_match_trade_symbol_window_algorithm 兜底，
        // 存量库存在历史重复行时约束暂缺，由本处应用层查重保证）
        if (!allRows.isEmpty()) {
            Set<String> existingKeys = screeningMatchRepository.findByTradeDate(targetDate).stream()
                    .map(m -> m.getSymbol() + "|" + m.getWindowDays() + "|" + m.getAlgorithm())
                    .collect(Collectors.toSet());
            List<ScreeningMatch> toSave = allRows.stream()
                    .filter(r -> !existingKeys.contains(r.getSymbol() + "|" + r.getWindowDays() + "|" + r.getAlgorithm()))
                    .collect(Collectors.toList());
            int skippedDuplicates = allRows.size() - toSave.size();
            if (skippedDuplicates > 0) {
                log.info("[Screening] batch save skipped {} duplicate row(s) for tradeDate={}",
                        skippedDuplicates, targetDate);
            }
            if (!toSave.isEmpty()) {
                screeningMatchRepository.saveAll(toSave);
            }
        }

        log.info("ScreeningServiceImpl: done batchId={}, tradeDate={}, symbols={}, processed={}, matchedRows={}",
                batchId, targetDate, barsBySymbol.size(), processed, totalMatchedRows);
        return batchId;
    }

    @Override
    public Map<String, Object> getLatestScreening() {
        Optional<ScreeningMatch> top = screeningMatchRepository.findTopByOrderByTradeDateDescIdDesc();
        if (top.isEmpty()) {
            Map<String, Object> emptyResult = new HashMap<>();
            emptyResult.put("batchId", null);
            emptyResult.put("tradeDate", null);
            emptyResult.put("matches", List.of());
            return emptyResult;
        }

        ScreeningMatch latest = top.get();
        String batchId = latest.getBatchId();
        List<ScreeningMatch> matches = screeningMatchRepository.findByBatchIdOrderByIdAsc(batchId);

        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);
        result.put("tradeDate", latest.getTradeDate().toString());
        result.put("totalMatches", matches.size());
        result.put("matches", buildMatchesWithName(matches));
        return result;
    }

    @Override
    public List<Map<String, Object>> getScreeningHistory(int limit) {
        int safeLimit = Math.min(Math.max(1, limit), 500);
        List<Object[]> batchSummaries = screeningMatchRepository.findBatchSummary(
                org.springframework.data.domain.PageRequest.of(0, safeLimit));
        List<Map<String, Object>> history = new ArrayList<>();
        for (Object[] row : batchSummaries) {
            Map<String, Object> item = new HashMap<>();
            item.put("batchId", row[0]);
            item.put("matchCount", row[1]);
            item.put("lastTradeDate", row[2] != null ? row[2].toString() : null);
            history.add(item);
        }
        return history;
    }

    @Override
    public Map<String, Object> getBatchDetail(String batchId) {
        List<ScreeningMatch> matches = screeningMatchRepository.findByBatchIdOrderByIdAsc(batchId);
        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);
        result.put("totalMatches", matches.size());
        result.put("matches", buildMatchesWithName(matches));
        return result;
    }

    @Override
    public List<Object[]> countByBatchIdGroupByWindowDays(String batchId) {
        return screeningMatchRepository.countByBatchIdGroupByWindowDays(batchId);
    }


    @Override
    public Map<String, Object> getLatestNotificationGrouped() {
        return getLatestNotificationGrouped(null);
    }

    @Override
    public Map<String, Object> getLatestNotificationGrouped(String windows) {
        Optional<ScreeningMatch> latest = screeningMatchRepository.findTopByOrderByTradeDateDescIdDesc();
        if (latest.isEmpty()) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("message", "暂无筛选数据");
            return empty;
        }
        return groupNotificationByBatch(latest.get().getBatchId(), latest.get().getTradeDate(), windows);
    }

    /**
     * 按指定交易日查询最新批次并分组统计（通知用）。
     */
    @Override
    public Map<String, Object> getNotificationGroupedByDate(String tradeDate, String windows) {
        LocalDate date = parseTradeDate(tradeDate);
        Optional<ScreeningMatch> top = screeningMatchRepository.findTopByTradeDateOrderByIdDesc(date);
        if (top.isEmpty()) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("batchId", null);
            empty.put("screenDate", tradeDate);
            empty.put("results", new LinkedHashMap<>());
            return empty;
        }
        return groupNotificationByBatch(top.get().getBatchId(), top.get().getTradeDate(), windows);
    }

    /**
     * 按指定交易日查询最新一次筛选结果（含 stock name）。
     */
    @Override
    public Map<String, Object> getScreeningByDate(String tradeDate) {
        LocalDate date = parseTradeDate(tradeDate);
        Optional<ScreeningMatch> top = screeningMatchRepository.findTopByTradeDateOrderByIdDesc(date);
        if (top.isEmpty()) {
            Map<String, Object> emptyResult = new HashMap<>();
            emptyResult.put("batchId", null);
            emptyResult.put("tradeDate", tradeDate);
            emptyResult.put("totalMatches", 0);
            emptyResult.put("matches", List.of());
            return emptyResult;
        }
        String batchId = top.get().getBatchId();
        List<ScreeningMatch> matches = screeningMatchRepository.findByBatchIdOrderByIdAsc(batchId);
        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);
        result.put("tradeDate", top.get().getTradeDate().toString());
        result.put("totalMatches", matches.size());
        result.put("matches", buildMatchesWithName(matches));
        return result;
    }

    private static LocalDate parseTradeDate(String tradeDate) {
        if (tradeDate == null || tradeDate.isBlank()) {
            throw new IllegalArgumentException("tradeDate is required");
        }
        try {
            return LocalDate.parse(tradeDate.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("tradeDate must be yyyy-MM-dd", e);
        }
    }

    /**
     * 按批次分组统计 algorithm + windowDays（通知 payload 结构）。
     */
    private Map<String, Object> groupNotificationByBatch(String batchId, LocalDate screenDate, String windows) {
        List<ScreeningMatch> allMatches = screeningMatchRepository.findByBatchIdOrderByIdAsc(batchId);
        Set<String> allowedWindows = null;
        if (windows != null && !windows.isBlank()) {
            allowedWindows = Arrays.stream(windows.split(","))
                    .map(String::trim)
                    .collect(Collectors.toSet());
        }
        Map<String, Map<String, Object>> resultByAlgo = new LinkedHashMap<>();
        for (ScreeningMatch m : allMatches) {
            String algo = m.getAlgorithm();
            int wd = m.getWindowDays();
            String windowKey = wd + "d";
            if (allowedWindows != null && !allowedWindows.contains(windowKey)) {
                continue;
            }

            Map<String, Object> windowData = resultByAlgo
                    .computeIfAbsent(algo, k -> new LinkedHashMap<>());
            @SuppressWarnings("unchecked")
            Map<String, Object> windowGroup = (Map<String, Object>) windowData
                    .computeIfAbsent(windowKey, k -> {
                        Map<String, Object> g = new LinkedHashMap<>();
                        g.put("count", 0L);
                        g.put("stocks", new ArrayList<Map<String, Object>>());
                        return g;
                    });

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stocks = (List<Map<String, Object>>) windowGroup.get("stocks");
            windowGroup.put("count", ((Long) windowGroup.get("count")) + 1L);

            Map<String, Object> stockInfo = new LinkedHashMap<>();
            stockInfo.put("symbol", m.getSymbol());
            java.math.BigDecimal close = m.getLastClose();
            stockInfo.put("lastClose", close == null ? null : strip3(close));
            stockInfo.put("rise", m.getRise());
            stocks.add(stockInfo);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchId", batchId);
        payload.put("screenDate", screenDate.toString());
        payload.put("results", resultByAlgo);
        return payload;
    }
    /**
     * 为匹配列表批量补充 stock name，构建带 name 的匹配项列表。
     */
    private List<Map<String, Object>> buildMatchesWithName(List<ScreeningMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }
        var symbols = matches.stream().map(ScreeningMatch::getSymbol).distinct().toList();
        var nameMap = stockDailyBarRepository.findBySymbolInAndNameIsNotNull(symbols)
                .stream()
                .collect(Collectors.toMap(
                        bar -> bar.getSymbol(),
                        bar -> bar.getName(),
                        (a, b) -> a
                ));
        return matches.stream().<Map<String, Object>>map(m -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", m.getId());
            item.put("symbol", m.getSymbol());
            item.put("name", nameMap.getOrDefault(m.getSymbol(), ""));
            java.math.BigDecimal lastClose = m.getLastClose();
            item.put("lastClose", lastClose == null ? null : strip3(lastClose));
            item.put("rise", m.getRise());
            item.put("windowDays", m.getWindowDays());
            item.put("algorithm", m.getAlgorithm());
            return item;
        }).toList();
    }

    /**
     * P2-6：lastClose 展示统一 3 位小数圆整 + 去尾零（整数值回落普通十进制，避免 1.5E+2 科学计数）。
     */
    private static java.math.BigDecimal strip3(java.math.BigDecimal value) {
        java.math.BigDecimal rounded = value.setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        return rounded.scale() < 0 ? rounded.setScale(0) : rounded;
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
        row.setRise(latest.getOpenPrice() != null
                && latest.getClosePrice().compareTo(latest.getOpenPrice()) > 0);
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
