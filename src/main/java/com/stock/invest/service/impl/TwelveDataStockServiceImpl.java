package com.stock.invest.service.impl;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.client.TwelveDataRestClient;
import com.stock.invest.config.ScannerProperties;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.StockInfo;
import com.stock.invest.service.DataSourceStrategy;
import com.stock.invest.util.PythonScriptExecutor;
import com.tigerbrokers.stock.openapi.client.struct.enums.Market;

@Service("twelveDataStockService")
public class TwelveDataStockServiceImpl implements DataSourceStrategy {
    private static final Logger log = LoggerFactory.getLogger(TwelveDataStockServiceImpl.class);
    private static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PythonScriptExecutor pythonScriptExecutor;
    private final TwelveDataRestClient twelveDataRestClient;
    
    private final ObjectMapper objectMapper;

    @Override
    public String getSourceName() {
        return "twelvedata";
    }

    @Override
    public boolean isAvailable() {
        return twelveDataRestClient.hasApiKey();
    }

    /**
     * 统一使用构造函数注入 - 符合 Spring 最佳实践
     */
    public TwelveDataStockServiceImpl(
            PythonScriptExecutor pythonScriptExecutor,
            TwelveDataRestClient twelveDataRestClient,
            
            ScannerProperties scannerProperties,
            ObjectMapper objectMapper) {
        log.info("TwelveDataStockServiceImpl {} : Service initialized", LocalDateTime.now().format(dateFormat));
        this.pythonScriptExecutor = pythonScriptExecutor;
        this.twelveDataRestClient = twelveDataRestClient;
        this.objectMapper = objectMapper;
    }

    private static String getScriptName() {
        return "stock_info_twelvedata.py";
    }
    public String getDailyKLineData(String symbol) {
        KLineData kLineData = getDailyKLine(symbol);
        if (kLineData != null) {
            return kLineData.toString();
        }
        return "{}";
    }

    public KLineData getDailyKLineDataAsObject(String symbol) {
        return getDailyKLine(symbol);
    }
    public StockInfo getStockInfo(String symbol) {
        try {
            String result = pythonScriptExecutor.executeScript(getScriptName(), "get_stock_info", symbol);
            return objectMapper.readValue(result, StockInfo.class);
        } catch (Exception e) {
            log.error("Failed to get stock info for {}: {}", symbol, e.getMessage());
            StockInfo emptyInfo = new StockInfo();
            emptyInfo.setSymbol(symbol);
            return emptyInfo;
        }
    }
    @SuppressWarnings({"unchecked"})
    public List<String> getStockList() {
        try {
            String result = pythonScriptExecutor.executeScript(getScriptName(), "get_stock_list");
            return objectMapper.readValue(result, List.class);
        } catch (Exception e) {
            log.error("Failed to get stock list: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    public KLineData getDailyKLine(String symbol) {
        try {
            String result = pythonScriptExecutor.executeScript(getScriptName(), "get_daily_kline", symbol);
            return objectMapper.readValue(result, KLineData.class);
        } catch (Exception e) {
            log.error("Failed to get daily kline for {}: {}", symbol, e.getMessage());
            return new KLineData();
        }
    }
    @SuppressWarnings({"unchecked"})
    public List<KLineData> getBatchKline(List<String> symbols, String period, int count) {
        try {
            String result = pythonScriptExecutor.executeScript(
                getScriptName(), 
                "get_batch_kline", 
                String.join(",", symbols), 
                period, 
                String.valueOf(count)
            );
            return objectMapper.readValue(result, List.class);
        } catch (Exception e) {
            log.error("Failed to get batch kline: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    public List<String> scanStocks(Market market, int limit, Double minPrice, Double maxPrice) {
        try {
            String minPriceStr = minPrice != null ? minPrice.toString() : "";
            String maxPriceStr = maxPrice != null ? maxPrice.toString() : "";
            
            return scanStocks(market.name(), limit, minPriceStr, maxPriceStr);
        } catch (Exception e) {
            log.error("Failed to scan stocks: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    @SuppressWarnings({"unchecked"})
    public List<String> scanStocks(String market, int limit, String minPrice, String maxPrice) {
        try {
            String result = pythonScriptExecutor.executeScript(
                getScriptName(), 
                "scan_stocks", 
                market, 
                String.valueOf(limit), 
                minPrice, 
                maxPrice
            );
            return objectMapper.readValue(result, List.class);
        } catch (Exception e) {
            log.error("Failed to scan stocks: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    public Map<String, Object> scanLowPriceStocksWithVolumePattern() {
        return scanLowPriceStocksWithVolumePattern(10);
    }
    public Map<String, Object> scanLowPriceStocksWithVolumePattern(int limit) {
        return new java.util.HashMap<>();
    }
} 