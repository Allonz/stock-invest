package com.stock.invest.datasource;

import java.util.Collections;
import java.util.Set;

/**
 * 单个数据源的可用性检查规则。
 * 每个数据源实现一个 Rule，由 DataSourceAvailabilityChecker 统一调度。
 */
public interface AvailabilityRule {

    /** 数据源名称，如 "tiger", "tigeropen", "yfinance" */
    String getSourceName();

    /** 该数据源的 API Key 需求级别 */
    SourceRequirement getRequirement();

    /**
     * 执行可用性检查。
     * @return true=可用，false=不可用
     */
    boolean check();

    /**
     * 获取检查详情文字说明。
     * 不可用时返回原因，可用时返回配置说明。
     */
    String getDetail();

    /**
     * 该数据源支持的能力列表。
     * 前端根据此方法返回值展示用途标签。
     * 默认返回空集合（无能力），需各实现覆写。
     */
    default Set<DataSourceCapability> capabilities() {
        return Collections.emptySet();
    }
}
