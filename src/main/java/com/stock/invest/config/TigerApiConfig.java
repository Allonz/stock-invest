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
 * 凭证读取顺序（方案 A）：优先从环境变量读取
 * {@code TIGER_OPENAPI_TIGER_ID / TIGER_OPENAPI_ACCOUNT / TIGER_OPENAPI_LICENSE /
 * TIGER_OPENAPI_PRIVATE_KEY / TIGER_OPENAPI_ENV}；
 * 环境变量不完整时回退到 classpath {@code tiger_openapi_config.properties}
 * （仅用于本地开发，不入 Git）。
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
     * 解析凭证：优先环境变量（方案 A），不完整时回退 classpath 配置文件。
     */
    private TigerCredentials resolveCredentials() throws IOException {
        String envTigerId = System.getenv("TIGER_OPENAPI_TIGER_ID");
        String envPrivateKey = System.getenv("TIGER_OPENAPI_PRIVATE_KEY");
        if (isNotBlank(envTigerId) && isNotBlank(envPrivateKey)) {
            String account = firstNonBlank(System.getenv("TIGER_OPENAPI_ACCOUNT"), "");
            String license = firstNonBlank(System.getenv("TIGER_OPENAPI_LICENSE"), "");
            String env = firstNonBlank(System.getenv("TIGER_OPENAPI_ENV"), "PROD");
            logger.info("Tiger credentials loaded from environment variables (tiger_id={})", envTigerId.trim());
            return credentialsFromEnv(envTigerId, envPrivateKey, account, license, env);
        }

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
            throw new IllegalArgumentException("tiger_id is required but not configured (env TIGER_OPENAPI_TIGER_ID or tiger_openapi_config.properties)");
        }
        if (privateKey == null || privateKey.isEmpty()) {
            throw new IllegalArgumentException("private_key is required but not configured (env TIGER_OPENAPI_PRIVATE_KEY or tiger_openapi_config.properties)");
        }

        logger.info("Tiger credentials loaded from classpath properties file: {}", configFilePath);
        return new TigerCredentials(
            tigerId.trim(),
            cleanPrivateKey(privateKey),
            account != null ? account.trim() : "",
            license != null ? license.trim() : "",
            env != null ? env.trim() : "PROD"
        );
    }

    /**
     * 从环境变量值构建凭证（包级可见，便于测试环境变量优先路径）。
     */
    static TigerCredentials credentialsFromEnv(String tigerId, String privateKey,
                                              String account, String license, String env) {
        return new TigerCredentials(
                tigerId.trim(),
                cleanPrivateKey(privateKey),
                account == null ? "" : account.trim(),
                license == null ? "" : license.trim(),
                isNotBlank(env) ? env.trim() : "PROD"
        );
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String firstNonBlank(String value, String defaultValue) {
        return isNotBlank(value) ? value : defaultValue;
    }

    /**
     * 是否配置了可用的 Tiger 凭证（env 或 classpath fallback）。
     * 供可用性规则使用；配置缺失时返回 false，不抛异常中断启动。
     */
    public boolean hasCredentials() {
        try {
            return getCredentials().isValid();
        } catch (RuntimeException e) {
            logger.warn("Tiger credentials are not available: {}", e.getMessage());
            return false;
        }
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
    private static String cleanPrivateKey(String rawKey) {
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
