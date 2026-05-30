package com.stock.invest.controller;

import com.stock.invest.datasource.DataSourceAvailabilityChecker;
import com.stock.invest.datasource.DataSourceCapability;
import com.stock.invest.datasource.SourceStatus;
import com.stock.invest.enums.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * 数据源状态 API 控制器
 */
@RestController
@RequestMapping("/api/datasource")
public class DataSourceStatusApiController {

    private static final Logger log = LoggerFactory.getLogger(DataSourceStatusApiController.class);

    private final DataSourceAvailabilityChecker checker;

    public DataSourceStatusApiController(DataSourceAvailabilityChecker checker) {
        this.checker = checker;
    }

    /**
     * 获取数据源状态（含能力标识）
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatus() {
        Map<String, SourceStatus> all = checker.getAllStatus();
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Map.Entry<String, SourceStatus> entry : all.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            SourceStatus status = entry.getValue();
            item.put("name", entry.getKey());
            item.put("available", status.isAvailable());
            item.put("reason", status.getReason());
            item.put("hasApiKey", status.isHasApiKey());
            item.put("capabilities", capabilityStrings(status.getCapabilities()));
            sources.add(item);
        }

        long availableCount = sources.stream().filter(s -> (boolean) s.get("available")).count();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sources", sources);
        data.put("availableCount", availableCount);
        data.put("totalCount", sources.size());
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    /**
     * GET /api/datasource/health — 数据源健康检查（详细状态）
     */
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> health() {
        try {
            Map<String, SourceStatus> statusMap = checker.getAllStatus();
            List<SourceStatus> statuses = new ArrayList<>(statusMap.values());
            List<Map<String, Object>> details = statuses.stream().map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", s.getSourceName());
                m.put("available", s.isAvailable());
                m.put("hasApiKey", s.isHasApiKey());
                m.put("reason", s.getReason());
                m.put("healthy", s.isAvailable() && s.isHasApiKey());
                m.put("capabilities", capabilityStrings(s.getCapabilities()));
                return m;
            }).toList();

            long healthyCount = details.stream().filter(d -> (boolean) d.get("healthy")).count();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sources", details);
            data.put("healthyCount", healthyCount);
            data.put("totalCount", details.size());
            data.put("allHealthy", healthyCount == details.size());
            return ResponseEntity.ok(ApiResponse.ok(data));
        } catch (Exception e) {
            log.error("datasource health check failed", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Health check failed"));
        }
    }

    /** 将 capabilities 枚举转为字符串列表 */
    private List<String> capabilityStrings(Set<DataSourceCapability> caps) {
        if (caps == null || caps.isEmpty()) return Collections.emptyList();
        return caps.stream().map(Enum::name).toList();
    }
}
