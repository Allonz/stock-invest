#!/usr/bin/env python3
"""P1-1 退出码测试：以 exit(1) 结束，stderr 带错误说明。"""
import sys

sys.stderr.write("boom: something failed\n")
sys.exit(1)
