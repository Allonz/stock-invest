package com.stock.invest.service;

import com.stock.invest.entity.FieldCapability;
import com.stock.invest.repository.FieldCapabilityRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据源字段能力服务 —— 启动时加载能力表到内存，提供字段缺失标记判断。
 * <p>
 * 取代硬编码的 supportsAfterHoursMerge 式判断：markable(source, field) 决定
 * 该源该字段缺失时是否标记补缺；queryMethod(field) 决定增补时的获取方式。
 */
@Service
public class FieldCapabilityService {

    private static final Logger log = LoggerFactory.getLogger(FieldCapabilityService.class);

    private final FieldCapabilityRepository repository;

    /** dataSource → (fieldName → Capability) */
    private final Map<String, Map<String, FieldCapability>> cache = new ConcurrentHashMap<>();

    /** fieldName → queryMethod（按字段聚合，同一字段各源方式一致） */
    private final Map<String, String> queryMethodByField = new ConcurrentHashMap<>();

    public FieldCapabilityService(FieldCapabilityRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void load() {
        cache.clear();
        queryMethodByField.clear();
        List<FieldCapability> all = repository.findAll();
        Map<String, Map<String, FieldCapability>> built = new HashMap<>();
        for (FieldCapability cap : all) {
            built.computeIfAbsent(cap.getDataSource(), k -> new HashMap<>())
                    .put(cap.getFieldName(), cap);
            queryMethodByField.putIfAbsent(cap.getFieldName(), cap.getQueryMethod());
        }
        cache.putAll(built);
        log.info("[FieldCapabilityService] loaded {} capabilities for {} sources", all.size(), built.size());
    }

    /**
     * 该源该字段缺失时是否应标记补缺。
     * 未知源/未知字段 → false（保守：不标记，避免无限补缺）。
     */
    public boolean isMarkable(String dataSource, String fieldName) {
        if (dataSource == null || fieldName == null) {
            return false;
        }
        FieldCapability cap = lookup(dataSource, fieldName);
        return cap != null && Boolean.TRUE.equals(cap.getMarkable());
    }

    /**
     * 该源是否支持查询该字段（supported=true）。
     * 未知源/未知字段 → false。
     */
    public boolean isSupported(String dataSource, String fieldName) {
        if (dataSource == null || fieldName == null) {
            return false;
        }
        FieldCapability cap = lookup(dataSource, fieldName);
        return cap != null && Boolean.TRUE.equals(cap.getSupported());
    }

    /**
     * 字段的补缺获取方式（DAILY_KLINE / AFTER_HOURS_API / CALC）。
     * 未知字段 → DAILY_KLINE 默认。
     */
    public String queryMethod(String fieldName) {
        return queryMethodByField.getOrDefault(fieldName, FieldCapability.METHOD_DAILY_KLINE);
    }

    private FieldCapability lookup(String dataSource, String fieldName) {
        Map<String, FieldCapability> byField = cache.get(dataSource);
        return byField == null ? null : byField.get(fieldName);
    }
}
