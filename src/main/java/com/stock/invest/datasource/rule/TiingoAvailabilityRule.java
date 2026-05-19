package com.stock.invest.datasource.rule;

import com.stock.invest.config.TiingoProperties;
import com.stock.invest.datasource.AvailabilityRule;
import com.stock.invest.datasource.SourceRequirement;
import org.springframework.stereotype.Component;

@Component
public class TiingoAvailabilityRule implements AvailabilityRule {

    private final TiingoProperties properties;

    public TiingoAvailabilityRule(TiingoProperties properties) {
        this.properties = properties;
    }

    @Override
    public String getSourceName() {
        return "tiingo";
    }

    @Override
    public SourceRequirement getRequirement() {
        return SourceRequirement.REQUIRED;
    }

    @Override
    public boolean check() {
        return properties.hasToken();
    }

    @Override
    public String getDetail() {
        if (properties.hasToken()) {
            return "已配置 Tiingo API Token";
        }
        return "缺失 Tiingo API Token";
    }
}
