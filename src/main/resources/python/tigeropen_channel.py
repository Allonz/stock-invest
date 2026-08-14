# -*- coding: utf-8 -*-
"""
Tiger OpenAPI (tigeropen) CLI for Java: market scanner + daily bars + calendar.
Credentials: TIGEROPEN_TIGER_ID, TIGEROPEN_ACCOUNT, TIGEROPEN_PRIVATE_KEY, TIGEROPEN_LICENSE (optional).

错误协议（P2-15）：所有命令失败时向 stdout 输出 {"error": {"code": ..., "message": ...}} 后 exit(1)；
账户级错误（4000 / permission / quota）code 置为 ACCOUNT_LEVEL，Java 侧据此抛 ACCOUNT_LEVEL（P1-5 联动）。
"""
from __future__ import annotations

import json
import logging
import sys
from zoneinfo import ZoneInfo

# P2-16：盘后合并与日 K 行统一使用美东时区（美股 bar 的日期归属），
# 避免美东 20:00 后的盘后 bar 落入 UTC 次日导致盘后价并入错误日期或丢失
NY_TZ = ZoneInfo("America/New_York")

# Reduce noise when invoked from Java
logging.getLogger("tiger_openapi").setLevel(logging.CRITICAL)


def _after_hours_trade_session():
    """tigeropen 3.5.8 的 TradingSession 枚举成员为 AfterHours（无 AFTER_HOURS 别名）。

    兼容两种命名：优先取库真实成员 AfterHours，回退 AFTER_HOURS，避免 AttributeError。
    返回 None 时上层调用会因缺参而失败，这里保证不抛属性错误。
    """
    from tigeropen.common.consts import TradingSession

    return getattr(TradingSession, "AfterHours", None) or getattr(TradingSession, "AFTER_HOURS", None)


def _fetch_after_hours_close(client, symbol: str, limit: int):
    """获取最近 limit 个交易日的盘后收盘价（按美东日期聚合）。

    tigeropen 官方约束：trade_session=AfterHours 时 period 必须为分钟级
    （BarPeriod.ONE_MINUTE ~ FOUR_HOURS），BarPeriod.DAY 会导致 bars 显示不正确
    或返回空。因此这里用 ONE_MINUTE 拉盘后分钟 bar，按美东日期取当日最后一条
    分钟的 close 作为该交易日盘后价（2026-08-13 修正）。

    返回 { 'YYYY-MM-DD': close } 或空 dict。
    """
    from datetime import datetime

    from tigeropen.common.consts import BarPeriod

    try:
        ah_df = client.get_bars(
            symbol,
            period=BarPeriod.ONE_MINUTE,
            limit=int(limit),
            trade_session=_after_hours_trade_session(),
        )
    except Exception:
        return {}
    if ah_df is None or ah_df.empty:
        return {}
    ah_by_date = {}
    for _, row in ah_df.iterrows():
        try:
            t = int(row["time"])
        except (KeyError, TypeError, ValueError):
            continue
        d = datetime.fromtimestamp(t / 1000, tz=NY_TZ).strftime("%Y-%m-%d")
        # 同一交易日多条分钟 bar，取最后一条（最新价）
        ah_by_date[d] = float(row["close"])
    return ah_by_date


def _fetch_after_hours_close_range(client, symbol: str, begin: str, end: str):
    """按日期范围获取盘后收盘价（两日窗口，ONE_MINUTE 聚合，按美东日期取当日最后一分钟）。"""
    from datetime import datetime

    from tigeropen.common.consts import BarPeriod

    try:
        begin_ms = int(datetime.strptime(begin, "%Y-%m-%d").replace(tzinfo=NY_TZ).timestamp() * 1000)
        end_ms = int(datetime.strptime(end, "%Y-%m-%d").replace(tzinfo=NY_TZ).timestamp() * 1000)
        ah_df = client.get_bars(
            symbol,
            period=BarPeriod.ONE_MINUTE,
            begin_time=begin_ms,
            end_time=end_ms,
            limit=5000,
            trade_session=_after_hours_trade_session(),
        )
    except Exception:
        return {}
    if ah_df is None or ah_df.empty:
        return {}
    ah_by_date = {}
    for _, row in ah_df.iterrows():
        try:
            t = int(row["time"])
        except (KeyError, TypeError, ValueError):
            continue
        d = datetime.fromtimestamp(t / 1000, tz=NY_TZ).strftime("%Y-%m-%d")
        # 同一交易日多条分钟 bar，取最后一条（最新价）
        ah_by_date[d] = float(row["close"])
    return ah_by_date


def _error_payload(exc):
    """将异常转为统一错误 JSON 结构；账户级错误（权限/配额）标为 ACCOUNT_LEVEL。"""
    code = getattr(exc, "code", None)
    raw_msg = getattr(exc, "message", None)
    message = str(raw_msg) if raw_msg else str(exc)
    lowered = message.lower()
    if (code is not None and "4000" in str(code)) \
            or "4000" in message or "permission" in lowered \
            or "quota" in lowered or "配额" in message or "权限" in message:
        return {"code": "ACCOUNT_LEVEL", "message": message}
    return {"code": str(code) if code is not None else "PYTHON_ERROR", "message": message}


def _fail(exc):
    print(json.dumps({"error": _error_payload(exc)}))
    sys.exit(1)


def _client():
    from tigeropen.tiger_open_config import TigerOpenClientConfig
    from tigeropen.quote.quote_client import QuoteClient

    cfg = TigerOpenClientConfig()
    return QuoteClient(cfg, logger=logging.getLogger("tiger_openapi"), is_grab_permission=True)


def _cmd_scan(client, limit: int, min_p: float, max_p: float):
    from tigeropen.common.consts import Market
    from tigeropen.common.consts.filter_fields import StockField
    from tigeropen.quote.domain.filter import StockFilter

    page_size = max(int(limit), 50)
    filters = [StockFilter(StockField.CurPrice, filter_min=min_p, filter_max=max_p)]
    result = client.market_scanner(market=Market.US, filters=filters, page=0, page_size=page_size)
    if not result or not result.items:
        print(json.dumps([]))
        return
    symbols = []
    seen = set()
    for it in result.items:
        sym = it.symbol
        if sym and sym not in seen:
            seen.add(sym)
            symbols.append(sym)
        if len(symbols) >= limit:
            break
    print(json.dumps(symbols))


def _cmd_bars_range(client, symbol: str, begin: str, end: str):
    """按日期范围拉日K线（两日窗口：前一交易日 + 目标交易日）。

    用 get_bars 的 begin_time/end_time 精确限定范围，避免一次性拉 12 天。
    """
    import math
    from datetime import datetime

    from tigeropen.common.consts import BarPeriod

    begin_ms = int(datetime.strptime(begin, "%Y-%m-%d").replace(tzinfo=NY_TZ).timestamp() * 1000)
    end_ms = int(datetime.strptime(end, "%Y-%m-%d").replace(tzinfo=NY_TZ).timestamp() * 1000)
    df = client.get_bars(
        symbol,
        period=BarPeriod.DAY,
        begin_time=begin_ms,
        end_time=end_ms,
        limit=10,
    )
    items = []
    prev_close = None
    if df is not None and not df.empty:
        for _, row in df.iterrows():
            vol = row.get("volume")
            if vol is None or (isinstance(vol, float) and math.isnan(vol)):
                vol = 0
            amt = row.get("amount")
            if amt is None or (isinstance(amt, float) and math.isnan(amt)):
                amt = 0.0
            t = int(row["time"])
            close = float(row["close"])
            # changePercent：相邻交易日计算（升序，首行无前值）
            if prev_close is not None and prev_close != 0:
                change_pct = (close - prev_close) / prev_close * 100.0
            else:
                change_pct = None
            prev_close = close
            items.append(
                {
                    "symbol": str(row.get("symbol", symbol)),
                    "time": t,
                    "timeString": "",
                    "open": float(row["open"]),
                    "high": float(row["high"]),
                    "low": float(row["low"]),
                    "close": close,
                    "volume": int(vol),
                    "amount": float(amt),
                    "changePercent": change_pct,
                }
            )

    # 盘后 K 线：按日期范围合并（P2-16：统一用美东时区取日期 key）
    ah_by_date = _fetch_after_hours_close_range(client, symbol, begin, end)
    if ah_by_date:
        for item in items:
            t = item.get("time", 0)
            d = datetime.fromtimestamp(t / 1000, tz=NY_TZ).strftime("%Y-%m-%d")
            if d in ah_by_date:
                ah_close = ah_by_date[d]
                item["afterHours"] = ah_close
                # 盘后涨跌幅源直取：与当日收盘 close 相邻计算
                reg_close = item.get("close")
                if reg_close:
                    item["afterHoursChangePercent"] = (ah_close - reg_close) / reg_close * 100.0
    print(json.dumps({"symbol": symbol, "items": items}))


def _cmd_bars(client, symbol: str, lim: int):
    """日K线：兼容两种模式 —— lim（最近 N 根）或日期范围（begin/end YYYY-MM-DD）。"""
    import math
    from datetime import datetime

    from tigeropen.common.consts import BarPeriod

    df = client.get_bars(symbol, period=BarPeriod.DAY, limit=int(lim))
    items = []
    prev_close = None
    if df is not None and not df.empty:
        for _, row in df.iterrows():
            vol = row.get("volume")
            if vol is None or (isinstance(vol, float) and math.isnan(vol)):
                vol = 0
            amt = row.get("amount")
            if amt is None or (isinstance(amt, float) and math.isnan(amt)):
                amt = 0.0
            t = int(row["time"])
            close = float(row["close"])
            # changePercent：相邻交易日计算（升序，首行无前值）
            if prev_close is not None and prev_close != 0:
                change_pct = (close - prev_close) / prev_close * 100.0
            else:
                change_pct = None
            prev_close = close
            items.append(
                {
                    "symbol": str(row.get("symbol", symbol)),
                    "time": t,
                    "timeString": "",
                    "open": float(row["open"]),
                    "high": float(row["high"]),
                    "low": float(row["low"]),
                    "close": close,
                    "volume": int(vol),
                    "amount": float(amt),
                    "changePercent": change_pct,
                }
            )

    # 获取盘后 K 线，按日期合并到日 K 线中（P2-16：统一用美东时区取日期 key）
    # 2026-08-13：tigeropen 官方要求 trade_session 必须配分钟级 period，
    # 原 BarPeriod.DAY + trade_session 写法无效，改用 ONE_MINUTE 聚合盘后收盘价
    ah_by_date = _fetch_after_hours_close(client, symbol, int(lim))
    if ah_by_date:
        for item in items:
            t = item.get("time", 0)
            d = datetime.fromtimestamp(t / 1000, tz=NY_TZ).strftime("%Y-%m-%d")
            if d in ah_by_date:
                ah_close = ah_by_date[d]
                item["afterHours"] = ah_close
                # 盘后涨跌幅源直取：与当日收盘 close 相邻计算
                reg_close = item.get("close")
                if reg_close:
                    item["afterHoursChangePercent"] = (ah_close - reg_close) / reg_close * 100.0

    items.sort(key=lambda x: x["time"], reverse=True)
    print(json.dumps({"symbol": symbol, "items": items}))


def _cmd_afterhours_bars(client, symbol: str, lim: int):
    import math
    from datetime import datetime

    # 2026-08-13：tigeropen 官方要求 trade_session 必须配分钟级 period，
    # 原 BarPeriod.DAY + trade_session 写法无效，改用 ONE_MINUTE 聚合盘后收盘价
    ah_by_date = _fetch_after_hours_close(client, symbol, int(lim))
    items = []
    for d, close in sorted(ah_by_date.items(), reverse=True):
        t = int(datetime.strptime(d, "%Y-%m-%d").replace(tzinfo=NY_TZ).timestamp() * 1000)
        items.append(
            {
                "symbol": symbol,
                "time": t,
                "timeString": d,
                "open": close,
                "high": close,
                "low": close,
                "close": close,
                "volume": 0,
                "amount": 0.0,
            }
        )
    items.sort(key=lambda x: x["time"], reverse=True)
    print(json.dumps({"symbol": symbol, "items": items}))


def _cmd_calendar(client, market: str, date_str: str):
    """查询指定日期是否为交易日。"""
    from datetime import datetime, timedelta

    from tigeropen.common.consts import Market

    begin = date_str
    end = (datetime.strptime(date_str, "%Y-%m-%d") + timedelta(days=1)).strftime("%Y-%m-%d")

    calendars = client.get_trading_calendar(
        market=getattr(Market, market.upper()),
        begin_date=begin,
        end_date=end,
    )
    if not calendars:
        print(
            json.dumps(
                {
                    "tradingDay": False,
                    "source": "tigeropen",
                    "type": "HOLIDAY",
                    "date": date_str,
                }
            )
        )
        return
    cal = calendars[0]
    print(
        json.dumps(
            {
                "tradingDay": cal["type"] == "TRADING",
                "source": "tigeropen",
                "type": cal.get("type", ""),
                "date": date_str,
            }
        )
    )


def main():
    if len(sys.argv) < 2:
        print(json.dumps({"error": "usage: tigeropen_channel.py scan|bars|calendar ..."}))
        sys.exit(2)
    cmd = sys.argv[1]
    client = _client()
    if cmd == "scan":
        if len(sys.argv) < 5:
            print(json.dumps({"error": "scan needs limit min_price max_price"}))
            sys.exit(2)
        _cmd_scan(client, int(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4]))
    elif cmd == "bars":
        if len(sys.argv) < 4:
            print(json.dumps({"error": "bars needs symbol limit|begin end"}))
            sys.exit(2)
        if len(sys.argv) >= 5:
            # 日期范围模式：bars symbol begin end（两日窗口）
            _cmd_bars_range(client, sys.argv[2], sys.argv[3], sys.argv[4])
        else:
            _cmd_bars(client, sys.argv[2], int(sys.argv[3]))
    elif cmd == "afterhours_bars":
        if len(sys.argv) < 4:
            print(json.dumps({"error": "afterhours_bars needs symbol limit"}))
            sys.exit(2)
        _cmd_afterhours_bars(client, sys.argv[2], int(sys.argv[3]))
    elif cmd == "calendar":
        if len(sys.argv) < 4:
            print(json.dumps({"error": "calendar needs market date (e.g. US 2026-06-01)"}))
            sys.exit(2)
        _cmd_calendar(client, sys.argv[2], sys.argv[3])
    else:
        print(json.dumps({"error": "unknown command"}))
        sys.exit(2)


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        # 用法错误等已自行输出 error JSON 并退出，直接透传
        raise
    except Exception as e:
        # P2-15：失败统一输出 {"error": {"code", "message"}} 到 stdout 后 exit(1)
        _fail(e)
