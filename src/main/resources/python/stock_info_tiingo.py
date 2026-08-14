#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Tiingo 数据源 Python SDK 实现（替换原 Java HTTP client）。

功能与 Java TiingoRestClient 保持一致：
  - get_daily_kline_range(symbol, start_date, end_date)  → 对应 fetchDailyBars(symbol, start, end)
  - get_daily_kline(symbol, days)                        → 对应 fetchDailyBars(symbol, N)
  - get_batch_kline(symbols, period, count)              → 对应 getBatchKline（循环单查）

低价股扫描（IEX /iex/ 端点）已确认用不到，Java 侧空实现，脚本不提供。

依赖 tiingo SDK（PyPI 包名 tiingo，无需 pandas，使用 get_ticker_price）。
API key 从环境变量 TIINGO_API_KEY 读取（Java 侧经 PythonScriptExecutor 注入）。
输出 JSON 格式与 yfinance/twelvedata 脚本一致：
  {"symbol": ..., "items": [{"time": ms, "timeString": "YYYY-MM-DD", "open": .., "high": .., "low": .., "close": .., "volume": .., "amount": ..}]}
"""
import json
import os
import random
import sys
import time
from datetime import datetime

import pytz

API_KEY = os.environ.get("TIINGO_API_KEY", "")

NEW_YORK = pytz.timezone("America/New_York")

# 全局速率限制（2026-08-14）：对齐 yfinance 脚本的限速设计。
# 背景：补缺字段增补时连续快速调用 tiingo API 打爆小时配额（429 hourly request allocation）。
# 每个请求前随机延迟 1~2s，控制调用频率。
MIN_REQUEST_INTERVAL = 1.0


def _rate_limit():
    """每个 API 请求前的随机延迟，防触发 tiingo 小时级限流（429）。"""
    time.sleep(random.uniform(MIN_REQUEST_INTERVAL, MIN_REQUEST_INTERVAL + 1.0))


def _client():
    if not API_KEY:
        raise ValueError("TIINGO_API_KEY environment variable is not set")
    from tiingo import TiingoClient
    return TiingoClient({"api_key": API_KEY})


def _to_items(rows):
    """把 SDK 返回的 list[dict] 转成与 yfinance 脚本一致的 items。"""
    items = []
    prev_close = None
    for v in rows:
        date_text = str(v.get("date", ""))[:10]
        if not date_text:
            continue
        try:
            dt_aware = NEW_YORK.localize(datetime.strptime(date_text, "%Y-%m-%d"))
        except ValueError:
            continue
        close = float(v.get("close") or 0)
        volume = int(v.get("volume") or 0)
        # changePercent：相邻交易日计算（升序，首行无前值）
        if prev_close is not None and prev_close != 0:
            change_pct = (close - prev_close) / prev_close * 100.0
        else:
            change_pct = None
        prev_close = close
        items.append({
            "time": int(dt_aware.timestamp() * 1000),
            "timeString": date_text,
            "open": float(v.get("open") or 0),
            "high": float(v.get("high") or 0),
            "low": float(v.get("low") or 0),
            "close": close,
            "volume": volume,
            "amount": round(close * volume, 2),
            "changePercent": change_pct,
        })
    return items


def get_daily_kline_range(symbol: str, start_date: str, end_date: str) -> str:
    """按日期范围获取日K线（对应 Java fetchDailyBars(symbol, start, end)）。

    输出最新在前（与 Java KLineDataUtils.sortItemsNewestFirst 一致）。
    """
    _rate_limit()
    try:
        rows = _client().get_ticker_price(symbol, startDate=start_date, endDate=end_date)
        items = _to_items(rows)
        items.reverse()
        return json.dumps({"symbol": symbol, "items": items})
    except Exception as e:
        return json.dumps({"error": str(e)})


def get_daily_kline(symbol: str, days: str = "30") -> str:
    """获取最近 N 天日K线（对应 Java fetchDailyBars(symbol, N)）。

    注意：SDK get_ticker_price 不传日期默认只返回最近 1 条（实测），
    因此显式传宽松日期窗口（start = today - N*2 - 10 天），本地再截取最后 N 条。
    """
    _rate_limit()
    try:
        try:
            n = int(days)
        except ValueError:
            n = 30
        from datetime import timedelta
        end = datetime.now().date()
        start = end - timedelta(days=n * 2 + 10)
        rows = _client().get_ticker_price(
            symbol, startDate=start.isoformat(), endDate=end.isoformat())
        items = _to_items(rows)[-n:]
        items.reverse()
        return json.dumps({"symbol": symbol, "items": items})
    except Exception as e:
        return json.dumps({"error": str(e)})


def get_batch_kline(symbols: str, period: str = "daily", count: str = "30") -> str:
    """批量获取日K线（对应 Java getBatchKline，循环单查）。"""
    try:
        try:
            n = int(count)
        except ValueError:
            n = 30
        from datetime import timedelta
        end = datetime.now().date()
        start = end - timedelta(days=n * 2 + 10)
        result = []
        for sym in symbols.split(","):
            sym = sym.strip()
            if not sym:
                continue
            try:
                _rate_limit()
                rows = _client().get_ticker_price(
                    sym, startDate=start.isoformat(), endDate=end.isoformat())
                items = _to_items(rows)[-n:]
                items.reverse()
                result.append({"symbol": sym, "items": items})
            except Exception as e:
                result.append({"symbol": sym, "items": [], "error": str(e)})
        return json.dumps(result)
    except Exception as e:
        return json.dumps({"error": str(e)})


def main():
    if len(sys.argv) < 2:
        print(json.dumps({"error": "usage: stock_info_tiingo.py <command> ..."}))
        sys.exit(2)
    cmd = sys.argv[1]
    if cmd == "get_daily_kline_range":
        if len(sys.argv) < 5:
            print(json.dumps({"error": "get_daily_kline_range needs symbol start_date end_date"}))
            sys.exit(2)
        print(get_daily_kline_range(sys.argv[2], sys.argv[3], sys.argv[4]))
    elif cmd == "get_daily_kline":
        symbol = sys.argv[2] if len(sys.argv) > 2 else ""
        days = sys.argv[3] if len(sys.argv) > 3 else "30"
        print(get_daily_kline(symbol, days))
    elif cmd == "get_batch_kline":
        symbols = sys.argv[2] if len(sys.argv) > 2 else ""
        period = sys.argv[3] if len(sys.argv) > 3 else "daily"
        count = sys.argv[4] if len(sys.argv) > 4 else "30"
        print(get_batch_kline(symbols, period, count))
    else:
        print(json.dumps({"error": f"unknown command: {cmd}"}))
        sys.exit(2)


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as e:
        print(json.dumps({"error": str(e)}))
        sys.exit(1)
