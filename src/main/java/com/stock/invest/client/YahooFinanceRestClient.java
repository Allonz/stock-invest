package com.stock.invest.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.http.ResilientHttpExecutor;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.util.KLineDataUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.net.URLEncoder;

/**
 * Yahoo Finance 非官方公开接口：Chart v8（K 线）与 Screener（候选标的）。
 */
@Component
public class YahooFinanceRestClient {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceRestClient.class);

    private final ResilientHttpExecutor http;
    private final ObjectMapper objectMapper;

    public YahooFinanceRestClient(ResilientHttpExecutor http, ObjectMapper objectMapper) {
        this.http = http;
        this.objectMapper = objectMapper;
    }

    public List<String> fetchMostActiveSymbols(int count) {
        try {
            String url = "https://query1.finance.yahoo.com/v1/finance/screener/predefined/saved?scrIds=most_actives&count=" + count;
            String body = http.get(url);
            JsonNode root = objectMapper.readTree(body);
            JsonNode quotes = root.path("finance").path("result").path(0).path("quotes");
            List<String> out = new ArrayList<>();
            if (quotes.isArray()) {
                for (JsonNode q : quotes) {
                    String sym = q.path("symbol").asText("");
                    if (!sym.isEmpty()) {
                        out.add(sym);
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Yahoo screener failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 批量获取 regularMarketPrice（用于价格区间过滤，减少 chart 拉取次数）。
     */
    public Map<String, Double> fetchRegularMarketPrices(List<String> symbols) {
        Map<String, Double> out = new HashMap<>();
        if (symbols == null || symbols.isEmpty()) {
            return out;
        }
        try {
            String joined = String.join(",", symbols);
            String enc = URLEncoder.encode(joined, "UTF-8");
            String url = "https://query1.finance.yahoo.com/v7/finance/quote?symbols=" + enc;
            String body = http.get(url);
            JsonNode root = objectMapper.readTree(body);
            JsonNode result = root.path("quoteResponse").path("result");
            if (result.isArray()) {
                for (JsonNode row : result) {
                    String sym = row.path("symbol").asText("");
                    if (sym.isEmpty()) {
                        continue;
                    }
                    JsonNode p = row.get("regularMarketPrice");
                    if (p == null || p.isNull()) {
                        continue;
                    }
                    double price = p.isNumber() ? p.asDouble() : Double.parseDouble(p.asText());
                    out.put(sym, price);
                }
            }
        } catch (Exception e) {
            log.warn("Yahoo quote fetch failed: {}", e.getMessage());
        }
        return out;
    }

    public KLineData fetchDailyChart(String symbol, String range) throws Exception {
        String symEnc = URLEncoder.encode(symbol, "UTF-8");
        String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + symEnc + "?interval=1d&range=" + range;
        String body = http.get(url);
        JsonNode root = objectMapper.readTree(body);
        JsonNode chart = root.path("chart").path("result");
        if (!chart.isArray() || chart.size() == 0) {
            return empty(symbol);
        }
        JsonNode result = chart.get(0);
        JsonNode timestamps = result.get("timestamp");
        JsonNode quote = result.path("indicators").path("quote").get(0);
        if (timestamps == null || !timestamps.isArray() || quote == null) {
            return empty(symbol);
        }
        JsonNode opens = quote.get("open");
        JsonNode highs = quote.get("high");
        JsonNode lows = quote.get("low");
        JsonNode closes = quote.get("close");
        JsonNode volumes = quote.get("volume");

        KLineData data = new KLineData();
        data.setSymbol(symbol);
        List<KLineIterator> items = new ArrayList<>();
        int n = timestamps.size();
        for (int i = 0; i < n; i++) {
            if (timestamps.get(i).isNull()) {
                continue;
            }
            long ts = timestamps.get(i).asLong() * 1000L;
            KLineIterator it = new KLineIterator();
            it.setSymbol(symbol);
            it.setTime(ts);
            it.setOpen(nodeDouble(opens, i));
            it.setHigh(nodeDouble(highs, i));
            it.setLow(nodeDouble(lows, i));
            it.setClose(nodeDouble(closes, i));
            it.setVolume(nodeLong(volumes, i));
            items.add(it);
        }
        data.setItems(items);
        KLineDataUtils.sortItemsNewestFirst(data);
        return data;
    }

    public Double extractRegularMarketPrice(String symbol) {
        try {
            KLineData d = fetchDailyChart(symbol, "5d");
            if (d.getItems() == null || d.getItems().isEmpty()) {
                return null;
            }
            return d.getItems().get(0).getClose();
        } catch (Exception e) {
            return null;
        }
    }

    private static KLineData empty(String symbol) {
        KLineData d = new KLineData();
        d.setSymbol(symbol);
        return d;
    }

    private static double nodeDouble(JsonNode arr, int i) {
        if (arr == null || !arr.has(i) || arr.get(i).isNull()) {
            return 0D;
        }
        return arr.get(i).asDouble();
    }

    private static long nodeLong(JsonNode arr, int i) {
        if (arr == null || !arr.has(i) || arr.get(i).isNull()) {
            return 0L;
        }
        return arr.get(i).asLong();
    }
}
