#!/usr/bin/env python
# -*- coding: utf-8 -*-

import sys
import json
from datetime import datetime

def get_low_price_stocks(limit=20):
    """
    模拟获取低价股票列表
    在实际应用中，这里应该调用TwelveData API
    """
    # 这里使用模拟数据
    stocks = [
        {
            "symbol": "AAPL",
            "name": "Apple Inc.",
            "price": 0.15,  # 修改为低价股票示例
            "volume": 1000000,
            "change": 2.5,
            "changePercent": 1.67,
            "timestamp": datetime.now().isoformat()
        },
        {
            "symbol": "MSFT",
            "name": "Microsoft Corporation",
            "price": 0.18,  # 修改为低价股票示例
            "volume": 800000,
            "change": -1.25,
            "changePercent": -0.44,
            "timestamp": datetime.now().isoformat()
        }
    ]
    
    # 按价格排序
    sorted_stocks = sorted(stocks, key=lambda x: x["price"])
    return sorted_stocks[:int(limit)]

def scan_stocks(market, limit=20, min_price=None, max_price=None):
    """
    扫描指定市场的股票
    """
    try:
        stocks = get_low_price_stocks(limit)
        
        # 过滤价格范围
        filtered_stocks = []
        min_price = float(min_price) if min_price and min_price != "" else 0
        max_price = float(max_price) if max_price and max_price != "" else float('inf')
        
        for stock in stocks:
            price = stock['price']
            if min_price <= price <= max_price:
                filtered_stocks.append(stock['symbol'])
        
        return filtered_stocks
    except Exception as e:
        print(json.dumps({"error": str(e)}), file=sys.stderr)
        return []

if __name__ == "__main__":
    try:
        if len(sys.argv) < 2:
            print(json.dumps({"error": "No command specified"}))
            sys.exit(1)
            
        command = sys.argv[1]
        
        if command == "get_low_price_stocks":
            limit = int(sys.argv[2]) if len(sys.argv) > 2 else 20
            result = get_low_price_stocks(limit)
            print(json.dumps(result))
            
        elif command == "scan_stocks":
            if len(sys.argv) < 3:
                print(json.dumps({"error": "Market not specified"}))
                sys.exit(1)
                
            market = sys.argv[2]
            limit = int(sys.argv[3]) if len(sys.argv) > 3 else 20
            min_price = sys.argv[4] if len(sys.argv) > 4 else None
            max_price = sys.argv[5] if len(sys.argv) > 5 else None
            
            result = scan_stocks(market, limit, min_price, max_price)
            print(json.dumps(result))
            
        else:
            print(json.dumps({"error": f"Unknown command: {command}"}))
            sys.exit(1)
            
    except Exception as e:
        print(json.dumps({"error": str(e)}), file=sys.stderr)
        sys.exit(1)