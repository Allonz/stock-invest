package com.stock.invest.datasource;

import java.util.Collections;
import java.util.Set;

/**
 * 单个数据源的可用性检查结果。
 */
public class SourceStatus {

    private final String sourceName;
    private final boolean available;
    private final String reason;
    private final SourceRequirement requirement;
    private final boolean hasApiKey;
    private final Set<DataSourceCapability> capabilities;

    public SourceStatus(String sourceName, boolean available, String reason,
                        SourceRequirement requirement, boolean hasApiKey) {
        this(sourceName, available, reason, requirement, hasApiKey, Collections.emptySet());
    }

    public SourceStatus(String sourceName, boolean available, String reason,
                        SourceRequirement requirement, boolean hasApiKey,
                        Set<DataSourceCapability> capabilities) {
        this.sourceName = sourceName;
        this.available = available;
        this.reason = reason;
        this.requirement = requirement;
        this.hasApiKey = hasApiKey;
        this.capabilities = capabilities != null ? capabilities : Collections.emptySet();
    }

    public String getSourceName() { return sourceName; }
    public boolean isAvailable() { return available; }
    public String getReason() { return reason; }
    public SourceRequirement getRequirement() { return requirement; }
    public boolean isHasApiKey() { return hasApiKey; }
    public Set<DataSourceCapability> getCapabilities() { return capabilities; }
}
