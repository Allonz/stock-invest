package com.stock.invest.datasource;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 数据源可用性检查器。
 * 启动时 @PostConstruct 自动运行，扫描所有注册的 AvailabilityRule，
 * 将结果缓存到内存中，供路由层和 API 层查询。
 *
 * 线程安全：结果写入后只读，无需加锁。
 */
@Component
public class DataSourceAvailabilityChecker {

    private static final Logger log = LoggerFactory.getLogger(DataSourceAvailabilityChecker.class);

    /** 所有注册的可用性检查规则 */
    private final List<AvailabilityRule> rules;

    /** 检查结果缓存：sourceName → SourceStatus */
    private final Map<String, SourceStatus> statusMap = new ConcurrentHashMap<>();

    public DataSourceAvailabilityChecker(List<AvailabilityRule> rules) {
        this.rules = rules;
    }

    /**
     * 启动时初始化：逐一检查所有数据源。
     */
    @PostConstruct
    public void init() {
        log.info("[DataSourceAvailabilityChecker] Starting availability check for {} data sources", rules.size());
        for (AvailabilityRule rule : rules) {
            String name = rule.getSourceName();
            boolean available = rule.check();
            String detail = rule.getDetail();
            SourceRequirement req = rule.getRequirement();
            // 对于 REQUIRED 类型且 check 返回 false，hasApiKey 为 false
            boolean hasKey = available || (req == SourceRequirement.OPTIONAL && !"缺失 API Key / 凭证".equals(detail));
            if (req == SourceRequirement.REQUIRED) {
                // REQUIRED 类型：available 时才有 key
                hasKey = available;
            }
            SourceStatus status = new SourceStatus(name, available, available ? null : detail, req, hasKey);
            statusMap.put(name, status);
            log.info("[DataSourceAvailabilityChecker] {}: available={} (req={}, detail={})",
                    name, available, req, detail);
        }
        log.info("[DataSourceAvailabilityChecker] Available sources: {}", getAvailableSourceNames());
    }

    /** 查询单个数据源是否可用 */
    public boolean isAvailable(String sourceName) {
        SourceStatus status = statusMap.get(sourceName);
        return status != null && status.isAvailable();
    }

    /** 获取当前所有可用数据源的名称列表（按注册顺序） */
    public List<String> getAvailableSourceNames() {
        return statusMap.values().stream()
                .filter(SourceStatus::isAvailable)
                .map(SourceStatus::getSourceName)
                .collect(Collectors.toList());
    }

    /** 获取全部数据源的状态（含不可用的） */
    public Map<String, SourceStatus> getAllStatus() {
        return Collections.unmodifiableMap(statusMap);
    }

    /** 获取单个数据源的详细状态 */
    public Optional<SourceStatus> getStatus(String sourceName) {
        return Optional.ofNullable(statusMap.get(sourceName));
    }
}
