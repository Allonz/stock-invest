package com.stock.invest.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 解析项目将使用的 Python 解释器路径（与 {@link PythonScriptExecutor} 规则一致），并做 venv / 版本诊断。
 */
public final class PythonRuntimeSupport {

    private static final Logger log = LoggerFactory.getLogger(PythonRuntimeSupport.class);
    private static final String WINDOWS_PYTHON_FALLBACK = "C:\\Program Files\\Python310\\python.exe";

    private PythonRuntimeSupport() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static Path projectVenvPythonPath() {
        String userDir = System.getProperty("user.dir");
        if (userDir == null || userDir.trim().isEmpty()) {
            return null;
        }
        Path root = Paths.get(userDir);
        if (isWindows()) {
            Path p = root.resolve(".venv").resolve("Scripts").resolve("python.exe");
            return Files.isRegularFile(p) ? p : null;
        }
        Path p = root.resolve(".venv").resolve("bin").resolve("python");
        return Files.isRegularFile(p) ? p : null;
    }

    /**
     * 与 {@link PythonScriptExecutor} 相同的解析顺序：STOCK_INVEST_PYTHON / PYTHON_EXECUTABLE → 项目 .venv → 系统 python。
     */
    public static String resolvePythonExecutable() {
        String envOverride = firstNonBlank(
                System.getenv("STOCK_INVEST_PYTHON"),
                System.getenv("PYTHON_EXECUTABLE")
        );
        if (envOverride != null && isPythonRunnable(envOverride, false)) {
            return envOverride;
        }

        Path venvPython = projectVenvPythonPath();
        if (venvPython != null && isPythonRunnable(venvPython.toString(), false)) {
            return venvPython.toString();
        }

        if (isWindows()) {
            if (isPythonRunnable(WINDOWS_PYTHON_FALLBACK, false)) {
                return WINDOWS_PYTHON_FALLBACK;
            }
            if (isPythonRunnable("python", false)) {
                return "python";
            }
            return WINDOWS_PYTHON_FALLBACK;
        }
        if (isPythonRunnable("python", false)) {
            return "python";
        }
        if (isPythonRunnable("python3", false)) {
            return "python3";
        }
        return "python";
    }

    /**
     * 当前解析到的解释器是否就是项目根目录下 {@code .venv} 中的 Python（通过真实路径比较）。
     */
    public static boolean isResolvedPythonFromProjectVenv(String pythonExecutable) {
        Path venvPy = projectVenvPythonPath();
        if (venvPy == null || !Files.isRegularFile(venvPy) || pythonExecutable == null || pythonExecutable.trim().isEmpty()) {
            return false;
        }
        try {
            Path chosen = Paths.get(pythonExecutable.trim());
            if (!chosen.isAbsolute()) {
                return false;
            }
            Path chosenReal = chosen.toRealPath();
            Path venvReal = venvPy.toRealPath();
            return chosenReal.equals(venvReal);
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    /**
     * 打印可执行的绝对路径、version 与 sys.executable（供日志诊断）。
     */
    public static String pythonDiagnosticsText(String pythonExecutable) {
        StringBuilder sb = new StringBuilder();
        sb.append("executable=").append(pythonExecutable).append("\n");
        sb.append(readProcessOutput(pythonExecutable, "--version"));
        sb.append(readProcessOutput(pythonExecutable, "-c",
                "import sys; print('sys.executable=' + sys.executable); print('sys.version=' + sys.version.replace(\"\\n\", \" \"))"));
        return sb.toString().trim();
    }

    static boolean isPythonRunnable(String pythonCmd, boolean logOnError) {
        Process process = null;
        try {
            process = new ProcessBuilder(pythonCmd, "--version").start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            if (logOnError) {
                log.warn("检查 Python 可用性时出错({}): {}", pythonCmd, e.getMessage());
            }
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String readProcessOutput(String pythonExecutable, String... args) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder();
            List<String> cmd = new ArrayList<>();
            cmd.add(pythonExecutable);
            for (String a : args) {
                cmd.add(a);
            }
            pb.command(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append("\n");
                }
            }
            p.waitFor();
            return out.toString();
        } catch (Exception e) {
            return "(failed to run: " + e.getMessage() + ")\n";
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    static String firstNonBlank(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a.trim();
        }
        if (b != null && !b.trim().isEmpty()) {
            return b.trim();
        }
        return null;
    }
}
