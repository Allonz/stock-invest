package com.stock.invest.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.invest.config.TiingoProperties;
import com.stock.invest.http.ResilientHttpExecutor;
import com.stock.invest.model.KLineData;
import com.stock.invest.model.KLineIterator;
import com.stock.invest.util.KLineDataUtils;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class TiingoRestClient {

    private final ResilientHttpExecutor http;
    private final TiingoProperties props;
    private final ObjectMapper objectMapper;

    public TiingoRestClient(ResilientHttpExecutor http, TiingoProperties props, ObjectMapper objectMapper) {
        this.http = http;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    private Map<String, String> authHeaders() {
        return Map.of("Authorization", "Token " + props.getToken().trim());
    }

    public List<String> listUsSymbolsByPriceRange(int limit, double minPrice, double maxPrice) throws Exception {
        requireToken();
        String url = baseUrl() + "/iex/";
        String body = http.get(url, authHeaders());
        JsonNode root = objectMapper.readTree(body);
        ensureNoApiError(root);
        if (!root.isArray()) {
            return new ArrayList<String>();
        }
        List<String> out = new ArrayList<String>();
        for (JsonNode row : root) {
            String symbol = row.path("ticker").asText("");
            if (symbol.isEmpty() || !symbol.matches("^[A-Z0-9\\-]+$")) {
                continue;
            }
            Double price = resolvePrice(row);
            if (price == null) {
                continue;
            }
            if (price >= minPrice && price <= maxPrice) {
                out.add(symbol);
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    public KLineData fetchDailyBars(String symbol, int outputSize) throws Exception {
        requireToken();
        int days = Math.max(outputSize + 10, 20);
        LocalDate end = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = end.minusDays(days);
        String url = baseUrl() + "/tiingo/daily/" + encode(symbol)
                + "/prices?startDate=" + start
                + "&endDate=" + end
                + "&resampleFreq=daily";
        String body = http.get(url, authHeaders());
        JsonNode root = objectMapper.readTree(body);
        ensureNoApiError(root);
        KLineData data = new KLineData();
        data.setSymbol(symbol);
        if (!root.isArray()) {
            return data;
        }
        List<KLineIterator> items = new ArrayList<KLineIterator>();
        for (JsonNode row : root) {
            KLineIterator it = new KLineIterator();
            it.setSymbol(symbol);
            String date = row.path("date").asText("");
            it.setTime(parseDateToMillis(date));
            it.setTimeString(extractDatePart(date));
            it.setOpen(parseDouble(row, "open"));
            it.setHigh(parseDouble(row, "high"));
            it.setLow(parseDouble(row, "low"));
            it.setClose(parseDouble(row, "close"));
            it.setVolume(parseLong(row, "volume"));
            items.add(it);
        }
        data.setItems(items);
        KLineDataUtils.sortItemsNewestFirst(data);
        return data;
    }

    public Double fetchLastClose(String symbol) throws Exception {
        KLineData data = fetchDailyBars(symbol, 3);
        if (data.getItems() == null || data.getItems().isEmpty()) {
            return null;
        }
        return data.getItems().get(0).getClose();
    }

    private void requireToken() {
        if (!props.hasToken()) {
            throw new IllegalStateException("tiingo token is missing");
        }
    }

    private String baseUrl() {
        String base = props.getBaseUrl() == null ? "https://api.tiingo.com" : props.getBaseUrl();
        return base.replaceAll("/$", "");
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static Double resolvePrice(JsonNode node) {
        String[] fields = {"tngoLast", "last", "prevClose", "open"};
        for (String field : fields) {
            JsonNode p = node.get(field);
            if (p == null || p.isNull()) {
                continue;
            }
            if (p.isNumber()) {
                return p.asDouble();
            }
            String text = p.asText("");
            if (text.trim().isEmpty()) {
                continue;
            }
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                // ignore - field not applicable
            }
        }
        return null;
    }

    private static double parseDouble(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return 0D;
        }
        return v.isNumber() ? v.asDouble() : safeDouble(v.asText());
    }

    private static long parseLong(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return 0L;
        }
        if (v.isNumber()) {
            return v.asLong();
        }
        String text = v.asText("");
        try {
            return (long) Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static long parseDateToMillis(String dateText) {
        if (dateText == null || dateText.trim().isEmpty()) {
            return 0L;
        }
        String normalized = dateText.trim();
        try {
            return java.time.OffsetDateTime.parse(normalized).toInstant().toEpochMilli();
        } catch (Exception e) {
            // ignore - skip on failure
        }
        try {
            LocalDate d = LocalDate.parse(extractDatePart(normalized));
            return d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String extractDatePart(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        if (t.length() >= 10) {
            return t.substring(0, 10);
        }
        return t;
    }

    private static double safeDouble(String text) {
        try {
            return Double.parseDouble(text);
        } catch (Exception e) {
            // ignore - skip on failure
            return 0D;
        }
    }

    private static void ensureNoApiError(JsonNode root) {
        if (root == null) {
            return;
        }
        if (root.isObject()) {
            String detail = root.path("detail").asText("");
            if (!detail.trim().isEmpty()) {
                throw new IllegalStateException("tiingo api error: " + detail);
            }
            String message = root.path("message").asText("");
            if (!message.trim().isEmpty() && root.has("code")) {
                throw new IllegalStateException("tiingo api error: " + message);
            }
            String error = root.path("error").asText("");
            if (!error.trim().isEmpty()) {
                throw new IllegalStateException("tiingo api error: " + error);
            }
        }
    }
}
