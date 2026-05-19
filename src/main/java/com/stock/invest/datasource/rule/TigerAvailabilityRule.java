package com.stock.invest.datasource.rule;

import com.stock.invest.datasource.AvailabilityRule;
import com.stock.invest.datasource.SourceRequirement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TigerAvailabilityRule implements AvailabilityRule {

    @Value("${tiger.api.tiger_id:}")
    private String tigerId;

    @Value("${tiger.api.private_key:}")
    private String privateKey;

    @Override
    public String getSourceName() {
        return "tiger";
    }

    @Override
    public SourceRequirement getRequirement() {
        return SourceRequirement.REQUIRED;
    }

    @Override
    public boolean check() {
        return hasNonEmpty(tigerId) && hasNonEmpty(privateKey);
    }

    @Override
    public String getDetail() {
        if (hasNonEmpty(tigerId) && hasNonEmpty(privateKey)) {
            return "已配置 tiger_id 与 private_key";
        }
        if (!hasNonEmpty(tigerId)) {
            return "缺失 tiger_id";
        }
        return "缺失 private_key";
    }

    private static boolean hasNonEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
