# stock-invest API 接口文档

> 股票投资分析应用 REST API 参考文档
>
> 基础地址：`http://localhost:8090`
>
> 更新日期：2026-05-04

---

## 目录

1. [股票行情 API](#1-股票行情-api)
2. [股票筛选 API](#2-股票筛选-api)
3. [自选股导入 API](#3-自选股导入-api)
4. [筛选结果查询 API](#4-筛选结果查询-api)
5. [页面接口](#5-页面接口)
6. [模型定义](#6-模型定义)
7. [配置说明](#7-配置说明)

---

## 1. 股票行情 API

数据通过 `PriorityStockServiceImpl` 多源回退获取（优先顺序：tigeropen → tiger → yfinance → twelvedata → tiingo）。

### 1.1 获取日 K 线（JSON 字符串）

```
GET /api/tiger/kline/daily/{symbol}
```

**路径参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| symbol | String | 是 | 股票代码，如 `AAPL` |

**响应：** `200 OK`

```json
"{\"symbol\":\"AAPL\",\"items\":[...]}"
```

返回 JSON 序列化的 `KLineData` 字符串（注意：响应体是纯文本字符串，非 JSON 对象）。

---

### 1.2 获取日 K 线（对象格式）

```
GET /api/tiger/kline/daily/object/{symbol}
```

**路径参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| symbol | String | 是 | 股票代码，如 `AAPL` |

**响应：`200 OK`**

```json
{
  "symbol": "AAPL",
  "items": [
    {
      "time": 1746345600000,
      "open": 189.50,
      "high": 191.20,
      "low": 188.80,
      "close": 190.75,
      "volume": 45231000
    },
    ...
  ]
}
```

返回 `KLineData` 对象（`symbol` + `items` 列表）。

---

### 1.3 查询日 K 线（带可选参数）

```
GET /api/tiger/kline/daily/query?symbol={symbol}&beginDate={beginDate}&endDate={endDate}&limit={limit}&pageToken={pageToken}
```

**查询参数**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| symbol | String | 是 | — | 股票代码 |
| beginDate | String | 否 | — | 开始日期，格式 `yyyy-MM-dd` |
| endDate | String | 否 | — | 结束日期，格式 `yyyy-MM-dd` |
| limit | Integer | 否 | `20` | 返回记录数 |
| pageToken | String | 否 | `""` | 分页标记（预留，当前未使用） |

> ⚠️ **注意：** 当前实现忽略 `beginDate`/`endDate`/`limit`/`pageToken` 参数，始终返回 `getDailyKLineData(symbol)` 的完整结果。

**响应：** `200 OK` — 同 [1.1](#11-获取日-k-线json-字符串) 的 JSON 字符串格式。

---

### 1.4 获取股票基本信息

```
GET /api/tiger/stock/{symbol}
```

**路径参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| symbol | String | 是 | 股票代码，如 `AAPL` |

**响应：`200 OK`**

```json
{
  "symbol": "AAPL",
  "name": "Apple Inc.",
  "currentPrice": 190.75,
  "openPrice": 189.50,
  "highPrice": 191.20,
  "lowPrice": 188.80,
  "volume": 45231000,
  "change": 1.25,
  "changePercent": 0.66,
  "latestPrice": 190.75,
  "changePrice": 1.25,
  "changeRate": 0.66,
  "latestVolume": 45231000,
  "marketCap": 2950000000000,
  "pe": 30.5,
  "week52High": 199.62,
  "week52Low": 164.08,
  "dividendYield": 0,
  "kLineItems": []
}
```

`kLineItems` 字段预留，当前不包含数据。

---

### 1.5 获取硬编码 AAPL 日 K 线

```
GET /api/tiger/kline/aapl
```

**响应：** `200 OK` — 同 [1.1](#11-获取日-k-线json-字符串)，硬编码为 AAPL。

---

## 2. 股票筛选 API

### 2.1 低价放量模式扫描

```
GET /api/tiger/scan/low-price-volume-pattern?limit={limit}
```

**查询参数**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| limit | Integer | 否 | `20` | 返回结果数量上限 |

**响应：`200 OK`**

```json
{
  "statistics": {
    "totalCandidates": 150,
    "processedStocks": 120,
    "matchingStocks": 15
  },
  "matchingStocks": [
    {
      "symbol": "SBFM",
      "name": "Sunshine Biopharma Inc.",
      "currentPrice": 0.12,
      "volume": 5842000,
      "kline": "KLineData 对象的 JSON 表示"
    }
  ]
}
```

数据源自动回退尝试 tigeropen → tiger → yfinance → twelvedata → tiingo。

---

### 2.2 低价放量模式扫描（固定 20 条）

```
GET /api/tiger/scan/low-price-volume-pattern/fixstock
```

同上，`limit` 固定为 20。

---

### 2.3 自定义股票筛选

```
POST /api/scanner/custom
Content-Type: application/json
```

**请求体：**

```json
{
  "market": "US",
  "category": "TOP_VOLUME",
  "limit": 20,
  "minPrice": 0.05,
  "maxPrice": 1000.0
}
```

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| market | String | 否 | `"US"` | 市场代码，可选 `US` / `HK` / `CN` |
| category | String | 否 | `"TOP_VOLUME"` | 筛选类型枚举 |
| limit | Integer | 否 | `20` | 返回结果数量上限 |
| minPrice | Double | 否 | — | 最低价格过滤（可选） |
| maxPrice | Double | 否 | — | 最高价格过滤（可选） |

**响应：`200 OK`**

```json
["AAPL", "MSFT", "GOOGL", "AMZN", "TSLA"]
```

返回匹配股票代码列表。

---

## 3. 自选股导入 API

### 3.1 导入老虎客户端自选股

```
POST /api/ingest/tiger-watchlist
Content-Type: application/json
X-INGEST-API-KEY: {apiKey}（可选）
```

**请求体：**

```json
{
  "tradeDate": "2026-04-26",
  "rows": [
    {
      "symbol": "AAPL",
      "name": "苹果",
      "lastPrice": 190.75,
      "volume": "4523.10万"
    },
    {
      "symbol": "MSFT",
      "name": "微软",
      "lastPrice": 420.50,
      "volume": "1856.20万"
    }
  ]
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| tradeDate | String | 是 | ISO-8601 日期，如 `2026-04-26` |
| rows | Array | 是 | 自选股行列表 |
| rows[].symbol | String | 是 | 股票代码 |
| rows[].name | String | 是 | 股票名称 |
| rows[].lastPrice | Double | 是 | 最新价 |
| rows[].volume | String | 是 | 成交量，保留中文单位（万/亿），如 `"4523.10万"`，后端自动解析 |

**请求头**

| 头字段 | 必填 | 说明 |
|--------|------|------|
| `X-INGEST-API-KEY` | 否 | 当 `ingest.api-key-required=true` 时必须提供。可以在配置中通过 `ingest.apiKey` 设置。 |

**响应：`200 OK`**

```json
{
  "batchId": "batch_20260426_abcdef",
  "tradeDate": "2026-04-26",
  "imported": 5,
  "skipped": 2,
  "skipReasons": [
    "symbol '???' is invalid — not found in Tiger market data",
    "duplicate symbol: SBFM"
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| batchId | String | 批次 ID，唯一标识本次导入 |
| tradeDate | String | 交易日期 |
| imported | Integer | 成功导入数量 |
| skipped | Integer | 跳过数量 |
| skipReasons | String[] | 跳过原因列表 |

**错误响应**

| 状态码 | 说明 |
|--------|------|
| `400 Bad Request` | 请求参数无效（如 `volume` 格式错误） |
| `401 Unauthorized` | API Key 未提供或不匹配（当 key 验证启用时） |

---

### 3.2 基于截图快照运行筛选

```
POST /api/screener/run-from-snapshot?date={date}&limit={limit}&windowDays={windowDays}
X-INGEST-API-KEY: {apiKey}（可选）
```

**查询参数**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| date | String | 否 | 当天 | ISO-8601 日期 |
| limit | Integer | 否 | `20` | 返回结果数量上限 |
| windowDays | Integer | 否 | `7` | K 线窗口天数（3~7） |

**请求头**

| 头字段 | 必填 | 说明 |
|--------|------|------|
| `X-INGEST-API-KEY` | 否 | 当 key 验证启用时必须提供 |

**响应：`200 OK`**

```json
{
  "batchId": "batch_20260426_abcdef",
  "tradeDate": "2026-04-26",
  "totalCandidates": 50,
  "processedStocks": 50,
  "matchedStocks": 8
}
```

> 本接口仅基于已导入的截图 K 线数据（`source=tiger_snap`）做量价形态筛选，不请求外部行情。

---

### 3.3 执行日常筛选（不依赖截图）

```
POST /api/screener/run?date={date}&limit={limit}&windowDays={windowDays}
```

**查询参数**

同 [3.2](#32-基于截图快照运行筛选)，但此接口会请求外部行情源进行筛选。

---

## 4. 筛选结果查询 API

### 4.1 按日期查询筛选结果

```
GET /api/screener/results?tradeDate={tradeDate}&minPrice={minPrice}&maxPrice={maxPrice}
```

**查询参数**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| tradeDate | String | 是 | — | ISO-8601 日期 |
| minPrice | Double | 否 | — | 最低价格过滤 |
| maxPrice | Double | 否 | — | 最高价格过滤 |

**响应：`200 OK`**

```json
[
  {
    "symbol": "SBFM",
    "price": 0.12,
    "rise": true,
    "source": "YFinance",
    "tradeDate": "2026-04-26"
  }
]
```

| 字段 | 类型 | 说明 |
|------|------|------|
| symbol | String | 股票代码 |
| price | Double | 当前价格 |
| rise | Boolean | 今日是否上涨（true=涨, false=跌） |
| source | String | 数据来源，如 `YFinance`, `Tiger`, `TwelveData` |
| tradeDate | String | 交易日期 |

---

### 4.2 查询最新筛选结果

```
GET /api/screener/results/latest?minPrice={minPrice}&maxPrice={maxPrice}
```

**查询参数**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| minPrice | Double | 否 | — | 最低价格过滤 |
| maxPrice | Double | 否 | — | 最高价格过滤 |

**响应：** 同 [4.1](#41-按日期查询筛选结果)。

---

## 5. 页面接口

### 5.1 筛选看板首页

```
GET /screener/daily?tradeDate={tradeDate}&minPrice={minPrice}&maxPrice={maxPrice}&windowDays={windowDays}&notice={notice}
```

返回 Thymeleaf 模板渲染的 HTML 页面，展示筛选结果矩阵和截图导入 K 线网格。

### 5.2 手动触发筛选（页面表单）

```
POST /screener/run?tradeDate={tradeDate}&limit={limit}&windowDays={windowDays}
```

重定向到 `GET /screener/daily`。

---

## 6. 模型定义

### 6.1 KLineData

| 字段 | 类型 | 说明 |
|------|------|------|
| symbol | String | 股票代码 |
| items | KLineIterator[] | K 线数据列表，按时间降序排列（最新在索引 0） |

### 6.2 KLineIterator

| 字段 | 类型 | 说明 |
|------|------|------|
| time | Long | Unix 毫秒时间戳 |
| open | Double | 开盘价 |
| high | Double | 最高价 |
| low | Double | 最低价 |
| close | Double | 收盘价 |
| volume | Long | 成交量 |

### 6.3 StockInfo

| 字段 | 类型 | 说明 |
|------|------|------|
| symbol | String | 股票代码 |
| name | String | 股票名称 |
| currentPrice | Double | 当前价格 |
| openPrice | Double | 开盘价 |
| highPrice | Double | 最高价 |
| lowPrice | Double | 最低价 |
| volume | Integer | 成交量 |
| change | Double | 价格变动 |
| changePercent | Double | 变动百分比（%） |
| latestPrice | Double | 最新价 |
| changePrice | Double | 变动价格 |
| changeRate | Double | 变动率 |
| latestVolume | Long | 最新成交量 |
| marketCap | Long | 市值 |
| pe | Double | 市盈率 |
| week52High | Double | 52 周最高 |
| week52Low | Double | 52 周最低 |
| dividendYield | Integer | 股息率（×10000） |

---

## 7. 配置说明

### 7.1 数据源 Profile

应用通过 Spring Profile 切换数据源：

| Profile | 数据源 | 启动命令 |
|---------|--------|----------|
| `twelvedata`（默认） | TwelveData API | `mvn spring-boot:run` |
| `tiger` | Tiger Brokers | `mvn spring-boot:run -Dspring.profiles.active=tiger` |
| `yfinance` | Yahoo Finance | `mvn spring-boot:run -Dspring.profiles.active=yfinance` |

### 7.2 关键环境变量

| 环境变量 | 说明 | 对应配置 |
|----------|------|----------|
| `TWELVEDATA_API_KEY` | TwelveData API Key | `twelvedata.api.apiKey` |
| `TWELVEDATA_API_KEYS` | 多个 API Key（逗号分隔，自动轮换） | `twelvedata.api.apiKeys` |
| `TIINGO_API_TOKEN` | Tiingo Token | `tiingo.api.token` |
| `TIGER_API_TIGER_ID` | 老虎证券 Tiger ID | `tiger.api.tiger_id` |
| `TIGER_API_PRIVATE_KEY_PK8` | 老虎证券私钥（PKCS#8 格式） | `tiger.api.private_key` |
| `TIGER_API_ACCOUNT` | 老虎证券账户号 | `tiger.api.account` |
| `HTTP_PROXY_HOST` | HTTP 代理地址 | `http.client.proxy-host` |
| `HTTP_PROXY_PORT` | HTTP 代理端口 | `http.client.proxy-port` |
| `MYSQL_DATABASE` | 数据库名称 | `spring.datasource.url` |
| `MYSQL_USERNAME` | 数据库用户 | `spring.datasource.username` |
| `MYSQL_PASSWORD` | 数据库密码 | `spring.datasource.password` |

---

## 附录：常见错误

| HTTP 状态码 | 错误场景 | 处理建议 |
|-------------|----------|----------|
| `400 Bad Request` | 请求参数格式错误（如日期格式错误、volume 格式异常） | 检查请求参数格式 |
| `401 Unauthorized` | API Key 不匹配 | 提供正确的 `X-INGEST-API-KEY` 或在配置中关闭 key 验证 |
| `404 Not Found` | 请求的路径不存在 | 检查 URL 路径 |
| `500 Internal Server Error` | 数据源异常或内部错误 | 检查日志 `logs/stock-invest/application.log` |

---

> 文档参考老虎开放平台格式编写。
