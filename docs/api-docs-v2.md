
 # Stock Invest API v2

## Overview

Spring Boot application running on port **8090**. All REST API endpoints are prefixed with `/api`. Thymeleaf page endpoints serve HTML directly.

**Base URL:** `http://localhost:8090`

**Last Updated:** 2026-05-10

---

## Endpoints Summary

| Method | Path | Controller | Description | Auth |
|--------|------|-----------|-------------|------|
| GET | `/api/tiger/kline/aapl` | StockController | Get AAPL daily K-line (hardcoded symbol, string response) | — |
| GET | `/api/tiger/kline/daily/{symbol}` | StockController | Get daily K-line for any symbol (string response) | — |
| GET | `/api/tiger/kline/daily/object/{symbol}` | StockController | Get daily K-line for any symbol (object response) | — |
| GET | `/api/tiger/kline/daily/query` | StockController | Query daily K-line with optional date range & pagination | — |
| GET | `/api/tiger/scan/low-price-volume-pattern` | StockController | Scan low-price stocks with volume pattern | — |
| GET | `/api/tiger/scan/low-price-volume-pattern/fixstock` | StockController | Scan low-price stocks with volume pattern (fixed limit=20) | — |
| POST | `/api/scanner/custom` | StockScannerController | Custom stock scanner with market/category/price filters | — |
| POST | `/api/screener/run` | ScreeningQueryController | Run daily screening scan (external market data) | — |
| POST | `/api/screener/run-from-snapshot` | ScreeningQueryController | Run screening from imported snapshot K-line only | X-INGEST-API-KEY |
| GET | `/api/screener/results` | ScreeningQueryController | Query screening results by trade date | — |
| GET | `/api/screener/results/latest` | ScreeningQueryController | Query latest screening results | — |
| POST | `/api/ingest/tiger-watchlist` | TigerWatchlistIngestController | Import Tiger Brokers watchlist/quote screenshot OCR data | X-INGEST-API-KEY |
| GET | `/screener/daily` | ScreeningPageController | Thymeleaf HTML — screening dashboard page | — |
| POST | `/screener/run` | ScreeningPageController | Thymeleaf HTML — trigger screening (redirects to `/screener/daily`) | — |
| GET | `/` | ScreeningPageController | Root redirect → `/screener/daily` | — |

---

## Authentication

### X-INGEST-API-KEY Header

Certain ingest and snapshot-based endpoints require an API key passed via the `X-INGEST-API-KEY` request header.

**Configuration (`application.yml`):**
```yaml
ingest:
  api-key: ""          # When non-empty, key verification is required
```

**Behavior:**
- If `ingest.api-key` is empty (default), the header is **optional** — requests without it will still succeed.
- If `ingest.api-key` is set to a non-empty value, the header **must** match that value exactly. Missing or mismatched keys return `401 Unauthorized`.

**Endpoints requiring this header:**
- `POST /api/ingest/tiger-watchlist`
- `POST /api/screener/run-from-snapshot`

---

## Endpoint Details

---

### [GET] /api/tiger/kline/aapl

**Description:** Returns the daily K-line data for Apple Inc. (AAPL) as a JSON string. The symbol is hardcoded to AAPL.

**Request Parameters:** None

**Request Example:**
```bash
curl http://localhost:8090/api/tiger/kline/aapl
```

**Response Example:**
```json
"{\"symbol\":\"AAPL\",\"items\":[{\"time\":1746345600000,\"open\":189.50,\"high\":191.20,\"low\":188.80,\"close\":190.75,\"volume\":45231000}]}"
```

> **Note:** The response body is a **JSON string** (not a JSON object). It must be parsed with `JSON.parse()` on the client.

**Error Codes:**
| HTTP Status | Condition |
|-------------|-----------|
| 200 | Success |
| 500 | Data source unavailable or internal error |

---

### [GET] /api/tiger/kline/daily/{symbol}

**Description:** Returns the daily K-line data for a specified stock symbol as a JSON string.

**Request Parameters:**
| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| symbol | String (path) | Yes | — | Stock symbol, e.g. `AAPL`, `MSFT`, `TSLA` |

**Request Example:**
```bash
curl http://localhost:8090/api/tiger/kline/daily/MSFT
```

**Response Example:**
```json
"{\"symbol\":\"MSFT\",\"items\":[{\"time\":1746345600000,\"open\":420.50,\"high\":425.10,\"low\":418.30,\"close\":423.80,\"volume\":18562000}]}"
```

**Error Codes:**
| HTTP Status | Condition |
|-------------|-----------|
| 200 | Success |
| 404 | Symbol not found |
| 500 | Data source unavailable or internal error |

---

### [GET] /api/tiger/kline/daily/object/{symbol}

**Description:** Returns the daily K-line data for a specified stock symbol as a proper JSON object.

**Request Parameters:**
| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| symbol | String (path) | Yes | — | Stock symbol, e.g. `AAPL`, `MSFT`, `TSLA` |

**Request Example:**
```bash
curl http://localhost:8090/api/tiger/kline/daily/object/AAPL
```

**Response Example:**
```json
{
  "symbol": "AAPL",
  "time": 1746345600000,
  "open": 189.50,
  "high": 191.20,
  "low": 188.80,
  "close": 190.75,
  "volume": 45231000,
  "amount": 8600000000.00,
  "items": [
    {
      "symbol": "AAPL",
      "time": 1746345600000,
      "open": 189.50,
      "high": 191.20,
      "low": 188.80,
      "close": 190.75,
      "volume": 45231000,
      "amount": 8600000000.00
    }
  ]
}
```

**Error Codes:**
| HTTP Status | Condition |
|-------------|-----------|
| 200 | Success |
| 404 | Symbol not found |
| 500 | Data source unavailable or internal error |

---

### [GET] /api/tiger/kline/daily/query

**Description:** Queries daily K-line data with optional date range and pagination parameters.

> **Note:** Current implementation ignores `beginDate`, `endDate`, `limit`, and `pageToken` — it always returns the full result of `getDailyKLineData(symbol)`. These parameters are reserved for future use.

**Request Parameters:**
| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| symbol | String | Yes | — | Stock symbol, e.g. `AAPL` |
| beginDate | String | No | — | Start date (`yyyy-MM-dd` format) |
| endDate | String | No | — | End date (`yyyy-MM-dd` format) |
| limit | Integer | No | `20` | Maximum records to return |
| pageToken | String | No | `""` | Pagination cursor token (reserved) |

**Request Example:**
```bash
curl "http://localhost:8090/api/tiger/kline/daily/query?symbol=AAPL&beginDate=2026-04-01&endDate=2026-05-01&limit=10"
```

**Response Example:** Same format as `GET /api/tiger/kline/daily/{symbol}` (JSON string).

**Error Codes:**
| HTTP Status | Condition |
|-------------|-----------|
| 200 | Success |
| 500 | Data source unavailable or internal error |

---

### [GET] /api/tiger/scan/low-price-volume-pattern

**Description:** Scans for low-price stocks (typically $0.05–$0.20 range) that exhibit a specific increasing volume pattern. The volume pattern checks that each preceding day's average volume is progressively lower than the next day's average volume. Data sources are tried in priority order: tigeropen → tiger → yfinance → twelvedata → tiingo.

**Request Parameters:**
| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| limit | Integer | No | `20` | Maximum number of matching stocks to return |

**Request Example:**
```bash
curl "http://localhost:8090/api/tiger/scan/low-price-volume-pattern?limit=10"
```

**Response Example:**
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
      "kline": "{...KLineData JSON...}"
    }
  ]
}
```

**Error Codes:**
| HTTP Status | Condition |
|-------------|-----------|
| 200 | Success |
| 500 | Data source unavailable or internal error |

---

### [GET] /api/tiger/scan/low-price-volume-pattern/fixstock

**Description:** Same as `/low-price-volume-pattern` but with a fixed limit of 20 (no parameter accepted). Equivalent to calling the above with `limit=20`.

**Request Parameters:** None

**Request Example:**
```bash
curl http://localhost:8090/api/tiger/scan/low-price-volume-pattern/fixstock
```

**Response Example:** Same format as `/low-price-volume-pattern`.

**Error Codes:**
| HTTP Status | Condition |
|-------------|-----------|
| 200 | Success |
| 500 | Data source unavailable or internal error |

---

### [POST] /api/scanner/custom

**Description:** Runs a custom stock scan using Tiger Brokers' market scanner API. Supports filtering by market, category, limit, and price range.

**Request Parameters (JSON body):**
| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| market | String | No | `"US"` | Market code: `US`, `HK`, or `CN` |
| category | String | No | `"TOP_VOLUME"` | Scanner category (see enum below) |
| limit | Integer | No | `20` | Maximum results to return |
| minPrice | Double | No | — | Minimum price filter (optional) |
| maxPrice | Double | No | — | Maximum price filter (optional) |

**MarketScannerCategory enum values:**
| Value | Description |
|-------|-------------|
| `TOP_VOLUME` | Sort by trading volume |
| `TOP_GAINER` | Sort by price gain |
| `TOP_LOSER` | Sort by price decline |
| `TOP_MARKETCAP` | Sort by market capitalization |
| `TOP_DIVIDEND` | Sort by dividend yield |
| `CUSTOM` | Custom scan criteria |

**Request Example:**
```bash
curl -X POST http://localhost:8090/api/scanner/custom \
  -H "Content-Type: application/json" \
  -d '{
    "market": "US",
    "category": "TOP_VOLUME",
    "limit": 10,
    "minPrice": 0.05,
    "maxPrice": 100.0
  }'
```

**Response Example:**
```json
["AAPL", "MSFT", "GOOGL", "AMZN", "TSLA", "META", "NVDA", "JPM", "V", "JNJ"]
```

Returns a flat list of matching stock symbols (strings).

**Error Codes:**
| HTTP Status | Condition |
|-------------|-----------|
| 200 | Success |
| 400 | Invalid market or category value |
| 500 | Data source unavailable or internal error |

---

### [POST] /api/screener/run

**Description:** Executes a full daily screening scan. This endpoint fetches live market data from external data sources (Tiger Brokers, YFinance, etc.) and runs the volume-price pattern matching. Results are persisted to the database.

**Request Parameters:**
| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| date | String | No | Today | Trade date in `yyyy-MM-dd` or ISO-8601 format |
| limit | Integer | No | `20` | Maximum number of candidate stocks to process |
| windowDays | Integer | No | `7` | K-line lookback window in days (clamped to 3–7) |

**Request Example:**
```bash
curl -X POST "http://localhost:8090/api/screener/run?date=2026-05-10&limit=50&windowDays=5"
```

**Response Example:**
```json
{
  "batchId": "batch_20260510_a1b2c3",
  "tradeDate": "2026-05-10",
  "totalCandidates": 150,
  "processedStocks": 50,
  "matchedStocks": 7
}
```

**Error Codes:**
| HTTP Status | Condition |
|-------------|-----------|
| 200 | Success |
| 400 | Invalid date format or parameters |
| 500 | Internal error during scan |

---

### [POST] /api/screener/run-from-snapshot

**Description:** Runs the volume-price pattern screening using only already-imported snapshot K-line data (`source=tiger_snap`). Does **not** request any external market data. Requires the `X-INGEST-API-KEY` header when configured.

**Request Parameters:**
| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| date | String | No | Today | Trade date in `yyyy-MM-dd` or ISO-8601 format |
| limit | Integer | No | `20` | Maximum number of candidate stocks to process |
| windowDays | Integer | No | `7` | K-line lookback window in days (clamped to 3–7) |

**Request Headers:**
| Name | Required | Description |
|------|----------|-------------|
| X-INGEST-API-KEY | Conditional | Required when `ingest.api-key` is configured |

**Request Example:**
```bash
curl -X POST "http://localhost:8090/api/screener/run-from-snapshot?date=2026-05-10&limit=30&windowDays=5" \
  -H "X-INGEST-API-KEY: your-api-key"
```

**Response Example:**
```json
{
  "batchId": "batch_20260510_x1y2z3",
  "tradeDate": "2026-05-10",
  "totalCandidates": 48,
  "processedStocks": 48,
  "matchedStocks": 5
}
```

**Error Codes:**
| HTTP Status | Condition |
|-------------|-----------|
| 200 | Success |
| 400 | Invalid date format or parameters |
| 401 | Missing or invalid X-INGEST-API-KEY |
| 500 | Internal error during scan |

---

### [GET] /api/screener/results

**Description:** Retrieves screening results for a specific trade date. Optional price range filtering is supported.

**Request Parameters:**
| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| tradeDate | String | Yes | — | Trade date in `yyyy-MM-dd` or ISO-8601 format |
| minPrice | Double | No | — | Minimum price filter (optional) |
| maxPrice | Double | No | — | Maximum price filter (optional) |

**Request Example:**
```bash
curl "http://localhost:8090/api/screener/results?tradeDate=2026-05-10&minPrice=0.05&maxPrice=1.00"
```

**Response Example:**
```json
[
  {
    "symbol": "SBFM",
    "price": 0.12,
    "rise": true,
    "source": "tiger_snap",
    "tradeDate": "2026-05-10"
  },
  {
    "symbol": "ABC",
    "price": 0.08,
    "rise": false,
    "source": "YFinance",
    "tradeDate": "2026-05-10"
  }
]
```

**Error Codes:**
| HTTP Status | Condition |
|-------------|-----------|
| 200 | Success |
| 400 | Invalid date format |
| 500 | Internal error |

---

### [GET] /api/screener/results/latest

**Description:** Retrieves the most recent screening results (latest batch/trade date). Optional price range filtering is supported.

**Request Parameters:**
| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| minPrice | Double | No | — | Minimum price filter (optional) |
| maxPrice | Double | No | — | Maximum price filter (optional) |

**Request Example:**
```bash
curl "http://localhost:8090/api/screener/results/latest?minPrice=0.05&maxPrice=5.00"
```

**Response Example:** Same format as `GET /api/screener/results`.

**Error Codes:**
| HTTP Status | Condition |
|-------------|-----------|
| 200 | Success |
| 500 | Internal error |

---

### [POST] /api/ingest/tiger-watchlist

**Description:** Imports stock watchlist/quote data extracted from Tiger Brokers client screenshots via OCR (OpenClaw / Hermes). The endpoint parses each row, validates the symbol against Tiger market data, and persists the stock info for snapshot-based screening.

**Request Headers:**
| Name | Required | Description |
|------|----------|-------------|
| X-INGEST-API-KEY | Conditional | Required when `ingest.api-key` is configured |
| Content-Type | Yes | `application/json` |

**Request Parameters (JSON body):**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| tradeDate | String | Yes | ISO-8601 date, e.g. `"2026-04-26"` — the trading date of the screenshot |
| rows | Array | Yes | List of watchlist row objects |

**Row object fields:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| symbol | String | Yes | Stock symbol. Accepts JSON key `symbol` or `code` (via `@JsonAlias`) |
| name | String | Yes | Stock name (e.g. `"苹果"`, `"Apple Inc."`) |
| lastPrice | Double | Yes | Latest traded price |
| volume | Object | Yes | Trading volume — supports plain numbers (e.g. `73300`) or Chinese-unit strings (e.g. `"7.33万"`, `"1.47亿") — parsed automatically |

**Request Example:**
```bash
curl -X POST http://localhost:8090/api/ingest/tiger-watchlist \
  -H "Content-Type: application/json" \
  -H "X-INGEST-API-KEY: your-api-key" \
  -d '{
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
      },
      {
        "code": "SBFM",
        "name": "Sunshine Biopharma",
        "lastPrice": 0.12,
        "volume": 5842000
      }
    ]
  }'
```

> **Note:** The `symbol` field can also be sent as `code` (backward compatibility). The `volume` field accepts both numeric and Chinese-unit string formats.

**Response Example:**
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

**Error Codes:**
| HTTP Status | Condition |
|-------------|-----------|
| 200 | Success |
| 400 | Invalid request body (e.g. missing fields, bad volume format) |
| 401 | Missing or invalid X-INGEST-API-KEY |
| 500 | Internal error during import |

---

### [GET] /screener/daily

**Description:** (Thymeleaf page) Renders the screening dashboard HTML page. Displays:
- Screening results table for the specified trade date
- Price filters and window-days configuration
- Snapshot import K-line matrix grid (48 cells)
- Notice message banner

**Request Parameters (query string):**
| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| tradeDate | String | No | Today | Trade date in `yyyy-MM-dd` format |
| minPrice | Double | No | — | Minimum price filter |
| maxPrice | Double | No | — | Maximum price filter |
| windowDays | Integer | No | `7` | K-line window (clamped to 3–7) |
| notice | String | No | — | Notice message displayed as banner |

**Request Example:**
```bash
curl "http://localhost:8090/screener/daily?tradeDate=2026-05-10&minPrice=0.05&maxPrice=1.00&windowDays=5"
```

**Response:** `200 OK` — HTML page rendered via Thymeleaf template `screener-daily`.

**Model Attributes (available in Thymeleaf template):**
| Attribute | Type | Description |
|-----------|------|-------------|
| tradeDate | String | Current trade date |
| minPrice | Double | Minimum price filter (or null) |
| maxPrice | Double | Maximum price filter (or null) |
| windowDays | Integer | K-line window days |
| notice | String | Notice banner message (or null) |
| rows | List\<ScreeningResultDto\> | Screening result rows |
| snapshotGrid | SnapshotGridViewDto | Snapshot K-line matrix grid data |

---

### [POST] /screener/run

**Description:** (Thymeleaf form action) Triggers the daily screening scan and redirects to `GET /screener/daily` with a notice message showing the scan summary.

**Request Parameters (form/query):**
| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| tradeDate | String | No | Today | Trade date in `yyyy-MM-dd` format |
| limit | Integer | No | `20` | Maximum candidate stocks |
| windowDays | Integer | No | `7` | K-line window (clamped to 3–7) |

**Request Example:**
```bash
curl -X POST "http://localhost:8090/screener/run?tradeDate=2026-05-10&limit=50&windowDays=5"
```

**Response:** `302 Redirect` → `/screener/daily?tradeDate=...&windowDays=...&notice=...`

The `notice` parameter is auto-generated:
```
已执行筛选: processed=<count>, matched=<count>, windowDays=<n>, batchId=<id>
```

---

### [GET] /

**Description:** Root path — permanently redirects to `/screener/daily`.

**Request Parameters:** None

**Request Example:**
```bash
curl -L http://localhost:8090/
```

**Response:** `302 Redirect` → `/screener/daily`

---

## Data Models

### KLineData

Used by: StockController (object endpoints)

| Field | Type | Description |
|-------|------|-------------|
| symbol | String | Stock symbol |
| time | Long | Unix epoch milliseconds of the data point |
| open | Double | Opening price |
| high | Double | Highest price |
| low | Double | Lowest price |
| close | Double | Closing price |
| volume | Long | Trading volume (shares) |
| amount | Double | Trading amount (currency) |
| items | List\<KLineIterator\> | List of individual K-line data points |
| nextPageToken | String | Pagination token for next page (reserved) |
| errorCode | String | Error code from data source (if any) |
| errorMessage | String | Error message from data source (if any) |
| code | String | Response code from data source |
| message | String | Response message from data source |

---

### KLineIterator

Used by: Inside `KLineData.items`

| Field | Type | Description |
|-------|------|-------------|
| symbol | String | Stock symbol |
| time | Long | Unix epoch milliseconds |
| timeString | String | Human-readable time string (alternative) |
| open | Double | Opening price |
| high | Double | Highest price |
| low | Double | Lowest price |
| close | Double | Closing price |
| volume | Long | Trading volume (shares) |
| amount | Double | Trading amount (currency) |
| openBD | BigDecimal | Opening price (BigDecimal precision) |
| highBD | BigDecimal | Highest price (BigDecimal precision) |
| lowBD | BigDecimal | Lowest price (BigDecimal precision) |
| closeBD | BigDecimal | Closing price (BigDecimal precision) |

---

### ApiResponse\<T\>

Used by: GlobalExceptionHandler (error responses)

| Field | Type | Description |
|-------|------|-------------|
| success | Boolean | Whether the operation succeeded |
| message | String | Human-readable message (null on success) |
| data | T | Response payload (null on error) |
| errorType | String | Error type identifier (e.g. `"StockDataException"`) |
| timestamp | Instant | ISO-8601 timestamp of the response |

---

### ScreenerRunResponseDto

Used by: `POST /api/screener/run`, `POST /api/screener/run-from-snapshot`

| Field | Type | Description |
|-------|------|-------------|
| batchId | String | Unique batch identifier for this scan run |
| tradeDate | LocalDate | Trade date of the scan (ISO-8601) |
| totalCandidates | Integer | Total number of candidate stocks considered |
| processedStocks | Integer | Number of stocks actually processed |
| matchedStocks | Integer | Number of stocks matching the volume-price pattern |

---

### ScreeningResultDto

Used by: `GET /api/screener/results`, `GET /api/screener/results/latest`, Thymeleaf `screener-daily` page

| Field | Type | Description |
|-------|------|-------------|
| symbol | String | Stock symbol (e.g. `"SBFM"`) |
| price | Double | Latest stock price |
| rise | Boolean | Whether the stock rose today (`true` = up, `false` = down) |
| source | String | Data source identifier (e.g. `"YFinance"`, `"Tiger"`, `"tiger_snap"`, `"TwelveData"`) |
| tradeDate | LocalDate | Trade date (ISO-8601) |

**Validation rules:**
- `symbol` must not be blank
- `tradeDate` must not be null

---

### TigerWatchlistIngestRequestDto

Used by: Request body for `POST /api/ingest/tiger-watchlist`

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| tradeDate | String | Yes | ISO-8601 date of the screenshot (e.g. `"2026-04-26"`) |
| rows | List\<TigerWatchlistRowDto\> | Yes | List of stock rows from OCR |

---

### TigerWatchlistRowDto

Used by: Inside `TigerWatchlistIngestRequestDto.rows`

| Field | Type | Required | JSON Aliases | Description |
|-------|------|----------|--------------|-------------|
| symbol | String | Yes | `symbol`, `code` | Stock symbol |
| name | String | Yes | — | Stock name |
| lastPrice | Double | Yes | — | Latest traded price |
| volume | Object | Yes | — | Trading volume — supports numbers (`73300`) or Chinese-unit strings (`"7.33万"`, `"1.47亿"`) |

---

### TigerWatchlistIngestResponseDto

Used by: Response body for `POST /api/ingest/tiger-watchlist`

| Field | Type | Description |
|-------|------|-------------|
| batchId | String | Unique batch identifier for this import |
| tradeDate | LocalDate | Trade date (ISO-8601) |
| imported | int | Number of stocks successfully imported |
| skipped | int | Number of stocks skipped |
| skipReasons | List\<String\> | Detailed reasons for each skipped row |

---

### SnapshotGridViewDto

Used by: Thymeleaf `screener-daily` page (`snapshotGrid` model attribute)

| Field | Type | Description |
|-------|------|-------------|
| dateHeaders | List\<String\> | Column headers — dates of available snapshot data |
| rows | List\<SnapshotGridRowDto\> | Row data — one per stock symbol |

---

### SnapshotGridRowDto

Used by: Inside `SnapshotGridViewDto.rows`

| Field | Type | Description |
|-------|------|-------------|
| symbol | String | Stock symbol |
| stockName | String | Stock name |
| cells | List\<String\> | Cell values — one per date column (price/volume representations) |

**Validation rules:**
- `symbol` must not be blank
- `stockName` defaults to empty string if null

---

### ScanResult Map (Low-Price Volume Pattern)

Used by: `GET /api/tiger/scan/low-price-volume-pattern`, `GET /api/tiger/scan/low-price-volume-pattern/fixstock`

The response is a `Map<String, Object>` with the following structure:

**Top-level fields:**

| Field | Type | Description |
|-------|------|-------------|
| statistics | Object | Scan statistics |
| statistics.totalCandidates | Integer | Total stocks scanned |
| statistics.processedStocks | Integer | Stocks successfully processed |
| statistics.matchingStocks | Integer | Stocks matching the volume pattern |
| matchingStocks | Array | List of matching stock objects |

**Matching stock object:**

| Field | Type | Description |
|-------|------|-------------|
| symbol | String | Stock symbol |
| name | String | Company name |
| currentPrice | Double | Latest traded price |
| volume | Long | Trading volume |
| kline | String | KLineData JSON string |

---

## Error Codes Reference

| HTTP Status | Code | Description |
|-------------|------|-------------|
| `200` | — | Request succeeded |
| `302` | — | Redirect (page endpoints) |
| `400` | `IllegalArgumentException` | Invalid request parameters, date format errors, or validation failures |
| `401` | — | Missing or invalid `X-INGEST-API-KEY` |
| `404` | `StockDataException` | Stock symbol not found in data source |
| `500` | `StockDataException` | Data source unavailable or data fetch failure |
| `500` | (various) | General internal server error |
| `503` | `StockDataException` | Data source temporarily unavailable |

---

## Configuration Reference

| Property | Environment Variable | Default | Description |
|----------|---------------------|---------|-------------|
| `server.port` | — | `8090` | Application HTTP port |
| `ingest.api-key` | — | `""` | API key for protected endpoints (empty = disabled) |
| `scanner.defaultLimit` | — | `20` | Default scanner result limit |
| `scanner.defaultMarket` | — | `US` | Default scanner market |
| `spring.datasource.url` | `MYSQL_DATABASE` | `stock_invest` | MySQL database name |
| `spring.datasource.username` | `MYSQL_USERNAME` | `root` | Database user |
| `spring.datasource.password` | `MYSQL_PASSWORD` | `allon23` | Database password |

---

## Data Source Profiles

| Profile | Data Source | Activation |
|---------|-------------|------------|
| `twelvedata` (default) | TwelveData API | `mvn spring-boot:run` |
| `tiger` | Tiger Brokers Open API | `mvn spring-boot:run -Dspring.profiles.active=tiger` |
| `yfinance` | Yahoo Finance | `mvn spring-boot:run -Dspring.profiles.active=yfinance` |

---

## Data Source Fallback Order

When fetching K-line data, the system tries data sources in priority order:

1. **tigeropen** — Tiger Brokers Open API
2. **tiger** — Tiger Brokers (alternative endpoint)
3. **yfinance** — Yahoo Finance
4. **twelvedata** — TwelveData API
5. **tiingo** — Tiingo API

If a data source fails (unavailable, rate-limited, or symbol not found), the next source in the chain is automatically tried.
