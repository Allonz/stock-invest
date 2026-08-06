#!/usr/bin/env python3
"""P1-1 超时测试专用脚本：把 PID 写入环境变量指定文件后挂起 60 秒。

由 PythonScriptExecutor 以 30s 超时执行 → 应被 destroyForcibly 杀掉；
测试通过 PID 文件断言进程已被销毁。
"""
import os
import sys
import time

pid_path = os.environ.get("HANG_PID_FILE")
if pid_path:
    with open(pid_path, "w") as f:
        f.write(str(os.getpid()))

# 不向 stdout 输出任何内容，模拟卡死的子进程
time.sleep(60)
