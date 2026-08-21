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
        # yfinance >=0.2.x: Ticker.info 是 dict 属性，不是方法；旧代码 stock.info() 会抛
        # "'dict' object is not callable"，导致 get_stock_info 永远失败（盘后价/涨跌幅全空）。
        # 修正为直接取属性（2026-08-13）。
        info = stock.info
        
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
            # 涨跌幅（%）取自 yfinance info 的 regularMarketChangePercent；
            # 评审 F-11：缺失时置 null（键保留）——"无数据"与"零涨跌"不再混为一谈，
            # 下游可区分（Java StockInfo.changePercent 反序列化为 null 而非 0.0）
            "changePercent": float(info["regularMarketChangePercent"])
            if info.get("regularMarketChangePercent") is not None else None,
        }
        # 盘后交易数据（部分股票无盘后交易）
        if info.get("postMarketPrice") is not None:
            result["afterHours"] = float(info["postMarketPrice"])
        if info.get("postMarketChangePercent") is not None:
            result["afterHoursChangePercent"] = float(info["postMarketChangePercent"])

        return json.dumps(result)
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
        prev_close = None
        for index, row in hist.iterrows():
            idx_ts = index if index.tz is not None else index.tz_localize("America/New_York")
            close = float(row['Close'])
            # changePercent：相邻交易日计算 (close[i]-close[i-1])/close[i-1]*100（升序，首行无前值）
            if prev_close is not None and prev_close != 0:
                change_pct = (close - prev_close) / prev_close * 100.0
            else:
                change_pct = None
            prev_close = close
            item = {
                "time": int(idx_ts.timestamp() * 1000),
                "timeString": index.strftime("%Y-%m-%d"),
                "open": float(row['Open']),
                "high": float(row['High']),
                "low": float(row['Low']),
                "close": close,
                "volume": int(row['Volume']),
                "amount": float(row['Close'] * row['Volume']),
                "changePercent": change_pct,
            }
            kline_data["items"].append(item)
        return json.dumps(kline_data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def get_after_hours_price(symbol: str, trade_date: str) -> str:
    """按交易日获取盘后收盘价。

    yfinance 的 info.postMarketPrice 是实时字段（仅当前盘后时段有值，盘中为 None，
    且无法查历史日期）。改用 history(interval='1m', prepost=True) 拉取该交易日
    4:00-20:00 ET 的分钟数据，取 16:00-20:00 ET（盘后时段）最后一条 close 作为
    该交易日盘后价（2026-08-13 新增，按交易日精确查询）。

    可用性边界（Yahoo 分钟数据保留限制）：
    - 近 30 天内：1m 数据可用，本函数按此查询
    - 30~60 天：1m 不可用，需降级到 15m/30m/60m 间隔（同样可取 16:00-20:00 段）
    - 超过 60 天：Yahoo 分钟数据不保留，返回 null（盘后价无法回溯）
    注意：盘后价查询依赖当日盘后交易存在，无盘后交易的股票/日期返回 null 属正常。

    返回 {"afterHours": <close>} 或 {"afterHours": null}（无盘后数据）。
    """
    try:
        import pytz
        from datetime import datetime, timedelta

        stock = yf.Ticker(symbol)
        start_dt = datetime.strptime(trade_date, "%Y-%m-%d")
        end_dt = start_dt + timedelta(days=1)
        start_str = start_dt.strftime("%Y-%m-%d")
        end_str = end_dt.strftime("%Y-%m-%d")
        # 分钟数据保留限制：1m 仅近 30 天 → 30 天外降级 15m/30m/60m（60m 保留期更长）
        for interval in ("1m", "15m", "30m", "60m"):
            try:
                hist = stock.history(start=start_str, end=end_str, interval=interval, prepost=True)
            except Exception:
                continue
            if hist is None or hist.empty:
                continue
            # 统一转美东时区（Yahoo 分钟数据默认 UTC）
            try:
                hist.index = hist.index.tz_convert("America/New_York")
            except Exception:
                pass
            # 盘后时段 16:00-20:00 ET，取最后一条 close
            ah = hist.between_time("16:00", "20:00")
            if not ah.empty:
                ah_close = float(ah["Close"].iloc[-1])
                # 盘后涨跌幅直算：常规时段收盘 = 9:30-15:59 最后一根 close。
                # 注意不能含 16:00 —— Yahoo 分钟数据 16:00 那根已属盘后时段（16:00-16:15 等），
                # 取到它会把盘后第一根当收盘 → 涨跌幅≈0 失真
                reg = hist.between_time("09:30", "15:59")
                pct = None
                if not reg.empty:
                    reg_close = float(reg["Close"].iloc[-1])
                    if reg_close != 0:
                        pct = (ah_close - reg_close) / reg_close * 100.0
                result = {"afterHours": ah_close}
                if pct is not None:
                    result["afterHoursChangePercent"] = pct
                return json.dumps(result)
        return json.dumps({"afterHours": None})
    except Exception as e:
        return json.dumps({"error": str(e)})


def get_daily_kline_range(symbol: str, start_date: str, end_date: str) -> str:
    """获取指定日期范围内的日K线数据"""
    try:
        time.sleep(random.uniform(2, 4))
        stock = yf.Ticker(symbol)
        # yfinance history(start, end) 的 end 为排他（不含当天）→ 内部 +1 天，保证含目标日
        from datetime import datetime, timedelta
        end_plus = (datetime.strptime(end_date, "%Y-%m-%d") + timedelta(days=1)).strftime("%Y-%m-%d")
        hist = safe_yfinance_request(stock.history, start=start_date, end=end_plus)
        if hist.empty:
            return json.dumps({
                "code": "NOT_FOUND",
                "symbol": symbol,
                "message": f"No data for {symbol} in range {start_date}~{end_date}",
                "source": "yfinance"
            })
        kline_data = {"symbol": symbol, "items": []}
        prev_close = None
        for index, row in hist.iterrows():
            idx_ts = index if index.tz is not None else index.tz_localize("America/New_York")
            close = float(row['Close'])
            # changePercent：相邻交易日计算 (close[i]-close[i-1])/close[i-1]*100（升序，首行无前值）
            if prev_close is not None and prev_close != 0:
                change_pct = (close - prev_close) / prev_close * 100.0
            else:
                change_pct = None
            prev_close = close
            item = {
                "time": int(idx_ts.timestamp() * 1000),
                "timeString": index.strftime("%Y-%m-%d"),
                "open": float(row['Open']),
                "high": float(row['High']),
                "low": float(row['Low']),
                "close": close,
                "volume": int(row['Volume']),
                "amount": float(row['Close'] * row['Volume']),
                "changePercent": change_pct,
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

    elif command == "get_after_hours_price":
        if len(sys.argv) < 4:
            print(json.dumps({"error": "Missing parameters: symbol trade_date"}))
            sys.exit(1)
        print(get_after_hours_price(sys.argv[2], sys.argv[3]))
    
    elif command == "get_batch_kline":
        if len(sys.argv) < 5:
            print(json.dumps({"error": "Missing parameters"}))
            sys.exit(1)
        print(get_batch_kline(sys.argv[2], sys.argv[3], int(sys.argv[4])))
    
    else:
        print(json.dumps({"error": f"Unknown command: {command}"}))

if __name__ == "__main__":
    main() 