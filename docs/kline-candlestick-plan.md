# 行情筛选页增加 K 线蜡烛图展示 — 开发方案（完整版）

## 需求概述

在行情筛选页面（ScreenerView）中，当用户点击筛选结果表格中的股票名称/代码时，在表格下方展示该股票最近 7 天的 K 线蜡烛图。要求：
- 初始默认展示尺度统一
- 支持缩放交互
- K 线行情页面上增加：最高价、最低价、涨跌幅、盘后价、盘后涨跌幅

---

## 一、技术选型

| 层级 | 技术方案 | 说明 |
|------|---------|------|
| 前端图表库 | **Apache ECharts** | 已广泛用于金融图表，内置 K 线（candlestick）类型，支持缩放（dataZoom）、手势交互 |
| 前端集成 | Vue 3 + ECharts Vue 组件 | 通过 `vue-echarts` 或直接 `echarts` 实例化 |
| 后端数据 | 复用 `stock_daily_bar` 表 | 已有 open/high/low/close/volume 数据 |
| 后端 API | 新增 `GET /api/bars/{symbol}/candles?days=7` | 返回最近 N 天 K 线数据（含新增字段） |

> 注：当前 `StockDailyBar` 实体只有 `openPrice`、`closePrice`，**缺少 `highPrice` 和 `lowPrice`**。需要数据库表和实体增加这两个字段。

---

## 二、数据库改动

### 2.1 表结构变更：`stock_daily_bar`

当前表结构：
```sql
CREATE TABLE stock_daily_bar (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL,
    stock_name VARCHAR(128),
    trade_date DATE NOT NULL,
    open_price DOUBLE NOT NULL,   -- 当前有
    close_price DOUBLE NOT NULL,  -- 当前有
    volume BIGINT NOT NULL,       -- 当前有
    source VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_symbol_trade_date (symbol, trade_date)
);
```

**新增字段**：
```sql
ALTER TABLE stock_daily_bar
    ADD COLUMN high_price DOUBLE NULL AFTER open_price,
    ADD COLUMN low_price DOUBLE NULL AFTER high_price,
    ADD COLUMN change_percent DOUBLE NULL AFTER close_price,
    ADD COLUMN after_hours DOUBLE NULL AFTER change_percent,
    ADD COLUMN after_hours_change_percent DOUBLE NULL AFTER after_hours;
```

> 字段说明：
> - `high_price` / `low_price`：NOT NULL DEFAULT 0，历史数据用 SQL 回填
> - `change_percent`：NULL，优先从 YFinance `stock.info["regularMarketChangePercent"]` 或 TwelveData `/quote` 的 `percent_change` 获取；其他数据源用 `(close - prevClose) / prevClose * 100` 计算
> - `after_hours` / `after_hours_change_percent`：NULL，优先从 YFinance `stock.info["postMarketPrice"]` / `postMarketChangePercent` 获取；Tiger SDK 方案作为 P1 备选

> 回填策略：
> 1. 新增字段时允许 `NULL`（避免阻塞现有数据写入）
> 2. 历史数据用 SQL 近似回填 high/low，计算回填 change_percent
> 3. 后续新数据采集时，优先从数据源获取真实值（YFinance stock.info 一次调用即可拿全五字段）
> 4. after_hours / after_hours_change_percent：YFinance 直接返回，Tiger SDK 方案作为 P1 备选

### 2.2 实体类变更：`StockDailyBar.java`

```java
@Column(nullable = false)
private Double openPrice;

@Column(nullable = false)
private Double highPrice;   // 新增（最高价）

@Column(nullable = false)
private Double lowPrice;    // 新增（最低价）

@Column(nullable = false)
private Double closePrice;

@Column(nullable = true)
private Double changePercent;           // 新增（涨跌幅 %）

@Column(nullable = true)
private Double afterHours;              // 新增（盘后价）

@Column(nullable = true)
private Double afterHoursChangePercent; // 新增（盘后涨跌幅 %）
```

### 2.3 历史数据回填方案（采用方案 A：SQL 批量回填）

**背景**：`stock_daily_bar` 表已有历史数据，但缺少 `high_price`、`low_price`、`change_percent`、`after_hours`、`after_hours_change_percent`。新增字段后，需要回填历史数据。

**采用方案 A：SQL 批量回填**，理由：
- 纯 SQL 执行，无需编写脚本，执行速度快
- 当前需求是快速验证 K 线功能，近似值足够使用
- 后续新数据采集时会写入真实值，逐步替换

```sql
-- 1. 新增字段（允许 NULL，避免阻塞现有数据写入）
ALTER TABLE stock_daily_bar
    ADD COLUMN high_price DOUBLE NULL AFTER open_price,
    ADD COLUMN low_price DOUBLE NULL AFTER high_price,
    ADD COLUMN change_percent DOUBLE NULL AFTER close_price,
    ADD COLUMN after_hours DOUBLE NULL AFTER change_percent,
    ADD COLUMN after_hours_change_percent DOUBLE NULL AFTER after_hours;

-- 2. 用 open/close 近似回填 high/low
-- 逻辑：high = max(open, close), low = min(open, close)
UPDATE stock_daily_bar
SET high_price = GREATEST(open_price, close_price),
    low_price = LEAST(open_price, close_price)
WHERE high_price IS NULL OR low_price IS NULL;

-- 3. 计算回填 change_percent（基于前一日收盘价）
-- 需要按 symbol 分组，用窗口函数或自连接计算
UPDATE stock_daily_bar s1
JOIN stock_daily_bar s2 ON s1.symbol = s2.symbol 
    AND s2.trade_date = DATE_SUB(s1.trade_date, INTERVAL 1 DAY)
SET s1.change_percent = ROUND((s1.close_price - s2.close_price) / s2.close_price * 100, 4)
WHERE s1.change_percent IS NULL;

-- 4. after_hours 和 after_hours_change_percent 暂留 NULL（数据源不支持）

-- 5. 确认回填完成后，改为 NOT NULL（可选，视业务需求）
-- ALTER TABLE stock_daily_bar
--     MODIFY high_price DOUBLE NOT NULL,
--     MODIFY low_price DOUBLE NOT NULL;
```

### 2.4 数据采集层变更（具体修改代码）

**目标**：修改数据采集逻辑，确保新采集的数据包含 `high`/`low`/`changePercent` 字段，并写入数据库。`afterHours` / `afterHoursChangePercent` 延时处理见下方调研结论。

#### 2.4.1 数据源字段支持调研结果

基于实际 API 调用测试（含 YFinance stock.info 实际调用、Tiger Java SDK 编译运行验证、TwelveData /quote 端点验证），各数据源对五个字段的支持情况：

| 数据源 | 实现方式 | 最高价 (high) | 最低价 (low) | 涨跌幅 (changePercent) | 盘后价 (afterHours) | 盘后涨跌幅 (afterHoursChangePercent) |
|--------|---------|--------------|-------------|----------------------|-------------------|-----------------------------------|
| **TwelveData** | Python 脚本 + Java REST | ✅ 返回 | ✅ 返回 | ✅ **`/quote` 直接返回 `percent_change`**（Python 脚本第 72 行已提取 ✅） | ❌ 不支持 | ❌ 不支持 |
| **Tiingo** | Java REST | ✅ 返回 | ✅ 返回 | ❌ 需计算 `(close-prevClose)/prevClose*100` | ❌ 不支持 | ❌ 不支持 |
|| **YFinance** | Python 脚本 | ✅ 返回 | ✅ 返回 | ⚠️ `info["regularMarketChangePercent"]` **可用但未提取**，当前用 `(close-open)/open*100` 计算错误 | ⚠️ `info["postMarketPrice"]` **可用但未提取**（一次 info 调用即返回） | ⚠️ `info["postMarketChangePercent"]` **可用但未提取** |
| **Tiger Java SDK** | Java SDK v2.2.6 | ✅ `KlinePoint.getHigh()` | ✅ `KlinePoint.getLow()` | ❌ 需计算 `(close-prevClose)/prevClose*100` | ⚠️ `TradeSession.AfterHours` 可获取盘后 K 线（需独立调用） | ❌ 需计算 |
| **TigerOpen Python Bridge** | Java → Python 桥接 | ✅ 返回 | ✅ 返回 | ❌ 需计算 | ⚠️ `trade_session=TradingSession.AfterHours` 可获取盘后 K 线（需独立调用） | ❌ 需计算 |

**关键发现**：
1. `high`/`low`：所有数据源都返回，Python 脚本和 Java SDK 已提取，只需要补全持久化逻辑
2. `changePercent`：**YFinance 和 TwelveData 都直接返回**：
   - YFinance：`stock.info["regularMarketChangePercent"]`（`get_stock_info()` 一次性调用即返回）
   - TwelveData：`/quote` 端点返回 `percent_change`（`get_stock_info()` 中已提取 ✅）
   - Tiingo / Tiger SDK 需手动计算：`(close - prevClose) / prevClose * 100`
   - 当前 `stock_info_yfinance.py` 的 `get_stock_info()` 计算错误（用 open 而非 previous_close，应直接使用 `info["regularMarketChangePercent"]`）
3. **`afterHours` 和 `afterHoursChangePercent` — YFinance 是唯一全能数据源**，`stock.info` 一次调用即返回：
   - `info["postMarketPrice"]` — 盘后价
   - `info["postMarketChangePercent"]` — 盘后涨跌幅
   - Tiger SDK 也支持盘后 K 线（`TradeSession.AfterHours`），但需独立 API 调用且返回的是单独 KlinePoint（非附加字段），复杂度较高，作为 P1 备选

#### 2.4.2 K 线数据模型 `KLineIterator.java`（已有 high/low，无需修改）

当前 `KLineIterator` 已有 `high`/`low` 字段：
```java
private double high;
private double low;
```

数据源返回的 K 线数据已经包含 high/low，问题出在**数据持久化层**没有将 high/low 写入数据库。

#### 2.4.3 数据持久化层修改：找到 `KLineData` → `StockDailyBar` 转换逻辑

找到将 `KLineData` / `KLineIterator` 转换为 `StockDailyBar` 并保存的代码（通常在 Service 或数据同步任务中），补充 high/low/changePercent 字段映射：

```java
// 示例：在数据同步/采集 Service 中
private StockDailyBar toEntity(KLineIterator item, String symbol, String source) {
    StockDailyBar bar = new StockDailyBar();
    bar.setSymbol(symbol);
    bar.setTradeDate(Instant.ofEpochMilli(item.getTime()).atZone(ZoneId.systemDefault()).toLocalDate());
    bar.setOpenPrice(item.getOpen());      // 已有
    bar.setHighPrice(item.getHigh());      // 新增（最高价）
    bar.setLowPrice(item.getLow());        // 新增（最低价）
    bar.setClosePrice(item.getClose());    // 已有
    bar.setVolume(item.getVolume());       // 已有
    // changePercent：TwelveData 直接返回，其他数据源需计算
    // 如果 item 有 changePercent 字段则设置，否则在 Service 层计算
    bar.setChangePercent(item.getChangePercent());  // 新增（涨跌幅）
    bar.setAfterHours(null);                        // 暂留空（数据源不支持盘后价）
    bar.setAfterHoursChangePercent(null);           // 暂留空
    bar.setSource(source);
    return bar;
}
```

#### 2.4.4 数据补全逻辑 `DataGapFillerServiceImpl.java` 修改

找到补全数据时创建 `StockDailyBar` 的逻辑，补充 high/low/changePercent：

```java
// 补全数据时，如果只有 open/close，用 GREATEST/LEAST 近似 high/low
bar.setHighPrice(Math.max(openPrice, closePrice));  // 或从数据源获取真实值
bar.setLowPrice(Math.min(openPrice, closePrice));     // 或从数据源获取真实值

// changePercent：如果数据源返回则使用，否则计算
Double prevClose = getPreviousClose(symbol, tradeDate); // 查询前一日收盘价
if (prevClose != null && prevClose != 0) {
    bar.setChangePercent((closePrice - prevClose) / prevClose * 100);
}

// afterHours / afterHoursChangePercent 暂留空
bar.setAfterHours(null);
bar.setAfterHoursChangePercent(null);
```

#### 2.4.5 数据采集 Python 脚本修改（如有）

如果项目使用 Python 脚本采集数据（如 Tiingo/TwelveData/YFinance），修改脚本将 high/low 写入数据库：

```python
# 示例：数据采集脚本
import mysql.connector

def save_daily_bar(symbol, trade_date, open_p, high_p, low_p, close_p, change_pct, after_hours, after_hours_chg, volume, source):
    conn = mysql.connector.connect(...)
    cursor = conn.cursor()
    sql = """
        INSERT INTO stock_daily_bar 
        (symbol, trade_date, open_price, high_price, low_price, close_price, 
         change_percent, after_hours, after_hours_change_percent, volume, source)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON DUPLICATE KEY UPDATE
            open_price = VALUES(open_price),
            high_price = VALUES(high_price),
            low_price = VALUES(low_price),
            close_price = VALUES(close_price),
            change_percent = VALUES(change_percent),
            after_hours = VALUES(after_hours),
            after_hours_change_percent = VALUES(after_hours_change_percent),
            volume = VALUES(volume)
    """
    cursor.execute(sql, (symbol, trade_date, open_p, high_p, low_p, close_p, 
                          change_pct, after_hours, after_hours_chg, volume, source))
    conn.commit()
    cursor.close()
    conn.close()

# 调用示例（从 yfinance/twelvedata 获取数据后）
# TwelveData 直接返回 changePercent，其他数据源需计算
for day in daily_data:
    change_pct = day.get('changePercent')  # TwelveData 直接返回
    if change_pct is None:
        # 其他数据源：需要查询前一日收盘价计算
        change_pct = calculate_change_percent(symbol, day['date'], day['close'])
    
    save_daily_bar(
        symbol=symbol,
        trade_date=day['date'],
        open_p=day['open'],
        high_p=day['high'],           # 新增
        low_p=day['low'],             # 新增
        close_p=day['close'],
        change_pct=change_pct,        # 新增（涨跌幅）
        after_hours=None,             # 暂留空（数据源不支持盘后价）
        after_hours_chg=None,         # 暂留空
        volume=day['volume'],
        source='yfinance'
    )
```

---

### 2.5 全链路审计报告 — 五个新增字段在各数据链路中的覆盖情况

基于源码逐文件审计，五大字段（`high_price` / `low_price` / `change_percent` / `after_hours` / `after_hours_change_percent`）在各数据层级的实际支持情况如下。

#### 2.5.1 实体与模型层

| 文件 | 已有字段 | 缺失字段 | 审计依据 |
|------|---------|---------|---------|
| `StockDailyBar.java` | `openPrice`, `closePrice`, `volume` | **全部五项缺失**：`highPrice`, `lowPrice`, `changePercent`, `afterHours`, `afterHoursChangePercent` | 第 49-57 行仅声明 3 个价格字段 |
| `KLineIterator.java` | `high`, `low` ✅ | `changePercent`, `afterHours`, `afterHoursChangePercent`（需从数据源透传） | 第 7-16 行已有 high/low/amount，无百分比与盘后字段 |
| `KLineData.java` | `high`, `low` ✅ | 同上（数据由 KLineIterator 承载） | 第 16-21 行已有 |
| `StockInfo.java` | `changePercent` ✅ | `highPrice`, `lowPrice`, `afterHours`, `afterHoursChangePercent` | 第 13-19 行，仅有 symbol/name/currentPrice/openPrice/volume/change/changePercent |
| `schema.sql` `stock_daily_bar` 表 | `open_price`, `close_price`, `volume` | **全部五列缺失** | 第 38-53 行 |

#### 2.5.2 数据源层 — 各数据源对五个字段的返回情况

##### TwelveData（两路：Python 脚本 + Java REST Client）

| 端点 | high | low | changePercent | afterHours | afterHoursChangePercent | 审计依据 |
|------|------|-----|-------------|-----------|----------------------|---------|
| `get_stock_info()` (Python) | ✅ `/quote` 返回 `high` | ✅ `/quote` 返回 `low` | ✅ **`/quote` 直接返回 `percent_change`**（第 72 行已提取 ✅） | ❌ 不支持 | ❌ 不支持 | `stock_info_twelvedata.py` 第 62-73 行 |
| `get_daily_kline()` (Python) | ✅ time_series 返回 `high` | ✅ time_series 返回 `low` | ❌ time_series 不返回 | ❌ 不支持 | ❌ 不支持 | 第 107-108 行 |
| `get_daily_kline_range()` (Python) | ✅ | ✅ | ❌ | ❌ | ❌ | 第 138-139 行 |
| `fetchDailyBars()` (Java REST) | ✅ 已解析 `high` | ✅ 已解析 `low` | ❌ 未提取 | ❌ 不支持 | ❌ 不支持 | `TwelveDataRestClient.java` 第 134-136 行 |

**日志现状**：`TwelveDataStockServiceImpl.getDailyKLineDataByDateRange()` 第 80 行打印 `[TwelveDataStockServiceImpl] dateRange symbol=..., range=[...,...]`；getDailyKLine() 第 138-139 行打印响应长度。**新字段查询结果无日志**。

##### YFinance（Python 脚本）

| 端点 | high | low | changePercent | afterHours | afterHoursChangePercent | 审计依据 |
|------|------|-----|-------------|-----------|----------------------|---------|
| `get_stock_info()` | ✅ `hist["High"]` | ✅ `hist["Low"]` | ⚠️ **计算错误**：用 `(Close-Open)/Open*100`（第 83 行），**标准涨跌幅应为 `(Close-PrevClose)/PrevClose*100`**。应改用 `info["regularMarketChangePercent"]`（第 66 行已获取 `stock.info`，但未提取此字段） | ❌ **未提取**：`info` 对象已获取（第 66 行），但未提取 `info["postMarketPrice"]` / `info["postMarketChangePercent"]`（YFinance 原生支持，一次调用即返回） | ❌ 同上 | `stock_info_yfinance.py` 第 66-84 行 |
| `get_daily_kline()` | ✅ `hist["High"]` | ✅ `hist["Low"]` | ❌ 不返回 | ❌ 不返回 | ❌ 不返回 | 第 136-137 行 |
| `get_daily_kline_range()` | ✅ | ✅ | ❌ | ❌ | ❌ | 第 167-168 行 |

**日志现状**：YFinanceStockServiceImpl 基本无详细日志，仅基础 warn/error。**新字段查询结果无日志**。

##### Tiingo（Java REST Client）

| 端点 | high | low | changePercent | afterHours | afterHoursChangePercent | 审计依据 |
|------|------|-----|-------------|-----------|----------------------|---------|
| `fetchDailyBars()` | ✅ `row.path("high")` 已解析 | ✅ `row.path("low")` 已解析 | ❌ 不返回（API 返回 OHLC/V，无百分比） | ❌ 不支持 | ❌ 不支持 | `TiingoRestClient.java` 第 95-96 行 |
| `getStockInfo()` (TiingoDataSourceStrategy) | ✅ (从 kline 取) | ✅ (从 kline 取) | ⚠️ **计算错误**：用 `(close-open)/open*100`（第 108-110 行） | ❌ 不支持 | ❌ 不支持 | `TiingoDataSourceStrategy.java` 第 100-111 行 |

**日志现状**：TiingoDataSourceStrategy 打印 dateRange 请求信息。**新字段无日志**。

##### Tiger Java SDK

| 端点 | high | low | changePercent | afterHours | afterHoursChangePercent | 审计依据 |
|------|------|-----|-------------|-----------|----------------------|---------|
| `getDailyKLineDataAsObject()` | ✅ `point.getHigh()` | ✅ `point.getLow()` | ❌ KlinePoint 无此字段 | ⚠️ `TradeSession.AfterHours` 可获取盘后 K 线（需独立调用，P1） | ❌ 需计算 | `TigerStockServiceImpl.java` 第 411-422 行 |
| `getStockInfo()` | ❌ 未返回 | ❌ 未返回 | ⚠️ **计算错误**：`(close-open)/open*100`（第 230 行） | ❌ 不支持 | ❌ 不支持 | 第 210-239 行 |

**日志现状**：TigerStockServiceImpl 使用中文日志，包含请求和错误信息。**新字段无日志**。

##### TigerOpen Python Bridge

| 端点 | high | low | changePercent | afterHours | afterHoursChangePercent | 审计依据 |
|------|------|-----|-------------|-----------|----------------------|---------|
| `_cmd_bars()` | ✅ `row["high"]` | ✅ `row["low"]` | ❌ 不返回 | ⚠️ `TradingSession.AfterHours` 可获取（需独立调用，P1） | ❌ 需计算 | `tigeropen_channel.py` 第 69-70 行 |

#### 2.5.3 数据持久化层 — 落库遗漏

##### DataGapFillerServiceImpl.persist() — 补缺数据落库

```java
// 第 405-420 行：仅持久化以下字段
bar.setOpenPrice(item.getOpen());
bar.setClosePrice(item.getClose());
bar.setVolume(item.getVolume());
bar.setSource(source);
```

**遗漏字段**：`highPrice`, `lowPrice`, `changePercent`, `afterHours`, `afterHoursChangePercent` — **全部五个缺失**。

**日志现状**：DataGapFillerServiceImpl 有完善的日志体系，包含：
- 彩色分隔线（`\033[31m` / `\033[32m` / `\033[34m`）
- 每 source 开始/结束标记
- item 级别日志（第 326-328 行）：打印 symbol, epochTime, timeString, parsedDate, open, close
- **但新字段 high/low/changePercent 未出现在 item 日志中**

##### TigerWatchlistIngestServiceImpl.ingest() — 老虎截图导入落库

```java
// 第 95-107 行：仅持久化以下字段
bar.setOpenPrice(px);
bar.setClosePrice(px);
bar.setVolume(vol);
bar.setSource(SNAPSHOT_SOURCE);
```

分析：老虎截图数据源只有 lastPrice（收盘价 = 开盘价 ≈ 当前价），**天然没有 high/low/changePercent/afterHours**。且 openPrice 和 closePrice 被设置为相同的 lastPrice 值。

**影响**：该数据源导入的股票，五个新增字段将全部为 NULL，符合"新增字段不可设为必填"的要求。K 线展示时需优雅处理 NULL。

**日志现状**：TigerIngest 打印 batch 级日志（第 50 行）和每个 symbol 的保存日志（第 109-110 行），但**新字段无日志**。

#### 2.5.4 API 层

| API 端点 | 状态 | 审计依据 |
|----------|------|---------|
| `GET /api/bars/{symbol}/candles?days=7` | **不存在**（方案需求） | `BarsController.java` 只有 `/single/query` 和 `/pages/query` |
| `GET /api/bars/single/query?symbol=X` | 存在 ✅，但返回原始 entity，不含蜡烛图相关字段 | 第 37-48 行 |
| Repository `findTop7BySymbolOrderByTradeDateDesc()` | 存在 ✅，可直接复用 | `StockDailyBarRepository.java` 第 17 行 |

#### 2.5.5 发版注意事项 — 字段不可设为必填

所有五个新增字段（`high_price`, `low_price`, `change_percent`, `after_hours`, `after_hours_change_percent`）**必须设为 NULLABLE**，原因：

| 数据源/场景 | 原因 |
|------------|------|
| **老虎截图导入** (`TigerWatchlistIngestService`) | 只有 lastPrice 和 volume，没有 OHLC 数据，五个字段全为 NULL |
| **补缺历史数据** | SQL 回填前旧数据 all NULL |
| **Tiingo / Tiger SDK** | 不返回 changePercent，需手动计算或留 NULL |
| **盘后价数据** | YFinance 支持，其他数据源不支持，部分股票无盘后交易 |
| **新增字段 ALTER TABLE** | 用 `ADD COLUMN xxx DOUBLE NULL`，不设 DEFAULT（避免误导） |

#### 2.5.6 日志改造要求

> **原则**：不改变现有日志风格（彩色分隔线、每 source 标记、item 级别日志都已存在），只在现有日志基础上**追加新字段的打印**。

**需改动的日志点**：

| 文件 | 现有日志 | 需追加的字段 |
|------|---------|-------------|
| `DataGapFillerServiceImpl.fetchAndPersist()` 第 326-328 行 | 打印 open, close | 追加 high, low, changePercent（若数据源返回） |
| `DataGapFillerServiceImpl.persist()` | 无持久化日志 | 新增一条日志打印保存的五个字段值 |
| `TwelveDataStockServiceImpl.getDailyKLineDataByDateRange()` | 打印 range 信息 | 追加结果中第一个 item 的 high/low |
| `YFinanceStockServiceImpl.getDailyKLineDataByDateRange()` 第 221 行 | 打印 range 信息 | 追加结果中第一个 item 的 high/low/changePercent |
| `TigerWatchlistIngestServiceImpl.ingest()` 第 109-110 行 | 打印 price, volume, date | 追加（字段均为 NULL，可注明 "high=null, low=null, ..."） |

**不修改的日志**：
- 现有彩色分隔线风格不变（`\033[31m` 红色标题、`\033[32m` 绿色开始/结束、`\033[34m` 蓝色请求/响应）
- 现有每 source 打印分隔线的格式不变
- 现有 item 日志之间的空行不变

#### 2.5.7 全链路审计汇总表

| # | 文件/模块 | highPrice | lowPrice | changePercent | afterHours | afterHoursChangePercent | 审计状态 | 说明 |
|---|----------|-----------|---------|-------------|-----------|----------------------|---------|------|
| 1 | `StockDailyBar.java` 实体 | ❌ 缺失 | ❌ 缺失 | ❌ 缺失 | ❌ 缺失 | ❌ 缺失 | P0 待增 | 全部五项缺失 |
| 2 | 数据库 `stock_daily_bar` 表 | ❌ 缺失 | ❌ 缺失 | ❌ 缺失 | ❌ 缺失 | ❌ 缺失 | P0 待增 | schema.sql 无对应列 |
| 3 | `KLineIterator.java` 模型 | ✅ 已有 | ✅ 已有 | ❌ 缺失 | ❌ 缺失 | ❌ 缺失 | P0 待增 | 需加 3 个透传字段 |
| 4 | `KLineData.java` 模型 | ✅ 已有 | ✅ 已有 | ❌ 缺失 | ❌ 缺失 | ❌ 缺失 | P0 待增 | 通过 KLineIterator 承载 |
| 5 | `StockDailyBarCandleDto.java` | ❌ 不存在 | ❌ 不存在 | ❌ 不存在 | ❌ 不存在 | ❌ 不存在 | P0 待新建 | API DTO |
| 6 | `StockDailyBarService` | ❌ 不存在 | ❌ 不存在 | ❌ 不存在 | ❌ 不存在 | ❌ 不存在 | P0 待新建 | 需新增 getRecentCandles() |
| 7 | `BarsController.java` | ❌ 无对应端点 | ❌ 无对应端点 | ❌ 无对应端点 | ❌ 无对应端点 | ❌ 无对应端点 | P0 待增 | 需新增 /{symbol}/candles |
| 8 | `DataGapFillerServiceImpl.persist()` | ❌ 未持久化 | ❌ 未持久化 | ❌ 未持久化 | ❌ 未持久化 | ❌ 未持久化 | P0 待改 | 仅写 open/close/vol/source |
| 9 | `TigerWatchlistIngestServiceImpl` | ❌ 无 OHLC 数据 | ❌ 无 OHLC 数据 | ❌ 无 | ❌ 无 | ❌ 无 | 声明不可为必填 | 天然无这五个字段 |
| 10 | `stock_info_yfinance.py` | ✅ 已提取 | ✅ 已提取 | ⚠️ 计算错误（用 open 而非 prevClose），且未用 `info["regularMarketChangePercent"]` | ❌ info 已有但未提取 | ❌ info 已有但未提取 | P0 待改 | stock.info 一次调用即包含全部 |
| 11 | `stock_info_twelvedata.py` | ✅ 已提取 | ✅ 已提取 | ✅ `/quote` 直接返回 `percent_change` | ❌ 不支持 | ❌ 不支持 | ✅ 无需修改、仅需透传 | |
| 12 | `TiingoRestClient.java` | ✅ 已提取 | ✅ 已提取 | ❌ 无字段 | ❌ 不支持 | ❌ 不支持 | ✅ 无需修改 | changePercent 在 Service 层计算 |
| 13 | `TiingoDataSourceStrategy.getStockInfo()` | ❌ 未提取 | ❌ 未提取 | ⚠️ 计算错误（用 open 而非 prevClose） | ❌ 不支持 | ❌ 不支持 | P0 待改 | 需改为标准涨跌幅公式 |
| 14 | `TigerStockServiceImpl.java` | ✅ `point.getHigh()` | ✅ `point.getLow()` | ❌ KlinePoint 无此字段 | ⚠️ TradeSession.AfterHours 可获取（P1） | ❌ 需计算 | P0/KLine 部分无需改，P1 加盘后 | |
| 15 | `TigerStockServiceImpl.getStockInfo()` | ❌ 未提取 | ❌ 未提取 | ⚠️ 计算错误（用 open 而非 prevClose） | ❌ 不支持 | ❌ 不支持 | P0 待改 | |
| 16 | `tigeropen_channel.py` | ✅ `row["high"]` | ✅ `row["low"]` | ❌ 不返回 | ⚠️ TradingSession.AfterHours（P1） | ❌ 需计算 | ✅ 透传即可 | |
| 17 | 数据回填 SQL | ❌ 未执行 | ❌ 未执行 | ❌ 未执行 | ❌ 未执行 | ❌ 未执行 | P0 待执行 | 发版后 DBA 执行 |
| 18 | 前端 `package.json` (echarts) | — | — | — | — | — | P0 待安装 | |
| 19 | 前端 `ScreenerView.vue` | ❌ 未实现 | ❌ 未实现 | ❌ 未实现 | ❌ 未实现 | ❌ 未实现 | P0 待开发 | 无 K 线组件 |
| 20 | 前端 `MarketView.vue` | ❌ 未展示 | ❌ 未展示 | ❌ 未展示 | ❌ 未展示 | ❌ 未展示 | P0 待改 | 无新增列 |

#### 2.5.8 日志改进清单

| # | 文件 | 行号 | 当前日志 | 需追加内容 | 优先级 |
|---|------|------|---------|-----------|-------|
| L1 | `DataGapFillerServiceImpl.fetchAndPersist()` | 326-328 | `open={}, close={}` | 追加 `high={}, low={}, changePercent={}` | P0 |
| L2 | `DataGapFillerServiceImpl.persist()` | 405-420 | 无持久化日志 | 新增一条 `save: symbol={}, date={}, open={}, high={}, low={}, close={}, changePct={}, vol={}` | P0 |
| L3 | `YFinanceStockServiceImpl.getDailyKLineDataByDateRange()` | 221 | `range=[{},{}]` | 追加响应中第一个 item 的 `high={}, low={}, close={}` | P0 |
| L4 | `TwelveDataStockServiceImpl.getDailyKLineDataByDateRange()` | 80 | `range=[{},{}]` | 追加响应中第一个 item 的 `high={}, low={}` | P0 |
| L5 | `TigerWatchlistIngestServiceImpl.ingest()` | 109-110 | `price={}, volume={}, date={}` | 追加 `high=null, low=null, changePct=null`（说明天然缺失） | P0 |
| L6 | `TiingoDataSourceStrategy.getDailyKLineDataByDateRange()` | 77 | `range=[{},{}]` | 追加响应中第一个 item 的 `high={}, low={}` | P1 |
| L7 | `TiingoDataSourceStrategy.getStockInfo()` | 100-111 | 无 info 日志 | 追加返回的 changePercent（修复计算后） | P1 |

**日志风格要求**（保持现有风格）：
```java
// 现有风格示例（DataGapFillerServiceImpl）：
log.info("");                                                  // 空行分隔
log.info("[DataGapFiller] ================================");  // 分隔线
log.info("\033[32m[DataGapFiller] {} source start\033[0m", name);  // 彩色开始标记
log.info("\033[34m[DataGapFiller] {} source then received response:\033[0m itemsCount={}", name, count);  // 蓝色响应标记

// 新增的日志需遵循同一风格：
log.info("[DataGapFiller] {} source item: symbol={}, open={}, high={}, low={}, close={}, changePercent={}",
         source.name, symbol, open, high, low, close, changePct);
```

---

## 三、后端改动

### 3.1 新增 DTO：`StockDailyBarCandleDto.java`

```java
package com.stock.invest.enums.dto;

import java.time.LocalDate;

/**
 * K 线蜡烛图数据项
 */
public record StockDailyBarCandleDto(
    String date,           // 格式: yyyy-MM-dd
    Double open,           // 开盘价
    Double high,           // 最高价
    Double low,            // 最低价
    Double close,          // 收盘价
    Double changePercent,  // 涨跌幅（%）
    Double afterHours,     // 盘后价
    Double afterHoursChangePercent,  // 盘后涨跌幅（%）
    Long volume            // 成交量
) {}
```

### 3.2 Repository 层：`StockDailyBarRepository.java`

已有方法：
```java
List<StockDailyBar> findTop7BySymbolOrderByTradeDateDesc(String symbol);
```

**复用该方法即可**，无需新增查询方法。返回后由 Service 转换为 DTO。

> 注意：当前返回的是 `Top7`，但蜡烛图需要按时间**正序**展示（从左到右）。需要在 Service 层反转列表。

### 3.3 Service 层：新增 `StockDailyBarService.getRecentCandles(symbol, days)`

```java
@Service
public class StockDailyBarService {
    
    @Autowired
    private StockDailyBarRepository repository;
    
    public List<StockDailyBarCandleDto> getRecentCandles(String symbol, int days) {
        // 查询最近 days 条，按 tradeDate 倒序
        List<StockDailyBar> bars = repository.findTop7BySymbolOrderByTradeDateDesc(symbol);
        
        // 反转成正序（旧→新），并限制条数
        Collections.reverse(bars);
        if (bars.size() > days) {
            bars = bars.subList(bars.size() - days, bars.size());
        }
        
        return bars.stream()
            .map(bar -> {
                Double open = bar.getOpenPrice();
                Double close = bar.getClosePrice();
                Double prevClose = ...; // 需要前一日收盘价计算涨跌幅
                Double changePercent = prevClose != null && prevClose != 0 
                    ? ((close - prevClose) / prevClose) * 100 
                    : 0.0;
                
                return new StockDailyBarCandleDto(
                    bar.getTradeDate().toString(),
                    open,                           // 开盘价
                    bar.getHighPrice(),             // 最高价
                    bar.getLowPrice(),              // 最低价
                    close,                          // 收盘价
                    changePercent,                  // 涨跌幅（%）
                    null,                           // 盘后价（数据源不支持，暂留空）
                    null,                           // 盘后涨跌幅（数据源不支持，暂留空）
                    bar.getVolume()                 // 成交量
                );
            })
            .toList();
    }
}
```

### 3.4 Controller 层：复用 `BarsController`

在 `BarsController` 中新增端点（已有 `/api/bars` 前缀）：

```java
@RestController
@RequestMapping("/api/bars")
public class BarsController {
    
    // ... 现有代码 ...
    
    /**
     * GET /api/bars/{symbol}/candles?days=7
     * 返回最近 N 天 K 线数据
     */
    @GetMapping("/{symbol}/candles")
    public ResponseEntity<ApiResponse<List<StockDailyBarCandleDto>>> getCandles(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "7") int days) {
        try {
            List<StockDailyBarCandleDto> candles = stockDailyBarService.getRecentCandles(symbol, days);
            return ResponseEntity.ok(ApiResponse.ok(candles));
        } catch (Exception e) {
            log.error("getCandles failed symbol={}", symbol, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve candle data"));
        }
    }
}
```

### 3.5 现有 `ScreeningController` 修改（不做）

**明确决策：不做修改。**

理由：
- 保持 `ScreeningController` 职责单一，只返回筛选结果列表
- K 线数据通过独立的 `BarsController.getCandles()` 接口按需获取
- 避免批量查询时一次性加载大量 K 线数据导致性能问题
- 前端点击股票时才请求对应 K 线，符合懒加载原则

---

## 四、前端改动

### 4.1 安装依赖

```bash
cd /home/allon/application/stock-invest/frontend
npm install echarts vue-echarts
```

### 4.2 新增 API：`frontend/src/api/bars.ts`（已有，需扩展）

```typescript
import request from './request'
import type { ApiResponse } from './request'

export interface CandleData {
  date: string
  open: number
  high: number
  low: number
  close: number
  changePercent: number    // 涨跌幅（%）
  afterHours: number | null       // 盘后价
  afterHoursChangePercent: number | null  // 盘后涨跌幅（%）
  volume: number
}

/** 获取股票最近 K 线数据 */
export function fetchCandles(symbol: string, days: number = 7) {
  return request.get<ApiResponse<CandleData[]>>(`/api/bars/${symbol}/candles?days=${days}`)
}
```

### 4.3 `ScreenerView.vue` 改造

#### 4.3.1 引入 ECharts 组件

```vue
<script setup lang="ts">
import { ref, h, onMounted } from 'vue'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { CandlestickChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, DataZoomComponent, TitleComponent } from 'echarts/components'
import VChart from 'vue-echarts'

use([CanvasRenderer, CandlestickChart, GridComponent, TooltipComponent, DataZoomComponent, TitleComponent])

// ... 现有 imports ...
import { fetchCandles } from '../api/bars'
import type { CandleData } from '../api/bars'
</script>
```

#### 4.3.2 新增状态

```typescript
// ===================== 蜡烛图状态 =====================
const selectedSymbol = ref<string | null>(null)
const candleLoading = ref(false)
const candleData = ref<CandleData[]>([])
const showCandleChart = ref(false)
```

#### 4.3.3 新增方法：点击股票加载蜡烛图

```typescript
async function onSymbolClick(symbol: string) {
  if (selectedSymbol.value === symbol && showCandleChart.value) {
    // 再次点击同一股票，关闭图表
    showCandleChart.value = false
    selectedSymbol.value = null
    return
  }
  
  selectedSymbol.value = symbol
  showCandleChart.value = true
  candleLoading.value = true
  
  try {
    const res = await fetchCandles(symbol, 7)
    if (res.data.success) {
      candleData.value = res.data.data || []
    } else {
      candleData.value = []
      notification.error({ title: 'K线数据加载失败', duration: 3000 })
    }
  } catch (err: any) {
    candleData.value = []
    notification.error({ title: 'K线数据异常', content: err.message, duration: 3000 })
  } finally {
    candleLoading.value = false
  }
}
```

#### 4.3.4 修改表格列：股票代码可点击

```typescript
const detailColumns = [
  { 
    title: '代码', 
    key: 'symbol', 
    width: 120, 
    align: 'center' as const,
    render: (row: ScreeningMatch) => {
      const isSelected = selectedSymbol.value === row.symbol && showCandleChart.value
      return h('span', {
        class: 'symbol-text',
        style: `cursor:pointer;color:${isSelected ? '#52c41a' : '#1890ff'};font-weight:600;`,
        onClick: () => onSymbolClick(row.symbol)
      }, row.symbol)
    }
  },
  // ... 其他列不变 ...
]
```

#### 4.3.5 新增图表区域模板

在表格下方（`batch-detail` 内或独立区域）添加图表：

```vue
<!-- 蜡烛图展示区域 -->
<div v-if="showCandleChart" class="candle-chart-card">
  <div class="candle-chart-header">
    <span class="candle-title">📈 {{ selectedSymbol }} — 最近7天 K线</span>
    <NButton size="tiny" quaternary @click="showCandleChart = false">关闭</NButton>
  </div>
  
  <div v-if="candleLoading" class="candle-loading">
    <NSpin size="medium" />
    <span>加载 K线数据...</span>
  </div>
  
  <v-chart
    v-else-if="candleData.length > 0"
    class="candle-chart"
    :option="candleChartOption"
    autoresize
  />
  
  <div v-else class="candle-empty">
    <p>暂无 K线数据</p>
  </div>
</div>
```

#### 4.3.6 图表配置（统一尺度 + 缩放）

```typescript
const candleChartOption = computed(() => {
  const dates = candleData.value.map(d => d.date)
  const data = candleData.value.map(d => [d.open, d.close, d.low, d.high])
  
  // 计算统一尺度：取所有数据的 min/max，留 5% 边距
  const allPrices = candleData.value.flatMap(d => [d.open, d.high, d.low, d.close])
  const minPrice = Math.min(...allPrices)
  const maxPrice = Math.max(...allPrices)
  const padding = (maxPrice - minPrice) * 0.05
  
  return {
    title: { text: `${selectedSymbol.value} K线图`, left: 'center', textStyle: { fontSize: 14 } },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' },
      formatter: (params: any) => {
        const d = params[0]
        const cd = candleData.value[d.dataIndex]
        return `
          <div style="font-weight:600">${cd.date}</div>
          <div>开盘: ${cd.open.toFixed(4)}</div>
          <div>收盘: ${cd.close.toFixed(4)}</div>
          <div>最高: ${cd.high.toFixed(4)}</div>
          <div>最低: ${cd.low.toFixed(4)}</div>
          <div>涨跌幅: ${cd.changePercent.toFixed(2)}%</div>
          <div>盘后价: ${cd.afterHours ? cd.afterHours.toFixed(4) : '—'}</div>
          <div>盘后涨跌: ${cd.afterHoursChangePercent ? cd.afterHoursChangePercent.toFixed(2) + '%' : '—'}</div>
          <div>成交量: ${cd.volume.toLocaleString()}</div>
        `
      }
    },
    grid: { left: '10%', right: '10%', bottom: '15%' },
    xAxis: {
      type: 'category',
      data: dates,
      axisLine: { lineStyle: { color: '#666' } },
      axisLabel: { color: '#999' }
    },
    yAxis: {
      type: 'value',
      scale: true,           // 统一尺度：自动缩放但不从0开始
      min: minPrice - padding,
      max: maxPrice + padding,
      axisLine: { lineStyle: { color: '#666' } },
      axisLabel: { color: '#999' },
      splitLine: { lineStyle: { color: '#eee' } }
    },
    dataZoom: [
      { type: 'inside', start: 0, end: 100 },     // 鼠标滚轮缩放
      { type: 'slider', start: 0, end: 100, bottom: '5%' }  // 底部滑块缩放
    ],
    series: [{
      type: 'candlestick',
      data: data,
      itemStyle: {
        color: '#ef5350',        // 涨 — 红色
        color0: '#26a69a',       // 跌 — 绿色
        borderColor: '#ef5350',
        borderColor0: '#26a69a'
      }
    }]
  }
})
```

#### 4.3.7 新增样式

```css
.candle-chart-card {
  margin-top: 16px;
  background: var(--bg-card);
  border-radius: 10px;
  padding: 16px;
  box-shadow: var(--shadow-sm);
}

.candle-chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.candle-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.candle-chart {
  width: 100%;
  height: 400px;
}

.candle-loading,
.candle-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 200px;
  color: var(--text-tertiary);
}
```

### 4.4 行情页面（MarketView / KLineView）增加字段

在行情展示页面（实时行情或 K 线详情页）的表格/卡片中增加以下字段：

| 字段 | 来源 | 说明 |
|------|------|------|
| 最高价 | `high` | 当日最高价 |
| 最低价 | `low` | 当日最低价 |
| 涨跌幅 | `changePercent` | (close - prevClose) / prevClose * 100 |
| 盘后价 | `afterHours` | 盘后交易价格（如有） |
| 盘后涨跌幅 | `afterHoursChangePercent` | 盘后价格相对收盘价涨跌幅 |

前端表格列配置示例：
```typescript
const marketColumns = [
  { title: '代码', key: 'symbol', width: 100 },
  { title: '名称', key: 'name', width: 120 },
  { title: '开盘', key: 'open', width: 90, render: (row) => row.open?.toFixed(4) },
  { title: '最高', key: 'high', width: 90, render: (row) => row.high?.toFixed(4) },   // 新增
  { title: '最低', key: 'low', width: 90, render: (row) => row.low?.toFixed(4) },     // 新增
  { title: '收盘', key: 'close', width: 90, render: (row) => row.close?.toFixed(4) },
  { title: '涨跌', key: 'changePercent', width: 90, render: (row) => `${row.changePercent?.toFixed(2)}%` },  // 新增
  { title: '盘后', key: 'afterHours', width: 90, render: (row) => row.afterHours?.toFixed(4) || '—' },     // 新增
  { title: '盘后涨跌', key: 'afterHoursChangePercent', width: 100, render: (row) => row.afterHoursChangePercent ? `${row.afterHoursChangePercent.toFixed(2)}%` : '—' },  // 新增
  { title: '成交量', key: 'volume', width: 100, render: (row) => row.volume?.toLocaleString() }
]
```

---

## 五、文件改动清单

| 文件路径 | 改动类型 | 说明 |
|---------|---------|------|
| `stock_daily_bar` 表 | 修改 | 新增 `high_price`, `low_price`, `change_percent`, `after_hours`, `after_hours_change_percent` 字段 |
| `StockDailyBar.java` | 修改 | 新增 `highPrice`, `lowPrice`, `changePercent`, `afterHours`, `afterHoursChangePercent` 字段 |
| `StockDailyBarCandleDto.java` | 新增 | K 线数据 DTO（含 changePercent, afterHours, afterHoursChangePercent） |
| `StockDailyBarService.java` | 新增 | 查询并转换 K 线数据（含 changePercent 计算） |
| `BarsController.java` | 修改 | 新增 `GET /{symbol}/candles` 端点 |
| `frontend/package.json` | 修改 | 新增 `echarts`, `vue-echarts` 依赖 |
| `frontend/src/api/bars.ts` | 修改 | 新增 `fetchCandles` API |
| `ScreenerView.vue` | 修改 | 集成 ECharts 蜡烛图 |
| `MarketView.vue` / `KLineView.vue` | 修改 | 增加最高价、最低价、涨跌幅、盘后价、盘后涨跌幅列 |
| 数据采集脚本 | 修改 | 采集 high/low/changePercent 数据，afterHours 通过 Tiger SDK TradeSession 获取 |
| 文件改动清单 | 更新 | 增加 Tiger Java SDK 修改项 |

---

## 六、实施顺序建议

1. **P0 - 数据库层**：先执行 `ALTER TABLE` 添加字段（5列全部 `DOUBLE NULL`）
2. **P0 - 实体层**：修改 `StockDailyBar.java`（新增 5 个字段，全部 `nullable = true`）
3. **P0 - 模型层**：`KLineIterator.java` 新增 `changePercent`, `afterHours`, `afterHoursChangePercent` 三个透传字段
4. **P0 - YFinance 脚本修复**：`stock_info_yfinance.py` — `get_stock_info()` 改用 `info["regularMarketChangePercent"]`，追加 `info["postMarketPrice"]` 和 `info["postMarketChangePercent"]`
5. **P0 - Tiingo/Tiger changePercent 计算修复**：三个 `getStockInfo()` 统一改为标准 `(close - prevClose) / prevClose * 100`
6. **P0 - 日志追加**：按 2.5.8 日志改进清单，在 5 个文件中追加新字段的日志打印（保持现有彩色/分隔线风格）
7. **P0 - 后端 API**：新增 DTO → Service → Controller
8. **P0 - 前端依赖**：安装 echarts + vue-echarts
9. **P0 - 前端页面**：修改 ScreenerView.vue
10. **P0 - 数据回填**：运行 SQL 回填历史数据 high/low/changePercent
11. **P0 - 数据采集持久化**：修改 `DataGapFillerServiceImpl.persist()`，写入 highPrice/lowPrice/changePercent
12. **P0 - 行情页面**：MarketView / KLineView 增加字段展示
13. **P1 - 盘后价采集（Tiger SDK）**：优化 TigerStockServiceImpl，增加 TradeSession.AfterHours 或 TradeSession.All 拉取
14. **P1 - 盘后价采集（TigerOpen Python）**：优化 tigeropen_channel.py 的 `_cmd_bars`，增加 `trade_session=TradingSession.All`
15. **P1 - 盘后价合并**：编写合并逻辑，更新 `after_hours` / `after_hours_change_percent` 字段

---

## 七、风险与注意事项

| 风险 | 说明 | 缓解措施 |
|------|------|---------|
| 历史数据 high/low/changePercent 缺失 | 新增字段后，旧数据为 NULL | 用 SQL 近似回填 high/low，计算回填 changePercent |
|| 新字段不允许设为 NOT NULL | 老虎截图导入、补缺逻辑、Tiingo/Tiger SDK 均天然缺少部分字段 | 所有新增字段必须用 `DOUBLE NULL`，回填前已有数据全为 NULL |
|| changePercent 计算不准确 | 部分数据源不直接返回 | **YFinance** `stock.info["regularMarketChangePercent"]` 直接返回；**TwelveData** `/quote` 的 `percent_change` 直接返回；Tiingo / Tiger SDK 用 `(close - prevClose) / prevClose * 100` 计算 |
|| **TiingoDataSourceStrategy.getStockInfo() 计算错误** | 用 `(close - open) / open * 100` 而非标准涨跌幅 | 改为 `(close - prevClose) / prevClose * 100`，需查询前一日收盘价 |
|| **TigerStockServiceImpl.getStockInfo() 计算错误** | 用 `(close - open) / open * 100` 而非标准涨跌幅 | 同上，改为标准公式 |
|| YFinance changePercent 计算错误 | 当前脚本用 `(close - open) / open` 而非标准涨跌幅 | 改为 `info["regularMarketChangePercent"]` 直接从 stock.info 获取（第 66 行已获取 info，仅需提取字段） |
|| **YFinance afterHours 未提取** | `stock.info` 已在第 66 行获取，但 `info["postMarketPrice"]` 和 `info["postMarketChangePercent"]` 从未提取 | 在 `get_stock_info()` 返回值中追加 `afterHours` / `afterHoursChangePercent`（一次 info 调用即返回，无需额外请求） |
|| afterHours 数据源 | YFinance `stock.info["postMarketPrice"]` 直接返回；Tiger SDK 也支持 | **优先 YFinance**（一次 stock.info 调用即返回）；Tiger SDK `TradeSession.AfterHours` 作为 P1 备选 |
| afterHours 响应空数据 | 部分股票可能无盘后交易 | 字段允许 NULL，UI 显示 "—" |
| 前端包体积增加 | ECharts 体积约 300KB+ | 按需引入（只导入 candlestick + dataZoom 组件） |
| 数据量过大 | 单股票 7 天数据量很小，无性能问题 | 已限制 `days=7`，可扩展 |
| 并发点击 | 用户快速切换股票可能触发多个请求 | 添加 `AbortController` 或防抖 |

---

请确认此方案后，我将按步骤执行实施。