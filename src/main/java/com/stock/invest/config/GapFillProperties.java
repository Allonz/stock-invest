package com.stock.invest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 数据补全相关可配置项。
 */
@Data
@Component
@ConfigurationProperties(prefix = "gap-fill")
public class GapFillProperties {

    /**
     * 最低价格阈值，低于此价格的股票将触发数据补全。
     */
    private BigDecimal minPriceThreshold = BigDecimal.valueOf(1.00);

    /**
     * 字段增补开关（2026-08-14）：补缺时是否对已有记录执行缺失字段增补（发现+增补）。
     * 默认开启；集成测试环境关闭以避免真实外部 API 调用拖慢测试。
     */
    private boolean fieldFillEnabled = true;
}
