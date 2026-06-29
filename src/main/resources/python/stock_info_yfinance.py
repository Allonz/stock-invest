import sys
import json
import os
import time
from datetime import datetime, timedelta
import pytz
import yfinance as yf
import pandas as pd
import numpy as np
from typing import List, Dict, Any, Optional
import backoff
import random
import logging

# 抑制 urllib3/requests/yfinance 的 HTTP 调试日志，避免污染 stdout
logging.getLogger("urllib3").setLevel(logging.WARNING)
logging.getLogger("requests").setLevel(logging.WARNING)
logging.getLogger("yfinance").setLevel(logging.WARNING)

# 全局变量用于请求限制
last_request_time = 0
min_request_interval = 2  # 最小请求间隔（秒）
request_count = 0
last_request_count_reset = time.time()

def rate_limit():
    """实现请求速率限制"""
    global last_request_time, request_count, last_request_count_reset
    
    current_time = time.time()
    
    # 每60秒重置请求计数
    if current_time - last_request_count_reset >= 60:
        request_count = 0
        last_request_count_reset = current_time
    
    # 确保请求间隔不小于最小间隔
    elapsed = current_time - last_request_time
    if elapsed < min_request_interval:
        time.sleep(min_request_interval - elapsed)
    
    # 更新最后请求时间
    last_request_time = time.time()
    request_count += 1

@backoff.on_exception(
    backoff.expo,
    (Exception),
    max_tries=5,
    max_time=30,
    giveup=lambda e: "404" in str(e)
)
def safe_yfinance_request(func, *args, **kwargs):
    """安全地执行yfinance请求，包含重试和速率限制"""
    rate_limit()
    return func(*args, **kwargs)

def get_stock_info(symbol: str) -> str:
    """获取股票信息"""
    try:
        # 添加随机延迟
        time.sleep(random.uniform(2, 4))
        
        # 获取股票信息
        stock = yf.Ticker(symbol)
        info = safe_yfinance_request(stock.info)
        
        # 获取历史数据
        hist = safe_yfinance_request(stock.history, period="1d")
        if hist.empty:
            return json.dumps({"error": f"No historical data found for {symbol}"})

        # 构建返回数据
        result = {
            "symbol": symbol,
            "name": info.get("longName", symbol),
            "currentPrice": float(hist["Close"].iloc[-1]),
            "openPrice": float(hist["Open"].iloc[-1]),
            "highPrice": float(hist["High"].iloc[-1]),
            "lowPrice": float(hist["Low"].iloc[-1]),
            "volume": int(hist["Volume"].iloc[-1]),
            "change": float(hist["Close"].iloc[-1] - hist["Open"].iloc[-1]),
            "changePercent": ((float(hist["Close"].iloc[-1]) - float(hist["Open"].iloc[-1])) / float(hist["Open"].iloc[-1]) * 100) if float(hist["Open"].iloc[-1]) != 0 else 0.0,
        }
        # 盘后交易数据（部分股票无盘后交易）
        if info.get("postMarketPrice") is not None:
            result["afterHours"] = float(info["postMarketPrice"])
        if info.get("postMarketChangePercent") is not None:
            result["afterHoursChangePercent"] = float(info["postMarketChangePercent"])

        return json.dumps(result)
    except Exception as e:
        return json.dumps({"error": str(e)})

def get_stock_list() -> str:
    """获取股票列表"""
    try:
        # 添加随机延迟
        time.sleep(random.uniform(2, 4))
        
        # 获取主要指数的成分股
        indices = {
            "SPY": "S&P 500",
            "QQQ": "NASDAQ 100",
            "DIA": "Dow Jones"
        }
        
        all_symbols = set()
        for symbol, name in indices.items():
            try:
                # 获取ETF持仓
                etf = yf.Ticker(symbol)
                holdings = safe_yfinance_request(etf.holdings)
                if holdings is not None:
                    for holding in holdings:
                        all_symbols.add(holding['symbol'])
                time.sleep(random.uniform(2, 4))  # 添加随机延迟
            except Exception as e:
                print(f"Error getting holdings for {symbol}: {str(e)}")
                continue
        
        return json.dumps(list(all_symbols))
    except Exception as e:
        return json.dumps({"error": str(e)})

def get_daily_kline(symbol: str, days: int = 7) -> str:
    """获取日K线数据"""
    try:
        time.sleep(random.uniform(2, 4))
        stock = yf.Ticker(symbol)
        hist = safe_yfinance_request(stock.history, period=str(days) + "d")
        if hist.empty:
            return json.dumps({"error": f"No historical data found for {symbol}"})
        kline_data = {"symbol": symbol, "items": []}
        for index, row in hist.iterrows():
            idx_ts = index if index.tz is not None else index.tz_localize("America/New_York")
            item = {
                "time": int(idx_ts.timestamp() * 1000),
                "timeString": index.strftime("%Y-%m-%d"),
                "open": float(row['Open']),
                "high": float(row['High']),
                "low": float(row['Low']),
                "close": float(row['Close']),
                "volume": int(row['Volume']),
                "amount": float(row['Close'] * row['Volume']),
                "changePercent": ((float(row['Close']) - float(row['Open'])) / float(row['Open']) * 100) if float(row['Open']) != 0 else 0.0
            }
            kline_data["items"].append(item)
        return json.dumps(kline_data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def get_daily_kline_range(symbol: str, start_date: str, end_date: str) -> str:
    """获取指定日期范围内的日K线数据"""
    try:
        time.sleep(random.uniform(2, 4))
        stock = yf.Ticker(symbol)
        hist = safe_yfinance_request(stock.history, start=start_date, end=end_date)
        if hist.empty:
            return json.dumps({
                "code": "NOT_FOUND",
                "symbol": symbol,
                "message": f"No data for {symbol} in range {start_date}~{end_date}",
                "source": "yfinance"
            })
        kline_data = {"symbol": symbol, "items": []}
        for index, row in hist.iterrows():
            idx_ts = index if index.tz is not None else index.tz_localize("America/New_York")
            item = {
                "time": int(idx_ts.timestamp() * 1000),
                "timeString": index.strftime("%Y-%m-%d"),
                "open": float(row['Open']),
                "high": float(row['High']),
                "low": float(row['Low']),
                "close": float(row['Close']),
                "volume": int(row['Volume']),
                "amount": float(row['Close'] * row['Volume']),
                "changePercent": ((float(row['Close']) - float(row['Open'])) / float(row['Open']) * 100) if float(row['Open']) != 0 else 0.0
            }
            kline_data["items"].append(item)
        return json.dumps(kline_data)
    except Exception as e:
        err_str = str(e)
        if "404" in err_str or "not found" in err_str.lower():
            code = "NOT_FOUND"
        elif "NaN" in err_str or "Non-standard token" in err_str:
            code = "PARSE_ERROR"
        else:
            code = "UNKNOWN_ERROR"
        return json.dumps({
            "code": code,
            "symbol": symbol,
            "message": err_str,
            "source": "yfinance"
        })


def get_batch_kline(symbols: str, period: str, count: int) -> str:
    """获取批量K线数据"""
    try:
        symbol_list = symbols.split(',')
        result = []
        
        for symbol in symbol_list:
            kline_data = json.loads(get_daily_kline(symbol))
            if "error" not in kline_data:
                result.append(kline_data)
            time.sleep(random.uniform(2, 4))  # 添加随机延迟
        
        return json.dumps(result)
    except Exception as e:
        return json.dumps({"error": str(e)})

def scan_stocks(market: str, limit: int, min_price: str = "", max_price: str = "") -> str:
    """扫描股票"""
    try:
        # 添加随机延迟
        time.sleep(random.uniform(2, 4))
        
        # 获取主要指数的成分股
        indices = {
            "US": ["SPY", "QQQ", "DIA"],  # S&P 500, NASDAQ 100, Dow Jones
            "HK": ["2800.HK"],  # Hang Seng Index
            "CN": ["000001.SS", "399001.SZ"]  # Shanghai Composite, Shenzhen Component
        }
        
        if market not in indices:
            return json.dumps({"error": f"Unsupported market: {market}"})
        
        all_symbols = set()
        for index in indices[market]:
            try:
                # 获取ETF持仓
                etf = yf.Ticker(index)
                holdings = safe_yfinance_request(etf.holdings)
                if holdings is not None:
                    for holding in holdings:
                        all_symbols.add(holding['symbol'])
                time.sleep(random.uniform(2, 4))  # 添加随机延迟
            except Exception as e:
                print(f"Error getting holdings for {index}: {str(e)}")
                continue
        
        # 获取股票价格
        stock_prices = {}
        for symbol in all_symbols:
            try:
                stock = yf.Ticker(symbol)
                hist = safe_yfinance_request(stock.history, period="1d")
                if not hist.empty:
                    price = float(hist["Close"].iloc[-1])
                    if (not min_price or price >= float(min_price)) and \
                       (not max_price or price <= float(max_price)):
                        stock_prices[symbol] = price
                time.sleep(random.uniform(2, 4))  # 添加随机延迟
            except Exception as e:
                print(f"Error getting price for {symbol}: {str(e)}")
                continue
        
        # 按价格排序并限制数量
        sorted_symbols = sorted(stock_prices.items(), key=lambda x: x[1], reverse=True)
        result = [symbol for symbol, _ in sorted_symbols[:limit]]
        
        return json.dumps(result)
    except Exception as e:
        return json.dumps({"error": str(e)})

def main():
    if len(sys.argv) < 2:
        print(json.dumps({"error": "No command specified"}))
        sys.exit(1)
    
    command = sys.argv[1]
    
    if command == "get_stock_info":
        if len(sys.argv) < 3:
            print(json.dumps({"error": "Symbol not specified"}))
            sys.exit(1)
        print(get_stock_info(sys.argv[2]))
    
    elif command == "get_stock_list":
        print(get_stock_list())
    
    elif command == "get_daily_kline":
        if len(sys.argv) < 3:
            print(json.dumps({"error": "Symbol not specified"}))
            sys.exit(1)
        days = int(sys.argv[3]) if len(sys.argv) > 3 else 7
        print(get_daily_kline(sys.argv[2], days))

    elif command == "get_daily_kline_range":
        if len(sys.argv) < 5:
            print(json.dumps({"error": "Missing parameters: symbol start_date end_date"}))
            sys.exit(1)
        print(get_daily_kline_range(sys.argv[2], sys.argv[3], sys.argv[4]))
    
    elif command == "get_batch_kline":
        if len(sys.argv) < 5:
            print(json.dumps({"error": "Missing parameters"}))
            sys.exit(1)
        print(get_batch_kline(sys.argv[2], sys.argv[3], int(sys.argv[4])))
    
    elif command == "scan_stocks":
        if len(sys.argv) < 3:
            print(json.dumps({"error": "Market not specified"}))
            sys.exit(1)
        market = sys.argv[2]
        limit = int(sys.argv[3]) if len(sys.argv) > 3 else 20
        min_price = sys.argv[4] if len(sys.argv) > 4 else ""
        max_price = sys.argv[5] if len(sys.argv) > 5 else ""
        print(scan_stocks(market, limit, min_price, max_price))
    
    else:
        print(json.dumps({"error": f"Unknown command: {command}"}))

if __name__ == "__main__":
    main() 