package com.stock.invest.mcp;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 端点 HEAD 探测支持。
 *
 * <p>MCP Streamable HTTP 传输（spring-ai 1.1.8 → MCP SDK
 * {@code WebMvcStreamableServerTransportProvider}）通过 RouterFunction 只注册了
 * GET/POST 到 {@code /api/mcp}（路径见 application.yml 的
 * {@code spring.ai.mcp.server.streamable-http.mcp-endpoint}），HEAD 请求匹配不到任何
 * 路由返回 404。部分 MCP 客户端（如 Hermes）会用 HEAD 探测端点、读取 Content-Type
 * 确认这是 MCP 端点，故此处显式注册 HEAD：返回 200 + {@code application/json}（MCP
 * Streamable HTTP 端点的主媒体类型），无响应体。</p>
 */
@RestController
public class McpEndpointHeadProbeController {

    /** 与 application.yml spring.ai.mcp.server.streamable-http.mcp-endpoint 保持一致 */
    private static final String MCP_ENDPOINT = "/api/mcp";

    @RequestMapping(value = MCP_ENDPOINT, method = RequestMethod.HEAD)
    public ResponseEntity<Void> headProbe() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
