package com.stock.invest.config;

import com.tigerbrokers.stock.openapi.client.config.ClientConfig;
import com.tigerbrokers.stock.openapi.client.https.client.TigerHttpClient;
import com.tigerbrokers.stock.openapi.client.struct.enums.Env;
import com.tigerbrokers.stock.openapi.client.struct.enums.License;
import com.tigerbrokers.stock.openapi.client.util.ApiLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Tiger API 配置类
 */
@Configuration
@Profile("tiger")
public class TigerApiConfig {

    private static final Logger logger = LoggerFactory.getLogger(TigerApiConfig.class);

    @Value("${tiger.api.configFilePath:}")
    private String configFilePath;

    @Value("${tiger.api.tiger_id:}")
    private String tigerId;

    @Value("${tiger.api.private_key:}")
    private String privateKey;

    @Value("${tiger.api.log_path:logs/tiger}")
    private String logPath;

    @Value("${tiger.api.account:}")
    private String account;

    @Value("${tiger.api.license:TBNZ}")
    private String license;

    @Value("${tiger.api.env:PROD}")
    private String env;

    @Value("${tiger.api.private_key_pk1:}")
    private String privateKeyPk1;

    @Value("${tiger.api.private_key_pk8:}")
    private String privateKeyPk8;

    /**
     * 创建并配置 TigerHttpClient 实例
     * @return TigerHttpClient 实例
     */
    @Bean
    public TigerHttpClient tigerHttpClient() {
        logger.info("Initializing TigerHttpClient with config:");
        logger.info("configFilePath: {}", configFilePath);
        logger.info("privateKey length: {}", privateKey != null ? privateKey.length() : 0);
        logger.info("logPath: {}", logPath);

        ClientConfig clientConfig = new ClientConfig();
        ApiLogger.setEnabled(true, logPath);

        try {
            // 所有配置在内存中完成，不向磁盘写入私钥
            clientConfig.isSslSocket = true;
            clientConfig.isAutoGrabPermission = true;
            clientConfig.failRetryCounts = 2;

            // 直接设置 env/license/account，无需经过文件
            if (env != null && !env.isEmpty()) {
                clientConfig.setEnv(Env.valueOf(env));
            }
            if (license != null && !license.isEmpty()) {
                clientConfig.license = License.valueOf(license);
            }
            if (account != null && !account.isEmpty()) {
                clientConfig.defaultAccount = account;
            }

            resolveTigerId(clientConfig);
            resolvePrivateKey(clientConfig);

            TigerHttpClient client = TigerHttpClient.getInstance().clientConfig(clientConfig);
            logger.info("TigerHttpClient initialized successfully");
            return client;
        } catch (Exception e) {
            String errorMsg = "Failed to initialize TigerHttpClient: " + e.getMessage();
            logger.error(errorMsg, e);
            throw new RuntimeException(errorMsg, e);
        }
    }

    /**
     * 解析 tigerId：优先从 application.yml 取值，其次从外部配置文件读取
     */
    private void resolveTigerId(ClientConfig clientConfig) throws IOException {
        if (tigerId != null && !tigerId.isEmpty()) {
            clientConfig.tigerId = tigerId;
            logger.debug("Using tigerId from application.yml");
        } else if (configFilePath != null && !configFilePath.isEmpty()) {
            Properties props = loadConfigProperties(configFilePath);
            String configTigerId = props.getProperty("tiger_id");
            if (configTigerId != null && !configTigerId.isEmpty()) {
                clientConfig.tigerId = configTigerId;
                logger.info("Using tigerId from config file: {}", configTigerId);
            } else {
                throw new IllegalArgumentException("tigerId is required but not configured");
            }
        } else {
            throw new IllegalArgumentException("tigerId is required but not configured; set tiger.api.tiger_id or tiger.api.configFilePath");
        }
    }

    /**
     * 解析私钥：优先从 application.yml 的 private_key/private_key_pk8 取值，
     * 其次从外部配置文件读取。写入方式从磁盘文件改为内存直接赋值。
     */
    private void resolvePrivateKey(ClientConfig clientConfig) throws IOException {
        String finalPrivateKey = privateKey;
        if (finalPrivateKey == null || finalPrivateKey.isEmpty()) {
            if (privateKeyPk8 != null && !privateKeyPk8.isEmpty()) {
                finalPrivateKey = privateKeyPk8;
            } else if (privateKeyPk1 != null && !privateKeyPk1.isEmpty()) {
                finalPrivateKey = privateKeyPk1;
            } else if (configFilePath != null && !configFilePath.isEmpty()) {
                Properties props = loadConfigProperties(configFilePath);
                finalPrivateKey = props.getProperty("private_key_pk8");
                if (finalPrivateKey == null || finalPrivateKey.isEmpty()) {
                    finalPrivateKey = props.getProperty("private_key");
                }
            }
        }
        if (finalPrivateKey != null && !finalPrivateKey.isEmpty()) {
            clientConfig.privateKey = cleanPrivateKey(finalPrivateKey);
            logger.info("Using privateKey from configuration");
        } else {
            throw new IllegalArgumentException("privateKey is required but not configured; set tiger.api.private_key or tiger.api.configFilePath");
        }
    }

    /**
     * 清理 PEM 私钥：
     * 1. 先移除 PEM 头尾标记（如 -----BEGIN RSA PRIVATE KEY-----）
     * 2. 再移除所有空白字符（换行、空格、制表符）
     * 顺序不可颠倒，否则先清空白会破坏 PEM 格式标记
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
     * 从外部配置文件加载属性（只读，不写入敏感信息）
     * @param filePath 配置文件路径
     * @return 配置属性对象
     */
    private Properties loadConfigProperties(String filePath) throws IOException {
        Properties properties = new Properties();
        Path path = Paths.get(filePath);
        if (Files.exists(path) && Files.size(path) > 0) {
            try (FileInputStream fis = new FileInputStream(path.toFile())) {
                properties.load(fis);
                logger.info("Loaded {} properties from config file: {}", properties.size(), filePath);
            }
        } else {
            logger.warn("Config file not found or empty: {}", filePath);
        }
        return properties;
    }
}
