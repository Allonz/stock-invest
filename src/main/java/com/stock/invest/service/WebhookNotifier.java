package com.stock.invest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
 */
@Service
public class WebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${orchestration.webhook-url:http://localhost:8644/webhooks/tiger-orch}")
    private String webhookUrl;

    @Value("${orchestration.webhook-secret:}")
    private String webhookSecret;

    /**
     * 发送编排完成通知。
     *
     * @param step      刚完成的步骤（history_backfill / day_backfill / screening）
     * @param status    success / failed
     * @param runId     链路标识
     * @param tradeDate 交易日（ISO）
     * @param message   结果描述
     * @param nextStep  下一步（history_backfill / day_backfill / screening / report / none）
     * @return 是否投递成功（3 次重试后）
     */
    public boolean notify(String step, String status, String runId, String tradeDate,
                          String message, String nextStep) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_type", "step_done");
        payload.put("step", step);
        payload.put("status", status);
        payload.put("run_id", runId);
        payload.put("trade_date", tradeDate);
        payload.put("message", message);
        payload.put("next_step", nextStep);
        return notify(payload);
    }

    public boolean notify(Map<String, Object> payload) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            long[] delays = {3_000L, 9_000L, 27_000L};
            for (int attempt = 0; attempt <= delays.length; attempt++) {
                try {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    if (webhookSecret != null && !webhookSecret.isEmpty()) {
                        headers.set("X-Webhook-Signature", hmacSha256(body, webhookSecret));
                    }
                    HttpEntity<String> entity = new HttpEntity<>(body, headers);
                    String resp = restTemplate.postForObject(webhookUrl, entity, String.class);
                    log.info("[WebhookNotifier] notify ok (attempt={}), resp={}, payload={}",
                            attempt + 1, resp, body);
                    return true;
                } catch (Exception e) {
                    log.warn("[WebhookNotifier] notify failed (attempt={}/{}): {}",
                            attempt + 1, delays.length + 1, e.getMessage());
                    if (attempt < delays.length) {
                        try {
                            Thread.sleep(delays[attempt]);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                }
            }
            log.error("[WebhookNotifier] notify FAILED after {} attempts, payload={}", delays.length + 1, body);
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
