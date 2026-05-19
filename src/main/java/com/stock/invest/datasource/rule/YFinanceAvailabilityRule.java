package com.stock.invest.datasource.rule;

import com.stock.invest.datasource.AvailabilityRule;
import com.stock.invest.datasource.SourceRequirement;
import org.springframework.stereotype.Component;

/**
 * YFinance 无 API Key 依赖，始终可用。
 */
@Component
public class YFinanceAvailabilityRule implements AvailabilityRule {

    @Override
    public String getSourceName() {
        return "yfinance";
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
        return "无需 API Key，始终可用";
    }
}
