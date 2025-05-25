package com.stock.invest.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 自动安装Python脚本的工具类
 */
public class PythonInstaller {
    private static final Logger logger = LoggerFactory.getLogger(PythonInstaller.class);
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final String PYTHON_DIR = "src/main/resources/python";
    
    /**
     * 确保Python脚本存在并且可执行
     * @param scriptName 脚本名称
     * @return 脚本的完整路径
     */
    public static String ensureScriptExists(String scriptName) throws IOException {
        logger.debug("确保Python脚本存在: {}", scriptName);
        
        // 创建Python目录
        Path pythonDir = Paths.get(PYTHON_DIR);
        if (!Files.exists(pythonDir)) {
            logger.debug("创建Python目录: {}", pythonDir);
            Files.createDirectories(pythonDir);
        }
        
        // 目标脚本路径
        Path scriptPath = pythonDir.resolve(scriptName);
        
        // 如果脚本已存在，直接返回
        if (Files.exists(scriptPath)) {
            logger.debug("Python脚本已存在: {}", scriptPath);
            return scriptPath.toAbsolutePath().toString();
        }
        
        // 从资源目录复制脚本
        logger.debug("从资源目录复制Python脚本: {}", scriptName);
        try {
            ClassPathResource resource = new ClassPathResource("python/" + scriptName);
            Files.copy(resource.getInputStream(), scriptPath, StandardCopyOption.REPLACE_EXISTING);
            logger.debug("Python脚本已复制到: {}", scriptPath);
        } catch (IOException e) {
            logger.error("复制Python脚本时出错: {}", e.getMessage());
            throw e;
        }
        
        return scriptPath.toAbsolutePath().toString();
    }
    
    /**
     * 生成测试脚本
     * @return 生成的测试脚本路径
     */
    public static String generateTestScript() throws IOException {
        logger.debug("生成Python测试脚本");
        
        // 创建Python目录
        Path pythonDir = Paths.get(PYTHON_DIR);
        if (!Files.exists(pythonDir)) {
            logger.debug("创建Python目录: {}", pythonDir);
            Files.createDirectories(pythonDir);
        }
        
        // 测试脚本路径
        Path scriptPath = pythonDir.resolve("test_script.py");
        
        // 测试脚本内容
        String scriptContent = "#!/usr/bin/env python\n" +
                "# -*- coding: utf-8 -*-\n\n" +
                "import sys\n" +
                "import json\n" +
                "from datetime import datetime\n\n" +
                "# 测试数据\n" +
                "data = [\n" +
                "    {\n" +
                "        \"symbol\": \"AAPL\",\n" +
                "        \"name\": \"Apple Inc.\",\n" +
                "        \"price\": 150.25,\n" +
                "        \"volume\": 1000000,\n" +
                "        \"change\": 2.5,\n" +
                "        \"changePercent\": 1.67,\n" +
                "        \"timestamp\": datetime.now().isoformat()\n" +
                "    },\n" +
                "    {\n" +
                "        \"symbol\": \"MSFT\",\n" +
                "        \"name\": \"Microsoft Corporation\",\n" +
                "        \"price\": 280.75,\n" +
                "        \"volume\": 800000,\n" +
                "        \"change\": -1.25,\n" +
                "        \"changePercent\": -0.44,\n" +
                "        \"timestamp\": datetime.now().isoformat()\n" +
                "    }\n" +
                "]\n\n" +
                "# 如果提供了参数，则返回指定数量的数据\n" +
                "if len(sys.argv) > 1:\n" +
                "    try:\n" +
                "        limit = int(sys.argv[1])\n" +
                "        data = data[:limit]\n" +
                "    except ValueError:\n" +
                "        print(\"参数必须是整数\")\n" +
                "        sys.exit(1)\n\n" +
                "# 输出JSON数据\n" +
                "print(json.dumps(data, ensure_ascii=False))\n";
        
        // 写入脚本文件
        Files.write(scriptPath, scriptContent.getBytes("UTF-8"));
        logger.debug("测试脚本已生成: {}", scriptPath);
        
        return scriptPath.toAbsolutePath().toString();
    }
    
    /**
     * 生成股票信息脚本
     * @return 生成的脚本路径
     */
    public static String generateStockInfoScript() throws IOException {
        logger.debug("生成股票信息脚本");
        
        // 创建Python目录
        Path pythonDir = Paths.get(PYTHON_DIR);
        if (!Files.exists(pythonDir)) {
            logger.debug("创建Python目录: {}", pythonDir);
            Files.createDirectories(pythonDir);
        }
        
        // 脚本路径
        Path scriptPath = pythonDir.resolve("stock_info_twelvedata.py");
        
        // 脚本内容
        String scriptContent = "#!/usr/bin/env python\n" +
                "# -*- coding: utf-8 -*-\n\n" +
                "import sys\n" +
                "import json\n" +
                "from datetime import datetime\n\n" +
                "def get_low_price_stocks(limit=20):\n" +
                "    \"\"\"\n" +
                "    模拟获取低价股票列表\n" +
                "    在实际应用中，这里应该调用TwelveData API\n" +
                "    \"\"\"\n" +
                "    # 这里使用模拟数据\n" +
                "    stocks = [\n" +
                "        {\n" +
                "            \"symbol\": \"AAPL\",\n" +
                "            \"name\": \"Apple Inc.\",\n" +
                "            \"price\": 150.25,\n" +
                "            \"volume\": 1000000,\n" +
                "            \"change\": 2.5,\n" +
                "            \"changePercent\": 1.67,\n" +
                "            \"timestamp\": datetime.now().isoformat()\n" +
                "        },\n" +
                "        {\n" +
                "            \"symbol\": \"MSFT\",\n" +
                "            \"name\": \"Microsoft Corporation\",\n" +
                "            \"price\": 280.75,\n" +
                "            \"volume\": 800000,\n" +
                "            \"change\": -1.25,\n" +
                "            \"changePercent\": -0.44,\n" +
                "            \"timestamp\": datetime.now().isoformat()\n" +
                "        }\n" +
                "    ]\n" +
                "    \n" +
                "    # 按价格排序\n" +
                "    sorted_stocks = sorted(stocks, key=lambda x: x[\"price\"])\n" +
                "    \n" +
                "    # 返回限制数量\n" +
                "    return json.dumps(sorted_stocks[:int(limit)], ensure_ascii=False)\n\n" +
                "if __name__ == \"__main__\":\n" +
                "    # 从命令行参数获取limit\n" +
                "    limit = int(sys.argv[1]) if len(sys.argv) > 1 else 20\n" +
                "    \n" +
                "    # 获取股票列表并输出为JSON\n" +
                "    stocks = get_low_price_stocks(limit)\n" +
                "    print(stocks)\n";
        
        // 写入脚本文件
        Files.write(scriptPath, scriptContent.getBytes("UTF-8"));
        logger.debug("股票信息脚本已生成: {}", scriptPath);
        
        return scriptPath.toAbsolutePath().toString();
    }
    
    /**
     * 设置Python脚本环境
     * @return 是否成功
     */
    public static boolean setupPythonScriptEnvironment() {
        logger.debug("设置Python脚本环境");
        
        try {
            // 确保Python目录存在
            Path pythonDir = Paths.get(PYTHON_DIR);
            if (!Files.exists(pythonDir)) {
                logger.debug("创建Python目录: {}", pythonDir);
                Files.createDirectories(pythonDir);
            }
            
            // 生成测试脚本
            generateTestScript();
            
            // 生成股票信息脚本
            generateStockInfoScript();
            
            logger.info("Python脚本环境设置成功");
            return true;
        } catch (Exception e) {
            logger.error("设置Python脚本环境时出错: {}", e.getMessage(), e);
            return false;
        }
    }
} 