package com.stock.invest.config;

import com.tigerbrokers.stock.openapi.client.config.ClientConfig;
import com.tigerbrokers.stock.openapi.client.https.client.TigerHttpClient;
import com.tigerbrokers.stock.openapi.client.util.ApiLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.FileCopyUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Tiger API 配置类
 */
@Configuration
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

    /**
     * 创建并配置 TigerHttpClient 实例
     * @return TigerHttpClient 实例
     */
    @Bean
    public TigerHttpClient tigerHttpClient() {
        // 输出日志信息
        logger.info("Initializing TigerHttpClient with config:");
        logger.info("configFilePath: {}", configFilePath);
        logger.info("tigerId: {}", tigerId);
        logger.info("privateKey length: {}", privateKey != null ? privateKey.length() : 0);
        logger.info("logPath: {}", logPath);
        
        // 配置客户端
        ClientConfig clientConfig = new ClientConfig();
        
        // 设置日志
        ApiLogger.setEnabled(true, logPath);
        
        try {
            // 1. 准备配置目录
            String configDir = prepareConfigDirectory();
            
            // 2. 准备配置文件
            prepareConfigFiles(configDir);
            
            // 3. 设置配置文件路径
            clientConfig.configFilePath = configDir;
            logger.info("Using config directory: {}", configDir);
            
            // 4. 设置基本选项
            clientConfig.isSslSocket = true;
            clientConfig.isAutoGrabPermission = true;
            clientConfig.failRetryCounts = 2;
            
            // 5. 设置API凭证
            if (tigerId != null && !tigerId.isEmpty()) {
                clientConfig.tigerId = tigerId;
                logger.info("Using tigerId from application.yml: {}", tigerId);
            } else {
                // 尝试从配置文件读取tiger_id
                Properties props = loadConfigProperties(configDir);
                String configTigerId = props.getProperty("tiger_id");
                if (configTigerId != null && !configTigerId.isEmpty()) {
                    clientConfig.tigerId = configTigerId;
                    logger.info("Using tigerId from config file: {}", configTigerId);
                } else {
                    throw new IllegalArgumentException("tigerId is required but not configured");
                }
            }
            
            // 设置私钥
            String finalPrivateKey = privateKey;
            if (finalPrivateKey == null || finalPrivateKey.isEmpty()) {
                // 尝试从配置文件读取私钥
                Properties props = loadConfigProperties(configDir);
                finalPrivateKey = props.getProperty("private_key_pk8");
                if (finalPrivateKey == null || finalPrivateKey.isEmpty()) {
                    finalPrivateKey = props.getProperty("private_key");
                }
            }
            
            if (finalPrivateKey != null && !finalPrivateKey.isEmpty()) {
                // 清理私钥格式
                String cleanedPrivateKey = finalPrivateKey.replaceAll("\\s+", "");
                if (cleanedPrivateKey.contains("-----BEGIN")) {
                    cleanedPrivateKey = cleanedPrivateKey
                        .replaceAll("-----BEGIN.*KEY-----", "")
                        .replaceAll("-----END.*KEY-----", "")
                        .replaceAll("\\s+", "");
                }
                clientConfig.privateKey = cleanedPrivateKey;
                logger.info("Using privateKey from configuration");
            } else {
                throw new IllegalArgumentException("privateKey is required but not configured");
            }
            
            // 6. 创建客户端实例
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
        // 尝试从resources目录获取tiger_openapi_config.properties文件
        try {
            ClassPathResource configResource = new ClassPathResource("tiger_openapi_config.properties");
            if (configResource.exists()) {
                File targetFile = new File(configDir, "tiger_openapi_config.properties");
                FileCopyUtils.copy(configResource.getInputStream(), new FileOutputStream(targetFile));
                logger.info("Copied tiger_openapi_config.properties to {}", targetFile.getAbsolutePath());
                
                // 验证复制后的文件存在
                if (targetFile.exists() && targetFile.length() > 0) {
                    logger.info("Verified config file exists and has content, size: {} bytes", targetFile.length());
                } else {
                    logger.warn("Config file copy seems to have failed, file doesn't exist or is empty");
                }
            } else {
                logger.warn("tiger_openapi_config.properties not found in resources");
            }
        } catch (IOException e) {
            logger.warn("Could not copy tiger_openapi_config.properties from resources", e);
            throw e;
        }
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
            try (java.io.FileInputStream fis = new java.io.FileInputStream(configFile)) {
                properties.load(fis);
                logger.info("Loaded {} properties from config file", properties.size());
            }
        }
        return properties;
    }
} 