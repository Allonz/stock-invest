# Tiger 截图导入与快照筛选 API

> 面向 Hermes / OpenClaw 等自动化工具：OCR 识别老虎证券自选股截图后，调用本服务写入 `stock_daily_bar`，再按需触发仅基于数据库快照的量价筛选。

## 目录

- [基础信息](#基础信息)
- [1. 导入截图识别结果](#1-导入截图识别结果)
- [2. 基于快照运行筛选](#2-基于快照运行筛选)
- [3. 通用筛选接口](#3-通用筛选接口)
- [4. 查询筛选结果](#4-查询筛选结果)
- [运维](#运维)

---

## 基础信息

| 项目 | 值 |
|------|-----|
| 默认地址 | `http://127.0.0.1:8090` |
| 截图列 | 代码 / 名称 / 最新价 / 成交量 |
| 数据源标识 | `source=tiger_snap` |
| 鉴权 | 若配置 `INGEST_API_KEY`，须在请求头携带 `X-INGEST-API-KEY`；未配置时不校验 |

---

## 1. 导入截图识别结果

```
POST /api/ingest/tiger-watchlist
Content-Type: application/json
```

按 `tradeDate` 对每行 upsert 一条 `StockDailyBar`：
- `open = close = lastPrice`
- `volume` 经解析后写入（支持 `7.33万`、`1.47亿` 等中文单位）
- `source` 固定为 `tiger_snap`
- `stockName` 可选写入

### 请求体

**顶层：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `tradeDate` | string | ✅ | 交易日，ISO 日期，如 `2026-04-26` |
| `rows` | array | ✅ | 非空数组，每行为一个 `TigerWatchlistRowDto` |

**行对象 `TigerWatchlistRowDto`：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `symbol` / `code` | string | ✅ 二选一 | 美股代码，大写字母 + 数字 |
| `name` | string | 否 | 股票名称，最长 128 字符 |
| `lastPrice` | number | ✅ | 最新价，须 > 0 |
| `volume` | string \| number | ✅ | 成交量：纯数字 / `7.33万` / `1.47亿` 等 |

### 响应 `TigerWatchlistIngestResponseDto`

| 字段 | 说明 |
|------|------|
| `batchId` | 本批次 UUID |
| `tradeDate` | 解析后的交易日 |
| `imported` | 成功写入行数 |
| `skipped` | 跳过行数 |
| `skipReasons` | 跳过原因列表 |

### 示例

```bash
curl -sS -X POST 'http://127.0.0.1:8090/api/ingest/tiger-watchlist' \
  -H 'Content-Type: application/json' \
  -H 'X-INGEST-API-KEY: your-secret' \
  -d '{
    "tradeDate": "2026-04-26",
    "rows": [
      { "symbol": "JOB",  "name": "Gee Group", "lastPrice": 0.2364, "volume": "7.33万" },
      { "code":   "CHSN", "name": "香颂国际",   "lastPrice": 0.1837, "volume": "1.47亿" }
    ]
  }'
```

---

## 2. 基于快照运行筛选

```
POST /api/screener/run-from-snapshot
```

从当日已导入的 `tiger_snap` 数据取候选 → 对每标的取最近 `windowDays` 条同 source K 线 → 按周期校验量价递增规则。**不请求外部行情 API。**

### Query 参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `date` | string | 当天 | 交易日，如 `2026-04-26` |
| `limit` | int | `20` | 最多命中条数 |
| `windowDays` | int | `7` | 量价判断周期天数，范围 `3-7`，超出自动收敛到边界 |

> 候选收盘价范围由 `scanner.minPrice` / `scanner.maxPrice` 控制。

### 响应 `ScreenerRunResponseDto`

| 字段 | 说明 |
|------|------|
| `batchId` | 本次筛选批次 UUID |
| `tradeDate` | 交易日 |
| `totalCandidates` | 候选标的总数 |
| `processedStocks` | 实际处理数 |
| `matchedStocks` | 命中筛选规则的标的列表 |

### 示例

```bash
curl -sS -X POST \
  'http://127.0.0.1:8090/api/screener/run-from-snapshot?date=2026-04-26&limit=20&windowDays=5' \
  -H 'X-INGEST-API-KEY: your-secret'
```

---

## 3. 通用筛选接口

```
POST /api/screener/run
```

与 `run-from-snapshot` 相同的 Query 参数（`date` / `limit` / `windowDays`），但数据源走 `MarketDataSourceRouter`（可拉取外部 API）。

| 对比 | `run-from-snapshot` | `run` |
|------|---------------------|-------|
| 数据源 | 仅数据库快照 | 外部 API + 数据库 |
| 鉴权 | 需 `X-INGEST-API-KEY` | 无需 |
| 适用场景 | 截图导入后快速筛选 | 独立全量扫描 |

---

## 4. 查询筛选结果

| 接口 | 说明 |
|------|------|
| `GET /api/screener/results?tradeDate=2026-04-26` | 按交易日查询 |
| `GET /api/screener/results/latest` | 最新一批结果 |

---

## 运维

### 定时任务

`scanner.scheduledScanSource`（环境变量 `SCANNER_SCHEDULED_SOURCE`）：

| 值 | 行为 |
|----|------|
| `snapshot`（默认） | 定时任务调用**快照筛选**，不拉外部 API |
| `external_api` | 沿用 `MarketDataSourceRouter` 路径 |

### 页面

- 页面 `/screener/daily` 的"执行筛选"表单支持 `windowDays` 下拉（3/4/5/6/7）
- 未显式传参时默认 7 天

### 配置摘要

| 配置项 | 说明 |
|--------|------|
| `ingest.api-key` / `INGEST_API_KEY` | 导入与筛选接口的共享密钥 |
| `scanner.scheduledScanSource` | `snapshot` \| `external_api` |
| `scanner.minPrice` / `scanner.maxPrice` | 低价股候选区间 |
| `server.port` | 服务端口，默认 `8090` |
