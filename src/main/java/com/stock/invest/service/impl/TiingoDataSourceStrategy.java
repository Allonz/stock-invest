package com.stock.invest.service.impl;

import com.stock.invest.client.TiingoRestClient;
import com.stock.invest.exception.StockDataException;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.StockInfo;
import com.stock.invest.service.StockScannerStrategy;
import com.tigerbrokers.stock.openapi.client.struct.enums.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
@Order(5)
public class TiingoDataSourceStrategy implements StockScannerStrategy {

    private static final Logger log = LoggerFactory.getLogger(TiingoDataSourceStrategy.class);

    private final TiingoRestClient tiingoRestClient;

    public TiingoDataSourceStrategy(TiingoRestClient tiingoRestClient) {
        this.tiingoRestClient = tiingoRestClient;
        log.info("TiingoDataSourceStrategy: Service initialized");
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
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404) {
                KLineData empty = new KLineData();
                empty.setSymbol(symbol);
                empty.setItems(java.util.Collections.emptyList());
                log.warn("[Tiingo] symbol not found (404): {}", symbol);
                return empty;
            }
            log.warn("tiingo getDailyKLineDataAsObject failed for {}: {}", symbol, e.getMessage());
            return new KLineData();
        } catch (Exception e) {
            log.warn("tiingo getDailyKLineDataAsObject failed for {}: {}", symbol, e.getMessage());
            return new KLineData();
        }
    }

    @Override
    public KLineData getDailyKLineDataByDateRange(String symbol, LocalDate tradeDate) {
        try {
            log.info("[TiingoDataSourceStrategy] dateRange symbol={}, range=[{},{}]", symbol, tradeDate, tradeDate);
            return tiingoRestClient.fetchDailyBars(symbol, tradeDate, tradeDate);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404) {
                // P1-3：404 = 确认不存在，计入黑名单
                log.warn("[Tiingo] symbol not found (404) for date range: {}", symbol);
                throw new StockDataException(symbol, "tiingo", "股票不存在 (404)",
                        StockDataException.ErrorCategory.CONFIRMED_NOT_FOUND);
            }
            // P1-3：其他 HTTP 错误（429/5xx 等）为瞬态失败，不计黑名单
            log.warn("tiingo getDailyKLineDataByDateRange failed for {}: {}", symbol, e.getMessage());
            throw new StockDataException(symbol, "tiingo", "HTTP错误: " + e.getMessage(),
                    StockDataException.ErrorCategory.TRANSIENT_FAILURE);
        } catch (Exception e) {
            log.warn("tiingo getDailyKLineDataByDateRange failed for {}: {}", symbol, e.getMessage());
            throw new StockDataException(symbol, "tiingo", "获取K线数据失败: " + e.getMessage(),
                    StockDataException.ErrorCategory.TRANSIENT_FAILURE);
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
            // 标准涨跌幅计算：(今日收盘 - 昨日收盘) / 昨日收盘 * 100
            if (data.getItems().size() >= 2) {
                com.stock.invest.model.KLineIterator prev = data.getItems().get(1);
                java.math.BigDecimal prevClose = prev.getClose();
                java.math.BigDecimal change = latest.getClose().subtract(prevClose);
                info.setChange(change);
                if (prevClose.compareTo(java.math.BigDecimal.ZERO) != 0) {
                    // R2 P3-4：精度统一 —— setScale(4, HALF_UP)，与 DB DECIMAL(12,4) 对齐
                    info.setChangePercent(change
                            .divide(prevClose, 8, java.math.RoundingMode.HALF_UP)
                            .multiply(java.math.BigDecimal.valueOf(100))
                            .setScale(4, java.math.RoundingMode.HALF_UP));
                }
            } else {
                info.setChange(java.math.BigDecimal.ZERO);
                info.setChangePercent(java.math.BigDecimal.ZERO);
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
            return tiingoRestClient.listUsSymbolsByPriceRange(
                    100, java.math.BigDecimal.valueOf(0.01D), java.math.BigDecimal.valueOf(1000D));
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
            java.math.BigDecimal min = minPrice == null ? java.math.BigDecimal.ZERO : java.math.BigDecimal.valueOf(minPrice);
            java.math.BigDecimal max = maxPrice == null
                    ? java.math.BigDecimal.valueOf(Double.MAX_VALUE) : java.math.BigDecimal.valueOf(maxPrice);
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
            java.math.BigDecimal min = (minPrice == null || minPrice.trim().isEmpty())
                    ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(minPrice.trim());
            java.math.BigDecimal max = (maxPrice == null || maxPrice.trim().isEmpty())
                    ? java.math.BigDecimal.valueOf(Double.MAX_VALUE) : new java.math.BigDecimal(maxPrice.trim());
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
