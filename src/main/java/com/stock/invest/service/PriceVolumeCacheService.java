package com.stock.invest.service;

import com.stock.invest.entity.StockDailyBar;

import java.time.LocalDate;
import java.util.List;

public interface PriceVolumeCacheService {

    List<StockDailyBar> getLatestSevenBars(String symbol);

    List<StockDailyBar> getLatestBars(String symbol, int windowDays);

    List<StockDailyBar> refreshBarsForSymbol(String symbol, String preferredSource, LocalDate tradeDate);

    List<StockDailyBar> refreshBarsForSymbol(String symbol, String preferredSource, LocalDate tradeDate, int windowDays);
}
