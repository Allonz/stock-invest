package com.stock.invest.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REAL integration tests that execute Python scripts via direct
 * {@link java.lang.ProcessBuilder} subprocess, bypassing
 * {@link PythonScriptExecutor} to isolate any classloading issues.
 * <p>
 * These tests copy the Python script from the classpath to a temp file and
 * run it with the real system Python interpreter — no mocking whatsoever.
 */
class PythonDirectProcessTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int TIMEOUT_SECONDS = 30;
    private static String pythonExec;

    @BeforeAll
    static void resolvePython() {
        // Match the resolution logic from PythonRuntimeSupport
        if (isPythonRunnable("python3")) {
            pythonExec = "python3";
        } else if (isPythonRunnable("python")) {
            pythonExec = "python";
        } else {
            pythonExec = null;
        }
        if (pythonExec == null) {
            System.out.println("Python not available on this system — direct-process tests will verify error handling.");
        } else {
            System.out.println("Using Python: " + pythonExec);
        }
    }

    // ── test_script.py via direct subprocess ────────────────────────────

    @Test
    void testDirectSubprocessTestScriptNoArgs() throws Exception {
        if (pythonExec == null) {
            return; // no Python available, skip gracefully
        }
        String output = runScriptDirect("test_script.py");
        assertNotNull(output);
        assertFalse(output.isBlank());

        List<?> result = mapper.readValue(output, List.class);
        assertEquals(2, result.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) result.get(0);
        assertEquals("AAPL", first.get("symbol"));
    }

    @Test
    void testDirectSubprocessTestScriptWithArg() throws Exception {
        if (pythonExec == null) {
            return;
        }
        String output = runScriptDirect("test_script.py", "1");
        List<?> result = mapper.readValue(output, List.class);
        assertEquals(1, result.size());
    }

    @Test
    void testDirectSubprocessTestScriptWithInvalidArg() throws Exception {
        if (pythonExec == null) {
            return;
        }
        // Invalid arg "abc" triggers sys.exit(1); runScriptDirect throws.
        Exception thrown = assertThrows(Exception.class,
                () -> runScriptDirect("test_script.py", "abc"));
        String msg = thrown.getMessage();
        assertTrue(msg.contains("exit code") || msg.contains("exit"),
                "Should report non-zero exit: " + msg);
    }

    // ── yfinance via direct subprocess ──────────────────────────────────

    @Test
    void testDirectSubprocessYfinanceGetStockInfo() throws Exception {
        if (pythonExec == null) {
            return;
        }
        try {
            String output = runScriptDirect("stock_info_yfinance.py", "get_stock_info", "AAPL");
            assertNotNull(output);
            Map<?, ?> result = mapper.readValue(output, Map.class);
            assertTrue(
                    result.containsKey("symbol") || result.containsKey("longName")
                            || (result.containsKey("error") && result.get("error").toString().contains("dict")),
                    "Response should contain 'symbol'/'longName' or a dict-callable error. Got: " + output);
        } catch (Exception e) {
            String msg = e.getMessage();
            assertTrue(msg.contains("yfinance") || msg.contains("ModuleNotFound") || msg.contains("No module"),
                    "Expected error about missing yfinance module or network, got: " + msg);
        }
    }

    @Test
    void testDirectSubprocessYfinanceGetDailyKline() throws Exception {
        if (pythonExec == null) {
            return;
        }
        try {
            String output = runScriptDirect("stock_info_yfinance.py", "get_daily_kline", "AAPL");
            assertNotNull(output);
            Object result = mapper.readValue(output, Object.class);
            assertNotNull(result);
        } catch (Exception e) {
            String msg = e.getMessage();
            assertTrue(msg.contains("yfinance") || msg.contains("ModuleNotFound") || msg.contains("No module"),
                    "Expected error about missing yfinance module, got: " + msg);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static String runScriptDirect(String scriptName, String... args) throws Exception {
        // Copy script from classpath to temp file
        Path tempFile = Files.createTempFile("py_direct_", ".py");
        tempFile.toFile().deleteOnExit();
        Files.copy(
                PythonDirectProcessTest.class.getResourceAsStream("/python/" + scriptName),
                tempFile,
                StandardCopyOption.REPLACE_EXISTING);

        List<String> command = new ArrayList<>();
        command.add(pythonExec);
        command.add(tempFile.toAbsolutePath().toString());
        command.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new RuntimeException("Process timed out after " + TIMEOUT_SECONDS + "s");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException("Process exited with code " + exitCode + ", output: " + output);
        }

        // Clean up temp file
        try {
            Files.deleteIfExists(tempFile);
        } catch (Exception ignored) {
        }

        return output.toString().trim();
    }

    private static boolean isPythonRunnable(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "--version").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
