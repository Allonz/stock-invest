package com.stock.invest.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Alpaca Markets HTTP 客户端。
 * 仅用于查询美股交易日历，不用于股票行情查询。
 * 数据范围：1970-01-01 ～ 2029-12-31。
 *
 * API 文档：https://docs.alpaca.markets/us/reference/legacycalendar
 */
@Component
public class AlpacaRestClient {

    private static final Logger log = LoggerFactory.getLogger(AlpacaRestClient.class);
    private static final String BASE_URL = "https://api.alpaca.markets/v2";

    private final String keyId;
    private final String secretKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AlpacaRestClient(
            @Value("${alpaca.api.key-id:}") String keyId,
            @Value("${alpaca.api.secret-key:}") String secretKey,
            ObjectMapper objectMapper) {
        this.keyId = keyId;
        this.secretKey = secretKey;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
    }

    /**
     * 是否配置了有效凭证。
     */
    public boolean hasCredentials() {
        return keyId != null && !keyId.trim().isEmpty()
                && secretKey != null && !secretKey.trim().isEmpty();
    }

    /**
     * 获取密钥 ID 的掩码版本（前端展示用）。
     */
    public String getKeyIdMasked() {
        if (keyId == null || keyId.length() < 4) return "";
        return keyId.substring(0, 4) + "****";
    }

    /**
     * 判断指定日期是否为美股交易日。
     * GET /v2/calendar?start=YYYY-MM-DD&end=YYYY-MM-DD
     *
     * @param date 查询日期
     * @return true=交易日，false=非交易日
     * @throws IOException 网络或认证错误
     */
    public boolean isTradingDay(LocalDate date) throws IOException {
        String dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String url = BASE_URL + "/calendar?start=" + dateStr + "&end=" + dateStr;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", basicAuthHeader())
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401 || response.statusCode() == 403) {
                log.warn("[alpaca] 认证失败 ({}): 请检查 API Key 是否正确", response.statusCode());
                throw new IOException("Alpaca API 认证失败，请检查 key-id 和 secret-key");
            }
            if (response.statusCode() != 200) {
                log.warn("[alpaca] 请求失败: status={}, body={}", response.statusCode(), response.body());
                throw new IOException("Alpaca API 返回状态码: " + response.statusCode());
            }

            List<Map<String, Object>> items = objectMapper.readValue(
                    response.body(), new TypeReference<List<Map<String, Object>>>() {});
            boolean isTrading = !items.isEmpty();
            log.debug("[alpaca] {} 查询结果: tradingDay={}, items={}", dateStr, isTrading, items.size());
            return isTrading;
        } catch (IOException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Alpaca 请求被中断", e);
        } catch (Exception e) {
            throw new IOException("Alpaca 日历查询失败: " + e.getMessage(), e);
        }
    }

    /** 构建 Basic Auth 头 */
    private String basicAuthHeader() {
        String credentials = keyId + ":" + secretKey;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
        return "Basic " + encoded;
    }
}
