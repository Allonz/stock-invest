# Stock-Invest 项目重构提示词

请按照以下需求重构 stock-invest 项目。

## 一、项目概览

- **项目位置：** `/home/allon/application/stock-invest`（WSL Ubuntu）
- **后端 API：** `http://127.0.0.1:8090`
- **运行环境：** WSL Ubuntu（命令行 bash，无图形界面）
- **当前栈：** Spring Boot 2.7.18（目标 3.3.13）+ Java 17 source / **Java 21 runtime** + Maven 3.8.7 + MySQL (port 3307) + JPA + Thymeleaf
- **前端：** 可选择 Thymeleaf 或内嵌 Vue SPA（使用 index.html + CDN Vue.js，不需 npm）

### 🔍 实际 WSL 环境确认

```bash
# Java
$ java -version
openjdk version "21.0.10" 2026-01-20
OpenJDK Runtime Environment (build 21.0.10+7-Ubuntu-124.04)
OpenJDK 64-Bit Server VM (build 21.0.10+7-Ubuntu-124.04, mixed mode, sharing)

# Maven
$ mvn --version | head -3
Apache Maven 3.8.7
Maven home: /usr/share/maven
Java version: 21.0.10, vendor: Ubuntu, runtime: /usr/lib/jvm/java-21-openjdk-amd64

# MySQL
端口 3307（见 application.yml datasource.url）
```

**⚠️ Java 21 注意事项：**
- pom.xml 中 `<java.version>17</java.version>` 是指定源码/目标字节码版本为 17，不是运行时 JDK 版本
- WSL 已安装 OpenJDK 21，且 `mvn` 默认使用 JDK 21 运行
- Spring Boot 3.3.13 原生支持 Java 17+，JDK 21 兼容性良好
- **不需要安装 openjdk-17-jdk**，保持 `java.version=17` 在 pom.xml 里，用 JDK 21 编译 17 字节码

### 现有核心编码规范

- Controller → Service（接口）→ ServiceImpl → Repository → Entity
- 新增实体用 Lombok `@Data`，主键用 `@Id + @GeneratedValue(IDENTITY)`
- 新增 Repository 继承 `JpaRepository`
- 使用构造函数注入（非 Field Injection）
- 使用 `@Scheduled` 管理定时任务
- 使用 `@EnableCaching` + Caffeine 缓存（已在主类 `@SpringBootApplication` + `@EnableCaching` 启用）
- 已配置 `@EnableAsync` + 自定义 `scanExecutor`（核心 8 线程、最大 16、队列 200）

### 现有项目文件结构（全量 Java 源文件）

```
src/main/java/com/stock/invest/
├── StockInvestApplication.java              # @EnableScheduling + @EnableCaching 已开启
├── client/
│   ├── TigerOpenPythonBridge.java           # Tiger Open Python SDK 桥接（ProcessBuilder 调用 python 脚本）
│   ├── TiingoRestClient.java                # Tiingo REST API 客户端
│   ├── TwelveDataRestClient.java            # Twelve Data REST API 客户端
│   └── YahooFinanceRestClient.java          # Yahoo Finance REST API 客户端
├── config/
│   ├── AsyncConfig.java                     # scanExecutor 线程池配置
│   ├── CacheConfig.java                     # Caffeine 缓存配置
│   ├── HttpClientProperties.java
│   ├── IngestProperties.java
│   ├── JpaAuditingConfig.java               # @EnableJpaAuditing
│   ├── PythonScriptProperties.java
│   ├── ScannerProperties.java               # scanner.* 可配置项
│   ├── TigerApiConfig.java
│   ├── TiingoProperties.java
│   └── TwelveDataProperties.java
├── controller/
│   ├── ScreeningPageController.java         # Thymeleaf 页面路由（/screener/daily, /screener/run）
│   ├── ScreeningQueryController.java        # /api/screener/* REST API
│   ├── StockController.java                 # /api/tiger/kline/daily/{symbol}
│   ├── StockScannerController.java          # /api/scanner/custom
│   └── TigerWatchlistIngestController.java  # /api/ingest/tiger-watchlist
├── entity/
│   ├── ScreeningMatch.java                  # screening_match 表（使用 javax.persistence.*）
│   └── StockDailyBar.java                   # stock_daily_bar 表（使用 javax.persistence.*）
├── enums/
│   └── dto/
│       ├── ApiResponse.java
│       ├── CustomScanRequest.java
│       ├── ScreenerRunResponseDto.java      # record: batchId, tradeDate, totalCandidates, processedStocks, matchedStocks
│       ├── ScreeningMatchProjection.java    # interface projection
│       ├── ScreeningResultDto.java           # record: symbol, price, rise, source, tradeDate
│       ├── SnapshotGridRowDto.java
│       ├── SnapshotGridViewDto.java
│       ├── TigerWatchlistIngestRequestDto.java  # record: tradeDate, rows
│       ├── TigerWatchlistIngestResponseDto.java
│       └── TigerWatchlistRowDto.java            # record: symbol, name, lastPrice, volume
├── exception/
│   ├── GlobalExceptionHandler.java
│   └── StockDataException.java
├── http/
│   └── ResilientHttpExecutor.java
├── model/
│   ├── KLineData.java                      # K线数据模型（items: List<KLineIterator>）
│   ├── KLineIterator.java                   # 单根K线
│   └── StockInfo.java
├── repository/
│   ├── ScreeningMatchRepository.java        # 已有：findByBatchId, findByTradeDate, findTopByOrderByTradeDateDesc
│   └── StockDailyBarRepository.java         # 已有：findTop7BySymbol, findDistinctSymbolsBy..., 批量IN查询等
├── security/
│   └── IngestApiGuard.java                  # API Key 验证
├── service/
│   ├── DataSourceStrategy.java              # 数据源策略接口（getSourceName, isAvailable, getDailyKLineData, scanStocks...）
│   ├── KLineService.java                    # K线查询服务接口（getDailyKLineData, getDailyKLineDataAsObject, getBatchKline）
│   ├── LowPriceVolumeScanService.java       # 低价放量筛选接口（scan(dataSource, limit, candidates, klineLoader)）
│   ├── MarketDataSourceRouter.java          # 市场数据源路由接口（loadCandidates, fetchDailyBars, fetchLatestDailyBar）
│   ├── PatternEvaluateService.java          # 成交量模式匹配接口（matchesIncreasingVolumePattern, matchesIncreasingVolumePatternFromKLine）
│   ├── PriceVolumeCacheService.java         # 价量缓存服务接口（getLatestBars, refreshBarsForSymbol）
│   ├── ScanOrchestratorService.java         # 筛选编排接口（runDailyScan, runDailyScanFromSnapshotImport, queryByDate, queryLatest）
│   ├── StockInfoService.java
│   ├── StockScannerService.java             # 股票筛选接口（scanStocks, scanLowPriceStocksWithVolumePattern）
│   ├── StockService.java                    # 股票查询接口（getDailyKLineData, getStockInfo, scanStocks...）
│   ├── TigerSnapshotGridService.java
│   └── TigerWatchlistIngestService.java     # 截图导入接口（常量 SNAPSHOT_SOURCE = "tiger_snap"）
└── service/impl/
    ├── DailyScanScheduler.java              # 已存在！@Scheduled(cron = "0 40 4 * * MON-FRI", zone = "America/New_York")
    ├── KLineServiceImpl.java
    ├── LowPriceVolumeScanServiceImpl.java   # scan() 实现：加载K线→排序→取前7→PatternEvaluateService→persistHit
    ├── MarketDataSourceRouterImpl.java      # 多源路由实现（tiger > tigeropen > yfinance > twelvedata > tiingo，带背压冷却）
    ├── PatternEvaluateServiceImpl.java      # 成交量递增模式匹配核心算法（checkIncreasingPattern）
    ├── PriceVolumeCacheServiceImpl.java     # 价量数据缓存+刷新（getLatestBars @Cacheable, refreshBarsForSymbol @Transactional）
    ├── PriorityQueryExecutor.java           # 带优先级和重试的查询执行器（最多5轮，每轮间隔5分钟）
    ├── PriorityStockServiceImpl.java        # @Primary StockService 实现，使用 PriorityQueryExecutor
    ├── ScanOrchestratorServiceImpl.java     # runDailyScan 核心：异步加载→PatternEvaluate→匹配→persist
    ├── StockInfoServiceImpl.java
    ├── StockScannerServiceImpl.java
    ├── TigerJavaDataSourceStrategy.java
    ├── TigerOpenDataSourceStrategy.java
    ├── TigerSnapshotGridServiceImpl.java
    ├── TigerStockServiceImpl.java
    ├── TigerWatchlistIngestServiceImpl.java
    ├── TiingoDataSourceStrategy.java
    ├── TiingoStockServiceImpl.java
    ├── TwelveDataDataSourceStrategy.java
    ├── TwelveDataStockServiceImpl.java
    ├── YFinanceDataSourceStrategy.java
    └── YFinanceStockServiceImpl.java

src/main/resources/
├── application.yml                          # MySQL 127.0.0.1:3307/stock_invest
├── db/schema.sql                             # screening_match + stock_daily_bar 建表
├── logback-spring.xml
├── python/                                   # Python 脚本
│   ├── requirements.txt
│   ├── stock_info_twelvedata.py
│   ├── stock_info_yfinance.py
│   ├── test_script.py
│   └── tigeropen_channel.py
├── static/api-docs.html
└── templates/screener-daily.html             # Thymeleaf 页面
```

---

## 二、不动什么（保留代码）

### 已存在且完整可用的代码

1. **截图导入 API：** `POST /api/ingest/tiger-watchlist` 已存在，完整可运行，不动。截图导入由 OpenClaw 端 skill 独立实现（非 Java 代码），stock-invest 只需提供此接口。
2. **`ScreeningMatch` Entity + Repository：** 筛选命中记录持久化，已完整。字段：`id, batchId, dataSource, symbol, lastClose, tradeDate, price, rise, createdAt`。
3. **`StockDailyBar` Entity + Repository：** 日线数据表，已完整。唯一约束：`(symbol, tradeDate)`。字段：`id, symbol, stockName, tradeDate, openPrice, closePrice, volume, source, createdAt, updatedAt`。**不需要重建。**
4. **`KLineData.java` / `KLineIterator.java` / `StockInfo.java`：** 数据模型完全复用。
5. **`KLineDataUtils.java`：** K线排序/切片工具完全复用（`sortItemsNewestFirst`, `firstItems`）。
6. **`PatternEvaluateService` / `PatternEvaluateServiceImpl`：** 成交量递增模式匹配核心算法（`matchesIncreasingVolumePattern`），**⚠️ 注意：不存在 `StockPatternUtil.java`。模式匹配逻辑全在 `PatternEvaluateServiceImpl.java` 里**。其 `checkIncreasingPattern()` 实现：对长度为 3~7 天的窗口，逐步缩短窗口计算尾均值，检查是否严格递减 → 最后检查倒数第 2 个均值 < 最新成交量。
7. **`LowPriceVolumeScanService` / `LowPriceVolumeScanServiceImpl`：** 低价放量筛选服务。`scan()` 方法接收 dataSource、limit、candidates、klineLoader function，内部调用 `PatternEvaluateService.matchesIncreasingVolumePatternFromKLine()`。
8. **`ScanOrchestratorService` / `ScanOrchestratorServiceImpl`：** 已被筛选编排的核心。`runDailyScan()` 使用 `MarketDataSourceRouter.loadCandidates()` → 异步 `PriceVolumeCacheService.refreshBarsForSymbol()` → `PatternEvaluateService.matchesIncreasingVolumePattern()` → `ScreeningMatchRepository.save()`。`runDailyScanFromSnapshotImport()` 从 `stock_daily_bar` 表的 `tiger_snap` 数据直接筛选。
9. **`DailyScanScheduler`：** 已存在，cron `0 40 4 * * MON-FRI America/New_York`（≈北京时间下午 4:40~5:40），按 `scanner.scheduledScanSource` 配置决定走 `external_api` 还是 `snapshot`。
10. **`PriceVolumeCacheService` / `PriceVolumeCacheServiceImpl`：** 价量缓存服务，`refreshBarsForSymbol()` 负责从远程数据源拉取 K 线并 upsert 到 `stock_daily_bar` 表。@Cacheable 缓存 `dailyBars`。
11. **`PriorityQueryExecutor`：** 带优先级和重试的查询执行器，最多 5 轮，每轮间隔 5 分钟。所有多源查询（KLineService、StockScannerService 等）都通过它。
12. **`DataSourceStrategy` 接口及各实现 + `TiingoStockServiceImpl`：** 多数据源策略模式（TigerOpenDataSourceStrategy、TigerJavaDataSourceStrategy、YFinanceDataSourceStrategy、TwelveDataDataSourceStrategy、TiingoDataSourceStrategy），TiingoStockServiceImpl 对应 Tiingo 数据源的 StockService 实现。按 `@Order` 优先级依次尝试，`PriorityStockServiceImpl` 和 `KLineServiceImpl` 通过 `PriorityQueryExecutor` 执行。
13. **`MarketDataSourceRouterImpl`：** 带背压冷却的多源路由器。数据源优先级：`tiger > tigeropen > yfinance > twelvedata > tiingo`。失败时自动降温（Tiger key 错误降 30 分钟，429 降 5 分钟等）。
14. **截图导入客户端：** `TigerOpenPythonBridge.java`、`TigerWatchlistIngestService.java` / `TigerWatchlistIngestServiceImpl.java` 完整可运行。
15. **现有 Client 类：** `TwelveDataRestClient.java`、`YahooFinanceRestClient.java`、`TiingoRestClient.java` 完全复用。
16. **现有 `application.yml`：** 保留，新增配置追加。
17. **现有 Controller：** `ScreeningPageController`、`ScreeningQueryController`、`StockController`、`StockScannerController`、`TigerWatchlistIngestController` 全部保留，不动。可在其基础上扩展新端点。
18. **现有 `PythonScriptExecutor.java` / `PythonRuntimeSupport.java`：** Python 脚本调用工具复用。
19. **现有 `AsyncConfig.java`（scanExecutor）：** 扫描线程池配置复用。
20. **现有 DTO：** `ScreenerRunResponseDto`、`ScreeningMatchProjection`、`ScreeningResultDto`、`ApiResponse` 等完整可用。
21. **现有 `WatchlistVolumeParser.java`：** 截图成交量解析工具（万/亿 → 数值）复用。

**⚠️ 重要的不存在文件：**
- **`StockPatternUtil.java` 不存在！** 模式匹配逻辑在 `PatternEvaluateServiceImpl.java`。请在代码中引用 `PatternEvaluateService`。
- **不需要重新创建 `StockDailyBar` Entity 或 Repository。** 已经存在。
- **不需要重新创建 `DailyScanScheduler`。** 已经存在。
- **不需要在主类上加 `@EnableScheduling`。** 已经存在。

---

## 三、要改什么 & 新增什么

### Step 2 — 数据补缺定时任务（新增）

#### 2.1 数据库表：`stock_daily_bar`（已存在，不需重建）

现有 `stock_daily_bar` 表定义：

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

现有 Java Entity `StockDailyBar.java` 字段：
`id, symbol, stockName, tradeDate, openPrice, closePrice, volume, source, createdAt, updatedAt`

现有 Repository `StockDailyBarRepository.java` 方法：
- `findTop7BySymbolOrderByTradeDateDesc(String symbol)`
- `findTop7BySymbolAndSourceOrderByTradeDateDesc(String symbol, String source)`
- `findBySymbolOrderByTradeDateDesc(String symbol, Pageable pageable)`
- `findBySymbolAndSourceOrderByTradeDateDesc(String symbol, String source, Pageable pageable)`
- `findBySymbolAndTradeDate(String symbol, LocalDate tradeDate)` ← ⭐ 可复用做 upsert 判断
- `findBySymbolsInAndSourceAndTradeDate(Collection<String>, String, LocalDate)` — 批量 IN 查询
- `findDistinctSymbolsByTradeDateAndSourceAndClosePriceBetween(...)` — 筛选价格范围内所有 symbol
- `findDistinctTradeDatesBySourceAsc(String)` — 获取某数据源所有交易日
- `findBySymbolInAndSourceOrderBySymbolAscTradeDateDesc(Collection<String>, String)` — 批量 symbol 查询
- `findBySourceAndTradeDateInOrderBySymbolAscTradeDateAsc(String src, Collection<LocalDate>)` — 多日期批量查询

**不需要新增 Repository 方法**，已有方法完全足够数据补缺需求。

#### 2.2 新增数据补缺 Service

**新增 `DataGapFillerService.java`** — 放在 `com.stock.invest.service.impl`

核心逻辑：
1. 从 `stock_daily_bar` 表获取所有有记录的不同 symbol（通过 `StockDailyBarRepository.findDistinctTradeDatesBySourceAsc` 或 JPA 方法）
2. 对每个 symbol，按 `tradeDate DESC` 排序取出所有记录
3. 找出日期不连续的部分（遍历相邻记录，计算日期差 > 1 天）
4. 检查该股票最新价：从 `stock_daily_bar` 取最近一条的 `closePrice`，如果 > $1.00 → 跳过（price-threshold 可配置）
5. 对存在数据缺口的股票，按数据源优先级补查缺失日数据（**每次只查 1 只股票**）
6. **写入逻辑**：使用已有的 `StockDailyBarRepository.findBySymbolAndTradeDate()` 判断是否存在。已存在 → 更新；不存在 → 插入新记录。`source` 字段记录实际数据源名称。
7. 查询失败 → 写入 `data_fill_tasks` 表，进入失败重试流程

**数据源优先级（Fallback 链）：**

完整优先级顺序（5级）：
1. **Tiger 开放平台 (Java SDK)** — TigerOpenDataSourceStrategy（最高优先级）
2. **Tiger (Python SDK)** — TigerJavaDataSourceStrategy
3. **YFinance** — YFinanceDataSourceStrategy
4. **TwelveData** — TwelveDataDataSourceStrategy
5. **Tiingo** — TiingoDataSourceStrategy（最后 fallback）

```
DataGapFillerService
  ├─ MarketDataSourceRouter.fetchDailyBars(symbol, "tiger", count)   [最高优先级，走路由链]
  ├─ PriceVolumeCacheService.refreshBarsForSymbol(symbol, "tiger", tradeDate)  [走缓存+刷新]
  └─ 直接调用 DataSourceStrategy 各实现（通过 PriorityQueryExecutor）
       └─ TigerOpen → TigerJava → YFinance → TwelveData → Tiingo（按 @Order 优先级）
```

#### 2.3 新增失败重试表：`data_fill_tasks`

新建 JPA Entity：

**`DataFillTask.java`** (`com.stock.invest.entity`)
```java
@Entity
@Table(name = "data_fill_tasks")
@Data
public class DataFillTask {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(nullable = false)
    private LocalDate missingDate;    // 缺失的交易日

    @Column(nullable = false, length = 16)
    private String status;            // pending / retrying / success / stopped

    @Column
    private Integer retryCount = 0;   // 当天已重试次数

    @Column
    private LocalDate retryDate;      // 最后重试日期

    @Column
    private Integer dayCount = 0;     // 已持续天数（最多7）

    @Column(length = 512)
    private String lastError;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}
```

唯一约束：`(symbol, missingDate)`。

**新增 Repository：**

**`DataFillTaskRepository.java`** (`com.stock.invest.repository`)
- `findByStatusOrderByCreatedAtAsc(String status)` — 按状态查询任务
- `findBySymbolAndMissingDate(String symbol, LocalDate missingDate)` — 查是否已有该任务
- `findByStatusIn(List<String> statuses)` — 批量查询
- `countByStatus(String status)` — 统计某状态任务数

#### 2.4 失败任务重试逻辑

**`DataGapFillerService`** 完成正常扫描后，继续处理失败重试：

```java
// 扫描缺失 → 创建 pending 任务
// 处理完 pending → 处理 retrying 任务
// 每条每天最多5次（可配置）
// 7天仍失败 → stopped

for (DataFillTask task : retryingTasks) {
    if (task.getRetryCount() >= 5) {  // max-retry-per-day
        task.setDayCount(task.getDayCount() + 1);
        if (task.getDayCount() >= 7) {  // max-days
            task.setStatus("stopped");
        } else {
            task.setRetryCount(0);  // 第二天重置
        }
        continue;
    }
    
    KLineData data = fetchFromDataSourceFallback(task.getSymbol(), task.getMissingDate());
    if (data != null) {
        saveToDailyBar(task.getSymbol(), data);
        task.setStatus("success");
    } else {
        task.setRetryCount(task.getRetryCount() + 1);
        task.setStatus("retrying");
        task.setLastError("所有数据源均返回空");
    }
}
```

**状态流转：**
```
pending ──→ retrying ──→ success
              │
              ├─ 当天<5次 → 继续重试
              ├─ 当天>=5次 → 第二天重置,dayCount+1
              └─ dayCount>=7 → stopped
```

**数据源 fallback 注意：** 每次失败任务重试时，走完整 fallback 链。

#### 2.5 新增定时任务

**`DataGapFillScheduler.java`** (`com.stock.invest.service.impl` 或新增 `com.stock.invest.scheduler` 包)

```java
@Component
public class DataGapFillScheduler {
    // 每天 6:00（北京时间），确保美股收盘后运行
    // 北京时间 06:00 = 美国东部时间 17:00（冬令时）/ 18:00（夏令时）的前一天
    @Scheduled(cron = "${scheduler.data-gap-fill.cron:0 0 6 * * ?}")
    public void fillDataGaps() {
        // 调用 DataGapFillerService
    }
}
```

**现有 `DailyScanScheduler` 定时任务参考：** 已存在 `@Scheduled(cron = "0 40 4 * * MON-FRI", zone = "America/New_York")`，注意两个定时任务不要互相冲突。

#### 2.6 在 `application.yml` 新增定时任务配置

```yaml
spring:
  task:
    scheduling:
      pool:
        size: 2

scheduler:
  data-gap-fill:
    enabled: true
    cron: "0 0 6 * * ?"         # 北京时间 06:00
    max-stocks-per-run: 1       # 每次只查1只
    price-threshold: 1.0        # 价格超过此值跳过补缺

data-fill:
  max-retry-per-day: 5
  max-days: 7
```

---

### Step 3 — 7天筛选定时任务（改造现有）

#### 3.1 现有筛选流程分析

现有代码已实现的筛选流程：

```
DailyScanScheduler
  └─ ScanOrchestratorService.runDailyScan(tradeDate, limit)
       └─ MarketDataSourceRouter.loadCandidates(limit*3, minPrice, maxPrice)
            └─ tryLoadCandidates(source, limit, minPrice, maxPrice) — 按优先级逐个尝试
       └─ 每只候选股票 → CompletableFuture.supplyAsync(() →
            PriceVolumeCacheService.refreshBarsForSymbol(sym, "tigeropen", tradeDate, windowDays)
            → PatternEvaluateService.matchesIncreasingVolumePattern(bars, windowDays)
            → 匹配成功 → ScreeningMatchRepository.save(row)
       └─ return ScreenerRunResponseDto
```

以及 `runDailyScanFromSnapshotImport` 方案：
```
直接使用 stock_daily_bar 中 source='tiger_snap' 的数据
→ 筛选价格范围内的 symbol
→ 按 symbol 分组获取日线数据
→ PatternEvaluateService.matchesIncreasingVolumePattern
→ 匹配成功 → ScreeningMatchRepository.save(row)
```

**已有实体字段差异说明：**
- `ScreeningMatch` 中 `rise` 字段为 `Boolean`，表示当日是否上涨（close > open）
- 当前 `ScreeningMatch` 只有 `price` 字段（= `lastClose`），未单独区分 `closePrice` 和 `openPrice`
- 当前筛选逻辑使用 `PatternEvaluateServiceImpl.checkIncreasingPattern()` 做成交量递增判断

#### 3.2 改造筛选服务

**新增 `ScreeningService.java`**（接口）+ **`ScreeningServiceImpl.java`**（实现），放入 `com.stock.invest.service` / `com.stock.invest.service.impl`

核心改造点：
1. **不再依赖 MarketDataSourceRouter.loadCandidates()**（像 runDailyScan 那样外部拉取），直接使用 `stock_daily_bar` 表的数据
2. 从 `stock_daily_bar` 中获取所有有 `tiger_snap`（截图导入）或其他数据源记录的 symbol
3. 对每个 symbol，取最近 7 天日线数据（已有 `StockDailyBarRepository.findTop7BySymbolOrderByTradeDateDesc`）
4. 复用 `PatternEvaluateService.matchesIncreasingVolumePattern(bars, windowDays)` 
5. 匹配成功 → 写入 `ScreeningMatch` 表（复用现有 entity/repository）
6. 同时写入历史记录表 `screening_result`（如果觉得需要独立的筛选结果审计表）

**新增 `ScreeningResult.java`** Entity（可选，如果认为需要用单独的审计表）：

```java
@Entity
@Table(name = "screening_result")
@Data
public class ScreeningResult {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String batchId;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(nullable = false, length = 16)
    private String source;        // 数据源：tiger_snap / tiger / yfinance

    @Column
    private Double lastClose;

    @Column(name = "is_rise")
    private Boolean rise;         // 是否为上涨

    @Column
    private LocalDate tradeDate;

    @Column(length = 1024)
    private String pattern;       // 匹配的模式名

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
```

**新增 Repository：**

**`ScreeningResultRepository.java`** (`com.stock.invest.repository`)
- `findByBatchIdOrderByIdAsc(String batchId)`
- `findTop20ByOrderByCreatedAtDesc()`
- `findTopByOrderByCreatedAtDesc()` — 获取最新一条

#### 3.3 保留并扩展现有 API

`ScreeningQueryController.java` 已包含：
- `POST /api/screener/run` — 手动触发筛选（走 MarketDataSourceRouter）
- `POST /api/screener/run-from-snapshot` — 从截图数据筛选
- `GET /api/screener/results?tradeDate=...` — 按日期查筛选结果
- `GET /api/screener/results/latest` — 最新筛选结果

**扩展建议：**
- 新增 `GET /api/screener/batch/{batchId}` — 按批次查询详情
- 新增 `GET /api/screener/history` — 历史筛选批次

#### 3.4 新增定时任务

**新增 `ScreeningScheduler.java`** （`com.stock.invest.service.impl`）

```java
@Component
public class ScreeningScheduler {
    // 默认 9:30 北京时间运行
    @Scheduled(cron = "${scheduler.screening.cron:0 30 9 * * ?}")
    public void runDailyScreening() {
        // 调用 ScreeningService.runScreening()
    }
}
```

---

### Step 4 — 通知（HTTP API 供 OpenClaw 调用）

#### 4.1 通知方式

stock-invest **不主动发送通知**。筛选完成后通过 HTTP 接口暴露结果，由 OpenClaw 定时查询该接口，获取结果后发送到 QQ Bot。

#### 4.2 新增 Controller

**`NotificationController.java`** (`com.stock.invest.controller`)

```java
@RestController
@RequestMapping("/api/notification")
public class NotificationController {
    // GET /api/notification/latest
    // 返回最近一次筛选的达标结果
}
```

#### 4.3 返回格式

```json
{
  "batchId": "batch-20260518-xxxx",
  "screenDate": "2026-05-18",
  "hitCount": 5,
  "results": [
    { "symbol": "ABC", "lastClose": 0.042 }
  ],
  "generatedAt": "2026-05-18T05:00:00"
}
```

如果当天无筛选结果：
```json
{
  "batchId": null,
  "screenDate": null,
  "hitCount": 0,
  "results": [],
  "generatedAt": "2026-05-18T05:00:00"
}
```

---

### Step 5 — 前端展示页面（新增）

#### 5.1 新增 REST API

在现有 Controller 基础上扩展，或新增：

**`ScreeningController.java`** (`com.stock.invest.controller`)
- `GET /api/screening/latest` — 最近一次筛选结果
- `GET /api/screening/history` — 历史筛选批次列表
- `GET /api/screening/batch/{batchId}` — 某批次详情

**`StockBarController.java`** (`com.stock.invest.controller`)
- `GET /api/bars/{symbol}` — 某股票的日线数据
- `GET /api/bars/list` — 所有有记录的股票列表
- `GET /api/bars/summary` — 数据统计摘要（总股票数、总记录数等）

#### 5.2 前端页面

**Thymeleaf 页面**（已有 `templates/screener-daily.html`，在其基础上补充或新增 3 个页面）：

1. `screening.html` — 筛选结果展示页（表格形式）
2. `bars.html` — 截图导入/日线数据查看页
3. `fill-tasks.html` — 数据补缺任务状态页（pending/retrying/success/stopped 及各重试计数）

---

## 四、新增文件清单总结

### 新增 Source Files

| 层 | 文件名 | 说明 |
|---|---|---|
| Entity | `DataFillTask.java` | `data_fill_tasks` 失败重试跟踪表 |
| Entity | `ScreeningResult.java` | （可选）独立筛选结果审计表 |
| Repository | `DataFillTaskRepository.java` | data_fill_tasks 的 CRUD |
| Repository | `ScreeningResultRepository.java` | （可选）screening_result 的 CRUD |
| Service | `DataGapFillerService.java` | 数据补缺核心逻辑（含失败重试） |
| Service | `ScreeningService.java`（接口） | 7天筛选服务接口（从 stock_daily_bar 读数据） |
| Service | `ScreeningServiceImpl.java`（实现） | 复用 PatternEvaluateService + StockDailyBarRepository |
| Controller | `NotificationController.java` | 通知 API（供 OpenClaw 轮询） |
| Scheduler | `DataGapFillScheduler.java` | 数据补缺定时任务 |
| Scheduler | `ScreeningScheduler.java` | 筛选定时任务 |
| Controller | `ScreeningController.java` | 筛选结果 REST API |
| Controller | `StockBarController.java` | 日线数据 REST API |
| Frontend | `templates/screening.html` | 筛选结果页面（Thymeleaf） |
| Frontend | `templates/bars.html` | 日线数据查看页面（Thymeleaf） |
| Frontend | `templates/fill-tasks.html` | 数据补缺任务状态页面（Thymeleaf） |

### 修改的 Source Files

| 文件 | 改动 |
|---|---|
| `pom.xml` | `spring-boot.version` → 3.3.13（注意 `javax.*` → `jakarta.*` 会影响所有 Entity）；Lombok 版本可能需升级；mysql-connector-j 确认兼容 |
| `application.yml` | 添加 scheduler.data-gap-fill、data-fill 配置项 |
| 所有 Entity 和 Repository | 替换 `javax.persistence.*` → `jakarta.persistence.*` |
| 所有 import `javax.servlet` 的文件 | 替换为 `jakarta.servlet` |
| `ScreeningPageController.java` | （可选）扩展页面路由 |
| `ScreeningQueryController.java` | （可选）新增 batch/history 端点 |

---

## 五、升级注意事项（Spring Boot 2.7.18 → 3.3.13）

### `javax.*` → `jakarta.*` 全局替换

**所有 Entity 类需要替换：**
```
javax.persistence.Entity          → jakarta.persistence.Entity
javax.persistence.Table           → jakarta.persistence.Table
javax.persistence.Id              → jakarta.persistence.Id
javax.persistence.GeneratedValue → jakarta.persistence.GeneratedValue
javax.persistence.Column         → jakarta.persistence.Column
javax.persistence.UniqueConstraint → jakarta.persistence.UniqueConstraint
javax.persistence.Index          → jakarta.persistence.Index
javax.persistence.EntityListeners → jakarta.persistence.EntityListeners
```

受影响文件（共约 10 个）：
- `ScreeningMatch.java`
- `StockDailyBar.java`
- 所有 Repository 无此问题（JpaRepository 内部处理）
- 无 `javax.servlet` 导入

### Spring Boot 3.x 配置变更

| 配置项 | SB 2.7.x | SB 3.3.x |
|---|---|---|
| Spring Security（如果启用） | `http.and()` 链式 | 新 Lambda DSL |
| Thymeleaf | spring5 依赖 | spring6 依赖（SB 3.x 自带） |
| Hibernate | 5.x | 6.x（JPA 3.x） |
| mysql-connector-j | 8.0.33 | 8.0.33+ 兼容 |
| `@ConstructorBinding` | 需显式标注 | 对 ConfigurationProperties 自动检测 |

### Spring Data JPA 3.x 变化

- `Pageable` 相关方法返回签名基本兼容
- `findDistinct*` 行为不变
- `@Query` 注解兼容
- `JpaRepository` 泛型签名不变

### 具体文件修改清单

#### pom.xml 需要更新

```xml
<spring-boot.version>3.3.13</spring-boot.version>
```

确认依赖：
- `spring-boot-starter-thymeleaf` → SB 3.x 自动引入 Thymeleaf 6，无需手动改版本
- `spring-boot-starter-data-jpa` → 自动升级 Hibernate 6.x
- `mysql-connector-j:8.0.33` → 兼容
- `lombok:1.18.34` → 兼容
- `tiger-api:2.2.6` → 兼容（不依赖 javax）
- `maven-compiler-plugin:3.12.1` → 兼容
- `maven-surefire-plugin:2.22.2` → 建议升级到 `3.2.5` 以支持 JUnit 5 最新版

#### 编译注意事项

```bash
# WSL 实际环境：JDK 21
# pom.xml 中 java.version=17 保持不变
# Spring Boot 3.3.13 需要 Java 17+
# mvn 用 JDK 21 编译 Java 17 字节码完全兼容
# 不需要安装 openjdk-17-jdk
mvn clean compile -DskipTests
```

#### 升级后编译测试命令

```bash
cd /home/allon/application/stock-invest

# 1. 先做 javax → jakarta 全局替换（可在 IDE 中批量做，或手动改每个 Entity）
# 2. 更新 pom.xml spring-boot.version
# 3. 编译
mvn clean compile -DskipTests 2>&1 | tail -50
# 4. 如果编译通过，跑测试
mvn test 2>&1 | tail -50
```

---

## 六、实现步骤顺序

### Phase 1：框架升级（先做）

1. 更新 `pom.xml`：`spring-boot.version` → `3.3.13`
2. 更新 `maven-surefire-plugin` → `3.2.5`
3. 全局替换 `javax.persistence.*` → `jakarta.persistence.*`（影响 `ScreeningMatch.java`、`StockDailyBar.java`。注意：Repository 的 import 不变，`@CreatedDate` / `@LastModifiedDate` 在 `org.springframework.data.annotation` 包，不变）
4. `mvn clean compile -DskipTests` 验证编译通过
5. `mvn test` 验证测试通过（如果有测试的话）

### Phase 2：数据补缺（Step 2）

6. 新增 `DataFillTask.java` Entity
7. 新增 `DataFillTaskRepository.java`
8. 新增 `DataGapFillerService.java`
   - 扫描 `stock_daily_bar` 中数据缺口
   - 判断最新价是否 > price-threshold → 跳过
   - 通过 `MarketDataSourceRouter.fetchDailyBars()` 补缺
   - 写入失败时创建 `DataFillTask`
9. 新增 `DataGapFillScheduler.java`
10. 在 `application.yml` 添加配置

### Phase 3：筛选改造（Step 3）

11. 新增 `ScreeningResult.java`（可选）
12. 新增 `ScreeningResultRepository.java`（可选）
13. 新增 `ScreeningService.java` / `ScreeningServiceImpl.java`
    - 从 `stock_daily_bar` 读数据 → 分组 → 传入 `PatternEvaluateService` → 写入 `ScreeningMatch`（或 `ScreeningResult`）
14. 新增 `ScreeningScheduler.java`

### Phase 4：通知 + 前端展示（Step 4 & 5）

15. 新增 `NotificationController.java`
16. 新增 `ScreeningController.java` + `StockBarController.java`
17. 创建 Thymeleaf 前端页面

### Phase 5：验证

18. `mvn clean compile -DskipTests` — 编译通过
19. 启动项目，确认定时任务日志正常
20. 访问前端页面，验证筛选结果展示
21. 手动触发一次数据补缺，验证 `stock_daily_bar` 写入

---

## 七、技术要点备忘

### 定时任务时间配置

```
DailyScanScheduler（已存在）:
  cron = "0 40 4 * * MON-FRI" | zone = "America/New_York"
  美国东部时间 04:40 ≈ 北京时间 16:40（夏令时）/ 17:40（冬令时）
  用于美股收盘后初次扫描

DataGapFillScheduler（新增）:
  cron = "0 0 6 * * ?" | 默认北京时间
  北京时间 06:00，在每日扫描之后做数据补缺

ScreeningScheduler（新增）:
  cron = "0 30 9 * * ?" | 默认北京时间
  北京时间 09:30 = 美国东部时间前一日 20:30（夏令时）/ 21:30（冬令时）
```

### 成交量模式匹配算法

现有 `PatternEvaluateServiceImpl.callcheckIncreasingPattern()`：

```java
private static boolean checkIncreasingPattern(long[] vols, int windowDays) {
    // 逐步缩短窗口，检查尾均值是否严格递减
    for (int len = windowDays; len > 1; len--) {
        double longerAvg = averageTail(vols, len);
        double shorterAvg = averageTail(vols, len - 1);
        if (!(longerAvg < shorterAvg)) {
            return false;
        }
    }
    // 最后检查：倒数第2个均值 < 最新日成交量
    return averageTail(vols, 2) < vols[windowDays - 1];
}
```

**没有 `StockPatternUtil.java`。** 模式匹配引用路径：
```java
// 正确引用方式（全部已存在）：
@Autowired
private PatternEvaluateService patternEvaluateService;

// 增量成交量（日线→StockDailyBar）：
patternEvaluateService.matchesIncreasingVolumePattern(bars, windowDays);

// 增量成交量（K线→KLineIterator / KLineData）：
patternEvaluateService.matchesIncreasingVolumePatternFromKLine(barsOldestFirst, windowDays);
```

### 数据源 fallback 实现模式

```java
// 已有 DataSourceStrategy 接口 + 各实现（含 TiingoDataSourceStrategy）
// 已有 PriorityQueryExecutor 带重试机制
// 已有 MarketDataSourceRouter 带背压冷却（tiger > tigeropen > yfinance > twelvedata > tiingo）

// 需要在 DataGapFillerService 中直接使用：
@Autowired
private MarketDataSourceRouter marketDataSourceRouter;  // 走路由链

// 或通过 PriorityQueryExecutor 走策略链：
@Autowired
private List<DataSourceStrategy> strategies;

@Autowired
private PriorityQueryExecutor priorityQueryExecutor;

// 完整 fallback 优先级：TigerOpen → TigerJava → YFinance → TwelveData → Tiingo
```

### 时间处理
- `StockDailyBar.tradeDate` 用 `LocalDate`（表示自然日，不含时区）
- 所有外部数据源获取的 K 线时间戳需转换为 `LocalDate` 再入库
- 已有 `PriceVolumeCacheServiceImpl.toTradeDate()` 工具方法可复用
- 北京时间 06:00 = 美国东部时间前一天的 17:00（夏令时）或 16:00（冬令时），确保收盘后运行

### 价格过滤规则
-  检查该股票最新价格（从 `stock_daily_bar` 取最近一条的 `closePrice` 字段）
-  如果最新 > `price-threshold`（默认 $1.00），跳过该股票的数据补缺
-  此规则用于 Step 2 的数据补缺任务

### 数据写入逻辑
- 查 `stock_daily_bar` 中是否已有 `(symbol, tradeDate)` 记录
- 已存在 → `save()` 直接更新该条记录（含 source 字段）
- 不存在 → 插入新记录
- 唯一约束已存在：`uk_stock_daily_bar_symbol_trade_date (symbol, trade_date)`

### 失败重试规则
- 每次补缺任务最多持续 **7 天**（可配置 `data-fill.max-days`）
- 每天最多重试 **5 次**（可配置 `data-fill.max-retry-per-day`）
- 7 天后仍查不到 → 标记 `stopped`，永不再执行
- 每次重试走完整数据源 fallback 链

### 回退/安全措施
- 定时任务应加日志，每次运行记录开始/结束/处理数量
- 如果 Tiger / yfinance 调用失败，日志记录但不中断整个任务
- 使用 `@Scheduled(fixedDelayString = "${scheduler.data-gap-fill.interval:3600000}")` 可做为 cron 失效的 fallback
- 注意 `MarketDataSourceRouterImpl` 已有背压冷却机制（失败自动降温），不要重复实现
