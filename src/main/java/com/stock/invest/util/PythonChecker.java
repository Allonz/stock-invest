package com.stock.invest.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Arrays;

/**
 * Python环境检查工具类
 */
public class PythonChecker {
    private static final Logger logger = LoggerFactory.getLogger(PythonChecker.class);
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final String PYTHON_CMD = IS_WINDOWS ? "py" : "python";
    
    /**
     * 检查Python是否已安装
     * @return true如果Python已安装
     */
    public static boolean isPythonInstalled() {
        try {
            logger.debug("检查Python是否已安装...");
            ProcessBuilder processBuilder = new ProcessBuilder(PYTHON_CMD, "--version");
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                logger.debug("Python已安装: {}", output.toString().trim());
                return true;
            } else {
                logger.error("Python未安装或不在PATH中, 退出码: {}, 输出: {}", exitCode, output.toString().trim());
                return false;
            }
        } catch (Exception e) {
            logger.error("检查Python安装时出错: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 验证指定列表的Python模块已安装
     * @param modules 需要验证的模块列表
     * @return true如果所有模块已安装
     */
    public static boolean checkPythonModules(String... modules) {
        if (modules == null || modules.length == 0) {
            return true;
        }
        
        logger.debug("检查Python模块是否已安装: {}", Arrays.toString(modules));
        
        for (String module : modules) {
            try {
                ProcessBuilder processBuilder = new ProcessBuilder(
                        PYTHON_CMD, "-c", "import " + module + "; print('Module " + module + " is installed')"
                );
                processBuilder.redirectErrorStream(true);
                Process process = processBuilder.start();
                
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                }
                
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    logger.error("Python模块 {} 未安装, 输出: {}", module, output.toString().trim());
                    return false;
                } else {
                    logger.debug("Python模块 {} 已安装", module);
                }
            } catch (Exception e) {
                logger.error("检查Python模块 {} 时出错: {}", module, e.getMessage());
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 检查Python脚本是否有效
     * @param scriptPath 脚本路径
     * @return true如果脚本有效
     */
    public static boolean isScriptValid(String scriptPath) {
        logger.debug("检查Python脚本是否有效: {}", scriptPath);
        
        File scriptFile = new File(scriptPath);
        if (!scriptFile.exists()) {
            logger.error("Python脚本不存在: {}", scriptPath);
            return false;
        }
        
        if (!scriptFile.canRead()) {
            logger.error("Python脚本不可读: {}", scriptPath);
            return false;
        }
        
        logger.debug("Python脚本有效: {}", scriptPath);
        return true;
    }
    
    /**
     * 检查Python环境
     * @param scriptPath 要执行的脚本路径
     * @param modules 要使用的模块
     * @return true如果需要所有模块
     */
    public static boolean checkPythonEnvironment(String scriptPath, String... modules) {
        if (!isPythonInstalled()) {
            return false;
        }
        
        if (!checkPythonModules(modules)) {
            return false;
        }
        
        return isScriptValid(scriptPath);
    }
} 