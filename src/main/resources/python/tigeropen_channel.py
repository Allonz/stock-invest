# -*- coding: utf-8 -*-
"""
Tiger OpenAPI (tigeropen) CLI for Java: market scanner + daily bars.
Credentials: TIGEROPEN_TIGER_ID, TIGEROPEN_ACCOUNT, TIGEROPEN_PRIVATE_KEY, TIGEROPEN_LICENSE (optional).
"""
from __future__ import annotations

import json
import logging
import sys

# Reduce noise when invoked from Java
logging.getLogger("tiger_openapi").setLevel(logging.CRITICAL)


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


def _cmd_bars(client, symbol: str, lim: int):
    import math

    from tigeropen.common.consts import BarPeriod

    df = client.get_bars(symbol, period=BarPeriod.DAY, limit=int(lim))
    items = []
    if df is not None and not df.empty:
        for _, row in df.iterrows():
            vol = row.get("volume")
            if vol is None or (isinstance(vol, float) and math.isnan(vol)):
                vol = 0
            amt = row.get("amount")
            if amt is None or (isinstance(amt, float) and math.isnan(amt)):
                amt = 0.0
            t = int(row["time"])
            items.append(
                {
                    "symbol": str(row.get("symbol", symbol)),
                    "time": t,
                    "timeString": "",
                    "open": float(row["open"]),
                    "high": float(row["high"]),
                    "low": float(row["low"]),
                    "close": float(row["close"]),
                    "volume": int(vol),
                    "amount": float(amt),
                }
            )
    items.sort(key=lambda x: x["time"], reverse=True)
    print(json.dumps({"symbol": symbol, "items": items}))


def main():
    if len(sys.argv) < 2:
        print(json.dumps({"error": "usage: tigeropen_channel.py scan|bars ..."}))
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
            print(json.dumps({"error": "bars needs symbol limit"}))
            sys.exit(2)
        _cmd_bars(client, sys.argv[2], int(sys.argv[3]))
    else:
        print(json.dumps({"error": "unknown command"}))
        sys.exit(2)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        sys.stderr.write(str(e) + "\n")
        sys.exit(1)
