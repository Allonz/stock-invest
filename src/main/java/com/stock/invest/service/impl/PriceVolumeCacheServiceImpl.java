package com.stock.invest.service.impl;

import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.MarketDataSourceRouter;
import com.stock.invest.service.PriceVolumeCacheService;
import com.stock.invest.util.KLineDataUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class PriceVolumeCacheServiceImpl implements PriceVolumeCacheService {

    private static final Logger log = LoggerFactory.getLogger(PriceVolumeCacheServiceImpl.class);
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
    private static final int MIN_WINDOW_DAYS = 3;
    private static final int MAX_WINDOW_DAYS = 7;

    private final StockDailyBarRepository stockDailyBarRepository;
    private final MarketDataSourceRouter marketDataSourceRouter;

    public PriceVolumeCacheServiceImpl(
            StockDailyBarRepository stockDailyBarRepository,
            MarketDataSourceRouter marketDataSourceRouter) {
        this.stockDailyBarRepository = stockDailyBarRepository;
        this.marketDataSourceRouter = marketDataSourceRouter;
    }

    @Override
    public List<StockDailyBar> getLatestSevenBars(String symbol) {
        return getLatestBars(symbol, MAX_WINDOW_DAYS);
    }

    @Override
    @Cacheable(value = "dailyBars", key = "#symbol + '-' + #windowDays")
    public List<StockDailyBar> getLatestBars(String symbol, int windowDays) {
        int days = sanitizeWindowDays(windowDays);
        List<StockDailyBar> latest = stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(symbol, PageRequest.of(0, days));
        latest.sort(Comparator.comparing(StockDailyBar::getTradeDate));
        return latest;
    }

    @Override
    @Transactional
    public List<StockDailyBar> refreshBarsForSymbol(String symbol, String preferredSource, LocalDate tradeDate) {
        return refreshBarsForSymbol(symbol, preferredSource, tradeDate, MAX_WINDOW_DAYS);
    }

    @Override
    @Transactional
    public List<StockDailyBar> refreshBarsForSymbol(String symbol, String preferredSource, LocalDate tradeDate, int windowDays) {
        int days = sanitizeWindowDays(windowDays);
        List<StockDailyBar> latest = getLatestBars(symbol, days);
        if (latest.size() < days) {
            Optional<KLineData> seed = marketDataSourceRouter.fetchDailyBars(symbol, preferredSource, 10);
            if (seed.isPresent()) {
                upsertFromKLineData(symbol, preferredSource, seed.get(), days);
            }
        } else if (!hasTradeDate(latest, tradeDate)) {
            Optional<KLineData> day = marketDataSourceRouter.fetchLatestDailyBar(symbol, preferredSource);
            if (day.isPresent()) {
                upsertFromKLineData(symbol, preferredSource, day.get(), 1);
            }
        }
        return getLatestBars(symbol, days);
    }

    private static boolean hasTradeDate(List<StockDailyBar> bars, LocalDate tradeDate) {
        for (StockDailyBar bar : bars) {
            if (tradeDate.equals(bar.getTradeDate())) {
                return true;
            }
        }
        return false;
    }

    private void upsertFromKLineData(String symbol, String source, KLineData data, int maxKeep) {
        KLineDataUtils.sortItemsNewestFirst(data);
        List<KLineIterator> items = data.getItems() == null ? new ArrayList<>() : data.getItems();
        int n = Math.min(maxKeep, items.size());
        for (int i = 0; i < n; i++) {
            KLineIterator item = items.get(i);
            LocalDate tradeDate = toTradeDate(item);
            if (tradeDate == null) {
                continue;
            }
            StockDailyBar bar = stockDailyBarRepository.findBySymbolAndTradeDate(symbol, tradeDate).orElseGet(StockDailyBar::new);
            bar.setSymbol(symbol);
            bar.setTradeDate(tradeDate);
            bar.setOpenPrice(item.getOpen());
            bar.setClosePrice(item.getClose());
            bar.setVolume(item.getVolume());
            bar.setSource(source);
            stockDailyBarRepository.save(bar);
        }
    }

    private static LocalDate toTradeDate(KLineIterator item) {
        if (item == null) {
            return null;
        }
        if (item.getTimeString() != null && item.getTimeString().matches("\\d{4}-\\d{2}-\\d{2}")) {
            return LocalDate.parse(item.getTimeString());
        }
        long ts = item.getTime();
        if (ts <= 0L) {
            return null;
        }
        return Instant.ofEpochMilli(ts).atZone(MARKET_ZONE).toLocalDate();
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
