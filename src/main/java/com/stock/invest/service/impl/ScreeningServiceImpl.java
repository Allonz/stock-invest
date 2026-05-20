package com.stock.invest.service.impl;

import com.stock.invest.config.ScannerProperties;
import com.stock.invest.constant.WindowConstants;
import com.stock.invest.entity.ScreeningMatch;
import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.repository.ScreeningMatchRepository;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.PatternEvaluateService;
import com.stock.invest.service.ScreeningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
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

    public ScreeningServiceImpl(
            StockDailyBarRepository stockDailyBarRepository,
            ScreeningMatchRepository screeningMatchRepository,
            PatternEvaluateService patternEvaluateService,
            ScannerProperties scannerProperties) {
        this.stockDailyBarRepository = stockDailyBarRepository;
        this.screeningMatchRepository = screeningMatchRepository;
        this.patternEvaluateService = patternEvaluateService;
        this.scannerProperties = scannerProperties;
    }

    @Override
    @Transactional
    public String runScreening(LocalDate tradeDate) {
        LocalDate targetDate = tradeDate == null ? LocalDate.now() : tradeDate;
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

            // 至少需要最小窗口天数数据才能评估
            if (bars.size() < Collections.min(WindowConstants.ALL_WINDOW_DAYS)) {
                continue;
            }

            // 取最近 7 天数据
            List<StockDailyBar> window = bars.subList(bars.size() - 7, bars.size());
            StockDailyBar latest = window.get(window.size() - 1);

            // 只检查目标交易日的个股
            if (!targetDate.equals(latest.getTradeDate())) {
                continue;
            }

            // 价格过滤
            if (latest.getClosePrice() == null) {
                continue;
            }
            if (latest.getClosePrice() < scannerProperties.getMinPrice() || latest.getClosePrice() > scannerProperties.getMaxPrice()) {
                continue;
            }

            processed++;

            // 多窗口并行评估
            for (int windowDays : WindowConstants.ALL_WINDOW_DAYS) {
                // 取对应窗口长度的数据
                List<StockDailyBar> windowSlice = bars.subList(bars.size() - windowDays, bars.size());

                if (!patternEvaluateService.matchesIncreasingVolumePattern(windowSlice, windowDays)) {
                    continue;
                }

                ScreeningMatch row = new ScreeningMatch();
                row.setBatchId(batchId);
                row.setDataSource(latest.getSource());
                row.setSymbol(symbol);
                row.setLastClose(latest.getClosePrice());
                row.setTradeDate(targetDate);
                row.setPrice(latest.getClosePrice());
                row.setRise(latest.getClosePrice() > latest.getOpenPrice());
                row.setWindowDays(windowDays);
                allRows.add(row);
                totalMatchedRows++;
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
}
