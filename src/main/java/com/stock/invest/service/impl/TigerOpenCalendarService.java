package com.stock.invest.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.client.TigerOpenPythonBridge;
import com.stock.invest.model.TradingCalendarResult;
import com.stock.invest.service.TradingCalendarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.*;

/**
 * TigerOpen Python 交易日历查询实现（第2顺位）。
 * 通过 tigeropen_channel.py calendar 命令查询。
 * 凭证复用 TigerOpenPythonBridge 的 hasCredentials() 判断。
 */
@Service
public class TigerOpenCalendarService implements TradingCalendarService {

    private static final Logger log = LoggerFactory.getLogger(TigerOpenCalendarService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final ObjectMapper objectMapper;
    private final TigerOpenPythonBridge bridge;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public TigerOpenCalendarService(ObjectMapper objectMapper,
                                    TigerOpenPythonBridge bridge) {
        this.objectMapper = objectMapper;
        this.bridge = bridge;
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
    public TradingCalendarResult isTradingDay(String market, LocalDate date) {
        if (!isAvailable()) {
            log.debug("[tigeropen] 日历查询跳过：凭证未配置");
            return null;
        }

        try {
            return executor.submit(() -> doQuery(market, date))
                    .get(TIMEOUT.getSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[tigeropen] 日历查询超时 ({}s)，触发 fallback", TIMEOUT.getSeconds());
            return null;
        } catch (Exception e) {
            log.warn("[tigeropen] 日历查询失败: {}，触发 fallback", e.getMessage());
            return null;
        }
    }

    private TradingCalendarResult doQuery(String marketCode, LocalDate date) {
        try {
            String dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE);

            String json = bridge.executePythonScript("calendar", marketCode, dateStr);

            Map<String, Object> result = objectMapper.readValue(json.trim(),
                    new TypeReference<Map<String, Object>>() {});

            boolean tradingDay = Boolean.TRUE.equals(result.get("tradingDay"));
            String type = (String) result.getOrDefault("type", "");
            log.debug("[tigeropen] {} {} 查询结果: tradingDay={}, type={}",
                    marketCode, dateStr, tradingDay, type);

            return tradingDay
                    ? TradingCalendarResult.trading(marketCode, date, getSourceName(), type)
                    : TradingCalendarResult.nonTrading(marketCode, date, getSourceName(), type);
        } catch (Exception e) {
            log.warn("[tigeropen] 日历查询异常: {}", e.getMessage());
            return null;
        }
    }
}
