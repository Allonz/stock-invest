package com.stock.invest.service;

import com.stock.invest.model.KLineData;

import java.util.List;
import java.util.Optional;

public interface MarketDataSourceRouter {

    List<String> loadCandidates(int limit, double minPrice, double maxPrice);

    Optional<KLineData> fetchDailyBars(String symbol, String preferredSource, int barsCount);

    Optional<KLineData> fetchLatestDailyBar(String symbol, String preferredSource);
}
