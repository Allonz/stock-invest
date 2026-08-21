package com.stock.invest.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评审 D-3：PythonRuntimeSupport.isPythonRunnable 探活超时 ——
 * waitFor(5s) 超时返回 false 且强杀进程，不再无限阻塞调用线程。
 */
class PythonRuntimeSupportTest {

    @Test
    @DisplayName("评审 D-3: 挂起的解释器在 5s 超时后判定不可用，不无限阻塞")
    void hangingInterpreter_timesOutAndReturnsFalse() throws Exception {
        Path script = Paths.get("target", "hang-python-probe.sh");
        Files.writeString(script, "#!/bin/sh\nsleep 30\n");
        script.toFile().setExecutable(true);

        try {
            long start = System.currentTimeMillis();
            boolean runnable = PythonRuntimeSupport.isPythonRunnable(script.toString(), false);
            long elapsed = System.currentTimeMillis() - start;

            assertFalse(runnable, "hanging interpreter must be judged not runnable");
            assertTrue(elapsed < 10_000,
                    "waitFor must time out (~5s) instead of blocking forever, elapsed=" + elapsed + "ms");
        } finally {
            Files.deleteIfExists(script);
        }
    }

    @Test
    @DisplayName("评审 D-3: 不存在的命令返回 false（原有行为保持）")
    void nonexistentCommand_returnsFalse() {
        assertFalse(PythonRuntimeSupport.isPythonRunnable(
                "/nonexistent/python-interpreter-xyz", false));
    }

    @Test
    @DisplayName("评审 D-3: 可用的 python 探活返回 true（真实解释器）")
    void realPython_returnsTrue() {
        String python = PythonRuntimeSupport.resolvePythonExecutable();
        assertTrue(PythonRuntimeSupport.isPythonRunnable(python, false),
                "resolved python executable must be runnable: " + python);
    }
}
