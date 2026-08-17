package com.stock.invest.service.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.config.TwelveDataProperties;
import com.stock.invest.exception.StockDataException;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.service.DataSourceStrategy;
import com.stock.invest.util.PythonScriptExecutor;
import org.springframework.core.annotation.Order;

@Service("twelveDataStockService")
@Order(2)
public class TwelveDataStockServiceImpl implements DataSourceStrategy {
    private static final Logger log = LoggerFactory.getLogger(TwelveDataStockServiceImpl.class);
    private static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PythonScriptExecutor pythonScriptExecutor;
    private final TwelveDataProperties twelveDataProperties;
    private final ObjectMapper objectMapper;

    @Override
    public String getSourceName() {
        return "twelvedata";
    }

    @Override
    public boolean isAvailable() {
        return !twelveDataProperties.resolvedKeys().isEmpty();
    }

    /**
     * 统一使用构造函数注入 - 符合 Spring 最佳实践
     */
    public TwelveDataStockServiceImpl(
            PythonScriptExecutor pythonScriptExecutor,
            TwelveDataProperties twelveDataProperties,
            ObjectMapper objectMapper) {
        log.info("TwelveDataStockServiceImpl {} : Service initialized", LocalDateTime.now().format(dateFormat));
        this.pythonScriptExecutor = pythonScriptExecutor;
        this.twelveDataProperties = twelveDataProperties;
        this.objectMapper = objectMapper;
    }

    private static String getScriptName() {
        return "stock_info_twelvedata.py";
    }

    private KLineData getDailyKLine(String symbol) {
        try {
            String result = pythonScriptExecutor.executeScriptWithEnvironment(
                    apiKeyEnv(), getScriptName(), "get_daily_kline", symbol);
            KLineData klineData = objectMapper.readValue(result, KLineData.class);
            if (klineData != null && klineData.getItems() != null) {
                for (KLineIterator item : klineData.getItems()) {
                    item.setSymbol(symbol);
                }
            }
            return klineData;
        } catch (Exception e) {
            log.error("Failed to get daily kline for {}: {}", symbol, e.getMessage());
            return new KLineData();
        }
    }

    @Override
    public KLineData getDailyKLineDataAsObject(String symbol) {
        return getDailyKLine(symbol);
    }

    @Override
    public KLineData getDailyKLineDataByDateRange(String symbol, LocalDate tradeDate) {
        try {
            // 两日窗口：目标日 + 其前一日（d-3 覆盖周末），脚本层相邻 close 算 changePercent
            LocalDate startDate = tradeDate.minusDays(3);
            log.info("[TwelveDataStockServiceImpl] dateRange symbol={}, range=[{},{}]", symbol, startDate, tradeDate);
            String result = pythonScriptExecutor.executeScriptWithEnvironment(
                    apiKeyEnv(), getScriptName(), "get_daily_kline_range", symbol, startDate.toString(), tradeDate.toString());
            if (result != null && result.contains("\"error\"")) {
                // P1-3：Python 侧失败输出 {"error": ...} —— 带分类抛出，not-found 才计入黑名单
                log.warn("[TwelveData] getDailyKLineDataByDateRange error for {}: {}", symbol,
                        result.substring(0, Math.min(result.length(), 500)));
                throw StockDataException.classify(symbol, "twelvedata", extractErrorFromJson(result), null);
            }
            // 目标日响应日志：只打印脚本返回中 tradeDate 那一条的完整参数（便于日志分析字段正确性/空值）
            KLineData parsed = objectMapper.readValue(result, KLineData.class);
            if (parsed != null && parsed.getItems() != null && !parsed.getItems().isEmpty()) {
                KLineIterator target = parsed.getItems().stream()
                        .filter(it -> it.getTimeString() != null && it.getTimeString().equals(tradeDate.toString()))
                        .findFirst().orElse(null);
                if (target != null) {
                    log.info("[TwelveDataStockServiceImpl] dateRange response target: symbol={}, date={}, json={}",
                            symbol, tradeDate, extractTargetItemJson(result, target.getTimeString()));
                } else {
                    log.info("[TwelveDataStockServiceImpl] dateRange response target: symbol={}, date={} NOT FOUND in {} items",
                            symbol, tradeDate, parsed.getItems().size());
                }
            }
            KLineData klineData = parsed;
            // 填充每个 item 的 symbol 字段（Python 脚本返回的 JSON 中 item 不含 symbol）
            if (klineData != null && klineData.getItems() != null) {
                for (KLineIterator item : klineData.getItems()) {
                    item.setSymbol(symbol);
                }
            }
            if (klineData != null && klineData.getItems() != null && !klineData.getItems().isEmpty()) {
                KLineIterator first = klineData.getItems().get(0);
                log.info("[TwelveDataStockServiceImpl] dateRange response: symbol={}, date={}, open={}, high={}, low={}, close={}, source=twelvedata",
                        symbol, first.getTimeString(), first.getOpen(), first.getHigh(), first.getLow(), first.getClose());
            }
            return klineData;
        } catch (StockDataException e) {
            throw e;
        } catch (Exception e) {
            // P1-3：瞬态失败（超时/连接/解析）抛带分类异常，不再返回 null 被误判
            log.error("Failed to get daily kline by date range for {}: {}", symbol, e.getMessage());
            throw new StockDataException(symbol, "twelvedata", "获取K线数据失败: " + e.getMessage(),
                    e, StockDataException.ErrorCategory.TRANSIENT_FAILURE);
        }
    }

    /**
     * 从 Python 脚本输出的 error JSON 中提取可读错误消息（兼容 {"error": "..."} 与 {"error": {"message": "..."}}）。
     */
    private String extractErrorFromJson(String result) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(result);
            com.fasterxml.jackson.databind.JsonNode err = node.get("error");
            if (err == null) {
                return result;
            }
            if (err.isTextual()) {
                return err.asText();
            }
            if (err.has("message")) {
                return err.path("message").asText();
            }
            return err.toString();
        } catch (Exception ignored) {
            return result;
        }
    }

    /**
     * 从脚本原始 JSON 中提取目标日 item 的 JSON（保留脚本原始字段/精度）。
     */
    private String extractTargetItemJson(String result, String timeString) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(result);
            for (com.fasterxml.jackson.databind.JsonNode it : root.path("items")) {
                if (timeString.equals(it.path("timeString").asText())) {
                    return it.toString();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

private Map<String, String> apiKeyEnv() {
        List<String> keys = twelveDataProperties.resolvedKeys();
        if (keys.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        return Map.of("TWELVEDATA_API_KEY", keys.get(0));
    }

} 