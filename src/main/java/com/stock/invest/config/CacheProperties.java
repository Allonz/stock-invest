package com.stock.invest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 缓存相关可配置项。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cache")
public class CacheProperties {

    /** Caffeine 最大缓存条目数 */
    private int maximumSize = 500;

    /** 缓存写入后过期时间（分钟） */
    private int expireAfterWriteMinutes = 30;

    public int getExpireAfterWriteMillis() {
        return (int) TimeUnit.MINUTES.toMillis(expireAfterWriteMinutes);
    }
}
