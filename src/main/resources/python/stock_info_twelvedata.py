#!/usr/bin/env python
# -*- coding: utf-8 -*-

import sys
import json
import os
import time
import random
from datetime import datetime, timedelta
import pytz
from typing import List, Dict, Any, Optional
import urllib.request
import urllib.parse
import urllib.error

# TwelveData API base URL
TWELVEDATA_BASE_URL = "https://api.twelvedata.com"
API_KEY = os.environ.get("TWELVEDATA_API_KEY", "")

# Rate limiting
last_request_time = 0
min_request_interval = 8  # Free tier: 8 requests per minute


def rate_limit():
    global last_request_time
    current_time = time.time()
    elapsed = current_time - last_request_time
    if elapsed < min_request_interval:
        time.sleep(min_request_interval - elapsed + random.uniform(1, 3))
    last_request_time = time.time()


def api_request(endpoint: str, params: Dict[str, str]) -> Dict:
    """Make a request to TwelveData API."""
    if not API_KEY:
        raise Exception("TWELVEDATA_API_KEY environment variable not set")

    params["apikey"] = API_KEY
    url = f"{TWELVEDATA_BASE_URL}/{endpoint}?{urllib.parse.urlencode(params)}"
    
    rate_limit()
    
    try:
        req = urllib.request.Request(url)
        req.add_header("User-Agent", "StockInvest/1.0")
        with urllib.request.urlopen(req, timeout=30) as response:
            data = json.loads(response.read().decode("utf-8"))
            if "code" in data and data["code"] != 200:
                raise Exception(f"API error: {data.get('message', 'Unknown error')}")
            return data
    except urllib.error.HTTPError as e:
        error_body = e.read().decode("utf-8") if e.fp else ""
        raise Exception(f"HTTP {e.code}: {error_body}")
    except urllib.error.URLError as e:
        raise Exception(f"Connection error: {e.reason}")


def get_stock_info(symbol: str) -> str:
    """获取股票信息 (TwelveData API)."""
    try:
        data = api_request("quote", {"symbol": symbol, "dp": "2"})
        result = {
            "symbol": data.get("symbol", symbol),
            "name": data.get("name", symbol),
            "currentPrice": float(data.get("close", 0)),
            "openPrice": float(data.get("open", 0)),
            "highPrice": float(data.get("high", 0)),
            "lowPrice": float(data.get("low", 0)),
            "volume": int(data.get("volume", 0)),
            "change": float(data.get("change", 0)),
            "changePercent": float(data.get("percent_change", 0)),
        }
        return json.dumps(result)
    except Exception as e:
        return json.dumps({"error": str(e)})


def get_stock_list() -> str:
    """获取股票列表 (TwelveData API)."""
    try:
        data = api_request("stocks", {"exchange": "NYSE", "country": "United States"})
        stocks = data.get("data", [])
        symbols = [s["symbol"] for s in stocks[:100]]
        return json.dumps(symbols)
    except Exception as e:
        return json.dumps({"error": str(e)})


def get_daily_kline(symbol: str) -> str:
    """获取日K线数据 (TwelveData API)."""
    try:
        data = api_request("time_series", {
            "symbol": symbol,
            "interval": "1day",
            "outputsize": "7",
            "dp": "2"
        })
        values = data.get("values", [])
        items = []
        for v in reversed(values):
            dt = datetime.strptime(v["datetime"], "%Y-%m-%d")
            dt_aware = pytz.timezone("America/New_York").localize(dt)
            items.append({
                "time": int(dt_aware.timestamp() * 1000),
                "timeString": v["datetime"],
                "open": float(v["open"]),
                "high": float(v["high"]),
                "low": float(v["low"]),
                "close": float(v["close"]),
                "volume": int(v.get("volume", 0)),
                "amount": float(v["close"]) * int(v.get("volume", 0))
            })
        return json.dumps({"symbol": symbol, "items": items})
    except Exception as e:
        return json.dumps({"error": str(e)})


def get_daily_kline_range(symbol: str, start_date: str, end_date: str) -> str:
    """按日期范围获取K线数据 (TwelveData API)."""
    try:
        data = api_request("time_series", {
            "symbol": symbol,
            "interval": "1day",
            "start_date": start_date,
            "end_date": end_date,
            "dp": "2"
        })
        values = data.get("values", [])
        items = []
        for v in reversed(values):
            dt = datetime.strptime(v["datetime"], "%Y-%m-%d")
            dt_aware = pytz.timezone("America/New_York").localize(dt)
            items.append({
                "time": int(dt_aware.timestamp() * 1000),
                "timeString": v["datetime"],
                "open": float(v["open"]),
                "high": float(v["high"]),
                "low": float(v["low"]),
                "close": float(v["close"]),
                "volume": int(v.get("volume", 0)),
                "amount": float(v["close"]) * int(v.get("volume", 0))
            })
        return json.dumps({"symbol": symbol, "items": items})
    except Exception as e:
        err_str = str(e)
        code = "NOT_FOUND" if "404" in err_str else "UNKNOWN_ERROR"
        return json.dumps({
            "code": code,
            "symbol": symbol,
            "message": err_str,
            "source": "twelvedata"
        })


def get_batch_kline(symbols: str, period: str, count: int) -> str:
    """获取批量K线数据."""
    try:
        symbol_list = symbols.split(",")
        result = []
        for symbol in symbol_list:
            kline_data = json.loads(get_daily_kline(symbol))
            if "error" not in kline_data:
                result.append(kline_data)
        return json.dumps(result)
    except Exception as e:
        return json.dumps({"error": str(e)})


def scan_stocks(market: str, limit: int, min_price: str = "", max_price: str = "") -> str:
    """扫描股票 (TwelveData API)."""
    try:
        exchanges = {
            "US": ["NYSE", "NASDAQ"],
            "HK": ["HKEX"],
            "CN": ["SSE", "SZSE"]
        }
        if market not in exchanges:
            return json.dumps({"error": f"Unsupported market: {market}"})

        # Get stocks from exchanges
        all_symbols = []
        for exchange in exchanges[market]:
            try:
                data = api_request("stocks", {"exchange": exchange})
                stocks = data.get("data", [])
                all_symbols.extend([s["symbol"] for s in stocks[:50]])
            except Exception as e:
                print(f"Error fetching stocks for {exchange}: {str(e)}", file=sys.stderr)
                continue

        # Get prices and filter
        stock_prices = {}
        for symbol in all_symbols:
            try:
                info_json = get_stock_info(symbol)
                info = json.loads(info_json)
                if "error" not in info and info.get("currentPrice", 0) > 0:
                    price = info["currentPrice"]
                    if (not min_price or price >= float(min_price)) and \
                       (not max_price or price <= float(max_price)):
                        stock_prices[symbol] = price
            except Exception:
                continue

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
        print(get_daily_kline(sys.argv[2]))

    elif command == "get_daily_kline_range":
        if len(sys.argv) < 5:
            print(json.dumps({"error": "Symbol, start_date, end_date required"}))
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
