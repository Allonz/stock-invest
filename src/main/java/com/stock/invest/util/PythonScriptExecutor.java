package com.stock.invest.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Component
public class PythonScriptExecutor {

    private static final Logger log = LoggerFactory.getLogger(PythonScriptExecutor.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** stdout 读取上限（约 8 MB / 20 万行），防止异常输出撑爆内存 */
    private static final int MAX_OUTPUT_CHARS = 8 * 1024 * 1024;
    /** stderr 仅保留尾部（8 KB），库日志/调试输出不占内存 */
    private static final int MAX_STDERR_CHARS = 8 * 1024;
    /** 进程退出后等待读流结束的保险时间 */
    private static final int DRAIN_GRACE_SECONDS = 5;

    /** P2-17：探活结果进程内缓存时长 —— 每次执行前不再都启动一次 Python 探活进程 */
    private static final long PROBE_CACHE_MILLIS = 60_000L;
    /** 最近一次探活成功时间（失败不缓存，下次重探） */
    private static volatile long lastProbeOkAtMillis = 0L;

    /**
     * 读流专用小线程池（2 个进程 × 2 路输出）。
     * <p>P1-1：超时等待期间必须并行排空 stdout/stderr，否则子进程写满管道缓冲（约 64KB）会永久阻塞；
     * 超时后 destroyForcibly 使流 EOF，读流任务自然结束，不会长期占用线程。</p>
     */
    private static final ExecutorService DRAIN_POOL = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private int seq;

        @Override
        public synchronized Thread newThread(Runnable r) {
            Thread t = new Thread(r, "python-drain-" + (++seq));
            t.setDaemon(true);
            return t;
        }
    });

    public String executeScript(String scriptName, String... args) throws IOException, InterruptedException {
        return executeScriptWithEnvironment(Collections.emptyMap(), scriptName, args);
    }

    public String executeScriptWithEnvironment(Map<String, String> extraEnv, String scriptName, String... args)
            throws IOException, InterruptedException {
        String pythonExec = resolvePythonExecutable();

        // P2-17：探活结果缓存 60s，避免每次执行都启动探活进程
        if (System.currentTimeMillis() - lastProbeOkAtMillis >= PROBE_CACHE_MILLIS
                && !probePythonRunnable(pythonExec)) {
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
                // P1-1：并行排空两路输出（防止管道缓冲写满死锁），同时等待超时
                CompletableFuture<String> outFuture = CompletableFuture.supplyAsync(
                        () -> drain(process.getInputStream(), MAX_OUTPUT_CHARS), DRAIN_POOL);
                CompletableFuture<String> errFuture = CompletableFuture.supplyAsync(
                        () -> drainTail(process.getErrorStream(), MAX_STDERR_CHARS), DRAIN_POOL);

                // 超时判定前置：子进程挂起时不再被 readLine() 永久阻塞
                boolean completed = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!completed) {
                    log.warn("Python脚本执行超时 ({}秒)，强制终止进程: {}", DEFAULT_TIMEOUT_SECONDS, scriptName);
                    process.destroy();
                    process.destroyForcibly();
                    throw new IOException("Python脚本执行超时 (" + DEFAULT_TIMEOUT_SECONDS + "秒)，已强制终止进程");
                }

                // 进程已退出，读流必然 EOF，get 仅为保险
                String output = awaitDrain(outFuture, scriptName);
                String stderr = awaitDrain(errFuture, scriptName);
                if (!stderr.isEmpty()) {
                    log.info("Python脚本 stderr (script={}): {}", scriptName, stderr);
                }

                String result = output.trim();

                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    // P2-15：脚本失败优先从 stdout 解析统一错误 JSON（{"error":{"code","message"}}），
                    // 错误码/消息随异常透出，供调用方分类（P1-5 账户级错误识别）
                    String errorDetail = extractPythonError(result);
                    if (errorDetail != null) {
                        throw new IOException("Python脚本执行失败: " + errorDetail);
                    }
                    log.warn("Python脚本执行失败，退出码: {}", exitCode);
                    throw new IOException("Python脚本执行失败，退出码: " + exitCode);
                }

                // P1-1/P2-17：stdout 全量日志降为 DEBUG（每次执行可能是全量 K 线 JSON）
                log.debug("Python脚本 stdout (script={}): {}", scriptName, result);
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

    /**
     * 等待读流任务结束；进程已退出后流必然 EOF，这里只做超时兜底。
     */
    private static String awaitDrain(CompletableFuture<String> future, String scriptName)
            throws IOException, InterruptedException {
        try {
            return future.get(DRAIN_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("Python输出读取失败 (script={}): {}", scriptName, cause.getMessage());
            return "";
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("Python输出读取超时 (script={})，按空输出处理", scriptName);
            return "";
        }
    }

    /**
     * 读取流到 EOF，最多保留 maxChars 字符（超出部分丢弃但仍继续消费，避免阻塞子进程写管道）。
     */
    private static String drain(InputStream in, int maxChars) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() < maxChars) {
                    sb.append(line).append('\n');
                }
            }
        } catch (IOException e) {
            log.debug("Python输出流读取失败: {}", e.getMessage());
        }
        return sb.toString();
    }

    /**
     * 读取流到 EOF，仅保留尾部 maxChars 字符（环形截断）。
     */
    private static String drainTail(InputStream in, int maxChars) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > maxChars) {
                    sb.delete(0, sb.length() - maxChars);
                }
            }
        } catch (IOException e) {
            log.debug("Python错误输出流读取失败: {}", e.getMessage());
        }
        return sb.toString();
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

    /**
     * P2-15：从脚本 stdout 提取统一错误 JSON（{"error": {"code": ..., "message": ...}}）。
     * 非 JSON 输出返回 null，由调用方按通用消息处理。
     */
    private static String extractPythonError(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = OBJECT_MAPPER.readTree(output.trim());
            com.fasterxml.jackson.databind.JsonNode err = root.path("error");
            if (err.isObject()) {
                String code = err.path("code").asText("");
                String message = err.path("message").asText("");
                if (!message.isEmpty()) {
                    return "code=" + code + ", message=" + message;
                }
            }
        } catch (Exception ignored) {
            // 普通报错文本 —— 按通用消息处理
        }
        return null;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * P2-17：执行探活并缓存成功结果（失败不缓存，下次执行重新探测）。
     */
    private static boolean probePythonRunnable(String pythonExec) {
        boolean ok = PythonRuntimeSupport.isPythonRunnable(pythonExec, true);
        if (ok) {
            lastProbeOkAtMillis = System.currentTimeMillis();
        }
        return ok;
    }
}
