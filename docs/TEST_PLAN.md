# Stock-Invest 测试方案

## 目录

1. [测试总体策略](#1-测试总体策略)
2. [测试环境](#2-测试环境)
3. [模块分析](#3-模块分析)
4. [现有测试覆盖度分析](#4-现有测试覆盖度分析)
5. [测试目录结构](#5-测试目录结构)
6. [第一轮：纯 Mock 测试](#6-第一轮纯-mock-测试)
7. [第二轮：Mock 数据 + 真实数据源](#7-第二轮mock-数据--真实数据源)
8. [测试数据准备](#8-测试数据准备)
9. [Mock 方案](#9-mock-方案)
10. [测试配置](#10-测试配置)
11. [测试清理](#11-测试清理)
12. [附录：覆盖度汇总矩阵](#12-附录覆盖度汇总矩阵)

---

## 1. 测试总体策略

采用**两轮测试**策略，逐步从纯逻辑验证过渡到真实环境验证。

### 第一轮：纯 Mock 测试

**目标：** 验证功能逻辑正确性，所有外部依赖全部隔离。

- **所有数据源全部 Mock**
  - Tiger Java SDK（`TigerHttpClient` → 模拟返回 `QuoteKlineResponse`）
  - Tiger Python Bridge（`TigerOpenPythonBridge` → 模拟返回 JSON）
  - YFinance REST Client（`YahooFinanceRestClient` → 模拟 HTTP 响应）
  - TwelveData REST Client（`TwelveDataRestClient` → 模拟 HTTP 响应）
  - Tiingo REST Client（`TiingoRestClient` → 模拟 HTTP 响应）
- **数据库用 H2 内存数据库**（`MODE=MySQL` 兼容模式）
- **对外 HTTP 请求全部拦截**（Mockito mock 或 MockRestServiceServer）
- **Thymeleaf 模板引擎可测试渲染**（`@WebMvcTest` 级别验证）

### 第二轮：Mock 数据 + 真实数据源

**目标：** 验证数据源连接、鉴权、限流处理是否正确。

- 数据内容用 **Mock 预置数据集**（H2 data.sql 或 @Sql 脚本）
- 数据源指向**真实的 API 端点**（Endpoint 启用，不开 Mock）
- 预期结果：
  - 限流/鉴权错误 → 测试通过（证明代码正确处理了异常情况）
  - 能正常获取数据 → 也通过（证明代码能正常工作）
  - 所有数据源均不可用 → fallback 到记录，也通过

> **注意：** 第二轮测试不应该在不合适的时段（如美股休市期间）执行，或应配置为只在 CI 的特定 job 中运行（`@Tag("integration")`）。

---

## 2. 测试环境

### 2.1 数据库

| 配置项 | 第一轮 | 第二轮 |
|--------|--------|--------|
| 数据库 | H2 内存数据库 | H2 内存数据库 |
| URL | jdbc:h2:mem:stock-invest-test;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE | 同上 |
| DDL | create-drop | create-drop |
| 数据预置 | @Sql + 手动 mock 数据 | data.sql 预置数据集 |

### 2.2 数据源

| 数据源 | 第一轮 | 第二轮 |
|--------|--------|--------|
| Tiger SDK | Mock | 启用真实连接 |
| YFinance REST | Mock | 启用真实连接 |
| TwelveData REST | Mock | 启用真实连接 |
| Tiingo REST | Mock | 启用真实连接 |

### 2.3 测试框架

- **JUnit 5**（Spring Boot Starter Test 集成）
- **Mockito**（内置于 spring-boot-starter-test，无需额外依赖）
- **Spring MockMvc**（Controller 层测试）
- **Spring @WebMvcTest**（Thymeleaf 页面渲染测试）
- **Spring @DataJpaTest**（Repository 层测试）

### 2.4 环境变量/系统属性

第一轮测试无需特殊环境变量。第二轮需要（可选）设置对应数据源的 API Key/Tiger Token。

---

## 3. 模块分析

### 3.1 核心业务流程

```
DailyScanScheduler (cron)
  └─ ScanOrchestratorService
       ├─ MarketDataSourceRouter.loadCandidates()    // 加载候选股票列表
       ├─ MarketDataSourceRouter.fetchDailyBars()     // 获取日 K 线数据（含 fallback 链）
       │    ├─ Tiger SDK           (优先)
       │    ├─ Tiger Python Bridge  (fallback 1)
       │    ├─ YFinance             (fallback 2)
       │    ├─ TwelveData           (fallback 3)
       │    └─ Tiingo               (fallback 4)
       ├─ StockDailyBarRepository  // 保存/更新日线数据
       ├─ PatternEvaluateService   // 模式匹配（成交量递增等）
       └─ ScreeningMatchRepository // 保存筛选匹配结果
```

### 3.2 模块划分

| 模块 | 核心类 | 职责 |
|------|--------|------|
| Data Ingestion | TigerWatchlistIngestService / TigerWatchlistIngestController / IngestApiGuard | 导入 Tiger 截图行情 |
| Data Fetch/Sync | MarketDataSourceRouterImpl / YahooFinanceStockServiceImpl / TwelveDataStockServiceImpl / TigerStockServiceImpl / TiingoStockServiceImpl | 多数据源 K 线获取 + fallback |
| Data Fill | MarketDataSourceRouterImpl 及相关 Service | 补缺逻辑（检测缺失 → fallback 获取 → 保存） |
| Pattern Evaluation | PatternEvaluateServiceImpl / LowPriceVolumeScanServiceImpl | 成交量和价格模式匹配 |
| Scan Orchestration | ScanOrchestratorServiceImpl / DailyScanScheduler | 编排扫描流程 |
| Stock Info | StockInfoServiceImpl / PythonScriptExecutor | Python 脚本辅助获取股票信息（Tiger Python SDK） |
| Cache | PriceVolumeCacheServiceImpl | 价格/成交量缓存（Caffeine） |
| API Query | ScreeningQueryController / StockController / StockScannerController | 查询和展示接口 |
| Page | ScreeningPageController | Thymeleaf 页面渲染 |
| Snapshot Grid | TigerSnapshotGridService / TigerSnapshotGridServiceImpl | 截图网格视图展示 |
| Utility | KLineDataUtils / PythonRuntimeSupport / PythonScriptExecutor / WatchlistVolumeParser | 工具类 |

### 3.3 数据源 fallback 顺序

```
Tiger SDK (Java) -> Tiger Python Bridge -> YFinance -> TwelveData -> Tiingo
```

每个数据源获取失败后继续尝试下一个。全部失败则记录失败任务。

---

## 4. 现有测试覆盖度分析

现有 **17 个** 测试文件：

| 测试文件 | 覆盖内容 | 覆盖深度 |
|---------|---------|---------|
| ScanOrchestratorServiceImplTest.java | 扫描编排核心流程（数据库模式） | 中 - 有 Mockito 基础但 case 不完整 |
| ScanOrchestratorSnapshotOnlyTest.java | 扫描编排（截图模式） | 较好 - 有完整的数据流断言 |
| PatternEvaluateServiceImplTest.java | K 线模式匹配 | 较好 - 覆盖多种 pattern |
| LowPriceVolumeScanServiceUnitTest.java | 低价量扫描 | 中 - 单元级 |
| PriceVolumeCacheServiceImplTest.java | 价格/成交量缓存 | 较好 |
| TigerWatchlistIngestServiceImplTest.java | 截图导入 | 较好 |
| TigerSnapshotGridServiceImplTest.java | 截图网格 | 较好 |
| ScreeningQueryControllerTest.java | 查询 API | 较好 |
| IntegrationControllerTest.java | 集成测试 | 基本 |
| ScreeningMatchRepositoryTest.java | Repository 层 | 基本 |
| StockDailyBarRepositoryTest.java | Repository 层 | 基本 |
| TwelveDataStockServiceImplScanTest.java | TwelveData 扫描集成 | 基础 |
| YFinanceStockServiceImplScanTest.java | YFinance 扫描集成 | 基础 |
| KLineDataUtilsTest.java | K 线工具 | 较好 |
| PythonScriptExecutorTest.java | Python 执行器 | 基础 |
| PythonDirectProcessTest.java | Python 直接进程 | 基础 |
| WatchlistVolumeParserTest.java | Watchlist 解析 | 较好 |

### 覆盖缺口

| 缺口 | 说明 |
|------|------|
| MarketDataSourceRouterImpl | fallback 链、候选人加载逻辑 |
| Data Fill 逻辑（补缺） | 无测试覆盖 |
| TigerStockServiceImpl | Tiger SDK 数据获取 |
| TiingoStockServiceImpl | Tiingo 数据源 |
| StockInfoServiceImpl | 股票信息查询 |
| StockController | K 线数据 API 端点 |
| StockScannerController | 手动触发扫描 |
| TigerWatchlistIngestController | 截图导入 API（含鉴权跳过） |
| ScreeningPageController | Thymeleaf 页面渲染 |
| DailyScanScheduler | 定时任务触发 |
| ScannerProperties 配置 | 边界值验证 |
| IngestApiGuard | API 鉴权逻辑 |

---

## 5. 测试目录结构

```
src/test/java/com/stock/invest/
├── config/                          # 配置类测试
│   └── ScannerPropertiesTest.java
├── controller/
│   ├── ScreeningPageControllerTest.java     # Thymeleaf 页面
│   ├── ScreeningQueryControllerTest.java    # ✅ 已有
│   ├── StockControllerTest.java             # K 线 API
│   ├── StockScannerControllerTest.java      # 手动扫描 API
│   └── TigerWatchlistIngestControllerTest.java
├── repository/
│   ├── ScreeningMatchRepositoryTest.java    # ✅ 已有
│   └── StockDailyBarRepositoryTest.java     # ✅ 已有
├── scheduler/
│   └── DailyScanSchedulerTest.java
├── security/
│   └── IngestApiGuardTest.java
├── service/
│   ├── MarketDataSourceRouterImplTest.java  # fallback 链 + 候选人
│   ├── ScanOrchestratorServiceImplTest.java # ✅ 已有（补充）
│   ├── PatternEvaluateServiceImplTest.java  # ✅ 已有（补充）
│   ├── LowPriceVolumeScanServiceUnitTest.java # ✅ 已有（补充）
│   ├── TigerStockServiceImplTest.java       # Tiger SDK 数据获取
│   ├── StockInfoServiceImplTest.java        # 股票信息查询
│   ├── DataFillServiceTest.java             # 补缺逻辑
│   └── impl/
│       ├── PriceVolumeCacheServiceImplTest.java   # ✅ 已有
│       ├── ScanOrchestratorSnapshotOnlyTest.java  # ✅ 已有
│       ├── TigerSnapshotGridServiceImplTest.java  # ✅ 已有
│       └── TigerWatchlistIngestServiceImplTest.java# ✅ 已有
├── util/
│   ├── KLineDataUtilsTest.java              # ✅ 已有
│   ├── PythonScriptExecutorTest.java        # ✅ 已有
│   ├── PythonDirectProcessTest.java         # ✅ 已有
│   └── WatchlistVolumeParserTest.java       # ✅ 已有
└── integration/
    └── RealDataSourceFallbackTest.java      # 第二轮测试
```

---

## 6. 第一轮：纯 Mock 测试

### 6.1 数据补缺测试（MarketDataSourceRouter fallback 流程）

#### TC-FILL-001: 发现缺失日期并触发数据补缺
- **描述**: 给定某股票在 stock_daily_bars 中只有部分日期的记录，验证系统能正确识别缺失日期
- **预置条件**: stock_daily_bars 表中有 stock_A 的部分数据，缺 2025-01-05、2025-01-07
- **Mock 数据源**: Tiger SDK mock 返回完整的 10 天数据
- **验证**: 系统调用 fetchDailyBars 时检测缺失天数>0，触发 Tiger SDK / Tiger Python Bridge
- **预期结果**: 补缺完成，缺失记录被新增

#### TC-FILL-002: Fallback 链 - Tiger SDK 成功
- **描述**: Tiger SDK 请求成功时直接返回，不走后续 fallback
- **Mock**: Tiger SDK 返回有效 KLineData；其余标记为不应被调用
- **验证**: 仅 Tiger SDK 被调用 1 次，其余调用次数=0
- **预期结果**: 数据正确返回

#### TC-FILL-003: Fallback 链 - Tiger SDK 失败 -> Tiger Python Bridge
- **描述**: Tiger SDK 抛出异常，验证自动切换到 Tiger Python Bridge
- **Mock**: Tiger SDK 抛出异常；Tiger Python Bridge 返回有效数据
- **验证**: Tiger SDK 调用 1 次 -> Tiger Python Bridge 调用 1 次
- **预期结果**: 数据返回，来源标记为 tiger_python_bridge

#### TC-FILL-003b: Fallback 链 - Tiger Python Bridge 失败 -> YFinance
- **描述**: Tiger SDK 和 Tiger Python Bridge 均抛出异常，验证切换到 YFinance
- **Mock**: Tiger SDK 抛出异常；Tiger Python Bridge 抛出异常；YFinance 返回有效数据
- **验证**: Tiger SDK 调用 1 次 -> Tiger Python Bridge 调用 1 次 -> YFinance 调用 1 次
- **预期结果**: 数据返回，来源标记为 yfinance

#### TC-FILL-004: Fallback 链 - 全部数据源均失败
- **描述**: 所有五个数据源都返回失败
- **Mock**: 全部 mock 抛出异常
- **验证**: 按序调用 5 个源后返回空
- **预期结果**: 返回 Optional.empty()

#### TC-FILL-005: 价格 > $1.00 跳过补缺
- **描述**: 股票最新收盘价 > $1.00，跳过补缺流程
- **预置条件**: stock_B 收盘价 $1.05
- **验证**: 补缺逻辑检查价格条件，跳过 stock_B
- **预期结果**: 未触发数据源调用

#### TC-FILL-006: 已存在记录更新（含 source）
- **描述**: 某日记录已存在但 source 不同，验证更新
- **Mock**: mock 数据返回某日数据，该日已有记录但 closePrice=null
- **验证**: 更新已有记录，source 字段更新
- **预期结果**: 同一 symbol+tradeDate 记录 closePrice 被更新

#### TC-FILL-007: 失败重试 - day_count < 7
- **描述**: 补缺失败但重试次数<7，继续重试
- **Mock**: 全部数据源失败
- **验证**: 失败记录表中 day_count < 7，status 保持 pending
- **预期结果**: 继续重试

#### TC-FILL-008: 失败重试 - day_count >= 7 -> stopped
- **描述**: 补缺失败达到 7 次，标记为 stopped
- **预置条件**: day_count = 7
- **Mock**: 全部数据源失败
- **验证**: 调用后 status 更新为 stopped
- **预期结果**: 不再对该股票继续补缺

#### TC-FILL-009: 单次最多处理 1 只股票
- **描述**: 一次补缺循环中，即使有多个股票待补缺，也只处理 1 只
- **预置条件**: 3 只股票同时有缺失记录
- **验证**: 只有 1 只股票触发了数据源调用
- **预期结果**: 处理 1 只，其余留待下次

---

### 6.2 候选股票加载测试

#### TC-CANDIDATE-001: 正确加载低价格候选
- **描述**: MarketDataSourceRouter.loadCandidates 按价格范围和数量限制返回候选列表
- **Mock**: Tiger SDK mock 返回指定数量股票列表
- **验证**: 返回列表长度<=limit；价格在 [minPrice, maxPrice) 范围内
- **预期结果**: 正确过滤和分页

#### TC-CANDIDATE-002: 候选数量为 0
- **Mock**: Tiger SDK 返回空列表
- **预期结果**: loadCandidates 返回空列表，扫描跳过

---

### 6.3 7 天筛选测试（Pattern Evaluation）

#### TC-SCREEN-001: 成交量递增模式匹配（3 天验证）
- **描述**: 7 天完整 K 线数据，最后 3 天成交量严格递增
- **测试数据**: volume=[1000,1500,1800,2200,2800,3500,4200]
- **验证**: matchesIncreasingVolumePatternFromKLine 返回 true
- **预期结果**: 匹配成功，rise=true

#### TC-SCREEN-002: 成交量不足 7 天
- **描述**: 只有 5 天 K 线数据，不足 7 天窗口，跳过筛选
- **预置条件**: 5 条 StockDailyBar 记录
- **预期结果**: 跳过，不写入 screening_match

#### TC-SCREEN-003: 模式匹配失败（量不递增）
- **测试数据**: volume=[5000,3000,4500,2000,1500,1000,500]
- **预期结果**: rise=false

#### TC-SCREEN-004: 价格 >= $0.50 跳过
- **预置条件**: stock_X 最新收盘价 $0.55
- **预期结果**: 该股票未被纳入筛选

#### TC-SCREEN-005: 多窗口天数验证（3/5/7 天）
- **描述**: 验证不同窗口天数的成交量递增模式匹配
- **预期结果**: 各窗口均匹配成功

---

### 6.4 全流程扫描编排测试

#### TC-ORCH-001: 完整扫描流程（数据库模式）
- **测试数据**: 候选列表 mock 3 只，每只 7 天 K 线，1 只满足模式匹配
- **预期结果**: batchId 不为空；matchedStocks() > 0；screening_match 有 1 条

#### TC-ORCH-002: 完整扫描流程（截图模式）
- **测试数据**: 预先注入截图 K 线；窗口=3 天
- **预期结果**: matchedStocks() > 0

#### TC-ORCH-003: 无匹配结果
- **Mock**: 候选 2 只，K 线成交量递减
- **预期结果**: matchedStocks() = 0

#### TC-ORCH-004: 定时任务触发（DailyScanScheduler）
- **Mock**: Mock ScanOrchestratorService
- **预期结果**: runDailyScanFromSnapshotImport 被调用

---

### 6.5 价格/成交量缓存测试

#### TC-CACHE-001: Caffeine 缓存命中
- **验证**: 第二次查询不调用数据源
- **预期结果**: 缓存命中

#### TC-CACHE-002: 缓存过期
- **验证**: 过期后再次调用数据源
- **预期结果**: 缓存机制正确

---

### 6.6 通知 API 测试

#### TC-NOTIFY-001: GET /api/screening/latest 有结果
- **预期结果**: HTTP 200，含匹配列表

#### TC-NOTIFY-002: GET /api/screening/latest 无结果
- **预期结果**: HTTP 200，空结果

#### TC-NOTIFY-003: GET /api/screening/query 按日期+价格范围查询
- **预期结果**: 查询结果正确

#### TC-NOTIFY-004: 非法日期格式处理
- **预期结果**: 返回 400 或 ApiResponse.error

---

### 6.7 Controller API 测试

#### TC-CONTROLLER-001: StockController - GET /api/stock/kline
- **Mock**: StockService mock 返回 KLineData
- **预期结果**: HTTP 200，含 K 线数据

#### TC-CONTROLLER-002: StockScannerController - POST /api/stock/scan
- **预期结果**: HTTP 200，扫描被触发

#### TC-CONTROLLER-003: TigerWatchlistIngestController 鉴权校验
- **Mock**: 不传鉴权头
- **预期结果**: HTTP 403，IngestApiGuard 拦截生效

---

### 6.8 Thymeleaf 页面渲染测试

#### TC-PAGE-001: 首页正常渲染
- **验证**: HTTP 200，Content-Type text/html
- **预期结果**: 页面渲染正常

#### TC-PAGE-002: 查询页面（含日期+价格范围）
- **预期结果**: 页面正确显示筛选结果

---

### 6.9 截图导入流程测试

#### TC-INGEST-001: 单个批量导入
- **验证**: stock_daily_bars 新增 n 条
- **预期结果**: 导入成功

#### TC-INGEST-002: 重复导入同一批次
- **验证**: 第二次请求更新已有记录
- **预期结果**: upsert 正确

---

### 6.10 截图网格视图测试

#### TC-GRID-001: 日期范围内多股票网格展示
- **预期结果**: 网格结构正确

#### TC-GRID-002: 无数据返回空网格
- **预期结果**: 空网格

---

### 6.11 工具类测试

#### TC-UTIL-001: WatchlistVolumeParser 解析格式
- **预期结果**: 解析正确

#### TC-UTIL-002: KLineDataUtils 转换
- **预期结果**: 转换正确

#### TC-UTIL-003: PythonScriptExecutor 异常处理
- **预期结果**: 优雅处理

---

### 6.12 配置类测试

#### TC-CONFIG-001: ScannerProperties 边界值
- **验证**: minPrice=0.05, maxPrice=0.2, defaultLimit=20

#### TC-CONFIG-002: HttpClientProperties 限流配置
- **验证**: minIntervalMs=250, maxRetries=4

---

### 6.13 异常处理测试

#### TC-EXCEPT-001: GlobalExceptionHandler 捕获 StockDataException
- **Mock**: Controller 抛出异常
- **预期结果**: HTTP 500, ApiResponse.error 格式

#### TC-EXCEPT-002: 空数据源列表处理
- **预期结果**: 优雅跳过，不抛出异常

---

## 7. 第二轮：Mock 数据 + 真实数据源

### 7.1 测试说明

第二轮测试仅在特定的 CI 配置或本地 -Dtest.profile=integration 模式下运行。使用 @Tag("integration") 标注。

### 7.2 测试用例

#### TC-REAL-001: Tiger Java SDK 连接测试
- **预期结果**: 不限流->成功；限流->抛出限流异常，代码正确处理

#### TC-REAL-002: Tiger Python Bridge 数据源测试
- **预期结果**: 连接成功->数据返回；连接失败->fallback

#### TC-REAL-003: YFinance 数据源测试
- **预期结果**: 连接成功->数据返回；连接失败->fallback

#### TC-REAL-004: TwelveData 数据源测试
- **凭证**: 需配置 TWELVEDATA_API_KEY

#### TC-REAL-005: Tiingo 数据源测试
- **凭证**: 需配置 TIINGO_API_KEY

#### TC-REAL-006: 所有数据源均不可用时 fallback
- **预置条件**: 移除所有合法 API Key
- **预期结果**: fallback 链走完，返回 Optional.empty()

---

## 8. 测试数据准备

### 8.1 测试数据构建策略

| 场景 | 构建方式 |
|------|---------|
| 单个 Service 测试 | Mockito 直接 mock 依赖对象，不依赖数据库 |
| Service 集成测试 | @SpringBootTest + @Sql + @MockBean 数据源 |
| Repository 测试 | @DataJpaTest + @Sql |
| Controller 测试 | @WebMvcTest + @MockBean |
| 全链路测试（第一轮） | @SpringBootTest + H2 + @MockBean 数据源 |
| 全链路测试（第二轮） | @SpringBootTest + H2 + 真实数据源，@Tag("integration") |

---

## 9. Mock 方案

### 9.1 Mock 工具选择

使用 **Mockito**（Spring Boot Starter Test 内建），配合：
- @MockBean - 替换 Spring Bean 为 Mock
- Mockito.when().thenReturn() - 设定 mock 行为

### 9.2 各层 Mock 策略

**Controller 层**: @WebMvcTest + @MockBean mock Service
**Service 层**: @SpringBootTest + @MockBean mock Repository/数据源客户端

**Data Source Mock 示例**:
```java
@MockBean
private TigerHttpClient tigerClient;
when(tigerClient.execute(any(QuoteKlineRequest.class)))
    .thenReturn(mockKlineResponse);

@MockBean
private PythonScriptExecutor pythonExecutor;
when(pythonExecutor.execute(contains("fetch_tiger_kline"), anyList()))
    .thenReturn(mockTigerPythonKlineJson());
```

### 9.3 Fallback Mock 模式

```java
when(tigerStockService.getDailyKLineDataAsObject("AAL"))
    .thenThrow(new RuntimeException("Tiger API error"));
when(tigerPythonBridgeService.getDailyKLineDataAsObject("AAL"))
    .thenThrow(new RuntimeException("Tiger Python Bridge error"));
when(yfinanceStockService.getDailyKLineDataAsObject("AAL"))
    .thenReturn(mockKLineData());
```

**Tiger Python Bridge Mock 说明**：
- Service：`TigerOpenPythonBridge` → 通过 `PythonScriptExecutor` 调用 Python 脚本
- Mock 方式：Mock `PythonScriptExecutor`，使其返回模拟 JSON 响应，无需真实调用 Python
- Mock 示例：
```java
@MockBean
private PythonScriptExecutor pythonExecutor;
when(pythonExecutor.execute(anyString(), anyList()))
    .thenReturn("{\"ticker\":\"AAPL\",\"items\":[...]}");
```

---

## 10. 测试配置

### 10.1 现有的 application-test.yml

项目已有 `src/test/resources/application-test.yml`，使用 H2 内存数据库。

### 10.2 补充配置建议

```yaml
stock:
  data:
    source: mock                     # 第一轮走 mock
tiger:
  enabled: false
logging:
  level:
    com.stock.invest: DEBUG
```

### 10.3 Maven Surefire 配置

```xml
<excludedGroups>integration</excludedGroups>
```

---

## 11. 测试清理

- @DataJpaTest：事务自动回滚（默认）
- @SpringBootTest + @Transactional：事务自动回滚
- H2 内存数据库：随 JVM 关闭自动销毁
- @MockBean：每个 Test Class 结束后 Spring 容器重建，自动清理

---

## 12. 附录：覆盖度汇总矩阵

### 测试用例总数

- **第一轮**: 约 37 个用例
- **第二轮**: 6 个用例
- **合计**: 43 个用例

### 覆盖度矩阵

| 模块 | 覆盖层级 | 新建用例数 | 当前已有 |
|------|---------|-----------|---------|
| 数据补缺（fallback 链） | Service | 9 | 0 |
| 候选股票加载 | Service | 2 | 0 |
| 模式匹配（7 天筛选） | Service | 5 | 2 |
| 完整扫描编排 | Service | 4 | 2 |
| 缓存 | Service | 2 | 1 |
| Controller API | Controller | 3 | 1 |
| Thymeleaf 页面 | Controller | 2 | 0 |
| 截图导入 | Service/Controller | 2 | 1 |
| 截图网格 | Service | 2 | 1 |
| 工具类 | Util | 3 | 3 |
| 配置类 | Config | 2 | 0 |
| 异常处理 | Controller/Handler | 2 | 0 |
| 定时调度 | Scheduler | 1 | 0 |
| 鉴权安全 | Security | 1 | 0 |
| 真实数据源连接 | Integration | 6 | 0 |
| 合计 | | 44 | 11 |

---

> **文档版本**: v1.0
> **更新日期**: 2025-05-18
> **作者**: 墨 (Mo)
