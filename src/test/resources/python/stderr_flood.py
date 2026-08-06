#!/usr/bin/env python3
"""P1-1 stderr 洪泛测试：向 stderr 写入 >64KB（管道缓冲量级），

验证执行器并行排空 stderr、stdout 不被阻塞（修复前会因管道写满而死锁）。
"""
import sys

# 约 1MB stderr 输出（远超 64KB 管道缓冲）
chunk = "e" * 1024
for _ in range(1024):
    sys.stderr.write(chunk + "\n")
sys.stderr.flush()

# 洪泛后再输出正常结果 —— 若排空失效，此处会永久阻塞
print('{"ok": true, "source": "stderr_flood"}')
