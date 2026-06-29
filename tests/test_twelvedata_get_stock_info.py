"""
PY-TD-001~005: 测试 stock_info_twelvedata.get_stock_info() (Mock 模式)
"""
import sys
import json
import os
import pytest
from unittest.mock import patch, MagicMock
import urllib.error

sys.path.insert(0, "src/main/resources/python")
import stock_info_twelvedata as td_module


# ===================================================================
# PY-TD-001: 正常调用 — API 返回含 percent_change=0.67
# ===================================================================
@patch("stock_info_twelvedata.time.sleep", return_value=None)
@patch("stock_info_twelvedata.urllib.request.urlopen")
@patch("stock_info_twelvedata.API_KEY", "test_key_123")
def test_py_td_001_normal(mock_urlopen, mock_sleep):
    """PY-TD-001: 正常 API 返回含 percent_change=0.67"""
    # Arrange
    fake_response_data = {
        "symbol": "AAPL",
        "name": "Apple Inc",
        "close": "150.50",
        "open": "149.00",
        "high": "152.00",
        "low": "148.50",
        "volume": "50000000",
        "change": "1.50",
        "percent_change": "0.67",
    }
    fake_json = json.dumps(fake_response_data).encode("utf-8")

    cm = MagicMock()
    cm.__enter__.return_value.read.return_value = fake_json
    mock_urlopen.return_value = cm

    # Act
    result = td_module.get_stock_info("AAPL")
    data = json.loads(result)

    # Assert
    assert "error" not in data, f"Unexpected error: {data.get('error')}"
    assert data["symbol"] == "AAPL"
    assert data["name"] == "Apple Inc"
    assert data["currentPrice"] == 150.50
    assert data["openPrice"] == 149.00
    assert data["highPrice"] == 152.00
    assert data["lowPrice"] == 148.50
    assert data["volume"] == 50000000
    assert data["change"] == 1.50
    assert data["changePercent"] == 0.67


# ===================================================================
# PY-TD-002: os.environ 不含 API KEY
# ===================================================================
@patch("stock_info_twelvedata.time.sleep", return_value=None)
@patch("stock_info_twelvedata.API_KEY", "")
def test_py_td_002_no_api_key(mock_sleep):
    """PY-TD-002: API KEY 未设置"""
    # 由于 API_KEY 是 module-level 变量，patch 后为 ""
    result = td_module.get_stock_info("AAPL")
    data = json.loads(result)

    assert "error" in data
    assert "API_KEY" in data["error"] or "environment variable" in data["error"] \
           or "not set" in data["error"]


# ===================================================================
# PY-TD-003: HTTPError 429
# ===================================================================
@patch("stock_info_twelvedata.time.sleep", return_value=None)
@patch("stock_info_twelvedata.API_KEY", "test_key_123")
def test_py_td_003_http_429(mock_sleep):
    """PY-TD-003: HTTP 429 Too Many Requests"""
    # 直接 patch api_request 让它抛 HTTPError
    # HTTPError(url, code, msg, hdrs, fp) — 但我们不想真正构造，直接 mock
    with patch.object(td_module, "api_request") as mock_api:
        mock_api.side_effect = Exception("HTTP 429: Too Many Requests")

        result = td_module.get_stock_info("AAPL")
        data = json.loads(result)

        assert "error" in data
        assert "429" in data["error"] or "Too Many" in data["error"]


# ===================================================================
# PY-TD-004: URLError
# ===================================================================
@patch("stock_info_twelvedata.time.sleep", return_value=None)
@patch("stock_info_twelvedata.API_KEY", "test_key_123")
def test_py_td_004_url_error(mock_sleep):
    """PY-TD-004: URLError (Connection error)"""
    with patch.object(td_module, "api_request") as mock_api:
        mock_api.side_effect = Exception("Connection error: [Errno 111] Connection refused")

        result = td_module.get_stock_info("AAPL")
        data = json.loads(result)

        assert "error" in data
        assert "Connection" in data["error"]


# ===================================================================
# PY-TD-005: percent_change=0.0
# ===================================================================
@patch("stock_info_twelvedata.time.sleep", return_value=None)
@patch("stock_info_twelvedata.urllib.request.urlopen")
@patch("stock_info_twelvedata.API_KEY", "test_key_123")
def test_py_td_005_percent_change_zero(mock_urlopen, mock_sleep):
    """PY-TD-005: percent_change=0.0"""
    fake_response_data = {
        "symbol": "TEST",
        "name": "Test Stock",
        "close": "100.00",
        "open": "100.00",
        "high": "101.00",
        "low": "99.00",
        "volume": "100000",
        "change": "0.00",
        "percent_change": "0.00",
    }
    fake_json = json.dumps(fake_response_data).encode("utf-8")

    cm = MagicMock()
    cm.__enter__.return_value.read.return_value = fake_json
    mock_urlopen.return_value = cm

    result = td_module.get_stock_info("TEST")
    data = json.loads(result)

    assert "error" not in data
    assert data["changePercent"] == 0.0
    assert data["change"] == 0.0
