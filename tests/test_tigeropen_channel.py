"""
PY-TO-001~004: 测试 tigeropen_channel._cmd_bars 和 _cmd_afterhours_bars (Mock 模式)
"""
import sys
import json
import pytest
from unittest.mock import patch, MagicMock
import pandas as pd
import numpy as np

sys.path.insert(0, "src/main/resources/python")
from tigeropen_channel import _cmd_bars, _cmd_afterhours_bars


# ---------- helpers ----------

def _build_bars_df(symbol: str = "AAPL", n: int = 3):
    """Build a DataFrame matching tigeropen get_bars output."""
    data = []
    for i in range(n):
        data.append({
            "symbol": symbol,
            "time": 1000000000 + i * 86400,
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
    # 解决 TigerOpen SDK 中 TradingSession.AFTER_HOURS 不存在的问题
    # 实际 SDK 是 TradingSession.AfterHours
    mock_trading_session.AFTER_HOURS = "AfterHours_mock"
    # 保留 DAY 枚举以便 _cmd_bars 中的 import 能正常工作
    from tigeropen.common.consts import BarPeriod
    # BarPeriod 是独立的，不受影响

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
    mock_trading_session.AFTER_HOURS = "AfterHours_mock"

    mock_client = MagicMock()
    mock_client.get_bars.return_value = pd.DataFrame()  # empty DataFrame

    with patch("builtins.print") as mock_print:
        _cmd_afterhours_bars(mock_client, "TSLA", 5)

    printed_json = mock_print.call_args[0][0]
    result = json.loads(printed_json)

    assert result["symbol"] == "TSLA"
    assert len(result["items"]) == 0
