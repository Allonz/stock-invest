#!/usr/bin/env python
# -*- coding: utf-8 -*-

import sys
import json
from datetime import datetime

# 测试数据
data = [
    {
        "symbol": "AAPL",
        "name": "Apple Inc.",
        "price": 150.25,
        "volume": 1000000,
        "change": 2.5,
        "changePercent": 1.67,
        "timestamp": datetime.now().isoformat()
    },
    {
        "symbol": "MSFT",
        "name": "Microsoft Corporation",
        "price": 280.75,
        "volume": 800000,
        "change": -1.25,
        "changePercent": -0.44,
        "timestamp": datetime.now().isoformat()
    }
]

# 如果提供了参数，则返回指定数量的数据
if len(sys.argv) > 1:
    try:
        limit = int(sys.argv[1])
        data = data[:limit]
    except ValueError:
        print("参数必须是整数")
        sys.exit(1)

# 输出JSON数据
print(json.dumps(data, ensure_ascii=False))
