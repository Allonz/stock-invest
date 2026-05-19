package com.stock.invest.datasource;

/**
 * 数据源对 API Key 的需求级别。
 */
public enum SourceRequirement {
    /** 必须提供 API Key / 凭证，否则完全不可用 */
    REQUIRED,
    /** API Key 可选 — 有 Key 配额更高，无 Key 也能降级使用 */
    OPTIONAL
}
