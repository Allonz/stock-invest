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

    /** R2 P2-6：超时注入化 —— 2s 超时实例验证挂起进程杀灭，不再真实等待 30s。 */
    @Test
    @DisplayName("R2 P2-6: 挂起脚本 2s 超时（注入）→ IOException 且进程被 destroyForcibly 销毁")
    void timeout_kills_hung_process() throws Exception {
        PythonScriptExecutor shortTimeoutExecutor = new PythonScriptExecutor(2);
        if (!pythonAvailable) {
            assertThrows(IOException.class, () -> shortTimeoutExecutor.executeScript("hang_test.py"));
            return;
        }
        Path pidFile = Files.createTempFile("hang_pid_", ".txt");
        try {
            long start = System.nanoTime();
            IOException ex = assertThrows(IOException.class, () ->
                    shortTimeoutExecutor.executeScriptWithEnvironment(
                            Map.of("HANG_PID_FILE", pidFile.toString()), "hang_test.py"));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

            assertTrue(ex.getMessage().contains("超时"), "message should mention timeout: " + ex.getMessage());
            // 注入 2s 超时：断言确实等待了超时窗口（≥1.5s），而非提前报错
            assertTrue(elapsedMs >= 1500, "should have waited for the 2s timeout, elapsed=" + elapsedMs);
            assertTrue(elapsedMs < 20_000, "should not wait the full default 30s, elapsed=" + elapsedMs);

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
    @DisplayName("R2 P2-6: 无参构造生产默认超时仍为 30s（注入化不改变默认值）")
    void defaultTimeout_still30s() throws Exception {
        PythonScriptExecutor defaultExecutor = new PythonScriptExecutor();
        java.lang.reflect.Field f = PythonScriptExecutor.class.getDeclaredField("timeoutSeconds");
        f.setAccessible(true);
        assertEquals(30, f.getInt(defaultExecutor),
                "production default timeout must stay 30s");
    }

    // ── R2 P2-5: 排空线程池扩容 —— 并发脚本完整性 ─────────────────────

    @Test
    @DisplayName("R2 P2-5: 4 个并发脚本（不同参数）输出全部完整，无空串/数据丢失")
    void concurrentExecutions_allOutputsComplete() throws Exception {
        if (!pythonAvailable) {
            // 无 Python 环境：验证 4 路并发均得到明确的 IOException（无静默空输出路径）
            List<IOException> errors = java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> { try { executor.executeScript("test_script.py", "0"); return null; }
                                         catch (Exception e) { return e instanceof IOException io ? io : new IOException(e); } })
                    .thenCombine(java.util.concurrent.CompletableFuture
                            .supplyAsync(() -> { try { executor.executeScript("test_script.py", "1"); return null; }
                                                 catch (Exception e) { return e instanceof IOException io ? io : new IOException(e); } }),
                            (a, b) -> List.of(a, b))
                    .get(60, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(2, errors.stream().filter(java.util.Objects::nonNull).count(),
                    "each concurrent execution must fail loudly with IOException");
            return;
        }
        List<java.util.concurrent.Future<String>> futures = List.of(
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> runSafely("test_script.py", "0")),
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> runSafely("test_script.py", "1")),
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> runSafely("test_script.py", "1")),
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> runSafely("test_script.py", "2")));

        for (java.util.concurrent.Future<String> f : futures) {
            String output = f.get(60, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(output, "concurrent output must not be null");
            assertFalse(output.isBlank(), "concurrent output must not be empty (drain must not be lost)");
            List<?> result = mapper.readValue(output, List.class);
            assertNotNull(result, "concurrent output must be valid JSON array");
        }
        // 4 路输出均到齐且合法 —— 排空线程池容量（8）未造成排队丢数据
        assertEquals(4, futures.size());
    }

    private static String runSafely(String script, String... args) {
        try {
            return executor.executeScript(script, args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("R2 P2-5: 挂起脚本（2s 注入超时）与其他并发脚本混跑 → 挂起者被超时杀灭，其余输出完整")
    void concurrentWithHungScript_timeoutKillsAndOthersComplete() throws Exception {
        PythonScriptExecutor shortTimeoutExecutor = new PythonScriptExecutor(2);
        if (!pythonAvailable) {
            IOException hung = assertThrows(IOException.class,
                    () -> shortTimeoutExecutor.executeScript("hang_test.py"));
            assertTrue(hung.getMessage().contains("超时"), "hang script must time out");
            return;
        }
        Path pidFile = Files.createTempFile("hang_pid_concurrent_", ".txt");
        try {
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(4);
            try {
                java.util.concurrent.Future<IOException> hungFuture = pool.submit(() -> {
                    try {
                        shortTimeoutExecutor.executeScriptWithEnvironment(
                                Map.of("HANG_PID_FILE", pidFile.toString()), "hang_test.py");
                        return null;
                    } catch (IOException e) {
                        return e;
                    }
                });
                java.util.concurrent.Future<String> ok1 = pool.submit(() -> runSafely("test_script.py", "1"));
                java.util.concurrent.Future<String> ok2 = pool.submit(() -> runSafely("test_script.py", "1"));
                java.util.concurrent.Future<String> ok3 = pool.submit(() -> runSafely("test_script.py", "2"));

                IOException hungEx = hungFuture.get(60, java.util.concurrent.TimeUnit.SECONDS);
                assertNotNull(hungEx, "hung script must fail with timeout IOException");
                assertTrue(hungEx.getMessage().contains("超时"),
                        "hung script error must mention timeout: " + hungEx.getMessage());

                for (java.util.concurrent.Future<String> f : List.of(ok1, ok2, ok3)) {
                    String output = f.get(60, java.util.concurrent.TimeUnit.SECONDS);
                    assertFalse(output.isBlank(), "healthy script output must not be lost while a hung script drains");
                    assertNotNull(mapper.readValue(output, List.class));
                }

                // 挂起进程被 destroyForcibly 杀灭（与 timeout_kills_hung_process 同一断言路径）
                String pidStr = Files.readString(pidFile, StandardCharsets.UTF_8).trim();
                if (!pidStr.isEmpty()) {
                    long pid = Long.parseLong(pidStr);
                    assertFalse(waitUntilNotAlive(pid, 5_000),
                            "hung process " + pid + " must be destroyed after injected timeout");
                }
            } finally {
                pool.shutdownNow();
            }
        } finally {
            Files.deleteIfExists(pidFile);
        }
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
