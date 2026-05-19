package com.stock.invest.datasource.rule;

import com.stock.invest.datasource.AvailabilityRule;
import com.stock.invest.datasource.SourceRequirement;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Properties;

@Component
public class TigerOpenAvailabilityRule implements AvailabilityRule {

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
        return checkTigerConfig();
    }

    @Override
    public String getDetail() {
        if (checkTigerConfig()) {
            return "已配置 tiger_openapi_config.properties（tiger_id + private_key + account）";
        }
        return "缺失 tiger_openapi_config.properties 或凭证不完整";
    }

    private boolean checkTigerConfig() {
        try {
            ClassPathResource r = new ClassPathResource("tiger_openapi_config.properties");
            if (!r.exists()) return false;
            Properties p = new Properties();
            try (InputStream is = r.getInputStream()) { p.load(is); }
            return nonEmpty(p.getProperty("tiger_id", ""))
                && nonEmpty(p.getProperty("private_key_pk8", ""))
                && nonEmpty(p.getProperty("account", ""));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean nonEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
