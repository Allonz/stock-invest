package com.stock.invest.service.impl;

import com.stock.invest.model.TradingCalendarResult;
import com.stock.invest.service.TradingCalendarService;
import com.tigerbrokers.stock.openapi.client.https.client.TigerHttpClient;
import com.tigerbrokers.stock.openapi.client.https.domain.quote.item.TradeCalendar;
import com.tigerbrokers.stock.openapi.client.https.request.quote.QuoteTradeCalendarRequest;
import com.tigerbrokers.stock.openapi.client.https.response.quote.QuoteTradeCalendarResponse;
import com.tigerbrokers.stock.openapi.client.struct.enums.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.*;

/**
 * Tiger Java SDK 交易日历查询实现（第1顺位）。
 * 使用 QuoteTradeCalendarRequest 查询指定日期的交易日。
 */
@Service
public class TigerCalendarService implements TradingCalendarService {

    private static final Logger log = LoggerFactory.getLogger(TigerCalendarService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final TigerHttpClient client;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public TigerCalendarService(@Autowired(required = true) TigerHttpClient client) {
        this.client = client;
    }

    @Override
    public String getSourceName() {
        return "tiger";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public TradingCalendarResult isTradingDay(String market, LocalDate date) {
        try {
            return executor.submit(() -> doQuery(market, date))
                    .get(TIMEOUT.getSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[tiger] 日历查询超时 ({}s)，触发 fallback", TIMEOUT.getSeconds());
            return null;
        } catch (Exception e) {
            log.warn("[tiger] 日历查询失败: {}，触发 fallback", e.getMessage());
            return null;
        }
    }

    private TradingCalendarResult doQuery(String marketCode, LocalDate date) {
        try {
            Market market = Market.valueOf(marketCode);
            String begin = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
            String end = date.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);

            QuoteTradeCalendarRequest request = QuoteTradeCalendarRequest.newRequest(market, begin, end);
            QuoteTradeCalendarResponse response = client.execute(request);

            if (!response.isSuccess()) {
                log.warn("[tiger] QuoteTradeCalendarRequest 失败: {}", response.getMessage());
                return null;
            }

            List<TradeCalendar> items = response.getItems();
            if (items == null || items.isEmpty()) {
                return TradingCalendarResult.nonTrading(marketCode, date, getSourceName(), "HOLIDAY");
            }

            TradeCalendar tc = items.get(0);
            String type = tc.getType();
            boolean isTrading = "TRADING".equals(type);
            log.debug("[tiger] {} 查询结果: type={}", date, type);

            return isTrading
                    ? TradingCalendarResult.trading(marketCode, date, getSourceName(), type)
                    : TradingCalendarResult.nonTrading(marketCode, date, getSourceName(), type);
        } catch (Exception e) {
            log.warn("[tiger] 日历查询异常: {}", e.getMessage());
            return null;
        }
    }
}
