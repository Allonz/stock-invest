package com.stock.invest.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.config.TwelveDataProperties;
import com.stock.invest.http.ResilientHttpExecutor;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.util.KLineDataUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.URLEncoder;

/**
 * TwelveData REST API（日线行情、报价、美股列表）。
 */
@Component
public class TwelveDataRestClient {

    private static final Logger log = LoggerFactory.getLogger(TwelveDataRestClient.class);

    private final ResilientHttpExecutor http;
    private final TwelveDataProperties props;
    private final ObjectMapper objectMapper;
    private final AtomicInteger keyRoundRobin = new AtomicInteger();

    public TwelveDataRestClient(ResilientHttpExecutor http, TwelveDataProperties props, ObjectMapper objectMapper) {
        this.http = http;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    private String nextApiKey() {
        List<String> keys = props.resolvedKeys();
        if (keys.isEmpty()) {
            return "";
        }
        int idx = Math.floorMod(keyRoundRobin.getAndIncrement(), keys.size());
        return keys.get(idx);
    }

    private String buildUrl(String path, String queryWithoutKey) {
        String base = props.getBaseUrl() == null ? "https://api.twelvedata.com" : props.getBaseUrl().replaceAll("/$", "");
        return base + path + queryWithoutKey;
    }

    private Map<String, String> authHeaders() {
        return Map.of("Authorization", "apikey " + nextApiKey());
    }

    /**
     * 拉取美国市场股票代码（按返回顺序截取）。
     */
    public List<String> listUsStockSymbols(int maxSymbols) throws Exception {
        String url = buildUrl("/stocks", "?country=United%20States");
        String body = http.get(url, authHeaders());
        JsonNode root = objectMapper.readTree(body);
        if (!root.path("code").asText().isEmpty() && !"200".equals(root.path("code").asText())) {
            log.warn("TwelveData /stocks error: {}", root.path("message").asText());
        }
        JsonNode data = root.get("data");
        List<String> out = new ArrayList<>();
        if (data != null && data.isArray()) {
            Iterator<JsonNode> it = data.elements();
            while (it.hasNext() && out.size() < maxSymbols) {
                JsonNode row = it.next();
                String sym = row.path("symbol").asText("");
                if (!sym.isEmpty()) {
                    out.add(sym);
                }
            }
        }
        return out;
    }

    /**
     * 最新价（或最近收盘）用于价格带过滤。
     */
    public Double fetchLastClose(String symbol) throws Exception {
        String url = buildUrl("/quote", "?symbol=" + URLEncoder.encode(symbol, "UTF-8"));
        String body = http.get(url, authHeaders());
        JsonNode root = objectMapper.readTree(body);
        if (root.has("code") && root.path("status").asText().equalsIgnoreCase("error")) {
            return null;
        }
        JsonNode close = root.get("close");
        if (close != null && close.isTextual()) {
            return Double.parseDouble(close.asText());
        }
        if (close != null && close.isNumber()) {
            return close.asDouble();
        }
        return null;
    }

    public KLineData fetchDailyBars(String symbol, int outputSize) throws Exception {
        String url = buildUrl(
                "/time_series",
                "?symbol=" + URLEncoder.encode(symbol, "UTF-8")
                        + "&interval=1day&outputsize=" + outputSize + "&order=DESC"
        );
        String body = http.get(url, authHeaders());
        JsonNode root = objectMapper.readTree(body);
        if (root.has("status") && "error".equalsIgnoreCase(root.path("status").asText())) {
            log.debug("TwelveData time_series error for {}: {}", symbol, root.path("message").asText());
            return null;
        }
        JsonNode values = root.get("values");
        KLineData data = new KLineData();
        data.setSymbol(symbol);
        if (values == null || !values.isArray()) {
            return data;
        }
        List<KLineIterator> items = new ArrayList<>();
        for (JsonNode v : values) {
            KLineIterator it = new KLineIterator();
            it.setSymbol(symbol);
            String dt = v.path("datetime").asText("");
            it.setTimeString(dt);
            it.setTime(parseDatetimeToMillis(dt));
            it.setOpen(parseDouble(v, "open"));
            it.setHigh(parseDouble(v, "high"));
            it.setLow(parseDouble(v, "low"));
            it.setClose(parseDouble(v, "close"));
            it.setVolume(parseLong(v, "volume"));
            items.add(it);
        }
        data.setItems(items);
        KLineDataUtils.sortItemsNewestFirst(data);
        return data;
    }

    private static long parseDatetimeToMillis(String dt) {
        if (dt == null) {
            return 0L;
        }
        String s = dt.trim();
        if (s.isEmpty()) {
            return 0L;
        }
        // 常见格式：YYYY-MM-DD（TwelveData time_series 的 datetime）
        try {
            if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
                LocalDate d = LocalDate.parse(s);
                return d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            }
        } catch (Exception e) {
            // ignore - skip on failure
        }
        // 兜底：尝试当作长整型时间戳（可能是秒或毫秒）
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            // ignore - field not applicable
            return 0L;
        }
    }

    private static double parseDouble(JsonNode v, String field) {
        JsonNode n = v.get(field);
        if (n == null || n.isNull()) {
            return 0D;
        }
        return Double.parseDouble(n.asText());
    }

    private static long parseLong(JsonNode v, String field) {
        JsonNode n = v.get(field);
        if (n == null || n.isNull()) {
            return 0L;
        }
        String s = n.asText();
        try {
            return (long) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
