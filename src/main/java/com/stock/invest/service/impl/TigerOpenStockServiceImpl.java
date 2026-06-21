package com.stock.invest.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.client.TigerOpenPythonBridge;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.StockInfo;
import com.stock.invest.service.DataSourceStrategy;
import com.tigerbrokers.stock.openapi.client.struct.enums.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Tiger OpenAPI (Python SDK) 桥接的数据源实现。
 * <p>
 * 替代原 {@link TigerPythonService}，注入 {@link TigerOpenPythonBridge} 实现
 * {@link DataSourceStrategy} 接口，作为 fallback 链的第二级（优先级 "tigeropen"）。
 * </p>
 */
@Service("tigerOpenStockService")
public class TigerOpenStockServiceImpl implements DataSourceStrategy {

    private static final Logger log = LoggerFactory.getLogger(TigerOpenStockServiceImpl.class);

    private final TigerOpenPythonBridge bridge;
    private final ObjectMapper objectMapper;

    public TigerOpenStockServiceImpl(TigerOpenPythonBridge bridge,
                                     ObjectMapper objectMapper) {
        this.bridge = bridge;
        this.objectMapper = objectMapper;
        log.info("TigerOpenStockServiceImpl: Service initialized (available={})", isAvailable());
    }

    @Override
    public String getSourceName() {
        return "tigeropen";
    }

    @Override
    public boolean isAvailable() {
        return bridge.hasCredentials();
    }

    @Override
    public String getDailyKLineData(String symbol) {
        try {
            KLineData data = getDailyKLineDataAsObject(symbol);
            return data == null ? "{}" : objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("[TigerOpenStock] getDailyKLineData failed for {}: {}", symbol, e.getMessage());
            return "{}";
        }
    }

    @Override
    public KLineData getDailyKLineDataAsObject(String symbol) {
        try {
            return bridge.fetchDailyBars(symbol, 12);
        } catch (Exception e) {
            log.warn("[TigerOpenStock] getDailyKLineDataAsObject failed for {}: {}", symbol, e.getMessage());
            return new KLineData();
        }
    }

    @Override
    public StockInfo getStockInfo(String symbol) {
        try {
            String result = bridge.executePythonScript("get_stock_info", symbol);
            return objectMapper.readValue(result, StockInfo.class);
        } catch (Exception e) {
            log.warn("[TigerOpenStock] getStockInfo failed for {}: {}", symbol, e.getMessage());
            StockInfo emptyInfo = new StockInfo();
            emptyInfo.setSymbol(symbol);
            return emptyInfo;
        }
    }

    @Override
    public List<String> getStockList() {
        try {
            return bridge.listCandidates(100, 0.0, 1000.0);
        } catch (Exception e) {
            log.warn("[TigerOpenStock] getStockList failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public KLineData getDailyKLine(String symbol) {
        return getDailyKLineDataAsObject(symbol);
    }

    @Override
    public List<KLineData> getBatchKline(List<String> symbols, String period, int count) {
        try {
            String symbolsStr = String.join(",", symbols);
            String result = bridge.executePythonScript("get_batch_kline",
                    symbolsStr, period, String.valueOf(count));
            return objectMapper.readValue(result, new TypeReference<List<KLineData>>() {});
        } catch (Exception e) {
            log.warn("[TigerOpenStock] getBatchKline failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public List<String> scanStocks(Market market, int limit, Double minPrice, Double maxPrice) {
        try {
            double min = minPrice == null ? 0.0 : minPrice;
            double max = maxPrice == null ? 100.0 : maxPrice;
            return bridge.listCandidates(limit, min, max);
        } catch (Exception e) {
            log.warn("[TigerOpenStock] scanStocks(Market) failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public List<String> scanStocks(String market, int limit, String minPrice, String maxPrice) {
        try {
            double min = (minPrice == null || minPrice.trim().isEmpty()) ? 0.0 : Double.parseDouble(minPrice);
            double max = (maxPrice == null || maxPrice.trim().isEmpty()) ? 100.0 : Double.parseDouble(maxPrice);
            return bridge.listCandidates(limit, min, max);
        } catch (Exception e) {
            log.warn("[TigerOpenStock] scanStocks(String) failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public Map<String, Object> scanLowPriceStocksWithVolumePattern(int limit) {
        return new HashMap<>();
    }

    // ---- 保留原 TigerPythonService 的公共方法 ----

    public KLineData fetchDailyKLine(String symbol) {
        try {
            String result = bridge.executePythonScript("get_daily_kline", symbol);
            return objectMapper.readValue(result, KLineData.class);
        } catch (Exception e) {
            log.warn("[TigerOpenStock] fetchDailyKLine failed for {}: {}", symbol, e.getMessage());
            return new KLineData();
        }
    }

    public String fetchBatchKLine(List<String> symbols, String period, int count) {
        try {
            String symbolsStr = String.join(",", symbols);
            return bridge.executePythonScript("get_batch_kline",
                    symbolsStr, period, String.valueOf(count));
        } catch (Exception e) {
            log.warn("[TigerOpenStock] fetchBatchKLine failed: {}", e.getMessage());
            return "[]";
        }
    }
}
