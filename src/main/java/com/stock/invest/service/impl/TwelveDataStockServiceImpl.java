package com.stock.invest.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.StockInfo;
import com.stock.invest.service.StockService;
import com.stock.invest.util.PythonInstaller;
import com.stock.invest.util.PythonScriptExecutor;
import com.tigerbrokers.stock.openapi.client.struct.enums.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Profile("twelvedata")
public class TwelveDataStockServiceImpl implements StockService {
    private static final Logger logger = LoggerFactory.getLogger(TwelveDataStockServiceImpl.class);
    private static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    @Autowired
    private PythonScriptExecutor pythonScriptExecutor;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Value("${stock.service.implementation:twelvedata}")
    private String implementation;
    
    public TwelveDataStockServiceImpl(ObjectMapper objectMapper) {
        logger.info("TwelveDataStockServiceImpl " + LocalDateTime.now().format(dateFormat) + ": Service initialized");
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        // 初始化时设置Python脚本环境
        try {
            boolean setupResult = PythonInstaller.setupPythonScriptEnvironment();
            if (setupResult) {
                logger.info("Python脚本环境初始化成功");
                // 测试Python脚本
                String testScriptPath = PythonInstaller.generateTestScript();
                try {
                    String testResult = pythonScriptExecutor.executeScript(testScriptPath, "1");
                    logger.info("Python测试脚本执行成功: {}", testResult);
                } catch (Exception e) {
                    logger.warn("Python测试脚本执行失败: {}", e.getMessage());
                }
            } else {
                logger.warn("Python脚本环境初始化失败");
            }
        } catch (Exception e) {
            logger.error("初始化Python脚本环境时出错: {}", e.getMessage(), e);
        }
    }

    private String getScriptName() {
        // 使用resources目录下的python文件夹中的脚本
        String path = "src/main/resources/python/stock_info_" + implementation + ".py";
        logger.debug("使用脚本路径: {}", path);
        return path;
    }

    @Override
    public String getDailyKLineData(String symbol) {
        KLineData kLineData = getDailyKLine(symbol);
        if (kLineData != null) {
            return kLineData.toString();
        }
        return "{}";
    }

    @Override
    public List<KLineData> getBatchKlineData(List<String> symbols, Period period, int count) {
        return getBatchKline(symbols, period.name().toLowerCase(), count);
    }

    @Override
    public KLineData getDailyKLineDataAsObject(String symbol) {
        return getDailyKLine(symbol);
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
            logger.error("Failed to get batch kline: {}", e.getMessage());
            return new ArrayList<>();
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
    
    @Override
    public Map<String, Object> scanLowPriceStocksWithVolumePattern() {
        return scanLowPriceStocksWithVolumePattern(20);
    }

    @Override
    public Map<String, Object> scanLowPriceStocksWithVolumePattern(int limit) {
        try {
            List<String> symbols = scanStocks("US", limit, "0.05", "0.2");
            
            Map<String, Object> resultMap = new HashMap<>();
            List<Map<String, Object>> stocks = new ArrayList<>();
            
            for (String symbol : symbols) {
                KLineData klineData = getDailyKLine(symbol);
                if (klineData != null && hasIncreasingVolume(klineData)) {
                    Map<String, Object> stockData = new HashMap<>();
                    stockData.put("symbol", symbol);
                    stockData.put("klineData", klineData);
                    stocks.add(stockData);
                }
            }
            
            resultMap.put("stocks", stocks);
            return resultMap;
        } catch (Exception e) {
            logger.error("Failed to scan low price stocks: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 检查K线数据是否显示持续增长的交易量模式
     */
    @SuppressWarnings("unchecked")
    private boolean hasIncreasingVolume(KLineData klineData) {
        List<?> items = klineData.getItems();
        if (items == null || items.size() < 7) return false;

        for (int i = 1; i < 7; i++) {
            double prevAvgVolume = calculateAverageVolume(items.subList(0, i));
            double currentAvgVolume = calculateAverageVolume(items.subList(0, i + 1));
            if (currentAvgVolume <= prevAvgVolume) return false;
        }
        return true;
    }

    /**
     * 计算平均交易量
     */
    private double calculateAverageVolume(List<?> items) {
        return items.stream()
                .map(item -> {
                    try {
                        // 尝试通过反射获取volume属性
                        return (int) item.getClass().getMethod("getVolume").invoke(item);
                    } catch (Exception e) {
                        logger.error("Error getting volume: {}", e.getMessage());
                        return 0;
                    }
                })
                .mapToDouble(Integer::doubleValue)
                .average()
                .orElse(0.0);
    }

    /**
     * 获取低价股票列表
     * 
     * 实现自定义的低价股票扫描逻辑，从TwelveData获取数据
     * 
     * @param limit 返回的股票数量上限
     * @return 股票信息列表
     */
    public List<StockInfo> getLowPriceStocks(int limit) {
        logger.info("开始扫描低价股票，限制数量: {}", limit);
        try {
            // 获取脚本路径
            String scriptPath = getScriptName();
            logger.debug("使用Python脚本路径: {}", scriptPath);
            
            // 执行Python脚本
            String result = pythonScriptExecutor.executeScript(scriptPath, "get_low_price_stocks", String.valueOf(limit));
            logger.debug("Python脚本执行结果: {}", result);
            
            // 解析结果
            List<Map<String, Object>> stockDataList = objectMapper.readValue(result, new TypeReference<List<Map<String, Object>>>() {});
            
            // 转换为StockInfo对象
            List<StockInfo> stocks = new ArrayList<>();
            for (Map<String, Object> data : stockDataList) {
                StockInfo stock = new StockInfo();
                stock.setSymbol((String) data.get("symbol"));
                stock.setName((String) data.get("name"));
                
                // 安全转换数值类型
                Object priceObj = data.get("price");
                if (priceObj instanceof Number) {
                    stock.setCurrentPrice(((Number) priceObj).doubleValue());
                }
                
                Object volumeObj = data.get("volume");
                if (volumeObj instanceof Number) {
                    // 确保转换为整数
                    stock.setVolume(((Number) volumeObj).intValue());
                }
                
                Object changeObj = data.get("change");
                if (changeObj instanceof Number) {
                    stock.setChange(((Number) changeObj).doubleValue());
                }
                
                Object changePercentObj = data.get("changePercent");
                if (changePercentObj instanceof Number) {
                    stock.setChangePercent(((Number) changePercentObj).doubleValue());
                }
                
                stocks.add(stock);
            }
            
            logger.info("成功获取 {} 支低价股票", stocks.size());
            return stocks;
        } catch (Exception e) {
            logger.error("扫描股票失败: {}", e.getMessage(), e);
            throw new RuntimeException("扫描股票失败", e);
        }
    }
} 