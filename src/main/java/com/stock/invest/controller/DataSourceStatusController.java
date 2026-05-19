package com.stock.invest.controller;

import com.stock.invest.datasource.DataSourceAvailabilityChecker;
import com.stock.invest.datasource.SourceStatus;
import com.stock.invest.enums.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.*;

@Controller
@RequestMapping
public class DataSourceStatusController {

    private final DataSourceAvailabilityChecker checker;

    public DataSourceStatusController(DataSourceAvailabilityChecker checker) {
        this.checker = checker;
    }

    /**
     * 数据源状态页面
     */
    @GetMapping("/page/datasource/status")
    public String statusPage() {
        return "datasource-status";
    }

    /**
     * 数据源状态 API
     */
    @GetMapping("/api/datasource/status")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatus() {
        Map<String, SourceStatus> all = checker.getAllStatus();
        List<Map<String, Object>> sources = new ArrayList<>();
        for (SourceStatus s : all.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", s.getSourceName());
            entry.put("available", s.isAvailable());
            entry.put("requirement", s.getRequirement().name());
            entry.put("hasApiKey", s.isHasApiKey());
            entry.put("reason", s.getReason());
            sources.add(entry);
        }

        long availableCount = sources.stream().filter(s -> (boolean) s.get("available")).count();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sources", sources);
        data.put("availableCount", availableCount);
        data.put("totalCount", sources.size());
        return ResponseEntity.ok(ApiResponse.ok(data));
    }
}
