package com.stock.invest.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.client.TigerOpenPythonBridge;
import com.stock.invest.model.TradingCalendarResult;
import com.stock.invest.service.TradingCalendarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private final Executor executor;

    public TigerOpenCalendarService(ObjectMapper objectMapper,
                                    TigerOpenPythonBridge bridge,
                                    @Qualifier("calendarExecutor") Executor executor) {
        this.objectMapper = objectMapper;
        this.bridge = bridge;
        this.executor = executor;
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
            CompletableFuture<TradingCalendarResult> future =
                    CompletableFuture.supplyAsync(() -> doQuery(market, date), executor);
            try {
                return future.get(TIMEOUT.getSeconds(), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                // 评审 C-1：CompletableFuture.cancel(true) 不会中断已在执行的 supplyAsync 任务，
                // 调用它只会制造"已取消"的假象，底层任务照常运行 —— 因此这里只放弃等待结果，
                // 不调用 cancel。僵尸任务的终结由真正的可取消层兜底：Python 执行器 30s 起
                // 强制杀子进程（PythonScriptExecutor），且日历查询走独立 calendarExecutor
                // （评审 D-1），不再占用与编排/筛选/补缺共用的 scanExecutor 线程。
                log.warn("[tigeropen] 日历查询超时 ({}s)，放弃等待，触发 fallback", TIMEOUT.getSeconds());
                return null;
            }
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
