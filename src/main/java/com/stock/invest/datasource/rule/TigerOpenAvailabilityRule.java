package com.stock.invest.datasource.rule;

import com.stock.invest.datasource.AvailabilityRule;
import com.stock.invest.datasource.SourceRequirement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TigerOpenAvailabilityRule implements AvailabilityRule {

    @Value("${tiger.api.tiger_id:}")
    private String tigerId;

    @Value("${tiger.api.private_key:}")
    private String privateKey;

    @Value("${tiger.api.account:}")
    private String account;

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
        return hasNonEmpty(tigerId) && hasNonEmpty(privateKey) && hasNonEmpty(account);
    }

    @Override
    public String getDetail() {
        if (check()) {
            return "已配置 tiger_id、private_key、account";
        }
        StringBuilder missing = new StringBuilder("缺失: ");
        if (!hasNonEmpty(tigerId)) missing.append("tiger_id ");
        if (!hasNonEmpty(privateKey)) missing.append("private_key ");
        if (!hasNonEmpty(account)) missing.append("account ");
        return missing.toString().trim();
    }

    private static boolean hasNonEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
