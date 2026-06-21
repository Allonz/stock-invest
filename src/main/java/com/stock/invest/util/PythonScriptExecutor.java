package com.stock.invest.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class PythonScriptExecutor {

    private static final Logger log = LoggerFactory.getLogger(PythonScriptExecutor.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    public String executeScript(String scriptName, String... args) throws IOException, InterruptedException {
        return executeScriptWithEnvironment(Collections.emptyMap(), scriptName, args);
    }

    public String executeScriptWithEnvironment(Map<String, String> extraEnv, String scriptName, String... args)
            throws IOException, InterruptedException {
        String pythonExec = resolvePythonExecutable();

        if (!PythonRuntimeSupport.isPythonRunnable(pythonExec, true)) {
            log.error("Python未安装或未添加到PATH环境变量中");
            throw new IOException("Python未安装或未添加到PATH环境变量中");
        }

        ClassPathResource resource = new ClassPathResource("python/" + scriptName);
        if (!resource.exists()) {
            log.error("Python脚本资源不存在: python/{}", scriptName);
            throw new IOException("Python脚本资源不存在: python/" + scriptName);
        }

        Path tempFile = Files.createTempFile("py_script_", ".py");
        try {
            Files.copy(resource.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            File scriptFile = tempFile.toFile();
            scriptFile.deleteOnExit();

            List<String> command = new ArrayList<>();
            command.add(pythonExec);
            command.add(scriptFile.getAbsolutePath());
            command.addAll(Arrays.asList(args));

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            // 不合并 stderr — 单独读取，避免库日志/调试输出污染 stdout 的 JSON 解析
            processBuilder.redirectErrorStream(false);
            processBuilder.directory(new File(System.getProperty("user.dir")));
            if (extraEnv != null && !extraEnv.isEmpty()) {
                Map<String, String> env = processBuilder.environment();
                for (Map.Entry<String, String> e : extraEnv.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        env.put(e.getKey(), e.getValue());
                    }
                }
            }

            Process process = processBuilder.start();
            log.info("Python脚本执行: script={} args={}", scriptName, java.util.Arrays.toString(args));
            try {
                // 单独读取 stdout（结果输出）
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                }

                // 单独读取 stderr（库日志/调试信息），仅 debug 级别记录，不影响结果解析
                StringBuilder errOutput = new StringBuilder();
                try (BufferedReader errReader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = errReader.readLine()) != null) {
                        errOutput.append(line).append("\n");
                    }
                }
                String stderr = errOutput.toString().trim();
                if (!stderr.isEmpty()) {
                    log.info("Python脚本 stderr (script={}): {}", scriptName, stderr);
                }

                boolean completed = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!completed) {
                    log.warn("Python脚本执行超时 ({}秒)，强制终止进程: {}", DEFAULT_TIMEOUT_SECONDS, scriptName);
                    process.destroyForcibly();
                    throw new IOException("Python脚本执行超时 (" + DEFAULT_TIMEOUT_SECONDS + "秒)，已强制终止进程");
                }

                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    log.warn("Python脚本执行失败，退出码: {}", exitCode);
                    throw new IOException("Python脚本执行失败，退出码: " + exitCode);
                }

                String result = output.toString().trim();
                // 记录执行结果摘要（取前200字符，避免刷屏）
                String preview = result.length() > 200 ? result.substring(0, 200) + "..." : result;
                log.info("Python脚本 stdout (script={}): {}", scriptName, preview);
                return result;
            } finally {
                process.destroy();
            }
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // cleanup best-effort
            }
        }
    }

    private String resolvePythonExecutable() {
        String envOverride = PythonRuntimeSupport.firstNonBlank(
                System.getenv("STOCK_INVEST_PYTHON"),
                System.getenv("PYTHON_EXECUTABLE")
        );
        String exe = PythonRuntimeSupport.resolvePythonExecutable();
        if (envOverride != null && exe.equals(envOverride)) {
            log.info("使用环境变量指定的 Python: {}", exe);
        } else if (PythonRuntimeSupport.isResolvedPythonFromProjectVenv(exe)) {
            // 项目虚拟环境 Python，无需额外日志
        } else if ("python3".equals(exe)) {
            log.info("检测到 python 命令不可用，已回退使用 python3");
        } else {
            log.warn("未使用项目 .venv，当前解析到的 Python: {}", exe);
        }
        return exe;
    }
}
