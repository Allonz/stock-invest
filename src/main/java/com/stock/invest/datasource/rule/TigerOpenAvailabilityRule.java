package com.stock.invest.datasource.rule;

import com.stock.invest.config.TigerApiConfig;
import com.stock.invest.datasource.AvailabilityRule;
import com.stock.invest.datasource.DataSourceCapability;
import com.stock.invest.datasource.SourceRequirement;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class TigerOpenAvailabilityRule implements AvailabilityRule {

    private final TigerApiConfig tigerApiConfig;

    public TigerOpenAvailabilityRule(TigerApiConfig tigerApiConfig) {
        this.tigerApiConfig = tigerApiConfig;
    }

    @Override
    public String getSourceName() {
        return "tigeropen";
    }

    @Override
    public SourceRequirement getRequirement() {
        return SourceRequirement.REQUIRED;
    }

    @Override
    public boolean check() {
        return tigerApiConfig.hasCredentials();
    }

    @Override
    public String getDetail() {
        if (tigerApiConfig.hasCredentials()) {
            return "已配置 Tiger 凭证（环境变量或本地 properties）";
        }
        return "缺失 Tiger 凭证（TIGER_OPENAPI_* 环境变量或 tiger_openapi_config.properties）";
    }

    @Override
    public Set<DataSourceCapability> capabilities() {
        return Set.of(DataSourceCapability.STOCK_QUOTE, DataSourceCapability.TRADING_CALENDAR);
    }
}
