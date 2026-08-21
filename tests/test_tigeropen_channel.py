"""
PY-TO-001~005: 测试 tigeropen_channel._cmd_bars / _cmd_afterhours_bars / _cmd_bars_range (Mock 模式)
"""
import sys
import json
import pytest
from datetime import datetime
from unittest.mock import patch, MagicMock
from zoneinfo import ZoneInfo
import pandas as pd
import numpy as np

sys.path.insert(0, "src/main/resources/python")
from tigeropen_channel import _cmd_bars, _cmd_afterhours_bars, _cmd_bars_range

NY_TZ = ZoneInfo("America/New_York")


# ---------- helpers ----------

def _build_bars_df(symbol: str = "AAPL", n: int = 3):
    """Build a DataFrame matching tigeropen get_bars output."""
    data = []
    for i in range(n):
        data.append({
            "symbol": symbol,
            "time": 1000000000000 + i * 86400000,
            "open": 150.0 + i,
            "high": 152.0 + i,
            "low": 149.0 + i,
            "close": 151.0 + i,
            "volume": 1_000_000 + i * 1000,
            "amount": 151_000_000.0 + i,
        })
    df = pd.DataFrame(data)
    df = df.sort_values("time", ascending=True).reset_index(drop=True)
    return df


# ===================================================================
# PY-TO-001: _cmd_bars 正常返回数据
# ===================================================================
def test_py_to_001_bars_normal():
    """PY-TO-001: _cmd_bars 正常返回 kline 数据"""
    mock_client = MagicMock()
    df = _build_bars_df("AAPL", 3)
    mock_client.get_bars.return_value = df

    with patch("builtins.print") as mock_print:
        _cmd_bars(mock_client, "AAPL", 3)

    # Verify print was called
    assert mock_print.call_count >= 1
    # Get the printed JSON
    printed_json = mock_print.call_args[0][0]
    result = json.loads(printed_json)

    assert result["symbol"] == "AAPL"
    assert len(result["items"]) == 3
    # Items should be sorted by time descending
    assert result["items"][0]["time"] > result["items"][1]["time"]
    # Verify data fields
    item = result["items"][0]
    assert "open" in item
    assert "high" in item
    assert "low" in item
    assert "close" in item
    assert "volume" in item
    assert "amount" in item


# ===================================================================
# PY-TO-002: _cmd_bars 无数据 (df is None)
# ===================================================================
def test_py_to_002_bars_no_data():
    """PY-TO-002: _cmd_bars 返回空数据"""
    mock_client = MagicMock()
    mock_client.get_bars.return_value = None

    with patch("builtins.print") as mock_print:
        _cmd_bars(mock_client, "AAPL", 5)

    printed_json = mock_print.call_args[0][0]
    result = json.loads(printed_json)

    assert result["symbol"] == "AAPL"
    assert len(result["items"]) == 0


# ===================================================================
# PY-TO-003: _cmd_afterhours_bars 正常返回盘后数据
# ===================================================================
@patch("tigeropen.common.consts.TradingSession")
def test_py_to_003_afterhours_bars_normal(mock_trading_session):
    """PY-TO-003: _cmd_afterhours_bars 正常返回盘后数据"""
    # tigeropen 3.5.8 的 TradingSession 成员名为 AfterHours（无 AFTER_HOURS 别名），
    # 脚本经 _after_hours_trade_session() 兼容解析；测试按库真实成员打桩。
    mock_trading_session.AfterHours = "AfterHours_mock"

    mock_client = MagicMock()
    df = _build_bars_df("MSFT", 2)
    mock_client.get_bars.return_value = df

    with patch("builtins.print") as mock_print:
        _cmd_afterhours_bars(mock_client, "MSFT", 2)

    printed_json = mock_print.call_args[0][0]
    result = json.loads(printed_json)

    assert result["symbol"] == "MSFT"
    assert len(result["items"]) == 2
    # Check that client.get_bars was called with trade_session
    mock_client.get_bars.assert_called_once()
    kwargs = mock_client.get_bars.call_args[1]
    assert "trade_session" in kwargs


# ===================================================================
# PY-TO-004: _cmd_afterhours_bars 无盘后数据
# ===================================================================
@patch("tigeropen.common.consts.TradingSession")
def test_py_to_004_afterhours_bars_no_data(mock_trading_session):
    """PY-TO-004: _cmd_afterhours_bars 空数据"""
    mock_trading_session.AfterHours = "AfterHours_mock"

    mock_client = MagicMock()
    mock_client.get_bars.return_value = pd.DataFrame()  # empty DataFrame

    with patch("builtins.print") as mock_print:
        _cmd_afterhours_bars(mock_client, "TSLA", 5)

    printed_json = mock_print.call_args[0][0]
    result = json.loads(printed_json)

    assert result["symbol"] == "TSLA"
    assert len(result["items"]) == 0


# ---------- 日期范围模式 helpers ----------

def _build_range_df(symbol: str, dates, hour: int = 16, minute: int = 0):
    """按美东时区构造指定日期 16:00（或指定时刻）的 bar —— 模拟"日线 bar 时间戳在盘中"。"""
    data = []
    for i, d in enumerate(dates):
        ts = datetime(int(d[:4]), int(d[5:7]), int(d[8:10]), hour, minute, tzinfo=NY_TZ)
        data.append({
            "symbol": symbol,
            "time": int(ts.timestamp() * 1000),
            "open": 150.0 + i,
            "high": 152.0 + i,
            "low": 149.0 + i,
            "close": 151.0 + i,
            "volume": 1_000_000 + i * 1000,
            "amount": 151_000_000.0 + i,
        })
    return pd.DataFrame(data)


# ===================================================================
# PY-TO-005: 评审 F-1 — bars range 端界扩张：end_time = end+1 天 00:00 ET，
#            目标交易日 bar（时间戳在盘中）必须被包含
# ===================================================================
@patch("tigeropen.common.consts.TradingSession")
def test_py_to_005_bars_range_end_boundary_includes_target_day(mock_trading_session):
    """PY-TO-005: F-1 边界 —— end_ms 取 end+1 天 00:00 ET，目标日不再被结构性排除"""
    mock_trading_session.AfterHours = "AfterHours_mock"

    mock_client = MagicMock()
    # 日K：2026-08-13 与 2026-08-14 各一条（16:00 ET —— 盘中时间戳 > 当日 00:00）
    daily_df = _build_range_df("AAPL", ["2026-08-13", "2026-08-14"])
    # 盘后分钟 bar：目标日 19:30 ET 一条
    ah_df = _build_range_df("AAPL", ["2026-08-14"], hour=19, minute=30)
    mock_client.get_bars.side_effect = [daily_df, ah_df]

    with patch("builtins.print") as mock_print:
        _cmd_bars_range(mock_client, "AAPL", "2026-08-13", "2026-08-14")

    # 两次 get_bars（日K + 盘后）的 begin/end 均应为 [13 日 00:00, 15 日 00:00) ET
    assert mock_client.get_bars.call_count == 2, "daily + after-hours calls expected"
    expected_begin_ms = int(datetime(2026, 8, 13, 0, 0, tzinfo=NY_TZ).timestamp() * 1000)
    expected_end_ms = int(datetime(2026, 8, 15, 0, 0, tzinfo=NY_TZ).timestamp() * 1000)
    for call in mock_client.get_bars.call_args_list:
        assert call.kwargs["begin_time"] == expected_begin_ms, call
        assert call.kwargs["end_time"] == expected_end_ms, \
            f"end_time must be end+1 day 00:00 ET (was {call.kwargs['end_time']})"

    printed_json = mock_print.call_args[0][0]
    result = json.loads(printed_json)

    # 目标日（2026-08-14）必须出现在结果中，且合并了盘后价
    dates = [datetime.fromtimestamp(it["time"] / 1000, tz=NY_TZ).strftime("%Y-%m-%d")
             for it in result["items"]]
    assert "2026-08-14" in dates, f"target day must be included in range query: {dates}"
    target = next(it for it in result["items"]
                  if datetime.fromtimestamp(it["time"] / 1000, tz=NY_TZ).strftime("%Y-%m-%d") == "2026-08-14")
    assert target.get("afterHours") == 151.0, "after-hours close must be merged for target day"
