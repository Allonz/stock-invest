package com.stock.invest.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.model.StockInfo;
import com.stock.invest.util.PythonScriptExecutor;
import com.tigerbrokers.stock.openapi.client.struct.enums.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * StockService接口的Yahoo Finance实现
 */
@Service("yFinanceStockService")
public class YFinanceStockServiceImpl {

    private static final Logger log = LoggerFactory.getLogger(YFinanceStockServiceImpl.class);

    private final ObjectMapper objectMapper;
    private final PythonScriptExecutor pythonScriptExecutor;
    private final String implementation;

    public YFinanceStockServiceImpl(
            ObjectMapper objectMapper,
            PythonScriptExecutor pythonScriptExecutor,
            @Value("${stock.service.implementation:yfinance}") String implementation) {
        log.info("YFinanceStockServiceImpl : Service initialized");
        this.objectMapper = objectMapper;
        this.pythonScriptExecutor = pythonScriptExecutor;
        this.implementation = implementation;
    }

    private String getScriptName() {
        return "stock_info_" + implementation + ".py";
    }
    public String getDailyKLineData(String symbol) {
        return getDailyKLineData(symbol, 30); // 默认获取30天的数据
    }
    
    public String getDailyKLineData(String symbol, int days) {
        try {
            KLineData klineData = getDailyKLineDataAsObject(symbol, days);
            return objectMapper.writeValueAsString(klineData);
        } catch (Exception e) {
            log.warn("Error getting daily kline data for {}: {}", symbol, e.getMessage());
            return "{}";
        }
    }
    public KLineData getDailyKLineDataAsObject(String symbol) {
        return getDailyKLineDataAsObject(symbol, 30); // 默认获取30天的数据
    }
    
    public KLineData getDailyKLineDataAsObject(String symbol, int days) {
        try {
            
            // 创建K线数据对象
            KLineData klineData = new KLineData();
            klineData.setSymbol(symbol);
            klineData.setTime(System.currentTimeMillis());
            
            // 获取K线数据
            // 使用PythonScriptExecutor获取K线数据
            KLineData pythonKLineData = getDailyKLine(symbol, days);
            if (pythonKLineData != null && pythonKLineData.getItems() != null && !pythonKLineData.getItems().isEmpty()) {
                klineData.setItems(pythonKLineData.getItems());
                
                // 从第一个K线数据中提取价格信息
                KLineIterator firstItem = pythonKLineData.getItems().get(0);
                klineData.setOpen(firstItem.getOpen());
                klineData.setHigh(firstItem.getHigh());
                klineData.setLow(firstItem.getLow());
                klineData.setClose(firstItem.getClose());
                klineData.setVolume(firstItem.getVolume());
            } else {
                // 如果没有数据，添加一个空的数据项
                KLineIterator item = new KLineIterator();
                item.setTime(System.currentTimeMillis());
                klineData.setItems(List.of(item));
            }
            
            return klineData;
        } catch (Exception e) {
            log.warn("Error getting daily kline data as object for {}: {}", symbol, e.getMessage());
            return new KLineData();
        }
    }
    public Map<String, Object> scanLowPriceStocksWithVolumePattern(int limit) {
        return new java.util.HashMap<>();
    }
    public List<KLineData> getBatchKline(List<String> symbols, String period, int count) {
        try {
            List<KLineData> klineDataList = new ArrayList<>();
            
            // 使用批量获取K线数据的函数
            final int BATCH_SIZE = 10;
            
            // 遍历股票列表并获取K线数据
            for (int i = 0; i < symbols.size(); i += BATCH_SIZE) {
                int endIndex = Math.min(i + BATCH_SIZE, symbols.size());
                List<String> batchSymbols = symbols.subList(i, endIndex);
                
                log.info("正在获取 {}/{} 批K线数据，共 {} 只股票", 
                    (i / BATCH_SIZE) + 1, 
                    (symbols.size() + BATCH_SIZE - 1) / BATCH_SIZE,
                    batchSymbols.size());
                
                // 使用Python脚本获取批量K线数据
                try {
                    String symbolsStr = String.join(",", batchSymbols);
                    String result = pythonScriptExecutor.executeScript(
                        getScriptName(),
                        "get_batch_kline",
                        symbolsStr,
                        period,
                        String.valueOf(count)
                    );
                    List<KLineData> batchResults = objectMapper.readValue(result, new TypeReference<List<KLineData>>() {});
                    if (batchResults != null) {
                        klineDataList.addAll(batchResults);
                    }
                } catch (Exception e) {
                    log.warn("批量获取K线数据时出错: {}", e.getMessage());
                    
                    // 如果获取失败，尝试逐个获取
                    for (String symbol : batchSymbols) {
                        try {
                            KLineData klineData = getDailyKLineDataAsObject(symbol, count);
                            if (klineData != null && klineData.getSymbol() != null) {
                                klineDataList.add(klineData);
                            }
                        } catch (Exception ex) {
                            log.warn("获取股票 {} K线数据时出错: {}", symbol, ex.getMessage());
                        }
                    }
                }
                
                // 根据API限制，等待一段时间
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            return klineDataList;
        } catch (Exception e) {
            log.warn("批量获取K线数据时出错: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    public StockInfo getStockInfo(String symbol) {
        try {
            String result = pythonScriptExecutor.executeScript(getScriptName(), "get_stock_info", symbol);
            return objectMapper.readValue(result, StockInfo.class);
        } catch (Exception e) {
            log.warn("Failed to get stock info for {}: {}", symbol, e.getMessage());
            StockInfo emptyInfo = new StockInfo();
            emptyInfo.setSymbol(symbol);
            return emptyInfo;
        }
    }
    public List<String> getStockList() {
        try {
            String result = pythonScriptExecutor.executeScript(getScriptName(), "get_stock_list");
            return objectMapper.readValue(result, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to get stock list: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    public KLineData getDailyKLine(String symbol) {
        try {
            String result = pythonScriptExecutor.executeScript(getScriptName(), "get_daily_kline", symbol);
            return objectMapper.readValue(result, KLineData.class);
        } catch (Exception e) {
            log.warn("Failed to get daily kline for {}: {}", symbol, e.getMessage());
            return new KLineData();
        }
    }

    public KLineData getDailyKLine(String symbol, int days) {
        try {
            String result = pythonScriptExecutor.executeScript(getScriptName(), "get_daily_kline", symbol, String.valueOf(days));
            return objectMapper.readValue(result, KLineData.class);
        } catch (Exception e) {
            log.warn("Failed to get daily kline for {}: {}", symbol, e.getMessage());
            return new KLineData();
        }
    }
    public List<String> scanStocks(Market market, int limit, Double minPrice, Double maxPrice) {
        try {
            String minPriceStr = minPrice != null ? minPrice.toString() : "";
            String maxPriceStr = maxPrice != null ? maxPrice.toString() : "";
            
            return scanStocks(market.name(), limit, minPriceStr, maxPriceStr);
        } catch (Exception e) {
            log.warn("Failed to scan stocks: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
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
            return objectMapper.readValue(result, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to scan stocks: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    
} 