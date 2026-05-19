package com.stock.invest.config;

import com.tigerbrokers.stock.openapi.client.config.ClientConfig;
import com.tigerbrokers.stock.openapi.client.https.client.TigerHttpClient;
import com.tigerbrokers.stock.openapi.client.util.ApiLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
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
            String configDir = prepareConfigDirectory();
            prepareConfigFiles(configDir);
            clientConfig.configFilePath = configDir;
            logger.info("Using config directory: {}", configDir);

            clientConfig.isSslSocket = true;
            clientConfig.isAutoGrabPermission = true;
            clientConfig.failRetryCounts = 2;

            applyTigerId(clientConfig, configDir);
            applyPrivateKey(clientConfig, configDir);

            TigerHttpClient client = TigerHttpClient.getInstance().clientConfig(clientConfig);
            logger.info("TigerHttpClient initialized successfully");
            return client;
        } catch (Exception e) {
            String errorMsg = "Failed to initialize TigerHttpClient: " + e.getMessage();
            logger.error(errorMsg, e);
            throw new RuntimeException(errorMsg, e);
        }
    }

    private void applyTigerId(ClientConfig clientConfig, String configDir) throws IOException {
        if (tigerId != null && !tigerId.isEmpty()) {
            clientConfig.tigerId = tigerId;
            logger.debug("Using tigerId from application.yml");
        } else {
            Properties props = loadConfigProperties(configDir);
            String configTigerId = props.getProperty("tiger_id");
            if (configTigerId != null && !configTigerId.isEmpty()) {
                clientConfig.tigerId = configTigerId;
                logger.info("Using tigerId from config file: {}", configTigerId);
            } else {
                throw new IllegalArgumentException("tigerId is required but not configured");
            }
        }
    }

    private void applyPrivateKey(ClientConfig clientConfig, String configDir) throws IOException {
        String finalPrivateKey = privateKey;
        if (finalPrivateKey == null || finalPrivateKey.isEmpty()) {
            Properties props = loadConfigProperties(configDir);
            finalPrivateKey = props.getProperty("private_key_pk8");
            if (finalPrivateKey == null || finalPrivateKey.isEmpty()) {
                finalPrivateKey = props.getProperty("private_key");
            }
        }
        if (finalPrivateKey != null && !finalPrivateKey.isEmpty()) {
            clientConfig.privateKey = cleanPrivateKey(finalPrivateKey);
            logger.info("Using privateKey from configuration");
        } else {
            throw new IllegalArgumentException("privateKey is required but not configured");
        }
    }

    private String cleanPrivateKey(String rawKey) {
        String cleaned = rawKey.replaceAll("\\s+", "");
        if (cleaned.contains("-----BEGIN")) {
            cleaned = cleaned
                    .replaceAll("-----BEGIN.*KEY-----", "")
                    .replaceAll("-----END.*KEY-----", "")
                    .replaceAll("\\s+", "");
        }
        return cleaned;
    }
    
    /**
     * 准备配置目录
     * @return 配置目录的路径
     */
    private String prepareConfigDirectory() throws IOException {
        // 在临时目录下创建tiger_config目录
        Path configDirPath = Paths.get(System.getProperty("java.io.tmpdir"), "tiger_config");
        
        if (!Files.exists(configDirPath)) {
            Files.createDirectories(configDirPath);
            logger.info("Created Tiger API config directory: {}", configDirPath);
        }
        
        return configDirPath.toString();
    }
    
    /**
     * 准备配置文件
     * 从resources目录复制配置文件到临时目录
     * @param configDir 配置目录路径
     */
    private void prepareConfigFiles(String configDir) throws IOException {
        File targetFile = new File(configDir, "tiger_openapi_config.properties");
        Properties props = new Properties();
        if (privateKeyPk1 != null && !privateKeyPk1.trim().isEmpty()) {
            props.setProperty("private_key_pk1", privateKeyPk1.trim());
        }
        if (privateKeyPk8 != null && !privateKeyPk8.trim().isEmpty()) {
            props.setProperty("private_key_pk8", privateKeyPk8.trim());
        }
        if (tigerId != null && !tigerId.trim().isEmpty()) {
            props.setProperty("tiger_id", tigerId.trim());
        }
        if (account != null && !account.trim().isEmpty()) {
            props.setProperty("account", account.trim());
        }
        props.setProperty("license", license);
        props.setProperty("env", env);

        try (OutputStream output = Files.newOutputStream(targetFile.toPath())) {
            props.store(output, "Generated from application.yml");
        }
        logger.info("Generated tiger_openapi_config.properties at {}", targetFile.getAbsolutePath());
    }
    
    /**
     * 加载配置文件属性
     * @param configDir 配置目录路径
     * @return 配置属性对象
     */
    private Properties loadConfigProperties(String configDir) throws IOException {
        Properties properties = new Properties();
        File configFile = new File(configDir, "tiger_openapi_config.properties");
        if (configFile.exists() && configFile.length() > 0) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                properties.load(fis);
                logger.info("Loaded {} properties from config file", properties.size());
            }
        }
        return properties;
    }
} 