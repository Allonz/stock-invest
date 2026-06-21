# Report C: 日志和时间线分析

**生成时间**: 2026-06-18 22:55 CST  
**分析范围**: 2026-06-16 ~ 2026-06-18  
**目标**: 重建 BIOX 在 stock_daily_bar 表中的数据写入时间线

---

## 1. 数据库配置

### 连接信息
| 项目 | 值 |
|------|-----|
| Host | 127.0.0.1:3307 |
| Database | stock_invest |
| Username | root |
| JDBC URL | `jdbc:mysql://127.0.0.1:3307/stock_invest?...&serverTimezone=Asia/Shanghai` |
| Server timezone | Asia/Shanghai (CST, UTC+8) |

### 表定义 (stock_daily_bar)
```sql
id          BIGINT     NOT NULL AUTO_INCREMENT PRIMARY KEY
symbol      VARCHAR(32)  NOT NULL  (indexed)
trade_date  DATE         NOT NULL  (indexed)
open_price  DOUBLE       NOT NULL
close_price DOUBLE       NOT NULL
volume      BIGINT       NOT NULL
source      VARCHAR(16)  NOT NULL
stock_name  VARCHAR(128) NULL
created_at  DATETIME(6)  NOT NULL  (auto, @CreatedDate)
updated_at  DATETIME(6)  NOT NULL  (auto, @LastModifiedDate)

UNIQUE: (symbol, trade_date)
```

**关键字段**:
- `trade_date` 类型是 `DATE`（Java `LocalDate`），不是 VARCHAR
- 有 `created_at` 和 `updated_at` 自动审计字段，可以用于判断最近写入时间
- 有唯一约束 `(symbol, trade_date)`，所以同一股票同一天只有一条记录

### 数据来源标识
| source 值 | 含义 |
|-----------|------|
| `tiger_snap` | Tiger 自选股截图导入（TigerWatchlistIngest） |
| `yfinance` / `twelvedata` / `tiingo` / `tigeropen` / `tiger` | DataGapFiller 的数据源 |

---

## 2. 数据库查询结果

```sql
SELECT * FROM stock_daily_bar 
WHERE symbol = 'BIOX' 
AND trade_date IN ('2026-06-15', '2026-06-16', '2026-06-17') 
ORDER BY trade_date;
```

| id | symbol | trade_date | open_price | close_price | volume | source | created_at (UTC) | updated_at (UTC) | stock_name |
|----|--------|-----------|------------|-------------|--------|--------|-------------------|-------------------|------------|
| 4725 | BIOX | 2026-06-15 | 0.4251 | 0.4251 | 52600 | tiger_snap | 2026-06-15 21:35:38.600225 | 2026-06-15 21:35:38.600225 | Bioceres Crop Solutions Corp. |
| 4940 | BIOX | 2026-06-16 | 0.4251 | 0.4251 | 52600 | tiger_snap | 2026-06-16 21:27:19.105925 | 2026-06-16 21:27:19.105925 | Bioceres Crop Solutions Corp. |
| 5180 | BIOX | 2026-06-17 | 0.4084 | 0.4084 | 429600 | tiger_snap | 2026-06-17 21:26:15.100533 | 2026-06-17 21:26:15.100533 | Bioceres Crop Solutions Corp. |

### 🔴 关键发现：06-15 和 06-16 数据完全一致！
- `trade_date=06-15` 和 `trade_date=06-16` 的 price/volume **完全相同** (price=0.4251, volume=52600)
- 只有 `trade_date=06-17` 的数据不同 (price=0.4084, volume=429600)
- 所有三行 source 都是 `tiger_snap`（来自 TigerWatchlistIngest，不是 DataGapFiller）

---

## 3. 日志时间线 (BIOX 相关)

### 3.1 TigerWatchlistIngest 日志（tiger_snap 来源）

| 时间 (CST) | 线程 | tradeDate | BIOX price | BIOX volume | batchId | 备注 |
|-----------|------|-----------|------------|-------------|---------|------|
| 06-16 05:35:38 | http-nio-8090-exec-1 | **2026-06-15** | 0.4251 | 52600 | c4a82698... | 第1次导入 |
| 06-16 07:02:41 | http-nio-8090-exec-5 | **2026-06-15** | 0.4251 | 52600 | e074e04b... | 🔄 重复导入(同一交易日同一数据) |
| 06-17 05:27:19 | http-nio-8090-exec-1 | **2026-06-16** | 0.4251 | 52600 | 4fb5690e... | ⚠️ 导入 06-15的数据 但 tradeDate=06-16 |
| 06-18 05:26:15 | http-nio-8090-exec-1 | **2026-06-17** | 0.4084 | 429600 | c5899da7... | 正确的新数据 |
| 06-18 05:26:38 | http-nio-8090-exec-2 | **2026-06-17** | 0.4084 | 429600 | 731a9951... | 🔄 重复导入 |
| 06-18 06:55:15 | http-nio-8090-exec-5 | **2026-06-17** | 0.4084 | 429600 | 902d20bc... | 🔄 三次导入(但1条skipped) |

### 3.2 DataGapFiller 调度日志

| 时间 (CST) | 事件 | 总symbols | gapsFound | filled | failed |
|-----------|------|-----------|-----------|--------|--------|
| 06-15 06:46:43 | DataFillScheduler START | - | - | - | - |
| 06-16 06:13:21 | DataFillScheduler START | 248(过滤后239) | 45 | 35 | 10 |
| 06-17 06:00:11 | DataFillScheduler START | 248 | 46 | 30 | 16 |
| 06-18 05:49:56 | DataFillScheduler START | 255 | 36 | 35 | 1 |

**DataFillScheduler cron**: `0 0 19 * * ?` (zone = America/New_York), 即**美东时间每天 19:00 执行**

### 3.3 BIOX 与 DataGapFiller 的关系

**DataGapFiller 日志中完全没有 BIOX 的记录！**
- 06-16 日志: 0 行 BIOX × DataGapFiller
- 06-17 日志: 0 行 BIOX × DataGapFiller  
- 06-18 日志: 0 行 BIOX × DataGapFiller

**结论**: BIOX 的所有 stock_daily_bar 数据**仅来自 TigerWatchlistIngest**（tiger_snap source），DataGapFiller 从未触及 BIOX。

---

## 4. 时间线重建

```
美东时间 (ET)           CST (UTC+8)          事件
─────────────────────────────────────────────────────────────────
06-15 daytime           06-15 夜间           美股 06-15 交易日
06-15 17:23             06-16 05:23          Tiger 截图 (含 06-15 收盘数据)
06-15 17:35             06-16 05:35:38       ✅ TigerIngest: 导入 BIOX, tradeDate=2026-06-15, price=0.4251
                                              → DB created_at: 06-15 21:35 UTC ← 正确
06-15 19:02             06-16 07:02:41       🔄 TigerIngest: 重复导入, tradeDate=2026-06-15 (同一数据, upsert)
06-15 18:13             06-16 06:13:21       DataFillScheduler 启动 (cron 19:00 ET? 实际略早)
─────────────────────────────────────────────────────────────────
06-16 daytime           06-16 夜间           美股 06-16 交易日
06-16 17:23             06-17 05:23          Tiger 截图
06-16 17:27             06-17 05:27:19       ⚠️ TigerIngest: 导入 BIOX, tradeDate=2026-06-16
                                             ⚠️ 但 price=0.4251 (仍是 06-15 的数据!) ← 数据错位!
                                              → DB created_at: 06-16 21:27 UTC
06-16 19:00             06-17 06:00:11       DataFillScheduler 启动
─────────────────────────────────────────────────────────────────
06-17 daytime           06-17 夜间           美股 06-17 交易日
06-17 17:23             06-18 05:23          Tiger 截图
06-17 17:26             06-18 05:26:15       ✅ TigerIngest: 导入 BIOX, tradeDate=2026-06-17, price=0.4084
                                              → 这是真正的新数据 (06-17 交易结果)
                                              → DB created_at: 06-17 21:26 UTC
06-17 19:00             06-18 05:49:56       DataFillScheduler 启动
```

---

## 5. 关键发现

### 5.1 🔴 数据错位 (Data Misalignment)

**现象**: 06-16 交易日结束后，Tiger 截图导入系统将 BIOX 在 06-15 的数据 (price=0.4251, volume=52600) **错误地写入为 tradeDate=2026-06-16 的记录**。

**证据**:
- DB 中 `trade_date=06-15` 行: price=0.4251, volume=52600, created_at=2026-06-15 21:35:38 UTC
- DB 中 `trade_date=06-16` 行: price=0.4251, volume=52600, created_at=2026-06-16 21:27:19 UTC  
- **两行 price/volume 完全相同！** 不合理的巧合（同一股票连续两天收盘价完全一样）

**根因推断**:
- 06-16 的 Tiger 截图中 BIOX 显示的价格仍为 0.4251（可能是盘中数据未更新，或截屏时用了缓存的旧截图）
- TigerWatchlistIngest 以 `tradeDate=2026-06-16` 写入了这个旧价格
- 实际上 BIOX 在 06-16 的真实收盘价应该不同于 06-15

**Tiger 截图调用方**需要在截图周期内向 stock-invest 发送正确的 `tradeDate` 参数。当前的行为似乎是把"截图时刻的前一个自然日"作为 tradeDate，但如果截图获取的是旧的缓存数据，就会导致数据错位。

### 5.2 ⚠️ 多次重复导入

每个交易日有 2-3 次 TigerWatchlistIngest 调用：
- 06-16: 2次 (05:35, 07:02) — 第2次是多余的
- 06-18: 3次 (05:26, 05:26, 06:55) — 第3次还丢了1行(imported=179/180)

虽然有唯一约束保护不会产生重复行（upsert），但多次调用增加了写入竞争的风险。

### 5.3 ✅ DataGapFiller 无影响

- DataGapFiller 从未处理 BIOX（在日志中 0 次提及）
- BIOX 的所有数据来源都是 `tiger_snap`
- DataGapFiller 的 `fillGapsForSymbol` 方法使用 `findBySymbolOrderByTradeDateDesc` 查找最近 30 天的记录，如果某个 symbol 已有 continuous data，不会产生 gap

### 5.4 时区分析

- 服务器时区: Asia/Shanghai (UTC+8)
- DB连接: `serverTimezone=Asia/Shanghai`
- DataFillScheduler cron: `0 0 19 * * ?` zone=America/New_York
- TigerWatchlistIngest 使用 `LocalDate.parse(request.tradeDate())` — 由调用方传入，**不涉及 JVM 时区转换**
- `created_at` / `updated_at` 使用 `Instant` (UTC)，不受时区影响

**时区问题**: 未发现 cron 任务的直接时区混淆，但 DataFillScheduler 的实际执行时间（~06:XX CST）与预期（19:00 ET = 次日 07:00 CST）相比有 ~14-50 分钟偏差，可能是应用重启或 Spring scheduler 调度不精确导致。这不影响 BIOX 数据。

### 5.5 trade_date 类型确认

- Java entity: `LocalDate` → MySQL column: `DATE`
- 不涉及时间部分，不会因时区产生日期偏移
- 唯一约束: `UNIQUE(symbol, tradeDate)` — 同一股票同一天只能有一条

---

## 6. 结论

**数据错位的直接原因**: Tiger 截图系统在 06-17 05:27 CST（美东 06-16 收盘后）调用 TigerWatchlistIngest API 时，传入的截图数据中 BIOX 的价格仍是 06-15 的数据（0.4251），但 `tradeDate` 参数设为 2026-06-16，导致旧数据被写入了新交易日。

**建议排查方向**:
1. 检查 Tiger 截图系统的截图逻辑 — 为什么 06-16 收盘后截取到的 BIOX 数据仍是 06-15 的？
2. 确认截图脚本是否正确传入了 `tradeDate` 参数（应该是截图所反映的交易日期，而非截图时刻的日期）
3. 考虑在 TigerWatchlistIngest 中添加去重逻辑：如果新导入的 price 与已有最近数据完全相同，记录 warning 但仍接受（因为确实可能连续两天同价）
