#!/usr/bin/env python3
"""P1-1 stdout 截断测试：向 stdout 写入约 10MB（> 8MB 上限），

验证 drain 截断生效且不 OOM。
"""
import sys

# 10MB 输出，每行 1KB
chunk = "x" * 1024
for _ in range(10 * 1024):
    sys.stdout.write(chunk + "\n")
sys.stdout.flush()
