package com.stock.invest.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Tiger API 配置类
 * <p>
 * 所有 API 凭证（tiger_id, private_key, account, license, env）统一从
 * {@code tiger_openapi_config.properties} 读取，经 {@link TigerCredentials} record 返回。
 * 配置文件路径由 application.yml 中 {@code tiger.api.configFilePath} 指定。
 * </p>
 * <p>
 * 2026-08-14：移除 TigerHttpClient（Java SDK）bean —— Tiger 数据源已删除，
 * 仅保留 TigerOpen（Python SDK）实现；本类只负责 TigerOpen 脚本所需的凭据解析。
 * </p>
 */
@Configuration
public class TigerApiConfig {

    private static final Logger logger = LoggerFactory.getLogger(TigerApiConfig.class);

    @Value("${tiger.api.configFilePath}")
    private String configFilePath;

    private final ResourceLoader resourceLoader;

    public TigerApiConfig(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    private volatile TigerCredentials cachedCredentials;

    /**
     * 解析凭证：从 {@code tiger_openapi_config.properties} 读取所有 Tiger API 凭证字段。
     */
    private TigerCredentials resolveCredentials() throws IOException {
        Properties props = loadConfigProperties(configFilePath);

        String tigerId = props.getProperty("tiger_id");
        String account = props.getProperty("account");
        String license = props.getProperty("license");
        String env = props.getProperty("env", "PROD");
        // 优先使用 pk8，没有则回退 pk1
        String privateKey = props.getProperty("private_key_pk8");
        if (privateKey == null || privateKey.isEmpty()) {
            privateKey = props.getProperty("private_key");
        }
        if (privateKey == null || privateKey.isEmpty()) {
            privateKey = props.getProperty("private_key_pk1");
        }

        if (tigerId == null || tigerId.isEmpty()) {
            throw new IllegalArgumentException("tiger_id is required but not configured in tiger_openapi_config.properties");
        }
        if (privateKey == null || privateKey.isEmpty()) {
            throw new IllegalArgumentException("private_key is required but not configured in tiger_openapi_config.properties");
        }

        return new TigerCredentials(
            tigerId.trim(),
            cleanPrivateKey(privateKey),
            account != null ? account.trim() : "",
            license != null ? license.trim() : "",
            env != null ? env.trim() : "PROD"
        );
    }

    /**
     * 公开方法：获取缓存的 Tiger 凭证。懒加载，双重检查锁。
     */
    public TigerCredentials getCredentials() {
        if (cachedCredentials == null) {
            synchronized (this) {
                if (cachedCredentials == null) {
                    try {
                        cachedCredentials = resolveCredentials();
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to load tiger credentials from tiger_openapi_config.properties", e);
                    }
                }
            }
        }
        return cachedCredentials;
    }

    /**
     * 清理 PEM 私钥：移除头尾标记及所有空白字符。
     */
    private String cleanPrivateKey(String rawKey) {
        // Step 1: 移除 PEM 头尾标记
        String cleaned = rawKey
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "");
        // Step 2: 移除所有剩余空白
        cleaned = cleaned.replaceAll("\\s+", "");
        return cleaned;
    }

    /**
     * 从 classpath 加载 .properties 文件。
     */
    private Properties loadConfigProperties(String filePath) throws IOException {
        Properties properties = new Properties();
        Resource resource = resourceLoader.getResource(filePath);
        if (resource.exists()) {
            try (InputStream is = resource.getInputStream()) {
                properties.load(is);
                logger.info("Loaded {} properties from config file: {}", properties.size(), filePath);
            }
        } else {
            logger.warn("Config file not found: {}", filePath);
        }
        return properties;
    }

    /**
     * Tiger API 凭证记录。
     *
     * @param tigerId    Tiger ID（必填）
     * @param privateKey 私钥（已清理，必填）
     * @param account    账号（必填）
     * @param license    许可证（可选，默认空）
     * @param env        环境（可选，默认 PROD）
     */
    public record TigerCredentials(
            String tigerId,
            String privateKey,
            String account,
            String license,
            String env
    ) {
        public boolean isValid() {
            return tigerId != null && !tigerId.isEmpty()
                    && privateKey != null && !privateKey.isEmpty()
                    && account != null && !account.isEmpty();
        }
    }
}
