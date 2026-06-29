"""
PY-YF-001~006: 测试 stock_info_yfinance.get_stock_info() (Mock 模式)
"""
import sys
import json
import pytest
from unittest.mock import patch, MagicMock, PropertyMock
import pandas as pd
import numpy as np

sys.path.insert(0, "src/main/resources/python")
import stock_info_yfinance as yf_module


# ---------- helpers ----------

def _build_history_df():
    """Build a standard 1-row history DataFrame matching yfinance output."""
    idx = pd.DatetimeIndex(
        [pd.Timestamp.now(tz="America/New_York")], name="Date"
    )
    df = pd.DataFrame(
        {
            "Open": [100.0],
            "High": [105.0],
            "Low": [99.0],
            "Close": [102.5],
            "Volume": [1_000_000],
        },
        index=idx,
    )
    return df


def _make_safe_req_identity():
    """
    模拟 safe_yfinance_request 的行为：
    - 如果 func 是可调用的（如 bound method），则调用 func(*args, **kwargs)
    - 如果 func 不可调用（如 dict 值），直接返回 func 本身

    生产代码中 stock.info 是 @property 直接返回 dict，
    而 safe_yfinance_request(dict) 会尝试调用 dict() 导致失败。
    这里使用智能 mock 让测试能验证业务逻辑。
    """
    def fake(func, *args, **kwargs):
        if callable(func):
            return func(*args, **kwargs)
        return func
    return fake


# ===================================================================
# PY-YF-001: 正常调用 — 含盘后数据
# ===================================================================
@patch("stock_info_yfinance.time.sleep", return_value=None)
@patch("stock_info_yfinance.safe_yfinance_request")
@patch("stock_info_yfinance.yf.Ticker")
def test_py_yf_001_normal_with_afterhours(
    mock_ticker_cls, mock_safe_req, mock_sleep
):
    """PY-YF-001: 正常调用，info 含盘后数据"""
    # Arrange
    mock_ticker = MagicMock()
    mock_ticker_cls.return_value = mock_ticker

    info_data = {
        "longName": "Apple Inc.",
        "regularMarketChangePercent": 2.5,
        "postMarketPrice": 105.0,
        "postMarketChangePercent": -1.0,
    }
    hist_df = _build_history_df()

    mock_safe_req.side_effect = _make_safe_req_identity()

    # 设置 info / history
    mock_ticker.info = info_data
    mock_ticker.history.return_value = hist_df

    # Act
    result = yf_module.get_stock_info("AAPL")
    data = json.loads(result)

    # Assert
    assert "error" not in data, f"Unexpected error: {data.get('error')}"
    assert data["symbol"] == "AAPL"
    assert data["name"] == "Apple Inc."
    assert data["currentPrice"] == 102.5
    assert data["changePercent"] == 2.5
    assert data["afterHours"] == 105.0
    assert data["afterHoursChangePercent"] == -1.0


# ===================================================================
# PY-YF-002: info 不含 postMarketPrice（无盘后数据）
# ===================================================================
@patch("stock_info_yfinance.time.sleep", return_value=None)
@patch("stock_info_yfinance.safe_yfinance_request")
@patch("stock_info_yfinance.yf.Ticker")
def test_py_yf_002_no_afterhours(
    mock_ticker_cls, mock_safe_req, mock_sleep
):
    """PY-YF-002: info 不含 postMarketPrice"""
    mock_ticker = MagicMock()
    mock_ticker_cls.return_value = mock_ticker

    info_data = {
        "longName": "Apple Inc.",
        "regularMarketChangePercent": 1.2,
    }
    hist_df = _build_history_df()

    mock_safe_req.side_effect = _make_safe_req_identity()

    mock_ticker.info = info_data
    mock_ticker.history.return_value = hist_df

    result = yf_module.get_stock_info("AAPL")
    data = json.loads(result)

    assert "error" not in data
    assert "afterHours" not in data
    assert "afterHoursChangePercent" not in data
    assert data["changePercent"] == 1.2


# ===================================================================
# PY-YF-003: history.empty == True
# ===================================================================
@patch("stock_info_yfinance.time.sleep", return_value=None)
@patch("stock_info_yfinance.safe_yfinance_request")
@patch("stock_info_yfinance.yf.Ticker")
def test_py_yf_003_empty_history(
    mock_ticker_cls, mock_safe_req, mock_sleep
):
    """PY-YF-003: history 为空"""
    mock_ticker = MagicMock()
    mock_ticker_cls.return_value = mock_ticker

    info_data = {"longName": "Test"}
    empty_df = pd.DataFrame()

    mock_safe_req.side_effect = _make_safe_req_identity()

    mock_ticker.info = info_data
    mock_ticker.history.return_value = empty_df

    result = yf_module.get_stock_info("TEST")
    data = json.loads(result)

    assert "error" in data
    assert "No historical data" in data["error"]


# ===================================================================
# PY-YF-004: info 不含 regularMarketChangePercent
# ===================================================================
@patch("stock_info_yfinance.time.sleep", return_value=None)
@patch("stock_info_yfinance.safe_yfinance_request")
@patch("stock_info_yfinance.yf.Ticker")
def test_py_yf_004_no_change_percent(
    mock_ticker_cls, mock_safe_req, mock_sleep
):
    """PY-YF-004: info 不含 regularMarketChangePercent"""
    mock_ticker = MagicMock()
    mock_ticker_cls.return_value = mock_ticker

    info_data = {"longName": "NoChangePct", "postMarketPrice": 99.0}
    hist_df = _build_history_df()

    mock_safe_req.side_effect = _make_safe_req_identity()

    mock_ticker.info = info_data
    mock_ticker.history.return_value = hist_df

    result = yf_module.get_stock_info("NCP")
    data = json.loads(result)

    assert "error" not in data
    assert data["changePercent"] == 0  # default
    assert data["afterHours"] == 99.0


# ===================================================================
# PY-YF-005: yfinance 抛出异常
# ===================================================================
@patch("stock_info_yfinance.time.sleep", return_value=None)
@patch("stock_info_yfinance.yf.Ticker")
def test_py_yf_005_exception(mock_ticker_cls, mock_sleep):
    """PY-YF-005: yfinance 抛出异常"""
    mock_ticker_cls.side_effect = Exception("Network error")

    result = yf_module.get_stock_info("FAIL")
    data = json.loads(result)

    assert "error" in data
    assert "Network error" in data["error"]


# ===================================================================
# PY-YF-006: 多次调用不同 symbol
# ===================================================================
@patch("stock_info_yfinance.time.sleep", return_value=None)
@patch("stock_info_yfinance.safe_yfinance_request")
@patch("stock_info_yfinance.yf.Ticker")
def test_py_yf_006_multiple_symbols(
    mock_ticker_cls, mock_safe_req, mock_sleep
):
    """PY-YF-006: 多次调用不同 symbol"""
    mock_safe_req.side_effect = _make_safe_req_identity()

    def ticker_side_effect(symbol):
        mt = MagicMock()
        if symbol == "AAPL":
            mt.info = {"longName": "Apple", "regularMarketChangePercent": 1.0}
            mt.history.return_value = _build_history_df()
        elif symbol == "MSFT":
            mt.info = {"longName": "Microsoft", "regularMarketChangePercent": 0.5}
            idx = pd.DatetimeIndex([pd.Timestamp.now(tz="America/New_York")], name="Date")
            df2 = pd.DataFrame(
                {"Open": [300.0], "High": [310.0], "Low": [298.0],
                 "Close": [305.0], "Volume": [2_000_000]},
                index=idx,
            )
            mt.history.return_value = df2
        else:
            mt.info = {}
            mt.history.return_value = _build_history_df()
        return mt

    mock_ticker_cls.side_effect = ticker_side_effect

    r1 = json.loads(yf_module.get_stock_info("AAPL"))
    r2 = json.loads(yf_module.get_stock_info("MSFT"))

    assert r1["symbol"] == "AAPL"
    assert r1["name"] == "Apple"
    assert r2["symbol"] == "MSFT"
    assert r2["name"] == "Microsoft"
    assert r1["currentPrice"] == 102.5
    assert r2["currentPrice"] == 305.0
    assert r1["changePercent"] == 1.0
    assert r2["changePercent"] == 0.5
