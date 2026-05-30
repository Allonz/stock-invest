package com.stock.invest.datasource.rule;

import com.stock.invest.config.TwelveDataProperties;
import com.stock.invest.datasource.AvailabilityRule;
import com.stock.invest.datasource.DataSourceCapability;
import com.stock.invest.datasource.SourceRequirement;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class TwelveDataAvailabilityRule implements AvailabilityRule {

    private final TwelveDataProperties properties;

    public TwelveDataAvailabilityRule(TwelveDataProperties properties) {
        this.properties = properties;
    }

    @Override
    public String getSourceName() {
        return "twelvedata";
    }

    @Override
    public SourceRequirement getRequirement() {
        return SourceRequirement.OPTIONAL;
    }

    @Override
    public boolean check() {
        return true;
    }

    @Override
    public String getDetail() {
        boolean hasKey = !properties.resolvedKeys().isEmpty();
        if (hasKey) {
            return "已配置 API Key（" + properties.resolvedKeys().size() + " 个密钥）";
        }
        return "未配置 API Key，降级使用（速率受限）";
    }

    @Override
    public Set<DataSourceCapability> capabilities() {
        return Set.of(DataSourceCapability.STOCK_QUOTE);
    }
}
