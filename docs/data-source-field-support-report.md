# 数据源字段支持调研报告

## 调研对象

| # | 数据源 | 实现方式 | 关键文件 |
|---|--------|---------|---------|
| 1 | TwelveData | Python 脚本 + Java REST Client | `stock_info_twelvedata.py`, `TwelveDataRestClient.java`, `TwelveDataStockServiceImpl.java` |
| 2 | Tiingo | Java REST Client | `TiingoRestClient.java`, `TiingoDataSourceStrategy.java` |
| 3 | YFinance | Python 脚本 | `stock_info_yfinance.py` |
| 4 | Tiger Java SDK | Java SDK v2.2.6 | `TigerStockServiceImpl.java` |
| 5 | TigerOpen Python Bridge | Java → Python 桥接 | `TigerOpenStockServiceImpl.java` → `TigerOpenPythonBridge` → `tigeropen_channel.py` |

---

## 一、TwelveData

### API 文档确认
- `/time_series` 端点返回：`datetime`, `open`, `high`, `low`, `close`, `volume`
- `/quote` 端点额外返回：`change`, `percent_change`, `previous_close`
- 有 `prepost` 参数，但仅限 **Pro+ 计划**的 intraday 数据（非日线）
- 无盘后价相关端点或字段

### 实际调用结果
```json
{
  "datetime": "2026-06-22",
  "open": "297.41", "high": "302.415",
  "low": "297.32", "close": "300.1",
  "volume": "1609176",
  "previous_close": "298.01001",
  "change": "2.08999",
  "percent_change": "0.70131545"
}
```
- 带 `prepost=true` 参数调用 `/time_series` 返回 404

### 当前代码分析
| 位置 | 提取字段 | 缺少字段 |
|------|---------|---------|
| `TwelveDataRestClient.fetchDailyBars()` (Java) | `datetime`, `open`, `high`, `low`, `close`, `volume` | `changePercent` ✅（需从 quote 获取） |
| `stock_info_twelvedata.py` 的 `get_daily_kline()` | `time`, `open`, `high`, `low`, `close`, `volume` | `changePercent` |
| `stock_info_twelvedata.py` 的 `get_stock_info()` | `symbol`, `name`, `currentPrice`, `openPrice`, `highPrice`, `lowPrice`, `volume`, `change`, **`changePercent`** | ⚠️ 计算方式是 `(close - open)/open`，非标准涨跌幅 |

### 结论
| 字段 | 支持情况 |
|------|---------|
| 最高价 (high) | ✅ 支持 |
| 最低价 (low) | ✅ 支持 |
| **changePercent** | ✅ **`/quote` 直接返回 `percent_change`** |
| 盘后价 (afterHours) | ❌ 不支持 |
| 盘后涨跌幅 (afterHoursChangePercent) | ❌ 不支持 |

---

## 二、Tiingo

### API 文档确认
- `/tiingo/daily/{symbol}/prices` 返回：`date`, `open`, `high`, `low`, `close`, `volume`, `adjOpen`, `adjHigh`, `adjLow`, `adjClose`, `adjVolume`, `divCash`, `splitFactor`
- IEX 实时端点返回：`ticker`, `timestamp`, `open`, `high`, `low`, `mid`, `tngoLast`, `last`, `prevClose`, `volume`
- 无盘后价端点

### 实际调用结果
```json
// Tiingo IEX
{
  "ticker": "AAPL",
  "open": 296.16, "high": 302.475, "low": 296.0,
  "mid": 299.73, "tngoLast": 299.64,
  "prevClose": 298.01, "volume": 256916.0
}
```
- 无盘后价字段，无 `changePercent`

### 当前代码分析
| 位置 | 提取字段 | 缺少字段 |
|------|---------|---------|
| `TiingoRestClient.fetchDailyBars()` | `date`, `open`, `high`, `low`, `close`, `volume` | `changePercent` |
| `TiingoRestClient.listUsSymbolsByPriceRange()` | IEX 实时数据，含 `prevClose` | — |

### 结论
| 字段 | 支持情况 |
|------|---------|
| 最高价 (high) | ✅ 支持 |
| 最低价 (low) | ✅ 支持 |
| 涨跌幅 (changePercent) | ❌ 不直接返回，但可通过 `(close - prevClose) / prevClose * 100` 计算 |
| 盘后价 (afterHours) | ❌ 不支持 |
| 盘后涨跌幅 (afterHoursChangePercent) | ❌ 不支持 |

---

## 三、YFinance

### 实际调用验证（2026-06-23 重新调研，AAPL 为例）

通过 `yfinance` 库实际调用 `yf.Ticker("AAPL").info`，发现 `stock.info` 字典中直接包含
大量盘后价、涨跌幅等字段，远多于之前文档记录：

```python
stock = yf.Ticker("AAPL")
info = stock.info

# 实际返回的关键字段：
info["regularMarketDayHigh"]       = 302.42       # 当日最高价 ✅
info["regularMarketDayLow"]        = 296.76       # 当日最低价 ✅
info["regularMarketChangePercent"] = -0.335559    # 标准涨跌幅 ✅（直接返回，无需计算）
info["regularMarketChange"]        = -1.0         # 涨跌额 ✅
info["previousClose"]              = 298.01       # 前收盘 ✅
info["postMarketPrice"]            = 295.65       # 盘后价 ✅（直接返回）
info["postMarketChange"]           = -1.36        # 盘后涨跌额 ✅
info["postMarketChangePercent"]    = -0.4579      # 盘后涨跌幅 ✅（直接返回）
info["hasPrePostMarketData"]       = True         # 支持盘前盘后 ✅
```

`stock.history(period="5d", prepost=True)` 对日线不增加额外列/行。
但 `history(period="2d", interval="1h", prepost=True)` 会返回盘后小时线
（16:00~19:00 EDT 有独立 OHLCV K 线，Volume=0 时为基础价格点）。

### 当前代码分析
| 位置 | 提取字段 | 缺少字段 | 问题 |
|------|---------|---------|------|
| `stock_info_yfinance.py` 的 `get_daily_kline()` | `time`, `open`, `high`, `low`, `close`, `volume`, `amount` | `changePercent`，盘后价 | 未使用 `stock.info` 获取 |
| `stock_info_yfinance.py` 的 `get_stock_info()` | `changePercent`（非标准计算） | 标准涨跌幅、盘后价、盘后涨跌幅 | ❌ 第 83 行 `(Close-Open)/Open*100` 是日内涨跌幅，非标准；未使用 `info["regularMarketChangePercent"]` |

### 结论
| 字段 | 支持情况 | 数据来源 |
|------|---------|---------|
| 最高价 (high) | ✅ **支持** | `history["High"]` 或 `info["regularMarketDayHigh"]` |
| 最低价 (low) | ✅ **支持** | `history["Low"]` 或 `info["regularMarketDayLow"]` |
| 涨跌幅 (changePercent) | ✅ **stock.info 直接返回**，无需计算 | `info["regularMarketChangePercent"]` |
| 盘后价 (afterHours) | ✅ **stock.info 直接返回** | `info["postMarketPrice"]` |
| 盘后涨跌幅 (afterHoursChangePercent) | ✅ **stock.info 直接返回** | `info["postMarketChangePercent"]` |

---

## 四、Tiger Java SDK

### SDK 源码分析（`io.github.tigerbrokers:openapi-java-sdk:2.2.6`）
- **`KlinePoint` 类字段**：`open`, `close`, `high`, `low`, `time`, `volume`, `amount`
- **无 `changePercent` 字段**在 KlinePoint 中
- **支持 `TradeSession` 枚举**：`PreMarket`, `Regular`, **`AfterHours`**, `OverNight`, `All`
- **支持 `includeHourTrading`**：`@JSONField(name = "include_hour_trading")`
- **`QuoteKlineRequest`** 有 `setTradeSession(TradeSession)` 方法，但当前代码**未调用**

### 当前代码分析
```java
// TigerStockServiceImpl.java - 当前实现
QuoteKlineRequest request = QuoteKlineRequest.newRequest(
    Collections.singletonList(symbol), KType.day, beginDate, endDate)
    .withLimit(30);
// 没有调用 setTradeSession()，默认 Regular 交易时段

// convertTigerKlinePointToKLineIterator - 提取字段
symbol, time, open, high, low, close, volume, amount
```

### 结论
| 字段 | 支持情况 |
|------|---------|
| 最高价 (high) | ✅ 支持 |
| 最低价 (low) | ✅ 支持 |
| 涨跌幅 (changePercent) | ❌ KlinePoint 无此字段，需计算 |
| 盘后价 (afterHours) | ⚠️ **可能支持**：设置 `TradeSession.AfterHours` 可获取盘后 K 线数据，但返回的 KlinePoint 结构与常规相同（单独的 OHLCV），不是附加字段 |
| 盘后涨跌幅 (afterHoursChangePercent) | ❌ 不存在此字段，需计算 |

---

## 五、TigerOpen Python Bridge

### Python SDK 源码分析
- `get_bars(symbol, period, limit, **trade_session=None**)` — **支持 `trade_session` 参数！**
- `TradingSession` 枚举值：`AfterHours`, `All`, `OverNight`, `PreMarket`, `Regular`
- 当前代码 `_cmd_bars()` 中**没有传 `trade_session`**，默认 `None`（即 Regular）

### 当前代码分析
```python
# tigeropen_channel.py - 当前实现
df = client.get_bars(symbol, period=BarPeriod.DAY, limit=int(lim))
# 没有传 trade_session 参数

# 返回字段
symbol, time, open, high, low, close, volume, amount
```

### 结论
| 字段 | 支持情况 |
|------|---------|
| 最高价 (high) | ✅ 支持 |
| 最低价 (low) | ✅ 支持 |
| 涨跌幅 (changePercent) | ❌ 不返回，需计算 |
| 盘后价 (afterHours) | ⚠️ **可能支持**：传 `trade_session=TradingSession.AfterHours` 可获取盘后 K 线 |
| 盘后涨跌幅 (afterHoursChangePercent) | ❌ 不存在此字段，需计算 |

---

## 六、汇总

### 各数据源字段支持矩阵

| 字段 | TwelveData | Tiingo | YFinance | Tiger Java SDK | TigerOpen Python |
|------|-----------|--------|---------|---------------|-----------------|
| **high** | ✅ 返回 | ✅ 返回 | ✅ 返回 | ✅ KlinePoint | ✅ 返回 |
| **low** | ✅ 返回 | ✅ 返回 | ✅ 返回 | ✅ KlinePoint | ✅ 返回 |
| **changePercent** | ✅ **`/quote` 端点直接返回** | ❌ 需计算 | ✅ **`stock.info` 直接返回** | ❌ 需计算 | ❌ 需计算 |
| **afterHours** | ❌ 不支持 | ❌ 不支持 | ✅ **`stock.info["postMarketPrice"]` 直接返回** | ⚠️ `TradeSession.AfterHours` | ⚠️ `trade_session=AfterHours` |
| **afterHoursChangePercent** | ❌ 不支持 | ❌ 不支持 | ✅ **`stock.info["postMarketChangePercent"]` 直接返回** | ❌ 需计算 | ❌ 需计算 |

### 关键发现

1. **high/low** — 所有数据源都直接返回，已在 Python 脚本和 Java 代码中提取 ✅

2. **changePercent** — YFinance 和 TwelveData 都直接返回，其余需计算
   - YFinance：`stock.info["regularMarketChangePercent"]`
   - TwelveData：`/quote` 端点直接返回 `percent_change`（Python 脚本第 72 行已提取 ✅）
   - Tiingo / Tiger SDK 需手动计算：`(close - previous_close) / previous_close * 100`
   - 当前 `stock_info_yfinance.py` 的 `get_stock_info()` 计算错误（用 open 而非 previous_close，应直接使用 `info["regularMarketChangePercent"]`）

3. **afterHours 和 afterHoursChangePercent** — 只有 YFinance 通过 `stock.info` 直接返回：
   - `info["postMarketPrice"]` — 盘后价
   - `info["postMarketChangePercent"]` — 盘后涨跌幅
   - Tiger SDK 支持通过 `TradeSession.AfterHours` 参数获取盘后 K 线（但需单独再拉取一次）

### 建议实现策略

| 字段 | 策略 | 优先级 |
|------|------|--------|
| **high/low** | 所有数据源已有，只需确保持久化 | P0 - 立即做 |
| **changePercent** | **优先方案**：YFinance 路由直接用 `stock.info["regularMarketChangePercent"]` 获取；TwelveData 路由直接从 `/quote` 取 `percent_change`（已有提取逻辑 ✅）；其他数据源用 `(close - prev_close) / prev_close * 100` 计算 | P0 - 立即做 |
| **afterHours** | **优先方案**：YFinance `stock.info["postMarketPrice"]` 直接获取；Tiger SDK 方案作为 P1 备选 | P1 - 后续 |
| **afterHoursChangePercent** | **优先方案**：YFinance `stock.info["postMarketChangePercent"]` 直接获取 | P1 - 后续 |
