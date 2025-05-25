package com.stock.invest.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

@Component
public class PythonScriptExecutor {
    private static final Logger logger = LoggerFactory.getLogger(PythonScriptExecutor.class);
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    
    // 使用Python路径
    private static final String PYTHON_PATH = IS_WINDOWS ? "C:\\Program Files\\Python310\\python.exe" : "python";
    private static final String PYTHON_CMD = IS_WINDOWS ? "\"" + PYTHON_PATH + "\"" : "python";
    
    public String executeScript(String scriptPath, String... args) throws IOException, InterruptedException {
        // 检查Python是否可用
        if (!isPythonAvailable()) {
            logger.error("Python未安装或未添加到PATH环境变量中");
            throw new IOException("Python未安装或未添加到PATH环境变量中");
        }

        // 获取脚本文件的绝对路径
        File scriptFile = new File(scriptPath);
        if (!scriptFile.exists()) {
            // 尝试从项目根目录查找文件
            File projectRoot = new File(System.getProperty("user.dir"));
            scriptFile = new File(projectRoot, scriptPath);
            if (!scriptFile.exists()) {
                logger.error("Python脚本文件不存在: {}", scriptPath);
                throw new IOException("Python脚本文件不存在: " + scriptPath);
            }
        }

        // 构建命令
        List<String> command = new ArrayList<>();
        command.add(PYTHON_PATH);
        command.add(scriptFile.getAbsolutePath());
        command.addAll(Arrays.asList(args));

        // 创建进程构建器
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        processBuilder.directory(new File(System.getProperty("user.dir"))); // 设置工作目录为项目根目录

        // 执行命令并获取输出
        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            logger.error("Python脚本执行失败，退出码: {}", exitCode);
            logger.error("错误输出: {}", output.toString());
            throw new IOException("Python脚本执行失败，退出码: " + exitCode);
        }

        return output.toString();
    }
    
    private static String tryExecuteWithPython(String scriptPath, String... args) throws IOException, InterruptedException {
        // 构建命令
        List<String> command = new ArrayList<>();
        if (IS_WINDOWS) {
            command.add(PYTHON_PATH); // 使用绝对路径
        } else {
            command.add(PYTHON_CMD);
        }
        command.add(scriptPath);
        if (args != null && args.length > 0) {
            command.addAll(Arrays.asList(args));
        }
        
        logger.debug("执行Python命令: {}", String.join(" ", command));
        
        // 设置ProcessBuilder工作目录
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(new File(scriptPath).getParentFile());
        
        // 设置环境变量
        Map<String, String> env = processBuilder.environment();
        env.put("PYTHONIOENCODING", "utf-8");
        
        // 执行命令
        Process process = processBuilder.start();
        
        // 读取输出
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        
        // 等待进程完成
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Python脚本执行失败，退出码: " + exitCode);
        }
        
        return output.toString();
    }
    
    private static String tryExecuteWithPowerShell(String scriptPath, String... args) throws IOException, InterruptedException {
        // 构建PowerShell命令
        StringBuilder psCommand = new StringBuilder();
        psCommand.append("& { & '").append(PYTHON_PATH).append("' '").append(new File(scriptPath).getAbsolutePath()).append("'");
        
        // 添加参数
        if (args != null && args.length > 0) {
            for (String arg : args) {
                psCommand.append(" '").append(arg).append("'");
            }
        }
        psCommand.append(" }");
        
        logger.debug("执行PowerShell命令: {}", psCommand.toString());
        
        // 创建临时文件用于输出重定向
        Path tempFile = Files.createTempFile("python_output_", ".txt");
        
        try {
            // 构建PowerShell命令
            List<String> command = new ArrayList<>();
            command.add("powershell.exe");
            command.add("-Command");
            command.add(psCommand.toString() + " | Out-File -Encoding utf8 '" + tempFile.toAbsolutePath() + "'");
            
            // 执行命令
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            
            // 等待进程完成
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("PowerShell脚本执行失败，退出码: " + exitCode);
            }
            
            // 读取输出
            return new String(Files.readAllBytes(tempFile), StandardCharsets.UTF_8);
        } finally {
            // 删除临时文件
            Files.deleteIfExists(tempFile);
        }
    }
    
    private boolean isPythonAvailable() {
        try {
            Process process = Runtime.getRuntime().exec(PYTHON_CMD + " --version");
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            logger.error("检查Python可用性时出错: {}", e.getMessage());
            return false;
        }
    }
} 