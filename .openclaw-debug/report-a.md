# Report A: stock_daily_bar 数据写入完整链路分析

## 1. 导入脚本 (tt_import.py)

**文件不存在。** 在 `/home/allon/application/stock-invest/scripts/` 下没有找到 `tt_import.py`。

实际的 OCR 截图导入流程是：外部自动化工具（Hermes/OpenClaw 等）直接调用 `POST /api/ingest/tiger-watchlist` API。
关于 `--date` 参数：该参数不是传递给 Python 脚本的，而是作为 OCR 解析结果的一部分写在 JSON 请求体的 `tradeDate` 字段中。

## 2. API 接口链路

```
外部自动化工具 (OCR 解析老虎截图)
  -> POST /api/ingest/tiger-watchlist
  -> TigerWatchlistIngestController.ingest()
  -> TigerWatchlistIngestServiceImpl.ingest()
  -> StockDailyBarRepository.findBySymbolAndTradeDate()  // upsert 查询
  -> StockDailyBarRepository.save()                       // 写入
  -> stock_daily_bar 表
```

## 3. tradeDate 处理逻辑

### 3.1 DTO: TigerWatchlistIngestRequestDto

```java
public record TigerWatchlistIngestRequestDto(
    String tradeDate,          // 调用方传入的字符串，如 "2026-06-17"
    List<TigerWatchlistRowDto> rows
) {}
```

### 3.2 Service: TigerWatchlistIngestServiceImpl (关键代码)

```java
// 校验 tradeDate 不为空
if (request.tradeDate() == null || request.tradeDate().trim().isEmpty()) {
    throw new IllegalArgumentException("tradeDate is required");
}

// 解析 tradeDate -> LocalDate（无时区）
LocalDate tradeDate = LocalDate.parse(request.tradeDate().trim());

// 用 tradeDate + symbol 查找已有记录（upsert）
StockDailyBar bar = stockDailyBarRepository
    .findBySymbolAndTradeDate(sym, tradeDate)
    .orElseGet(StockDailyBar::new);

// 直接设置 tradeDate（无校验、无修改）
bar.setTradeDate(tradeDate);
bar.setVolume(vol);  // 来自 OCR 解析的 volume
bar.setSource("tiger_snap");
```

**结论：API 端对 tradeDate 无任何校验或修改**，完全信任调用方传入的值。

### 3.3 Entity: StockDailyBar

```java
@Table(name = "stock_daily_bar",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_stock_daily_bar_symbol_trade_date",
                          columnNames = {"symbol", "tradeDate"})
    })
public class StockDailyBar {
    @Column(nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false)
    private Long volume;

    @Column(nullable = false, length = 16)
    private String source;  // 固定为 "tiger_snap"
}
```

### 3.4 Upsert 行为

```java
Optional<StockDailyBar> findBySymbolAndTradeDate(String symbol, LocalDate tradeDate);
```

1. 先按 `(symbol, tradeDate)` 查是否存在
2. 存在 -> 更新已有记录的 volume/open/close/source/name
3. 不存在 -> 新建记录

### 3.5 建表 SQL

```sql
CREATE TABLE IF NOT EXISTS stock_daily_bar (
    id BIGINT NOT NULL AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL,
    stock_name VARCHAR(128) NULL,
    trade_date DATE NOT NULL,
    open_price DOUBLE NOT NULL,
    close_price DOUBLE NOT NULL,
    volume BIGINT NOT NULL,
    source VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_daily_bar_symbol_trade_date (symbol, trade_date),
    INDEX idx_stock_daily_bar_trade_date (trade_date),
    INDEX idx_stock_daily_bar_symbol (symbol)
);
```

## 4. BIOX 06-16 和 06-17 数据颠倒分析

### 4.1 实际日志证据

从 application.log 提取的 BIOX 相关 TigerIngest 日志：

| 时间戳 | 价格 | 成交量 | tradeDate |
|--------|------|--------|-----------|
| 2026-06-16 05:35:38 UTC | 0.4251 | 52,600 | 2026-06-15 |
| 2026-06-16 07:02:41 UTC | 0.4251 | 52,600 | 2026-06-15 |
| **2026-06-17 05:27:19 UTC** | **0.4251** | **52,600** | **2026-06-16** |
| 2026-06-18 05:26:15 UTC | 0.4084 | 429,600 | 2026-06-17 |
| 2026-06-18 05:26:38 UTC | 0.4084 | 429,600 | 2026-06-17 |
| 2026-06-18 06:55:14 UTC | 0.4084 | 429,600 | 2026-06-17 |

### 4.2 库表最终数据

| trade_date | volume | price | source |
|-----------|--------|-------|--------|
| 2026-06-15 | 52,600 | 0.4251 | tiger_snap |
| 2026-06-16 | 52,600 | 0.4251 | tiger_snap |
| 2026-06-17 | 429,600 | 0.4084 | tiger_snap |

### 4.3 老虎客户端显示

| 日期 | 成交量 |
|------|--------|
| 2026-06-15 | 5.26万 (52,600) |
| 2026-06-16 | 42.96万 (429,600) |
| 2026-06-17 | 23.85万 (238,500) |

### 4.4 结论：数据在导入阶段就错了

**不是简单的"06-16 和 06-17 互换"，而是更复杂的错位：**

1. **06-16 的数据被 06-15 的数据覆盖了**：
   - 06-17 05:27 UTC 导入 BIOX 时，调用方传入 `tradeDate=2026-06-16`
   - 但 OCR 解析出的 `volume=52600`（5.26万），`price=0.4251`
   - 这两个值都与 06-15 完全相同（价格 0.4251, 成交量 5.26万）

2. **429,600（42.96万）被写入了 06-17**：
   - 这个成交量在老虎客户端上显示在 06-16 这一天
   - 但在库表里被存到了 trade_date=2026-06-17

3. **238,500（23.85万）从未写入**：
   - 06-17 的真实成交量从未进入库表

4. **对比表：**

| | 老虎客户端真实数据 | 写入库表 | 状态 |
|---|-----------------|---------|------|
| 06-15 | 5.26万 (52,600) | 5.26万 (52,600) | ✓ 正确 |
| 06-16 | 42.96万 (429,600) | 5.26万 (52,600) | ✗ 被06-15数据覆盖 |
| 06-17 | 23.85万 (238,500) | 42.96万 (429,600) | ✗ 实际是06-16的数据 |

**根因：调用方（外部 OCR 自动化工具）传入 API 的 tradeDate 与实际 OCR 数据不匹配。**

可能原因：
- 截图文件的时间戳判断逻辑偏移了一天
- 或者 OCR 截图文件本身的日期标签就有问题
- 调用方可能在组装 JSON 时把前一天的截图数据用后一天的 tradeDate 发了

### 4.5 DataGapFiller 未覆盖此情况

日志确认 DataGapFiller **没有为 BIOX 补述数据**：
- BIOX 在 06-15/16/17 都有 tiger_snap 数据
- DataGapFiller 检测到的 missing dates 只针对完全没有数据的日期
- 因此 gapfiller 不会检测到"数据存在但值错误"的情况

## 5. 其他关键代码

### 5.1 DTO 行对象 (TigerWatchlistRowDto)

```java
public record TigerWatchlistRowDto(
    @JsonAlias("code") String symbol,  // 支持 "symbol" 或 "code" 字段名
    String name,
    Double lastPrice,
    Object volume  // 支持数字或字符串（如 "42.96万"）
) {}
```

### 5.2 成交量解析器 (WatchlistVolumeParser)

```java
public final class WatchlistVolumeParser {
    // 正则匹配: 数字 + 可选单位(万/亿)
    // "万" -> *10000, "亿" -> *100000000
    // 例: "42.96万" -> 429600, "1.47亿" -> 147000000
}
```

### 5.3 Controller 入口

```java
@PostMapping(value = "/tiger-watchlist",
    consumes = "application/json", produces = "application/json")
public ResponseEntity<ApiResponse<TigerWatchlistIngestResponseDto>> ingest(
    @RequestHeader(value = "X-INGEST-API-KEY", required = false) String apiKey,
    @RequestBody TigerWatchlistIngestRequestDto body) {
    // 无 tradeDate 校验逻辑，直接透传给 service
    TigerWatchlistIngestResponseDto result = tigerWatchlistIngestService.ingest(body);
    return ResponseEntity.ok(ApiResponse.ok(result));
}
```

## 6. 建议修复方向

1. **调用方（OCR 自动化工具）**：检查截图文件名/时间戳到 tradeDate 的映射逻辑，这是根因所在
2. **API 端防御**：对同一 `(symbol, source)` 在相邻日期间 volume 突变 >10x 的情况记录 WARN 日志
3. **考虑增加校验**：如果两次连续导入的 `(symbol, price, volume)` 完全相同但 tradeDate 不同，可能是截图复用/日期错位信号
4. **DataGapFiller 增强**：除了检查"缺失日期"，也可以检查"同 symbol 相邻日期 OHLCV 完全相同"的异常情况
