package com.stock.invest.service.impl;

import com.stock.invest.entity.StockDailyBar;
import com.stock.invest.repository.StockDailyBarRepository;
import com.stock.invest.service.LatestBarsCacheService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class LatestBarsCacheServiceImpl implements LatestBarsCacheService {

    private static final int MIN_WINDOW_DAYS = 3;
    private static final int MAX_WINDOW_DAYS = 7;

    private final StockDailyBarRepository stockDailyBarRepository;

    public LatestBarsCacheServiceImpl(StockDailyBarRepository stockDailyBarRepository) {
        this.stockDailyBarRepository = stockDailyBarRepository;
    }

    @Override
    @Cacheable(value = "dailyBars", key = "#symbol + '-' + #windowDays")
    public List<StockDailyBar> getLatestBars(String symbol, int windowDays) {
        int days = sanitizeWindowDays(windowDays);
        List<StockDailyBar> latest = stockDailyBarRepository.findBySymbolOrderByTradeDateDesc(symbol, PageRequest.of(0, days));
        latest.sort(Comparator.comparing(StockDailyBar::getTradeDate));
        return latest;
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
