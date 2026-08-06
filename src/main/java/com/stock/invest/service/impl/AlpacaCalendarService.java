package com.stock.invest.service.impl;

import com.stock.invest.client.AlpacaRestClient;
import com.stock.invest.model.TradingCalendarResult;
import com.stock.invest.service.TradingCalendarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Alpaca Markets 交易日历查询实现（第3顺位）。
 * 仅用于查询美股日历，不用于股票行情。
 * 只支持 US 市场，非 US 市场返回 null 触发 fallback。
 */
@Service
public class AlpacaCalendarService implements TradingCalendarService {

    private static final Logger log = LoggerFactory.getLogger(AlpacaCalendarService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final AlpacaRestClient alpacaClient;
    private final Executor executor;

    public AlpacaCalendarService(AlpacaRestClient alpacaClient,
                                  @Qualifier("scanExecutor") Executor executor) {
        this.alpacaClient = alpacaClient;
        this.executor = executor;
    }

    @Override
    public String getSourceName() {
        return "alpaca";
    }

    @Override
    public boolean isAvailable() {
        return alpacaClient.hasCredentials();
    }

    @Override
    public TradingCalendarResult isTradingDay(String market, LocalDate date) {
        if (!isAvailable()) {
            log.debug("[alpaca] 日历查询跳过：凭证未配置");
            return null;
        }

        if (!"US".equals(market)) {
            log.debug("[alpaca] 日历查询跳过：不支持市场 {}", market);
            return null;
        }

        try {
            CompletableFuture<TradingCalendarResult> future =
                    CompletableFuture.supplyAsync(() -> doQuery(date), executor);
            try {
                return future.get(TIMEOUT.getSeconds(), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                // P2-14：超时必须取消底层任务，避免僵尸任务占用 scanExecutor 线程
                future.cancel(true);
                log.warn("[alpaca] 日历查询超时 ({}s)，触发 fallback", TIMEOUT.getSeconds());
                return null;
            }
        } catch (Exception e) {
            log.warn("[alpaca] 日历查询失败: {}，触发 fallback", e.getMessage());
            return null;
        }
    }

    private TradingCalendarResult doQuery(LocalDate date) {
        try {
            boolean isTrading = alpacaClient.isTradingDay(date);
            log.debug("[alpaca] {} 查询结果: tradingDay={}", date, isTrading);

            return isTrading
                    ? TradingCalendarResult.trading("US", date, getSourceName(), "TRADING")
                    : TradingCalendarResult.nonTrading("US", date, getSourceName(), "HOLIDAY");
        } catch (Exception e) {
            log.warn("[alpaca] 日历查询异常: {}", e.getMessage());
            return null;
        }
    }
}
