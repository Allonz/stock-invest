package com.stock.invest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.web.client.MockRestServiceServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("WebhookNotifier — 签名、重试与 3xx 处理")
class WebhookNotifierTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("通知成功：POST JSON 且携带 HMAC-SHA256 签名头")
    void notifySuccess_sendsSignatureAndPayload() throws Exception {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        String url = "http://localhost:8644/webhooks/tiger-orch";
        String secret = "test-secret";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_type", "step_done");
        payload.put("step", "screening");
        String body = objectMapper.writeValueAsString(payload);
        String expectedSig = hmacSha256(body, secret);

        server.expect(once(), requestTo(url))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Webhook-Signature", expectedSig))
                .andExpect(jsonPath("$.step").value("screening"))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        WebhookNotifier notifier = new WebhookNotifier(
                objectMapper, rt, url, secret, new long[]{0, 0, 0});

        boolean ok = notifier.notify(payload);
        assertTrue(ok, "notify should succeed on 200 response");
        server.verify();
    }

    @Test
    @DisplayName("通知失败重试 4 次后返回 false（重试延迟注入为 0）")
    void notifyFailure_retriesFourTimesThenFalse() throws Exception {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        String url = "http://localhost:8644/webhooks/tiger-orch";

        server.expect(times(4), requestTo(url))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("{\"error\":\"boom\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        WebhookNotifier notifier = new WebhookNotifier(
                objectMapper, rt, url, "", new long[]{0, 0, 0});

        assertFalse(notifier.notify(Map.of("event_type", "step_done")),
                "notify must return false after all retries exhausted");
        server.verify();
    }

    @Test
    @DisplayName("3xx 响应按失败处理并重试，不视为成功")
    void notifyRedirect_isTreatedAsFailure() throws Exception {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        String url = "http://localhost:8644/webhooks/tiger-orch";

        server.expect(times(4), requestTo(url))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .body("redirect")
                        .contentType(MediaType.TEXT_HTML));

        WebhookNotifier notifier = new WebhookNotifier(
                objectMapper, rt, url, "", new long[]{0, 0, 0});

        assertFalse(notifier.notify(Map.of("event_type", "step_done")),
                "3xx must be treated as failure and exhaust retries");
        server.verify();
    }

    private static String hmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : raw) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
