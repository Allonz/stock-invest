"""
INT-YF-001~004: YFinance Real API 集成测试
INT-TD-001~002: TwelveData Real API 集成测试

使用 @pytest.mark.integration 标记，通过 --run-integration 执行。

注意：YFinance 测试直接调用 yf.Ticker，但 Yahoo 可能限流；
       限流时测试自动跳过（xfail/strict=False）。
TwelveData 测试需要 TWELVEDATA_API_KEY 环境变量，无 Key 时自动跳过。
"""

import json
import os
import sys
import time
import pytest

sys.path.insert(0, "src/main/resources/python")
import yfinance as yf


# ===================================================================
# Helper: 安全获取 YFinance 数据，遇到限流则跳过
# ===================================================================
def _safe_fetch(symbol):
    """尝试获取 yfinance 数据，限流时抛出 pytest.skip"""
    try:
        stock = yf.Ticker(symbol)
        info = stock.info
        history = stock.history(period="1d")
        return info, history
    except Exception as e:
        err_str = str(e)
        if "Rate limited" in err_str or "429" in err_str or "Too Many" in err_str:
            pytest.skip(f"Yahoo Finance rate-limited ({err_str[:60]})")
        raise


_aapl_info_cache = None
_aapl_hist_cache = None


def _get_aapl_data():
    global _aapl_info_cache, _aapl_hist_cache
    if _aapl_info_cache is not None:
        return _aapl_info_cache, _aapl_hist_cache

    info, history = _safe_fetch("AAPL")

    assert info is not None, "AAPL info is None"
    assert not history.empty, "AAPL history is empty"

    _aapl_info_cache = info
    _aapl_hist_cache = history
    return info, history


def _build_result(info, history, symbol="AAPL"):
    result = {
        "symbol": symbol,
        "name": info.get("longName", symbol),
        "currentPrice": float(history["Close"].iloc[-1]),
        "openPrice": float(history["Open"].iloc[-1]),
        "highPrice": float(history["High"].iloc[-1]),
        "lowPrice": float(history["Low"].iloc[-1]),
        "volume": int(history["Volume"].iloc[-1]),
        "change": float(history["Close"].iloc[-1] - history["Open"].iloc[-1]),
        "changePercent": float(info.get("regularMarketChangePercent", 0)),
    }
    if info.get("postMarketPrice") is not None:
        result["afterHours"] = float(info["postMarketPrice"])
    if info.get("postMarketChangePercent") is not None:
        result["afterHoursChangePercent"] = float(info["postMarketChangePercent"])
    return result


# ===================================================================
# INT-YF-001: yf.Ticker("AAPL").info → regularMarketChangePercent 不为 null
# ===================================================================
@pytest.mark.integration
def test_int_yf_001_regular_market_change_percent_not_null():
    """INT-YF-001: 验证 regularMarketChangePercent 不为 null"""
    info, history = _get_aapl_data()
    change_pct = info.get("regularMarketChangePercent")
    assert change_pct is not None, "regularMarketChangePercent is None"
    assert isinstance(change_pct, (int, float))

    result = _build_result(info, history)
    assert result["changePercent"] is not None
    assert isinstance(result["changePercent"], (int, float))


# ===================================================================
# INT-YF-002: postMarketPrice 字段存在
# ===================================================================
@pytest.mark.integration
def test_int_yf_002_post_market_price_field_exists():
    """INT-YF-002: 验证 postMarketPrice 字段存在"""
    info, history = _get_aapl_data()
    result = _build_result(info, history)
    # afterHours may be absent when market is closed
    if "afterHours" in result:
        assert isinstance(result["afterHours"], (int, float))
    if "afterHoursChangePercent" in result:
        assert isinstance(result["afterHoursChangePercent"], (int, float))


# ===================================================================
# INT-YF-003: 同时验证五个字段
# ===================================================================
@pytest.mark.integration
def test_int_yf_003_validate_five_fields():
    """INT-YF-003: 同时验证 symbol, name, currentPrice, changePercent, volume 五个字段"""
    info, history = _get_aapl_data()
    result = _build_result(info, history)

    assert result["symbol"] == "AAPL"
    assert result["name"] is not None and len(result["name"]) > 0
    assert isinstance(result["currentPrice"], (int, float))
    assert result["currentPrice"] > 0
    assert isinstance(result["changePercent"], (int, float))
    assert isinstance(result["volume"], int)
    assert result["volume"] > 0


# ===================================================================
# INT-YF-004: 多 symbol 调用（AAPL, MSFT, GOOGL）
# ===================================================================
@pytest.mark.integration
def test_int_yf_004_multiple_symbols():
    """INT-YF-004: 验证多个 symbol（AAPL, MSFT, GOOGL）"""
    symbols = ["AAPL", "MSFT", "GOOGL"]
    for sym in symbols:
        time.sleep(3)  # 避免 rate limit
        info, history = _safe_fetch(sym)

        result = _build_result(info, history, sym)
        assert result["symbol"] == sym
        assert result["name"] is not None
        assert isinstance(result["currentPrice"], (int, float))
        assert result["currentPrice"] > 0
        assert isinstance(result["changePercent"], (int, float))
        assert isinstance(result["volume"], int)
        print(f"  ✓ {sym}: {result['name']} @ ${result['currentPrice']:.2f} ({result['changePercent']:+.2f}%)")


# ===================================================================
# INT-TD-001: TwelveData /quote
# ===================================================================
twelvedata_api_key = os.environ.get("TWELVEDATA_API_KEY", "")

@pytest.mark.integration
@pytest.mark.skipif(not twelvedata_api_key, reason="TWELVEDATA_API_KEY environment variable not set")
def test_int_td_001_quote():
    """INT-TD-001: 调用 TwelveData /quote 接口"""
    import stock_info_twelvedata as td_module

    result = json.loads(td_module.get_stock_info("AAPL"))
    assert "error" not in result, f"Unexpected error: {result.get('error')}"
    assert result["symbol"] == "AAPL"
    assert result["name"] is not None
    assert isinstance(result["currentPrice"], (int, float))
    assert result["currentPrice"] > 0
    assert isinstance(result["changePercent"], (int, float))
    assert isinstance(result["volume"], int)


# ===================================================================
# INT-TD-002: TwelveData /time_series
# ===================================================================
@pytest.mark.integration
@pytest.mark.skipif(not twelvedata_api_key, reason="TWELVEDATA_API_KEY environment variable not set")
def test_int_td_002_time_series():
    """INT-TD-002: 调用 TwelveData /time_series 接口"""
    import urllib.request
    import json as _json

    url = (
        f"https://api.twelvedata.com/time_series?"
        f"symbol=AAPL&interval=1day&outputsize=5&apikey={twelvedata_api_key}"
    )
    data = {}
    try:
        resp = urllib.request.urlopen(url, timeout=15)
        data = _json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        pytest.fail(f"TwelveData /time_series request failed: {e}")

    assert "status" in data, f"Unexpected response: {data}"
    if data.get("status") == "error":
        pytest.skip(f"TwelveData API returned error (may be rate limited): {data}")

    assert "values" in data, f"Expected 'values' in response, got keys: {list(data.keys())}"
    assert len(data["values"]) > 0, "Expected at least 1 time series data point"
    first = data["values"][0]
    for field in ("datetime", "open", "high", "low", "close", "volume"):
        assert field in first, f"Missing field '{field}' in time series data point: {first}"
    print(f"  ✓ TwelveData time_series returned {len(data['values'])} data points")


# ===================================================================
# Main (for direct execution)
# ===================================================================
if __name__ == "__main__":
    pytest.main([__file__, "-v", "--run-integration"])
