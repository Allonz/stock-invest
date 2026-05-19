# Stock-Invest 项目需求规格说明书 (PRD)

## 1. 项目概述与目标

### 1.1 项目背景

Stock-Invest 是一个美股低价股筛选与分析系统，专注于发现 0.001 ~ 0.50 美元区间的潜在交易机会。系统通过多数据源采集每日交易数据，结合自有的量价形态识别算法，自动筛选符合条件的股票并通过 OpenClaw 通知用户。

### 1.2 项目目标

- 建立从「数据采集 → 数据补全 → 模式筛选 → 通知推送」的自动化管线
- 覆盖美股低价股（$0.001 ~ $0.50）的日常监控
- 多数据源兜底，确保数据的连续性
- 7 日量价形态识别，自动生成候选股票名单

### 1.3 核心流程

```
┌─────────────────────────────────────────────────────────────────┐
│  OpenClaw 端（独立 skill）                                       │
│                                                                  │
│  定时对 TigerTrade 截图 → OCR → POST /api/ingest/tiger-watchlist │
│  定时 GET /api/notification/latest → 解析结果 → QQ 通知         │
└──────────┬──────────────────────────────┬────────────────────────┘
           │ POST 导入                    │ GET 查询通知
           ▼                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  Stock-Invest (Spring Boot, port 8090)                          │
│                                                                  │
│  stock_daily_bars ← 缺失数据补查 → 其他数据源                     │
│         │                                                       │
│         ▼                                                       │
│  7天量价形态筛选 → screening_match → /api/notification/latest   │
└─────────────────────────────────────────────────────────────────┘
```

### 1.4 项目路径与技术栈总览

- 项目根目录：`/home/allon/application/stock-invest`
- 后端 API：`http://127.0.0.1:8090`
- 语言/框架：Java 17, Spring Boot 3.3.13, Maven
- 数据库：MySQL 8.0（数据库名：`stock_invest`）
- 页面：Spring Boot + Thymeleaf
- 截图导入：由 OpenClaw skill 独立实现，非 Java 代码
- 数据源客户端：Tiger OpenAPI Java SDK, Tiger Python SDK, YFinance (Python), TwelveData (REST API + Python), Tiingo (REST API + Java)

---

## 2. 功能需求

### Step 1 — 截图自动导入（已完成，OpenClaw 端实现）

#### 2.1.1 功能描述

由 OpenClaw 的 Tiger Watchlist Ingest skill 实现：定时对 TigerTrade 券商界面截图 → OCR 识别 → POST 到 stock-invest 的 `/api/ingest/tiger-watchlist` 接口 → 数据写入 `stock_daily_bars` 表（`source = 'tiger_snap'`）。

#### 2.1.2 涉及的 API（stock-invest 端）

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/ingest/tiger-watchlist` | 接收 OpenClaw 发送的 OCR 识别数据 |

> 此接口已存在，无需改动。

---

### Step 2 — 数据补缺定时任务

#### 2.2.1 功能描述

定时扫描 `stock_daily_bars` 表，识别数据不连续的股票（缺少某交易日记录），从其他数据源按优先级顺序补查缺失日期的数据。

**核心规则：股票最新价 > $1.00 → 跳过，不补查。**

#### 2.2.2 输入

- `stock_daily_bars` 表数据
- 数据库连接配置（`application.yml` 中的 `spring.datasource`）

#### 2.2.3 处理逻辑

1. **扫描缺失**：查询 `stock_daily_bars`，按 `symbol` 分组，检查交易日期间是否存在跳空（即某股票某天没有记录）
2. **价格过滤**：查询该股票在 `stock_daily_bars` 中的最新记录，若 `latestPrice > 1.00` 则跳过该股票
3. **补查流程**：
   - 按优先级依次尝试数据源（每次只查 1 只股票）
   - 每个数据源只查缺失的日期区间
   - 单次查询时间范围：从上次有记录的下一天到当前日期
   - 查询结果写入 `stock_daily_bars`
4. **写入逻辑（修改后）**：写入前检查是否已有该 `(symbol, trade_date)` 记录——**如果已存在则直接更新该条记录（包括 source 字段一起更新）；不存在则插入新记录**。

> 唯一键改为 `(symbol, trade_date)`，不再含 `source` 字段。因为每次更新 source 会覆盖为当前数据源名称。

#### 2.2.4 优先级与数据源适配层

| 优先级 | 数据源 | 实现方式 |
|--------|--------|----------|
| 1 | Tiger 开放平台 | Tiger OpenAPI Java SDK |
| 2 | Tiger (Python SDK) | 通过 Python 脚本调用 tigeropen |
| 3 | YFinance | `yfinance` Python 包 |
| 4 | TwelveData | `twelvedata` Python 包 或 Java RestClient |
| 5 | Tiingo | REST API via `ResilientHttpExecutor` (免费套餐每天 500 次请求) |

#### 2.2.4.1 Tiingo 数据源

作为最后 fallback 数据源，当高优先级数据源均无法获取数据时使用。

| 属性 | 说明 |
|------|------|
| 数据源名称 | Tiingo |
| 访问方式 | REST API（使用 `ResilientHttpExecutor`） |
| Service 类 | `TiingoStockServiceImpl`（已有代码） |
| Client 类 | 已有 |
| API 限制 | 免费套餐每天 500 次请求 |
| 优先级 | 5（最后 fallback） |

#### 2.2.5 失败任务重试机制

**单次最大查询数：1只股票。**

引入 `data_fill_tasks` 表记录每个补缺任务的状态：

```sql
TABLE data_fill_tasks (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    symbol           VARCHAR(32)  NOT NULL COMMENT '股票代码',
    missing_date     DATE         NOT NULL COMMENT '缺失的交易日',
    status           VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending/retrying/success/stopped',
    retry_count      INT          NOT NULL DEFAULT 0 COMMENT '当天已重试次数',
    retry_date       DATE                  COMMENT '最后重试日期',
    day_count        INT          NOT NULL DEFAULT 0 COMMENT '已持续天数（最多7）',
    last_error       VARCHAR(512)          COMMENT '最后错误信息',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_symbol_date (symbol, missing_date),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据补缺任务跟踪';
```

**执行流程：**

```
数据补缺定时任务触发
       │
       ├─ ① 扫描缺失 → 创建 data_fill_tasks（status=pending）
       │
       ├─ ② 优先处理 pending 任务（按 symbol 顺序，每次1只）
       │
       ├─ ③ 完成后，读取 retrying 状态的重试任务
       │     └─ 每条每天最多跑5次，逐次累加 retry_count
       │
       ├─ ④ 查到数据 → 写入 stock_daily_bars → status=success
       │
       └─ ⑤ 查不到数据 → status=retrying
             ├─ 当天 retry_count < 5 → 下次轮次继续重试
             ├─ 当天 retry_count >= 5 → 标记为第二天重试（day_count+1）
             └─ day_count >= 7 → status=stopped，永不再执行
```

**状态流转：**

```
pending ──→ retrying ──→ success（查到数据，结束）
              │  ↑
              │  └─ 当天<5次 → 继续重试
              │
              ├─ 当天>=5次 → 第二天重置，day_count+1
              │
              └─ day_count>=7 → stopped（永久停止）
```

**关键规则：**
- 每个 `(symbol, missing_date)` 最多持续 **7 天**
- 每天最多重试 **5 次**
- 7 天后仍查不到数据 → `stopped`，永不再执行
- 补缺任务会**跨越多个定时轮次**持续执行，直到 `success` 或 `stopped`

#### 2.2.6 定时配置

| 参数 | 建议值 |
|------|--------|
| 执行频率 | 每天 6:00（美股交易日盘后） |
| 首次启动延迟 | 应用启动后 60 秒 |
| 单次最大查询 | 1 只股票 |

---

### Step 3 — 7天筛选定时任务

#### 3.1 功能描述

直接从 `stock_daily_bars` 表读取最近 7 个交易日的 K 线数据，使用 `StockPatternUtil.java` 的算法进行量价形态匹配，记录每天符合要求的股票代码。

**关键约束：不调 HTTP API，直接从数据库表读取。**

#### 3.2 输入

- `stock_daily_bars` 表中目标价格区间（$0.001 ~ $0.50）的股票最近 7 天数据

#### 3.3 筛选算法（StockPatternUtil）

以最近 7 个交易日（按时间降序，最新在前）的成交量数组 `vol[0..6]` 为输入，逐层校验：

```
条件 1: avg2 = (vol[1] + vol[2]) / 2  <  vol[0]       ← 当日量 vs 前两日均量
条件 2: avg3 = (vol[1] + vol[2] + vol[3]) / 3  <  avg2
条件 3: avg4 = (vol[1] + vol[2] + vol[3] + vol[4]) / 4  <  avg3
条件 4: avg5 = (vol[1] + vol[2] + vol[3] + vol[4] + vol[5]) / 5  <  avg4
条件 5: avg6 = (...) / 6  <  avg5
```

全部满足 → 该股票在最近 7 个交易日内出现「逐渐放量」形态，判定为符合条件。

#### 3.4 处理逻辑

1. 从 `stock_daily_bars` 查询所有 symbol
2. 按 symbol 分组，获取每个股票最近 7 个交易日的数据
3. 使用 `StockPatternUtil.matchesVolumePatternForKLineIterator(List<KLineIterator>)` 做模式匹配
4. 命中结果写入 `screening_match` 表

#### 3.5 定时配置

| 参数 | 建议值 |
|------|--------|
| 执行频率 | 每日美股盘后（北京时间 09:30） |
| 扫描范围 | `stock_daily_bars` 中价格 ≤ $0.50 的所有股票 |
| 批次标识 | 按日期生成 `batchId`（如 `screen-20260518`） |

---

### Step 4 — 通知（HTTP 接口供 OpenClaw 调用）

#### 4.1 功能描述

Stock-invest 不直接发通知。筛选出结果后，提供一个 **HTTP GET 接口**，由 OpenClaw 定时查询该接口获取达标股票信息，由 OpenClaw 发送到 QQ Bot。

#### 4.2 接口定义

**`GET /api/notification/latest`**

返回最近一次筛选的达标结果：

```json
{
  "batchId": "screen-20260518-xxxx",
  "screenDate": "2026-05-18",
  "hitCount": 5,
  "results": [
    { "symbol": "ABC", "lastClose": 0.042 },
    { "symbol": "XYZ", "lastClose": 0.087 }
  ],
  "generatedAt": "2026-05-18T05:00:00"
}
```

如果当天无筛选结果或没有新批次，返回：

```json
{
  "batchId": null,
  "screenDate": null,
  "hitCount": 0,
  "results": [],
  "generatedAt": "2026-05-18T05:00:00"
}
```

#### 4.3 处理逻辑

1. OpenClaw 定时（如每 5 分钟）GET 该接口
2. 接口查询 `screening_match` 表中最新批次的记录
3. 返回格式化的 JSON 数据
4. OpenClaw 解析后发送到 QQ Bot

#### 4.4 配置参数

```yaml
notification:
  api:
    enabled: true     # 通知 API 开关
```

---

## 3. 数据源说明

| 数据源 | 实现方式 | 备注 |
|--------|----------|------|
| Tiger 开放平台 | Java SDK (`io.github.tigerbrokers:openapi-java-sdk:2.2.6`) | 需要 Tiger API 权限 |
| Tiger (Python SDK) | Python `tigeropen` 包，Java 通过 PythonScriptExecutor 调用 | Java SDK 的备选 |
| YFinance | `yfinance` Python 包 | 无需 API Key |
| TwelveData | `twelvedata` Python 包 / `TwelveDataRestClient.java` | 免费套餐每分钟 8 次 |
| TigerTrade 截图 | OpenClaw skill 独立实现，非 Java 代码 | 数据源标记 `tiger_snap` |

---

## 4. 数据库表结构

### 4.1 `stock_daily_bars` — 日线行情数据

```sql
CREATE TABLE stock_daily_bars (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    symbol        VARCHAR(32)  NOT NULL COMMENT '股票代码',
    name          VARCHAR(128)          COMMENT '股票名称',
    trade_date    DATE         NOT NULL COMMENT '交易日',
    close_price   DECIMAL(12,4)         COMMENT '收盘价/最新价',
    volume        BIGINT                COMMENT '成交量',
    source        VARCHAR(32)  NOT NULL COMMENT '数据源 (tiger_snap/tiger_api/yfinance/twelvedata/tiingo)',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_symbol_date (symbol, trade_date),
    KEY idx_symbol (symbol),
    KEY idx_trade_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日线行情数据';
```

> ⚠️ 唯一键改为 `(symbol, trade_date)`，不再含 `source`。因为写入时如果记录已存在则直接更新（含 source），不同数据源会相互覆盖。

### 4.2 `data_fill_tasks` — 数据补缺任务跟踪（新增）

```sql
CREATE TABLE data_fill_tasks (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    symbol           VARCHAR(32)  NOT NULL COMMENT '股票代码',
    missing_date     DATE         NOT NULL COMMENT '缺失的交易日',
    status           VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending/retrying/success/stopped',
    retry_count      INT          NOT NULL DEFAULT 0 COMMENT '当天已重试次数',
    retry_date       DATE                  COMMENT '最后重试日期',
    day_count        INT          NOT NULL DEFAULT 0 COMMENT '已持续天数（最多7）',
    last_error       VARCHAR(512)          COMMENT '最后错误信息',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_symbol_date (symbol, missing_date),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据补缺任务跟踪';
```

### 4.3 `screening_match` — 筛选命中记录（已有，不动）

```sql
CREATE TABLE screening_match (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    batch_id      VARCHAR(32)  NOT NULL COMMENT '筛选批次UUID',
    data_source   VARCHAR(32)  NOT NULL COMMENT '数据来源',
    symbol        VARCHAR(32)  NOT NULL COMMENT '股票代码',
    last_close    DOUBLE               COMMENT '最近收盘价',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_batch_id (batch_id),
    KEY idx_symbol (symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='筛选命中记录';
```

---

## 5. 页面展示需求（Thymeleaf）

### 5.1 截图导入记录查看页

- 分页展示 `stock_daily_bars` 记录（全部数据源）
- 表格列：日期、股票代码、名称、最新价、成交量、数据源、导入时间
- 筛选：按日期范围、股票代码、数据源搜索
- URL：`/page/tiger-snap`

### 5.2 7天筛选结果展示页

- 按批次日期展示 `screening_match` 记录
- 表格列：批次 ID、日期、股票代码、收盘价
- 筛选：按日期范围、股票代码搜索
- 导出 CSV
- URL：`/page/screening-results`

### 5.3 数据补缺任务状态页

- 展示 `data_fill_tasks` 表状态
- 表格列：股票代码、缺失日期、状态、重试次数、持续天数
- URL：`/page/fill-tasks`

---

## 6. 定时任务配置

| 任务 | 名称 | 频率 | 说明 |
|------|------|------|------|
| T1 | 截图导入 | OpenClaw 端控制 | 非 Java 代码 |
| T2 | 数据补缺 | 每天 6:00 | 每次查 1 只，含失败重试 |
| T3 | 7天筛选 | 每日 09:30 CST | 从 stock_daily_bars 读取 |
| T4 | 通知查询 | OpenClaw 端定时 GET | `/api/notification/latest` |

```yaml
stock:
  scheduler:
    fill-gap:
      enabled: true
      cron: "0 0 6 * * ?"
      max-stocks-per-run: 1           # 每次只查1只
    screening:
      enabled: true
      cron: "0 30 9 * * ?"
      price-min: 0.001
      price-max: 0.50
```

主类添加 `@EnableScheduling`。

---

## 7. 非功能需求

### 7.1 限流

| 数据源 | 策略 |
|--------|------|
| TwelveData | 间隔 ≥ 250ms |
| YFinance | 间隔 ≥ 250ms |
| Tiger OpenAPI | SDK 自带限制 + 最小间隔 |
| Tiingo | 免费套餐每天 500 次请求，间隔 ≥ 1s |

### 7.2 容错

| 场景 | 处理 |
|------|------|
| 单只股票查不到 | 记录到 data_fill_tasks，持续重试最多 7 天 |
| 数据源不可用 | 降级到下一优先级 |
| 所有数据源都失败 | 记录日志，标记任务为 retrying，下次重试 |
| 时间处理 | `tradeDate` 用 `LocalDate`，北京时间 |

---

## 附录：配置示例

```yaml
server:
  port: 8090

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/stock_invest
    username: root
    password: ${MYSQL_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: update

stock:
  scheduler:
    fill-gap:
      enabled: true
      cron: "0 0 6 * * ?"
      max-stocks-per-run: 1
    screening:
      enabled: true
      cron: "0 30 9 * * ?"
      price-min: 0.001
      price-max: 0.50
  data-fill:
    max-retry-per-day: 5
    max-days: 7
    price-threshold: 1.0
  notification:
    api:
      enabled: true
