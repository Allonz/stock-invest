package com.stock.invest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Webhook 通知器：编排步骤完成后回调 Hermes。
 * 签名：X-Webhook-Signature = HMAC-SHA256(body)，hex 小写（Hermes V1 body-only 模式）。
 * 失败重试 3 次（间隔 3s/9s/27s 退避），3 次仍失败记录错误日志。
 *
 * 支持按 run 动态指定回调端点（webhookUrl 参数），null 时回退全局配置
 * orchestration.webhook-url（"谁触发就回调谁"）。
 */
@Service
public class WebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);
    private static final long[] DEFAULT_RETRY_DELAYS_MS = {3_000L, 9_000L, 27_000L};

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String webhookUrl;
    private final String webhookSecret;
    private final long[] retryDelaysMs;

    @Autowired
    public WebhookNotifier(ObjectMapper objectMapper,
                           @Qualifier("webhookRestTemplate") RestTemplate restTemplate,
                           @Value("${orchestration.webhook-url:http://localhost:8644/webhooks/tiger-orch}") String webhookUrl,
                           @Value("${orchestration.webhook-secret:}") String webhookSecret) {
        this(objectMapper, restTemplate, webhookUrl, webhookSecret, DEFAULT_RETRY_DELAYS_MS);
    }

    /** 包级测试构造器：可注入短重试延迟，避免单测等待 3/9/27 秒。 */
    WebhookNotifier(ObjectMapper objectMapper,
                    RestTemplate restTemplate,
                    String webhookUrl,
                    String webhookSecret,
                    long[] retryDelaysMs) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
        this.webhookUrl = webhookUrl;
        this.webhookSecret = webhookSecret;
        this.retryDelaysMs = retryDelaysMs == null ? DEFAULT_RETRY_DELAYS_MS : retryDelaysMs;
    }

    /**
     * 发送编排完成通知（使用全局配置的回调端点）。
     */
    public boolean notify(String step, String status, String runId, String tradeDate,
                          String message, String nextStep) {
        return notify(step, status, runId, tradeDate, message, nextStep, null);
    }

    /**
     * 发送编排完成通知。
     *
     * @param step      刚完成的步骤（history_backfill / day_backfill / screening）
     * @param status    success / failed
     * @param runId     链路标识
     * @param tradeDate 交易日（ISO）
     * @param message   结果描述
     * @param nextStep  下一步（history_backfill / day_backfill / screening / report / none）
     * @param runWebhookUrl 本 run 的回调端点（可空，null 回退全局配置）
     * @return 是否投递成功（3 次重试后）
     */
    public boolean notify(String step, String status, String runId, String tradeDate,
                          String message, String nextStep, String runWebhookUrl) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_type", "step_done");
        payload.put("step", step);
        payload.put("status", status);
        payload.put("run_id", runId);
        payload.put("trade_date", tradeDate);
        payload.put("message", message);
        payload.put("next_step", nextStep);
        return notify(payload, runWebhookUrl);
    }

    public boolean notify(Map<String, Object> payload) {
        return notify(payload, null);
    }

    public boolean notify(Map<String, Object> payload, String runWebhookUrl) {
        String targetUrl = (runWebhookUrl != null && !runWebhookUrl.isBlank())
                ? runWebhookUrl
                : webhookUrl;
        try {
            String body = objectMapper.writeValueAsString(payload);
            for (int attempt = 0; attempt <= retryDelaysMs.length; attempt++) {
                try {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    if (webhookSecret != null && !webhookSecret.isEmpty()) {
                        headers.set("X-Webhook-Signature", hmacSha256(body, webhookSecret));
                    }
                    HttpEntity<String> entity = new HttpEntity<>(body, headers);
                    ResponseEntity<String> response = restTemplate.postForEntity(targetUrl, entity, String.class);
                    // 已禁用自动重定向，但 3xx 仍会被 RestTemplate 视为可读取响应：
                    // 显式按失败处理，避免把重定向当作成功。
                    if (response.getStatusCode().is3xxRedirection()) {
                        throw new java.io.IOException("Webhook returned redirect status: "
                                + response.getStatusCode().value());
                    }
                    log.info("[WebhookNotifier] notify ok (attempt={}), url={}, status={}, resp={}, payload={}",
                            attempt + 1, targetUrl, response.getStatusCode().value(), response.getBody(), body);
                    return true;
                } catch (Exception e) {
                    log.warn("[WebhookNotifier] notify failed (attempt={}/{}): {}",
                            attempt + 1, retryDelaysMs.length + 1, e.getMessage());
                    if (attempt < retryDelaysMs.length) {
                        try {
                            Thread.sleep(retryDelaysMs[attempt]);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                }
            }
            log.error("[WebhookNotifier] notify FAILED after {} attempts, url={}, payload={}",
                    retryDelaysMs.length + 1, targetUrl, body);
            return false;
        } catch (Exception e) {
            log.error("[WebhookNotifier] serialize payload failed", e);
            return false;
        }
    }

    private String hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
