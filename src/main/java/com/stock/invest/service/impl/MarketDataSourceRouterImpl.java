package com.stock.invest.service.impl;

import com.stock.invest.client.TigerOpenPythonBridge;
import com.stock.invest.client.TwelveDataRestClient;
import com.stock.invest.client.TiingoRestClient;
import com.stock.invest.client.YahooFinanceRestClient;
import com.stock.invest.model.KLineData;
import com.stock.invest.service.MarketDataSourceRouter;
import com.tigerbrokers.stock.openapi.client.struct.enums.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MarketDataSourceRouterImpl implements MarketDataSourceRouter {

    private static final Logger log = LoggerFactory.getLogger(MarketDataSourceRouterImpl.class);

    @Autowired
    private TwelveDataRestClient twelveDataRestClient;

    @Autowired
    private YahooFinanceRestClient yahooFinanceRestClient;

    @Autowired
    private TiingoRestClient tiingoRestClient;

    @Autowired
    private TigerOpenPythonBridge tigerOpenPythonBridge;

    @Autowired(required = false)
    private TigerStockServiceImpl tigerStockService;

    private static final List<String> SOURCE_ORDER = Arrays.asList("tiger", "tigeropen", "yfinance", "twelvedata", "tiingo");
    private static final long DEFAULT_COOLDOWN_MS = 5 * 60 * 1000L;
    private final Map<String, Long> sourceBackoffUntil = new ConcurrentHashMap<>();

    @Override
    public List<String> loadCandidates(int limit, double minPrice, double maxPrice) {
        log.debug("[DataSourceRouter] loadCandidates: begin — limit={}, minPrice={}, maxPrice={}", limit, minPrice, maxPrice);
        for (String source : SOURCE_ORDER) {
            log.debug("[DataSourceRouter] loadCandidates: trying source={}", source);
            List<String> out = tryLoadCandidates(source, limit, minPrice, maxPrice);
            if (!out.isEmpty()) {
                log.info("[DataSourceRouter] loadCandidates: success — source={}, candidates={}", source, out.size());
                return out;
            }
            log.debug("[DataSourceRouter] loadCandidates: no candidates from source={}", source);
        }
        log.warn("[DataSourceRouter] loadCandidates: all sources exhausted");
        return Collections.emptyList();
    }

    @Override
    public Optional<KLineData> fetchDailyBars(String symbol, String preferredSource, int barsCount) {
        log.debug("[DataSourceRouter] fetchDailyBars: begin — symbol={}, preferredSource={}, barsCount={}",
                symbol, preferredSource, barsCount);
        List<String> order = orderedSources(preferredSource);
        for (String source : order) {
            log.info("[DataSourceRouter] fetchDailyBars: trying — symbol={}, source={}", symbol, source);
            Optional<KLineData> data = tryFetchBars(symbol, source, barsCount);
            if (data.isPresent()) {
                log.info("[DataSourceRouter] fetchDailyBars: success — symbol={}, source={}", symbol, source);
                return data;
            }
            log.warn("[DataSourceRouter] fetchDailyBars: no data — symbol={}, source={}", symbol, source);
        }
        log.error("[DataSourceRouter] fetchDailyBars: all sources exhausted — symbol={}", symbol);
        return Optional.empty();
    }

    @Override
    public Optional<KLineData> fetchLatestDailyBar(String symbol, String preferredSource) {
        log.debug("[DataSourceRouter] fetchLatestDailyBar: begin — symbol={}, preferredSource={}", symbol, preferredSource);
        return fetchDailyBars(symbol, preferredSource, 1);
    }

    private List<String> tryLoadCandidates(String source, int limit, double minPrice, double maxPrice) {
        if (isSourceCoolingDown(source)) {
            log.warn("[DataSourceRouter] tryLoadCandidates: source in cooldown — source={}", source);
            return Collections.emptyList();
        }
        try {
            if ("tigeropen".equals(source)) {
                if (!tigerOpenPythonBridge.hasCredentials()) {
                    return Collections.emptyList();
                }
                return tigerOpenPythonBridge.listCandidates(Math.max(limit, 50), minPrice, maxPrice);
            }
            if ("tiger".equals(source)) {
                if (tigerStockService == null) {
                    return Collections.emptyList();
                }
                return tigerStockService.scanStocks(Market.US, Math.max(limit, 50), minPrice, maxPrice);
            }
            if ("twelvedata".equals(source)) {
                List<String> symbols = twelveDataRestClient.listUsStockSymbols(Math.max(limit * 5, 100));
                List<String> out = new ArrayList<>();
                for (String sym : symbols) {
                    Double close = twelveDataRestClient.fetchLastClose(sym);
                    if (close != null && close >= minPrice && close <= maxPrice) {
                        out.add(sym);
                    }
                    if (out.size() >= limit) {
                        break;
                    }
                }
                return out;
            }
            if ("yfinance".equals(source)) {
                List<String> symbols = yahooFinanceRestClient.fetchMostActiveSymbols(Math.max(limit * 5, 100));
                List<String> usSymbols = new ArrayList<>();
                for (String symbol : symbols) {
                    if (symbol != null && symbol.matches("^[A-Z0-9\\-]+$")) {
                        usSymbols.add(symbol);
                    }
                }
                Map<String, Double> prices = yahooFinanceRestClient.fetchRegularMarketPrices(usSymbols);
                List<String> out = new ArrayList<>();
                for (String sym : usSymbols) {
                    Double price = prices.get(sym);
                    if (price != null && price >= minPrice && price <= maxPrice) {
                        out.add(sym);
                    }
                    if (out.size() >= limit) {
                        break;
                    }
                }
                return out;
            }
            if ("tiingo".equals(source)) {
                return tiingoRestClient.listUsSymbolsByPriceRange(Math.max(limit, 50), minPrice, maxPrice);
            }
        } catch (Exception e) {
            log.error("[DataSourceRouter] loadCandidates: error — source={}, error={}", source, e.getMessage(), e);
            applyCooldown(source, e);
        }
        return Collections.emptyList();
    }

    private Optional<KLineData> tryFetchBars(String symbol, String source, int barsCount) {
        if (isSourceCoolingDown(source)) {
            log.warn("[DataSourceRouter] tryFetchBars: source in cooldown — source={}, symbol={}", source, symbol);
            return Optional.empty();
        }
        try {
            if ("tigeropen".equals(source)) {
                if (tigerOpenPythonBridge.hasCredentials()) {
                    KLineData data = tigerOpenPythonBridge.fetchDailyBars(symbol, Math.max(barsCount, 7));
                    if (data != null && data.getItems() != null && !data.getItems().isEmpty()) {
                        return Optional.of(data);
                    }
                }
            }
            if ("tiger".equals(source) && tigerStockService != null) {
                KLineData data = tigerStockService.getDailyKLineDataAsObject(symbol);
                if (data != null && data.getItems() != null && !data.getItems().isEmpty()) {
                    return Optional.of(data);
                }
            }
            if ("twelvedata".equals(source)) {
                KLineData data = twelveDataRestClient.fetchDailyBars(symbol, Math.max(barsCount, 7));
                if (data != null && data.getItems() != null && !data.getItems().isEmpty()) {
                    return Optional.of(data);
                }
            }
            if ("yfinance".equals(source)) {
                KLineData data = yahooFinanceRestClient.fetchDailyChart(symbol, barsCount <= 1 ? "5d" : "1mo");
                if (data != null && data.getItems() != null && !data.getItems().isEmpty()) {
                    return Optional.of(data);
                }
            }
            if ("tiingo".equals(source)) {
                KLineData data = tiingoRestClient.fetchDailyBars(symbol, Math.max(barsCount, 7));
                if (data != null && data.getItems() != null && !data.getItems().isEmpty()) {
                    return Optional.of(data);
                }
            }
        } catch (Exception e) {
            log.error("[DataSourceRouter] tryFetchBars: error — symbol={}, source={}, error={}", symbol, source, e.getMessage(), e);
            applyCooldown(source, e);
        }
        return Optional.empty();
    }

    private boolean isSourceCoolingDown(String source) {
        Long until = sourceBackoffUntil.get(source);
        if (until == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now >= until) {
            sourceBackoffUntil.remove(source);
            log.info("[DataSourceRouter] isSourceCoolingDown: cooldown expired — source={}", source);
            return false;
        }
        long remainingSec = (until - now) / 1000;
        log.debug("[DataSourceRouter] isSourceCoolingDown: source={}, remainingSec={}", source, remainingSec);
        return true;
    }

    private void applyCooldown(String source, Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        long cooldownMs = 0L;
        if (msg.contains("InvalidKeySpecException") || msg.contains("Unable to decode key")) {
            cooldownMs = 30 * 60 * 1000L; // Tiger key error: long cooldown
        } else if (msg.contains("permission denied")) {
            cooldownMs = 30 * 60 * 1000L; // Tiger quota/permission issue
        } else if (msg.contains("403 Forbidden")) {
            cooldownMs = 10 * 60 * 1000L; // Yahoo anti-bot
        } else if (msg.toLowerCase().contains("apikey") && msg.toLowerCase().contains("incorrect")) {
            cooldownMs = 30 * 60 * 1000L; // TwelveData key invalid
        } else if (msg.contains("401 Unauthorized") || msg.toLowerCase().contains("tiingo token")) {
            cooldownMs = 30 * 60 * 1000L; // Tiingo auth issue
        } else if (msg.contains("429")) {
            cooldownMs = 5 * 60 * 1000L;
        } else if (msg.contains("timed out") || msg.contains("timeout")) {
            cooldownMs = DEFAULT_COOLDOWN_MS;
        }
        if (cooldownMs > 0) {
            long until = System.currentTimeMillis() + cooldownMs;
            Long old = sourceBackoffUntil.put(source, until);
            if (old == null || old < until) {
                log.warn("[DataSourceRouter] enterCooldown: source={} enters cooldown for {}s — error={}",
                        source, cooldownMs / 1000L, msg);
            }
        }
    }

    private static List<String> orderedSources(String preferred) {
        if (preferred == null || preferred.trim().isEmpty()) {
            return SOURCE_ORDER;
        }
        List<String> ordered = new ArrayList<>();
        ordered.add(preferred);
        for (String source : SOURCE_ORDER) {
            if (!source.equals(preferred)) {
                ordered.add(source);
            }
        }
        return ordered;
    }
}
