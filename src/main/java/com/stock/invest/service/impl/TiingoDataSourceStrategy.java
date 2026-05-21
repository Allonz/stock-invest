package com.stock.invest.service.impl;

import com.stock.invest.client.TiingoRestClient;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.StockInfo;
import com.stock.invest.service.DataSourceStrategy;
import com.tigerbrokers.stock.openapi.client.struct.enums.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
@Order(5)
public class TiingoDataSourceStrategy implements DataSourceStrategy {

    private static final Logger log = LoggerFactory.getLogger(TiingoDataSourceStrategy.class);

    private final TiingoRestClient tiingoRestClient;

    public TiingoDataSourceStrategy(TiingoRestClient tiingoRestClient) {
        this.tiingoRestClient = tiingoRestClient;
    }

    @Override
    public String getSourceName() {
        return "tiingo";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String getDailyKLineData(String symbol) {
        try {
            KLineData data = tiingoRestClient.fetchDailyBars(symbol, 30);
            return data == null ? "{}" : data.toString();
        } catch (Exception e) {
            log.warn("tiingo getDailyKLineData failed for {}: {}", symbol, e.getMessage());
            return "{}";
        }
    }

    @Override
    public KLineData getDailyKLineDataAsObject(String symbol) {
        try {
            return tiingoRestClient.fetchDailyBars(symbol, 30);
        } catch (Exception e) {
            log.warn("tiingo getDailyKLineDataAsObject failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    @Override
    public StockInfo getStockInfo(String symbol) {
        try {
            KLineData data = tiingoRestClient.fetchDailyBars(symbol, 5);
            if (data == null || data.getItems() == null || data.getItems().isEmpty()) {
                return null;
            }
            com.stock.invest.model.KLineIterator latest = data.getItems().get(0);
            StockInfo info = new StockInfo();
            info.setSymbol(symbol);
            info.setCurrentPrice(latest.getClose());
            info.setOpenPrice(latest.getOpen());
            info.setVolume(latest.getVolume());
            info.setChange(latest.getClose() - latest.getOpen());
            if (latest.getOpen() != 0D) {
                info.setChangePercent((latest.getClose() - latest.getOpen()) / latest.getOpen() * 100D);
            }
            return info;
        } catch (Exception e) {
            log.warn("tiingo getStockInfo failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    @Override
    public List<String> getStockList() {
        try {
            return tiingoRestClient.listUsSymbolsByPriceRange(100, 0.01D, 1000D);
        } catch (Exception e) {
            log.warn("tiingo getStockList failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public KLineData getDailyKLine(String symbol) {
        return getDailyKLineDataAsObject(symbol);
    }

    @Override
    public List<KLineData> getBatchKline(List<String> symbols, String period, int count) {
        try {
            List<KLineData> out = new ArrayList<>();
            for (String symbol : symbols) {
                KLineData data = tiingoRestClient.fetchDailyBars(symbol, count);
                if (data != null && data.getItems() != null && !data.getItems().isEmpty()) {
                    out.add(data);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("tiingo getBatchKline failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<String> scanStocks(Market market, int limit, Double minPrice, Double maxPrice) {
        try {
            if (market != Market.US) {
                return Collections.emptyList();
            }
            double min = minPrice == null ? 0.0D : minPrice;
            double max = maxPrice == null ? Double.MAX_VALUE : maxPrice;
            return tiingoRestClient.listUsSymbolsByPriceRange(limit, min, max);
        } catch (Exception e) {
            log.warn("tiingo scanStocks(Market) failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<String> scanStocks(String market, int limit, String minPrice, String maxPrice) {
        try {
            if (market == null || !"US".equalsIgnoreCase(market)) {
                return Collections.emptyList();
            }
            double min = (minPrice == null || minPrice.trim().isEmpty()) ? 0.0D : Double.parseDouble(minPrice);
            double max = (maxPrice == null || maxPrice.trim().isEmpty()) ? Double.MAX_VALUE : Double.parseDouble(maxPrice);
            return tiingoRestClient.listUsSymbolsByPriceRange(limit, min, max);
        } catch (Exception e) {
            log.warn("tiingo scanStocks(String) failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public Map<String, Object> scanLowPriceStocksWithVolumePattern(int limit) {
        return Collections.emptyMap();
    }
}
