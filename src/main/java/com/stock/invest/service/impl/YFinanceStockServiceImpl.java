package com.stock.invest.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDate;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.exception.StockDataException;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.model.StockInfo;
import com.stock.invest.service.DataSourceStrategy;
import com.stock.invest.util.PythonScriptExecutor;
import com.tigerbrokers.stock.openapi.client.struct.enums.Market;
import org.springframework.core.annotation.Order;


/**
 * StockService接口的Yahoo Finance实现
 */
@Service("yFinanceStockService")
@Order(1)
public class YFinanceStockServiceImpl implements DataSourceStrategy {

    private static final Logger log = LoggerFactory.getLogger(YFinanceStockServiceImpl.class);

    private final ObjectMapper objectMapper;
    private final PythonScriptExecutor pythonScriptExecutor;

    @Override
    public String getSourceName() {
        return "yfinance";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    public YFinanceStockServiceImpl(
            ObjectMapper objectMapper,
            PythonScriptExecutor pythonScriptExecutor) {
        log.info("YFinanceStockServiceImpl : Service initialized");
        this.objectMapper = objectMapper;
        this.pythonScriptExecutor = pythonScriptExecutor;
    }

    private String getScriptName() {
        return "stock_info_yfinance.py";
    }

    private KLineData getDailyKLine(String symbol, int days) {
        try {
            String result = pythonScriptExecutor.executeScript(getScriptName(), "get_daily_kline", symbol, String.valueOf(days));
            return objectMapper.readValue(result, KLineData.class);
        } catch (Exception e) {
            log.warn("Failed to get daily kline for {}: {}", symbol, e.getMessage());
            return new KLineData();
        }
    }

    @Override
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
                // 填充每个 item 的 symbol 字段（Python 脚本返回的 JSON 中 item 不含 symbol）
                for (KLineIterator item : klineData.getItems()) {
                    item.setSymbol(symbol);
                }
                
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
    /**
     * 按指定交易日获取K线数据。
     * yfinance 的 history(start=, end=) 是 end-exclusive，
     * 所以内部将 tradeDate 和 tradeDate+1 传给 Python 脚本。
     */
    @Override
    public KLineData getDailyKLineDataByDateRange(String symbol, LocalDate tradeDate) {
        try {
            // 两日窗口：目标日 + 其前一日（d-3 覆盖周末，保证含前一交易日）
            // 脚本层用相邻交易日 close 计算 changePercent（真实交易日序列，无隔日错位）
            LocalDate yfStart = tradeDate.minusDays(3);
            log.info("[YFinanceStockServiceImpl] dateRange symbol={}, range=[{},{}]", symbol, yfStart, tradeDate);
            String result = pythonScriptExecutor.executeScript(getScriptName(),
                    "get_daily_kline_range", symbol, yfStart.toString(), tradeDate.toString());
            // P1-3：Python 侧失败输出 {"error": ...} —— 解析消息并带分类抛出，
            // 避免"确认不存在"（No data found）与瞬态失败混为一谈
            if (result != null && result.contains("\"error\"")) {
                throw StockDataException.classify(symbol, "yfinance", extractErrorFromJson(result), null);
            }
            KLineData klineData = objectMapper.readValue(result, KLineData.class);
            // 填充每个 item 的 symbol 字段（Python 脚本返回的 JSON 中 item 不含 symbol）
            if (klineData != null && klineData.getItems() != null) {
                for (KLineIterator item : klineData.getItems()) {
                    item.setSymbol(symbol);
                }
            }
            if (klineData != null && klineData.getItems() != null && !klineData.getItems().isEmpty()) {
                // 目标日响应日志：只打印脚本返回中 tradeDate 那一条的完整参数（便于日志分析字段正确性/空值）
                KLineIterator target = klineData.getItems().stream()
                        .filter(it -> it.getTimeString() != null && it.getTimeString().equals(tradeDate.toString()))
                        .findFirst().orElse(null);
                if (target != null) {
                    log.info("[YFinanceStockServiceImpl] dateRange response target: symbol={}, date={}, json={}",
                            symbol, tradeDate, result != null && result.contains(target.getTimeString())
                                    ? extractTargetItemJson(result, target.getTimeString()) : objectMapper.writeValueAsString(target));
                } else {
                    log.info("[YFinanceStockServiceImpl] dateRange response target: symbol={}, date={} NOT FOUND in {} items",
                            symbol, tradeDate, klineData.getItems().size());
                }
            }
            return klineData;
        } catch (StockDataException e) {
            throw e;
        } catch (Exception e) {
            // P1-3：瞬态失败（超时/连接/解析等）抛带分类异常，不再返回空 KLineData 被误判为 not-found
            log.warn("Failed to get daily kline by range for {}: {}", symbol, e.getMessage());
            throw StockDataException.classify(symbol, "yfinance", e.getMessage(), e);
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
            // fallback below
        }
        return null;
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
     * 按指定交易日获取盘后价。
     * <p>调用 Python get_after_hours_price（1m 分钟数据 prepost=True，取 16:00-20:00 ET
     * 最后一条 close），按交易日精确查询——修复 2026-08-13 前用 get_stock_info 实时
     * postMarketPrice 的缺陷：盘中恒为 None、历史日期拿到的是当天实时值而非目标日期。</p>
     * <p>可用性边界（Yahoo 分钟数据保留限制）：近 30 天内可查；30~60 天需降级到
     * 15m/30m/60m 间隔；超过 60 天 Yahoo 不保留分钟数据，返回空 KLineData。</p>
     */
    @Override
    public KLineData getAfterHoursKLineDataByDateRange(String symbol, LocalDate tradeDate) {
        try {
            String result = pythonScriptExecutor.executeScript(getScriptName(),
                    "get_after_hours_price", symbol, tradeDate.toString());
            // Python 侧失败输出 {"error": ...} → 返回空（盘后不可得，不影响日K主链路）
            if (result != null && result.contains("\"error\"")) {
                log.warn("[YFinanceStockServiceImpl] afterHours fetch failed for {}, date={}: {}",
                        symbol, tradeDate, extractErrorFromJson(result));
                return new KLineData();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> infoMap = objectMapper.readValue(result, Map.class);
            Object ahPrice = infoMap.get("afterHours");
            if (ahPrice == null) {
                log.debug("[YFinanceStockServiceImpl] afterHours: no after-hours data for symbol={}, date={}",
                        symbol, tradeDate);
                return new KLineData();
            }

            java.math.BigDecimal ahClose = java.math.BigDecimal.valueOf(((Number) ahPrice).doubleValue());

            KLineData ahData = new KLineData();
            ahData.setSymbol(symbol);

            KLineIterator item = new KLineIterator();
            item.setSymbol(symbol);
            item.setTime(tradeDate.atStartOfDay(java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli());
            item.setTimeString(tradeDate.toString());
            item.setClose(ahClose);
            // 脚本直算的盘后涨跌幅（源直取优先）；脚本未返回时由调用方用 (ahClose-regClose)/regClose 兜底计算
            Object ahPct = infoMap.get("afterHoursChangePercent");
            if (ahPct != null) {
                item.setAfterHoursChangePercent(java.math.BigDecimal.valueOf(((Number) ahPct).doubleValue()));
            }
            ahData.setItems(List.of(item));

            log.info("[YFinanceStockServiceImpl] afterHours: symbol={}, date={}, afterHours={}, afterHoursChangePercent={}",
                    symbol, tradeDate, ahClose, item.getAfterHoursChangePercent());
            return ahData;
        } catch (Exception e) {
            log.warn("[YFinanceStockServiceImpl] afterHours fetch failed for {}, date={}: {}",
                    symbol, tradeDate, e.getMessage());
            return new KLineData();
        }
    }

}