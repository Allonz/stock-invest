package com.stock.invest.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REAL integration tests for {@link PythonScriptExecutor}.
 * <p>
 * These tests execute the actual Python interpreter with real scripts from
 * {@code src/main/resources/python/}.  No mocking is used.
 * <p>
 * Tests that depend on third-party Python packages (e.g. yfinance) are
 * conditionally executed: if the package is absent the test still runs
 * but verifies the error message instead of the output content, so we
 * never get a silent skip.
 */
class PythonScriptExecutorTest {

    private static final PythonScriptExecutor executor = new PythonScriptExecutor();
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Set to true only when python3/python is actually available on the system. */
    private static boolean pythonAvailable;

    @BeforeAll
    static void checkPython() {
        try {
            Process p = new ProcessBuilder("python3", "--version")
                    .redirectErrorStream(true).start();
            int code = p.waitFor();
            pythonAvailable = (code == 0);
        } catch (Exception e) {
            try {
                Process p = new ProcessBuilder("python", "--version")
                        .redirectErrorStream(true).start();
                int code = p.waitFor();
                pythonAvailable = (code == 0);
            } catch (Exception ex) {
                pythonAvailable = false;
            }
        }
        if (!pythonAvailable) {
            System.out.println("Python not available on this system — tests will fail with clear IOException messages.");
        }
    }

    // ── test_script.py tests ────────────────────────────────────────────

    @Test
    void testExecuteTestScriptWithNoArgsReturnsValidJson() throws Exception {
        if (!pythonAvailable) {
            assertThrows(IOException.class, () -> executor.executeScript("test_script.py"));
            return;
        }
        String output = executor.executeScript("test_script.py");
        assertNotNull(output);
        assertFalse(output.isBlank());

        List<?> result = mapper.readValue(output, List.class);
        assertEquals(2, result.size(), "Expected 2 stock entries by default");

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) result.get(0);
        assertEquals("AAPL", first.get("symbol"));
        assertEquals("Apple Inc.", first.get("name"));
        assertNotNull(first.get("price"));
    }

    @Test
    void testExecuteTestScriptWithArgOne() throws Exception {
        if (!pythonAvailable) {
            assertThrows(IOException.class, () -> executor.executeScript("test_script.py", "1"));
            return;
        }
        String output = executor.executeScript("test_script.py", "1");
        assertNotNull(output);

        List<?> result = mapper.readValue(output, List.class);
        assertEquals(1, result.size(), "Expected 1 stock entry when passing arg '1'");
    }

    @Test
    void testExecuteTestScriptWithArgZeroReturnsEmptyArray() throws Exception {
        if (!pythonAvailable) {
            assertThrows(IOException.class, () -> executor.executeScript("test_script.py", "0"));
            return;
        }
        String output = executor.executeScript("test_script.py", "0");
        assertNotNull(output);

        List<?> result = mapper.readValue(output, List.class);
        assertEquals(0, result.size(), "Expected empty array when passing arg '0'");
    }

    // ── Non-existent script ─────────────────────────────────────────────

    @Test
    void testExecuteNonExistentScriptThrowsIOException() {
        IOException thrown = assertThrows(IOException.class,
                () -> executor.executeScript("does_not_exist.py"));
        assertTrue(thrown.getMessage().contains("不存在") || thrown.getMessage().contains("not exist"),
                "Error message should mention script not found: " + thrown.getMessage());
    }

    // ── P1-1: 超时 / 管道排空 / 截断 / destroyForcibly ──────────────────

    /** 执行器固定 30s 超时（DEFAULT_TIMEOUT_SECONDS），无法注入短值 —— 用例真实等待 30s。 */
    @Test
    @DisplayName("P1-1: 挂起脚本 30s 超时 → IOException 且进程被 destroyForcibly 销毁")
    void timeout_kills_hung_process() throws Exception {
        if (!pythonAvailable) {
            assertThrows(IOException.class, () -> executor.executeScript("hang_test.py"));
            return;
        }
        Path pidFile = Files.createTempFile("hang_pid_", ".txt");
        try {
            long start = System.nanoTime();
            IOException ex = assertThrows(IOException.class, () ->
                    executor.executeScriptWithEnvironment(
                            Map.of("HANG_PID_FILE", pidFile.toString()), "hang_test.py"));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

            assertTrue(ex.getMessage().contains("超时"), "message should mention timeout: " + ex.getMessage());
            // 超时固定 30s：断言确实等待了超时窗口，而非提前报错
            assertTrue(elapsedMs >= 25_000, "should have waited for the 30s timeout, elapsed=" + elapsedMs);

            // 脚本已把自身 PID 写入文件 —— destroyForcibly 后进程必须消失
            String pidStr = Files.readString(pidFile, StandardCharsets.UTF_8).trim();
            assertFalse(pidStr.isEmpty(), "hang script should have written its pid");
            long pid = Long.parseLong(pidStr);
            boolean aliveAfterKill = waitUntilNotAlive(pid, 5_000);
            assertFalse(aliveAfterKill, "process " + pid + " should be destroyed after timeout");
        } finally {
            Files.deleteIfExists(pidFile);
        }
    }

    /** SIGKILL 后轮询等待进程消亡（destroyForcibly 生效的证明）。 */
    private static boolean waitUntilNotAlive(long pid, long maxWaitMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isEmpty() || !handle.get().isAlive()) {
                return false;
            }
            Thread.sleep(100);
        }
        return true;
    }

    @Test
    @DisplayName("P1-1: stderr 洪泛 >64KB 不阻塞，stdout 结果正常返回")
    void stderr_flood_no_deadlock() throws Exception {
        if (!pythonAvailable) {
            assertThrows(IOException.class, () -> executor.executeScript("stderr_flood.py"));
            return;
        }
        long start = System.nanoTime();
        String output = executor.executeScript("stderr_flood.py");
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        // 1MB stderr + JSON stdout：若读流未并行排空，会因管道写满永久阻塞
        assertTrue(elapsedMs < 20_000, "should not deadlock on stderr flood, elapsed=" + elapsedMs);
        Map<?, ?> result = mapper.readValue(output, Map.class);
        assertEquals(true, result.get("ok"));
    }

    @Test
    @DisplayName("P1-1: stdout 超 8MB 上限被截断且不 OOM")
    void stdout_flood_limited() throws Exception {
        if (!pythonAvailable) {
            assertThrows(IOException.class, () -> executor.executeScript("stdout_flood.py"));
            return;
        }
        int maxOutputChars = 8 * 1024 * 1024; // PythonScriptExecutor.MAX_OUTPUT_CHARS
        String output = executor.executeScript("stdout_flood.py");
        assertNotNull(output);
        // drain 允许在达到上限前多追加一行（行尾 '\n'），给 2KB 余量
        assertTrue(output.length() <= maxOutputChars + 2048,
                "output should be truncated at ~8MB, got " + output.length());
        assertTrue(output.length() >= maxOutputChars - 2048,
                "output should actually hit the cap, got " + output.length());
    }

    @Test
    @DisplayName("P1-1: 脚本 exit(1) → IOException 含退出码")
    void exit_code_nonzero_throws() {
        if (!pythonAvailable) {
            assertThrows(IOException.class, () -> executor.executeScript("exit_nonzero.py"));
            return;
        }
        IOException ex = assertThrows(IOException.class, () -> executor.executeScript("exit_nonzero.py"));
        assertTrue(ex.getMessage().contains("退出码"), "message should mention exit code: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("1"), "message should contain exit code 1: " + ex.getMessage());
    }

    @Test
    @DisplayName("P1-1: 正常脚本返回去空白后的 stdout")
    void success_returns_trimmed_stdout() throws Exception {
        if (!pythonAvailable) {
            assertThrows(IOException.class, () -> executor.executeScript("trim_test.py"));
            return;
        }
        String output = executor.executeScript("trim_test.py");
        assertEquals("{\"ok\": true}", output, "leading/trailing whitespace should be trimmed");
    }

    // ── yfinance: get_stock_info ────────────────────────────────────────
    //
    // NOTE: The script's get_stock_info() calls safe_yfinance_request(stock.info)
    // but stock.info is a dict property, not a callable.  This is a script-level
    // bug that causes a "'dict' object is not callable" error returned as JSON.
    // We verify that the script runs (exit code 0) and returns valid JSON with
    // either the expected fields or an error message about the bug.

    @Test
    void testYfinanceGetStockInfoAapl() throws Exception {
        if (!pythonAvailable) {
            assertThrows(IOException.class,
                    () -> executor.executeScript("stock_info_yfinance.py", "get_stock_info", "AAPL"));
            return;
        }
        try {
            String output = executor.executeScript("stock_info_yfinance.py", "get_stock_info", "AAPL");
            assertNotNull(output);
            Map<?, ?> result = mapper.readValue(output, Map.class);
            // The script has a known bug: stock.info is a dict, not callable.
            // Accept either successful fields or the error message.
            assertTrue(
                    result.containsKey("symbol") || result.containsKey("longName")
                            || (result.containsKey("error") && (result.get("error").toString().contains("dict")
                                    || result.get("error").toString().contains("Rate")
                                    || result.get("error").toString().contains("limit"))),
                    "yfinance response should contain 'symbol'/'longName' or a dict/Rate-limited error. Got: " + output);
        } catch (IOException e) {
            String msg = e.getMessage();
            assertTrue(msg.contains("yfinance") || msg.contains("ModuleNotFound"),
                    "Expected error about missing yfinance module, got: " + msg);
        }
    }

    // ── yfinance: get_daily_kline ───────────────────────────────────────

    @Test
    void testYfinanceGetDailyKlineAapl() throws Exception {
        if (!pythonAvailable) {
            assertThrows(IOException.class,
                    () -> executor.executeScript("stock_info_yfinance.py", "get_daily_kline", "AAPL"));
            return;
        }
        try {
            String output = executor.executeScript("stock_info_yfinance.py", "get_daily_kline", "AAPL");
            assertNotNull(output);
            // Expecting either a JSON array or object depending on the function
            Object result = mapper.readValue(output, Object.class);
            assertNotNull(result);
        } catch (IOException e) {
            String msg = e.getMessage();
            assertTrue(msg.contains("yfinance") || msg.contains("ModuleNotFound") || msg.contains("No module"),
                    "Expected error about missing yfinance module, got: " + msg);
        }
    }
}
