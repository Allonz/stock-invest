#!/usr/bin/env python
# -*- coding: utf-8 -*-

import sys
import json
import platform

# 打印Python版本和系统信息
print("Python版本:", platform.python_version())
print("操作系统:", platform.system(), platform.release())

# 输出JSON格式的测试数据
data = {
    "status": "success",
    "message": "Python脚本执行成功",
    "python_version": platform.python_version(),
    "system": platform.system(),
    "arguments": sys.argv[1:] if len(sys.argv) > 1 else []
}

print(json.dumps(data, ensure_ascii=False, indent=2)) 