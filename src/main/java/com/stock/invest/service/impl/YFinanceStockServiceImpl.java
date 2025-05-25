package com.stock.invest.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.model.StockInfo;
import com.stock.invest.service.StockService;
import com.stock.invest.util.PythonScriptExecutor;
import com.stock.invest.util.StockPatternUtil;
import com.tigerbrokers.stock.openapi.client.struct.enums.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * StockService接口的Yahoo Finance实现
 */
@Service("yFinanceStockService")
@org.springframework.context.annotation.Profile("yfinance")
public class YFinanceStockServiceImpl implements StockService {

    private static final Logger logger = LoggerFactory.getLogger(YFinanceStockServiceImpl.class);
    private static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Autowired
    private PythonScriptExecutor pythonScriptExecutor;
    
    @Value("${stock.service.implementation:yfinance}")
    private String implementation;

    public YFinanceStockServiceImpl(ObjectMapper objectMapper) {
        logger.info("YFinanceStockServiceImpl " + LocalDateTime.now().format(dateFormat) + ": Service initialized");
        this.objectMapper = objectMapper;
    }

    private String getScriptName() {
        // 使用resources目录下的python文件夹中的脚本
        String path = "src/main/resources/python/stock_info_" + implementation + ".py";
        logger.debug("使用脚本路径: {}", path);
        return path;
    }

    @Override
    public String getDailyKLineData(String symbol) {
        return getDailyKLineData(symbol, 30); // 默认获取30天的数据
    }
    
    public String getDailyKLineData(String symbol, int days) {
        try {
            KLineData klineData = getDailyKLineDataAsObject(symbol, days);
            return objectMapper.writeValueAsString(klineData);
        } catch (Exception e) {
            logger.error("Error getting daily kline data for {}: {}", symbol, e.getMessage(), e);
            return "{}";
        }
    }
    
    @Override
    public KLineData getDailyKLineDataAsObject(String symbol) {
        return getDailyKLineDataAsObject(symbol, 30); // 默认获取30天的数据
    }
    
    public KLineData getDailyKLineDataAsObject(String symbol, int days) {
        try {
            // 使用 days 参数获取指定股票的K线数据
            StockInfo stockInfo = getStockInfo(symbol);
            if (stockInfo == null) {
                return new KLineData();
            }
            
            // 创建K线数据对象
            KLineData klineData = new KLineData();
            klineData.setSymbol(stockInfo.getSymbol());
            klineData.setTime(System.currentTimeMillis());
            
            // 获取K线数据
            // 使用PythonScriptExecutor获取K线数据
            KLineData pythonKLineData = getDailyKLine(symbol);
            if (pythonKLineData != null && pythonKLineData.getItems() != null && !pythonKLineData.getItems().isEmpty()) {
                klineData.setItems(pythonKLineData.getItems());
                
                // 从第一个K线数据中提取价格信息
                if (!pythonKLineData.getItems().isEmpty()) {
                    Object firstItem = pythonKLineData.getItems().get(0);
                    // 尝试获取价格信息
                    try {
                        klineData.setOpen((Double) firstItem.getClass().getMethod("getOpen").invoke(firstItem));
                        klineData.setHigh((Double) firstItem.getClass().getMethod("getHigh").invoke(firstItem));
                        klineData.setLow((Double) firstItem.getClass().getMethod("getLow").invoke(firstItem));
                        klineData.setClose((Double) firstItem.getClass().getMethod("getClose").invoke(firstItem));
                        klineData.setVolume((Long) firstItem.getClass().getMethod("getVolume").invoke(firstItem));
                    } catch (Exception e) {
                        logger.warn("Failed to get price data from KLineIterator: {}", e.getMessage());
                    }
                }
            } else {
                // 如果没有数据，添加一个空的数据项
                KLineIterator item = new KLineIterator();
                item.setTime(System.currentTimeMillis());
                item.setOpen(stockInfo.getCurrentPrice());
                item.setHigh(stockInfo.getCurrentPrice());
                item.setLow(stockInfo.getCurrentPrice());
                item.setClose(stockInfo.getCurrentPrice());
                item.setVolume((int)stockInfo.getVolume());
                klineData.addItem(item);
            }
            
            return klineData;
        } catch (Exception e) {
            logger.error("Error getting daily kline data as object for {}: {}", symbol, e.getMessage(), e);
            return new KLineData();
        }
    }
    
    @Override
    public Map<String, Object> scanLowPriceStocksWithVolumePattern(int limit) {
        try {
            Map<String, Object> result = new HashMap<>();
            Map<String, Object> statistics = new HashMap<>();
            result.put("statistics", statistics);
            
            // 1. 获取低价股票
            List<String> lowPriceStocks = scanStocks(Market.US, limit * 2, 0.05, 0.2);
            
            logger.info("找到 {} 只低价股票", lowPriceStocks.size());
            
            statistics.put("totalStocks", lowPriceStocks.size());
            statistics.put("processedStocks", 0);
            statistics.put("matchingStocks", 0);
            
            int processed = 0;
            int matching = 0;
            List<String> symbols = lowPriceStocks.stream()
                .limit(limit * 2) // 限制股票列表
                .collect(Collectors.toList());
            
            // 2. 获取股票K线数据并应用模式选择
            // 使用批量获取K线数据的函数
            final int BATCH_SIZE = 10;
            
            // 遍历股票列表并获取K线数据
            for (int i = 0; i < symbols.size(); i += BATCH_SIZE) {
                int endIndex = Math.min(i + BATCH_SIZE, symbols.size());
                List<String> batchSymbols = symbols.subList(i, endIndex);
                
                logger.info("正在获取 {}/{} 批K线数据，共 {} 只股票", 
                    (i / BATCH_SIZE) + 1, 
                    (symbols.size() + BATCH_SIZE - 1) / BATCH_SIZE,
                    batchSymbols.size());
                
                try {
                    for (String symbol : batchSymbols) {
                        processed++;
                        
                        // 获取7天的K线数据
                        List<Map<String, Object>> klineData = new ArrayList<>();
                        KLineData stockKline = getDailyKLineDataAsObject(symbol, 7); // A) 使用7天数据
                        
                        // 如果没有足够的K线数据，跳过该股票
                        if (stockKline == null || stockKline.getItems().size() < 7) {
                            logger.debug("股票 {}：K线数据不足 (需要7天)", symbol);
                            continue;
                        }
                        
                        // 转换KLineData为模式匹配的格式
                        for (Object item : stockKline.getItems()) {
                            Map<String, Object> klineItem = new HashMap<>();
                            try {
                                klineItem.put("time", item.getClass().getMethod("getTime").invoke(item));
                                klineItem.put("open", item.getClass().getMethod("getOpen").invoke(item));
                                klineItem.put("high", item.getClass().getMethod("getHigh").invoke(item));
                                klineItem.put("low", item.getClass().getMethod("getLow").invoke(item));
                                klineItem.put("close", item.getClass().getMethod("getClose").invoke(item));
                                klineItem.put("volume", item.getClass().getMethod("getVolume").invoke(item));
                                klineData.add(klineItem);
                            } catch (Exception e) {
                                logger.warn("转换K线数据时出错: {}", e.getMessage());
                            }
                        }
                        
                        // 使用StockPatternUtil判断是否匹配模式
                        if (StockPatternUtil.matchesVolumePattern(klineData)) {
                            matching++;
                            result.put(symbol, stockKline);
                            logger.info("股票 {} 匹配模式，标准", symbol);
                        }
                    }
                    
                    // 统计信息
                    statistics.put("processedStocks", processed);
                    statistics.put("matchingStocks", matching);
                } catch (Exception e) {
                    logger.warn("批量获取K线数据时出错: {}", e.getMessage());
                }
                
                // 如果已经找到足够的匹配股票，提前结束
                if (matching >= limit) {
                    break;
                }
            }
            
            return result;
        } catch (Exception e) {
            logger.error("扫描低价股票时出错: {}", e.getMessage(), e);
            return new HashMap<>();
        }
    }
    
    @Override
    public Map<String, Object> scanLowPriceStocksWithVolumePattern() {
        return scanLowPriceStocksWithVolumePattern(10);
    }
    
    @Override
    public List<KLineData> getBatchKlineData(List<String> symbols, Period period, int count) {
        try {
            return getBatchKline(symbols, period.name().toLowerCase(), count);
        } catch (Exception e) {
            logger.error("Failed to get batch kline data: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    
    @Override
    public List<KLineData> getBatchKline(List<String> symbols, String period, int count) {
        try {
            List<KLineData> klineDataList = new ArrayList<>();
            
            // 使用批量获取K线数据的函数
            final int BATCH_SIZE = 10;
            
            // 遍历股票列表并获取K线数据
            for (int i = 0; i < symbols.size(); i += BATCH_SIZE) {
                int endIndex = Math.min(i + BATCH_SIZE, symbols.size());
                List<String> batchSymbols = symbols.subList(i, endIndex);
                
                logger.info("正在获取 {}/{} 批K线数据，共 {} 只股票", 
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
                    List<KLineData> batchResults = objectMapper.readValue(result, List.class);
                    if (batchResults != null) {
                        klineDataList.addAll(batchResults);
                    }
                } catch (Exception e) {
                    logger.warn("批量获取K线数据时出错: {}", e.getMessage());
                    
                    // 如果获取失败，尝试逐个获取
                    for (String symbol : batchSymbols) {
                        try {
                            KLineData klineData = getDailyKLineDataAsObject(symbol, count);
                            if (klineData != null && klineData.getSymbol() != null) {
                                klineDataList.add(klineData);
                            }
                        } catch (Exception ex) {
                            logger.warn("获取股票 {} K线数据时出错: {}", symbol, ex.getMessage());
                        }
                    }
                }
                
                // 根据API限制，等待一段时间
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
            }
            
            return klineDataList;
        } catch (Exception e) {
            logger.error("批量获取K线数据时出错: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }
    
    @Override
    public StockInfo getStockInfo(String symbol) {
        try {
            String result = pythonScriptExecutor.executeScript(getScriptName(), "get_stock_info", symbol);
            return objectMapper.readValue(result, StockInfo.class);
        } catch (Exception e) {
            logger.error("Failed to get stock info for {}: {}", symbol, e.getMessage());
            return null;
        }
    }
    
    @Override
    public List<String> getStockList() {
        try {
            String result = pythonScriptExecutor.executeScript(getScriptName(), "get_stock_list");
            return objectMapper.readValue(result, List.class);
        } catch (Exception e) {
            logger.error("Failed to get stock list: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    
    @Override
    public KLineData getDailyKLine(String symbol) {
        try {
            String result = pythonScriptExecutor.executeScript(getScriptName(), "get_daily_kline", symbol);
            return objectMapper.readValue(result, KLineData.class);
        } catch (Exception e) {
            logger.error("Failed to get daily kline for {}: {}", symbol, e.getMessage());
            return null;
        }
    }
    
    @Override
    public List<String> scanStocks(Market market, int limit, Double minPrice, Double maxPrice) {
        try {
            String minPriceStr = minPrice != null ? minPrice.toString() : "";
            String maxPriceStr = maxPrice != null ? maxPrice.toString() : "";
            
            return scanStocks(market.name(), limit, minPriceStr, maxPriceStr);
        } catch (Exception e) {
            logger.error("Failed to scan stocks: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    
    @Override
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
            logger.error("Failed to scan stocks: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * 设置ObjectMapper
     * @param objectMapper JSON解析器
     */
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
} 