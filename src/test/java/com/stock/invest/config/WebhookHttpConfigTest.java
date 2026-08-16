package com.stock.invest.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("WebhookHttpConfig — 禁重定向 + 超时")
class WebhookHttpConfigTest {

    @Test
    @DisplayName("webhookRestTemplate 不跟随 302 重定向（SSRF 防绕过）")
    void webhookRestTemplate_doesNotFollowRedirects() throws Exception {
        AtomicBoolean internalHit = new AtomicBoolean(false);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal", exchange -> {
            internalHit.set(true);
            byte[] body = "internal".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/redirect", exchange -> {
            String location = "http://127.0.0.1:" + server.getAddress().getPort() + "/internal";
            exchange.getResponseHeaders().add("Location", location);
            byte[] body = "redirect".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(302, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            WebhookHttpConfig config = new WebhookHttpConfig();
            ReflectionTestUtils.setField(config, "connectTimeoutMs", 1_000);
            ReflectionTestUtils.setField(config, "readTimeoutMs", 2_000);
            RestTemplate rt = config.webhookRestTemplate();

            int port = server.getAddress().getPort();
            ResponseEntity<String> response = rt.postForEntity(
                    "http://127.0.0.1:" + port + "/redirect",
                    new HttpEntity<>("{}"),
                    String.class);

            assertEquals(302, response.getStatusCode().value(),
                    "redirect must be returned as-is when following is disabled");
            assertFalse(internalHit.get(), "internal endpoint must NOT be hit after redirect");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("webhookRestTemplate read 超时生效，不会无限挂起")
    void webhookRestTemplate_readTimeoutThrowsQuickly() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            WebhookHttpConfig config = new WebhookHttpConfig();
            ReflectionTestUtils.setField(config, "connectTimeoutMs", 500);
            ReflectionTestUtils.setField(config, "readTimeoutMs", 200);
            RestTemplate rt = config.webhookRestTemplate();

            ExecutorService pool = Executors.newSingleThreadExecutor();
            Future<?> acceptedFuture = pool.submit(() -> {
                try (Socket ignored = serverSocket.accept()) {
                    // accept 后不响应，等待客户端 read 超时
                    Thread.sleep(5_000);
                } catch (Exception ignored) {
                    // 测试结束后关闭
                }
            });

            long start = System.nanoTime();
            try {
                assertThrows(ResourceAccessException.class, () ->
                        rt.postForEntity(
                                "http://127.0.0.1:" + serverSocket.getLocalPort() + "/hook",
                                new HttpEntity<>("{}"),
                                String.class));
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                assertTrue(elapsedMs < 3_000,
                        "read timeout should fire well under 3s, elapsed=" + elapsedMs + "ms");
            } finally {
                acceptedFuture.cancel(true);
                pool.shutdownNow();
            }
        }
    }
}
