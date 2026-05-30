package com.stock.invest.service;

import com.tigerbrokers.stock.openapi.client.config.ClientConfig;
import com.tigerbrokers.stock.openapi.client.https.client.TigerHttpClient;
import com.tigerbrokers.stock.openapi.client.https.request.quote.QuoteTradeCalendarRequest;
import com.tigerbrokers.stock.openapi.client.https.response.quote.QuoteTradeCalendarResponse;
import com.tigerbrokers.stock.openapi.client.https.domain.quote.item.TradeCalendar;
import com.tigerbrokers.stock.openapi.client.struct.enums.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;

@Service
public class TigerTradeCalendarService {

    private static final Logger log = LoggerFactory.getLogger(TigerTradeCalendarService.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private boolean initialized = false;
    private String initError = null;

    @PostConstruct
    public void init() {
        try {
            ClassPathResource r = new ClassPathResource("tiger_openapi_config.properties");
            if (!r.exists()) {
                initError = "tiger_openapi_config.properties not found";
                log.warn("[TigerTradeCalendarService] {}", initError);
                return;
            }
            Properties props = new Properties();
            try (InputStream is = r.getInputStream()) {
                props.load(is);
            }

            String tigerId = props.getProperty("tiger_id", "").trim();
            String privateKey = props.getProperty("private_key_pk8", "").trim();
            String account = props.getProperty("account", "").trim();

            if (tigerId.isEmpty() || privateKey.isEmpty()) {
                initError = "incomplete tiger credentials";
                log.warn("[TigerTradeCalendarService] {}", initError);
                return;
            }

            ClientConfig config = new ClientConfig();
            config.tigerId = tigerId;
            config.privateKey = privateKey;
            config.defaultAccount = account;

            TigerHttpClient.getInstance().clientConfig(config);
            initialized = true;
            log.info("[TigerTradeCalendarService] initialized (tigerId={})", tigerId);
        } catch (Exception e) {
            initError = "init failed: " + e.getMessage();
            log.error("[TigerTradeCalendarService] {}", initError, e);
        }
    }

    public boolean isTradingDay(LocalDate date) {
        if (!initialized) {
            log.warn("[TigerTradeCalendarService] not initialized ({}), assuming trading day", initError);
            return true;
        }

        try {
            String dateStr = date.format(DATE_FMT);
            String nextDayStr = date.plusDays(1).format(DATE_FMT);
            QuoteTradeCalendarRequest request = QuoteTradeCalendarRequest.newRequest(
                    Market.US, dateStr, nextDayStr);

            QuoteTradeCalendarResponse response = (QuoteTradeCalendarResponse)
                    TigerHttpClient.getInstance().execute(request);

            if (response == null) {
                log.warn("[TigerTradeCalendarService] null response for {}", dateStr);
                return true;
            }

            if (!response.isSuccess()) {
                log.warn("[TigerTradeCalendarService] API error for {}: {}", dateStr, response.getMessage());
                return true;
            }

            List<TradeCalendar> items = response.getItems();
            if (items == null || items.isEmpty()) {
                log.warn("[TigerTradeCalendarService] {}: no calendar data (resp isSuccess={}, msg={}, code={})",
                        dateStr, response.isSuccess(), response.getMessage(), response.getCode());
                return false;
            }

            for (TradeCalendar item : items) {
                log.info("[TigerTradeCalendarService] {} item: date={} type={}",
                        dateStr, item.getDate(), item.getType());
                if (dateStr.equals(item.getDate())) {
                    boolean isTrade = item.getType() != null && !item.getType().isEmpty();
                    log.info("[TigerTradeCalendarService] {}: type={}, isOpen={}",
                            dateStr, item.getType(), isTrade);
                    return isTrade;
                }
            }

            log.info("[TigerTradeCalendarService] {}: date not found in response, non-trading day", dateStr);
            return false;

        } catch (Exception e) {
            log.error("[TigerTradeCalendarService] query failed for {}: {}", date, e.getMessage() != null ? e.getMessage() : e.toString(), e);
            return true;
        }
    }
}
