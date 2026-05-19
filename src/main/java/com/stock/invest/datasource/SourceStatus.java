package com.stock.invest.datasource;

/**
 * 单个数据源的可用性检查结果。
 */
public class SourceStatus {

    private final String sourceName;
    private final boolean available;
    private final String reason;
    private final SourceRequirement requirement;
    private final boolean hasApiKey;

    public SourceStatus(String sourceName, boolean available, String reason,
                        SourceRequirement requirement, boolean hasApiKey) {
        this.sourceName = sourceName;
        this.available = available;
        this.reason = reason;
        this.requirement = requirement;
        this.hasApiKey = hasApiKey;
    }

    public String getSourceName() { return sourceName; }
    public boolean isAvailable() { return available; }
    public String getReason() { return reason; }
    public SourceRequirement getRequirement() { return requirement; }
    public boolean isHasApiKey() { return hasApiKey; }
}
