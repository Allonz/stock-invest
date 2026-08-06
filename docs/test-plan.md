# stock-invest 测试方案（test-plan）

> 配套文档：`docs/optimization-plan.md`。本文档针对优化方案中每一项修复给出测试设计，覆盖单元测试、集成测试、回归测试与补缺/定时任务专项测试。
>
> 约束：本文档仅描述测试方案，**不包含任何实际执行动作**；测试在 `fix/code-review-optimization` 分支上随修复同步实施。

---

## 0. 总则

### 0.1 测试分层与工具

| 层级 | 工具/框架 | 运行环境 | 说明 |
|---|---|---|---|
| 单元测试 | JUnit 5 + Mockito（`spring-boot-starter-test`） | 纯内存，无 Spring 容器（或 `@WebMvcTest`/`@MockBean` 切片） | 覆盖纯逻辑：日期计算、判定函数、解析器、熔断状态机、参数校验 |
| 服务层测试 | `@ExtendWith(MockitoExtension.class)` 手搭 mock | 内存 | 覆盖事务拆分、互斥、异常分类、进度更新 |
| 集成测试（H2） | `application-test.yml`（H2 内存） | `mvn test -Ptest` 默认 profile | 覆盖 Repository/Service 全链路；**注意 H2 不支持 MySQL 专有语法**（P2-2 改造后此约束消除） |
| 集成测试（MySQL） | `application-integration.yml` + 本地 MySQL 3307 或 Testcontainers | `mvn verify -Pintegration` | 覆盖 Flyway 迁移、唯一约束、`ON DUPLICATE` 语义、真实 SQL 行为 |
| 外部依赖 mock | MockWebServer（okhttp）/ WireMock + 固定 fixture JSON | 内存 | 数据源客户端（twelvedata/tiingo/alpaca/tiger）、ResilientHttpExecutor |
| Python 脚本测试 | `tests/`（现有 pytest 用例，如 `test_tigeropen_channel.py`） | 本地 `.venv` | 覆盖盘后合并时区、错误 JSON 协议、配额错误识别 |

### 0.2 现有测试资产（改造基础，避免重复建）

- 服务层：`DataGapFillerServiceTest`、`DataGapFillerServiceImplTest`、`DataGapFillerPersistTest`、`DataGapFillerAfterHoursTest`、`DataSourceDateRangeTest`、`ScreeningServiceTest`、`StockDailyBarServiceTest`、`StockDataSourcePriorityServiceTest`、`SymbolBlacklistServiceTest`
- 调度/集成：`DataGapFillerIntegrationTest`（`src/test/java/.../scheduler/`）
- Python 子进程：`PythonScriptExecutorTest`、`PythonDirectProcessTest`
- HTTP：`ResilientHttpExecutorBackoffTest`、`AlpacaRestClientTest`、`TwelveDataRestClientCandleTest`、`TiingoRestClientCandleTest`、`TwelveDataRestClientAuthTest`
- 日历：`TradingCalendarFallbackTest`、`TradingCalendarControllerTest`、`AlpacaCalendarServiceTest`、`TigerCalendarServiceTest`、`TigerOpenCalendarServiceTest`
- Web：`BarsControllerCandlesTest`、`BarsControllerIntegrationTest`、`DataSourceStatusApiControllerTest`
- 工具/实体：`WatchlistVolumeParserTest`、`KLineDataUtilsTest`、`StockDailyBarFieldTest`、`DataFillTaskConstraintTest`
- 支撑：`TestDataFactory`、`BaseMockTest`

### 0.3 通过标准（总则）

1. 所有单元/服务层测试全绿，且**每个新增用例先失败（红）后通过（绿）**——证明用例有效；
2. 集成测试在 H2 与 MySQL 双环境通过（MySQL 环境至少覆盖 P2-1/P2-2/P2-5 相关 SQL）；
3. 修复项对应用例覆盖：正常路径 + 边界 + 异常路径，无 `@Disabled`/`@Ignore` 遗留；
4. 全量回归 `mvn test` 无 flaky（连续 3 次执行稳定）；
5. 补缺/定时专项测试（第 4 节）全部通过。

---

## 1. 单元测试（按修复项映射）

### 1.1 P1-1 PythonScriptExecutor 超时与管道死锁

**修改**：`PythonScriptExecutorTest`（新增用例）。

| 用例 | 场景 | 断言要点 |
|---|---|---|
| `timeout_kills_hung_process` | 脚本 sleep 60s 不输出 | `executeScript` 在 ~30s 内抛 `IOException`（消息含"超时"）；进程已被销毁（`destroyForcibly` 后 `waitFor` 立即返回） |
| `stderr_flood_no_deadlock` | 脚本向 stderr 写 > 64KB | 不阻塞、正常返回 stdout 结果（读流截断生效） |
| `stdout_flood_limited` | 脚本 stdout 输出 > 上限 | 返回内容被截断且不 OOM（上限常量可配置） |
| `exit_code_nonzero_throws` | 脚本 `exit(1)` | 抛 `IOException` 含退出码 |
| `success_returns_trimmed_stdout` | 正常脚本 | 返回 stdout 去空白内容（回归现有行为） |

**关键点**：真实启动 Python 进程（`PythonDirectProcessTest` 已有模式），脚本用 `src/main/resources/python/test_script.py` 或测试临时脚本；超时用例需把超时配置注入为短值（如 2s）以缩短测试时间。

### 1.2 P1-2 事务边界与运行互斥

**修改**：`DataGapFillerServiceImplTest`、`DataGapFillerServiceTest`、`ScreeningServiceTest`（新增用例）。

| 用例 | 场景 | 断言要点 |
|---|---|---|
| `fillGaps_partial_failure_keeps_committed_rows` | 第 3 个 symbol 的持久化抛异常 | 前 2 个 symbol 的数据已落库（mock repository 的 save 已调用且无回滚）；批次继续或终止但**不整体回滚** |
| `fillGaps_running_guard_blocks_second_call` | 线程 A 进入 `fillGaps`（用 latch 卡在中间），线程 B 调用 | B 立即返回（互斥标记置位），不进入循环；A 结束后可再次调用 |
| `processRetryingTasks_running_guard` | 同上场景换 `processRetryingTasks` | 同断言 |
| `retry_task_persist_own_transaction` | `createRetryTask` 在批次失败后仍落库 | `dataFillTaskRepository.save` 被调用且状态为 `retrying` |
| `screening_running_guard` | 两个线程同时 `runScreening` | 仅一个进入评估循环 |

**关键点**：互斥用 `CountDownLatch` 控制时序；断言"第二个调用返回"可用 `AtomicReference` 记录返回时间与首次进入时间差。

### 1.3 P1-3 not-found 三态判定

**修改**：`DataGapFillerServiceImplTest`（新增 `isNotFoundError` 直测，方法为 private 时通过 package-private 化或反射，或改为通过 `fillWithFallback` 行为断言）。

| 用例 | 场景 | 断言要点 |
|---|---|---|
| `confirmed_not_found_counts` | 错误消息含 `not found` / `invalid symbol` | 计入 not-found 计数，累计 ≥2 后入黑名单 |
| `transient_failure_never_counts` | 抛 `StockDataException(TRANSIENT)`（超时/连接/5xx） | not-found 计数不变，不入黑名单；生成 retry 任务 |
| `account_level_aborts_chain` | 抛 `StockDataException(ACCOUNT_LEVEL)` | 该 symbol 不再尝试后续源；触发熔断冷却 |
| `empty_success_does_not_count` | 请求成功但 items 空（EMPTY 标记） | 不计入 not-found 计数 |
| `three_sources_transient_no_blacklist` | 5 源全部瞬态失败 | 不入黑名单（对比旧行为：入黑名单） |
| `two_confirmed_one_transient` | 2 源确认不存在 + 1 源瞬态 | 入黑名单（确认计数≥2） |

### 1.4 P1-4 findMissingTradeDates 顺序修复

**修改**：`DataSourceDateRangeTest`、`DataGapFillerServiceImplTest`（新增用例，可直测 `findMissingTradeDates`）。

| 用例 | 场景 | 断言要点 |
|---|---|---|
| `desc_input_consistent` | 传入 DESC 序 bars | 与 ASC 序输入返回**完全一致**的 missing 日期集合 |
| `internal_gap_detected` | bars 存在中间空洞（如 07-20、07-22 有，07-21 缺且为交易日） | 返回 07-21（修复前不返回） |
| `tail_gap_detected` | 最新 bar 之后缺 1 个交易日 | 返回该尾部日期（回归现有行为） |
| `weekend_and_holiday_skipped` | 空洞落在周末/节假日（mock `TradingCalendarDbService`） | 不返回非交易日 |
| `lookback_boundary` | 空洞早于 `today - MAX_LOOKBACK_DAYS` | 不返回（范围截断仍生效） |

### 1.5 P1-5 Tiger 配额熔断

**修改**：`DataGapFillerServiceImplTest`、新增 `CircuitBreakerTest`（若实现独立组件）。

| 用例 | 场景 | 断言要点 |
|---|---|---|
| `permission_denied_recognized` | 错误消息 `4000:permission denied`（Tiger/TigerOpen 客户端模拟） | 抛 `ACCOUNT_LEVEL` 分类 |
| `circuit_opens_after_threshold` | 连续 N 次账户级错误 | 熔断器状态 OPEN，后续调用直接短路（不再发请求） |
| `half_open_probe_succeeds` | 冷却期后第一次探测成功 | 状态回 CLOSED，流量恢复 |
| `cooldown_expires` | 冷却期内调用被拒；过期后可重试 | 时间推进（mock clock）后行为恢复 |
| `source_skipped_for_batch` | 熔断生效后批次内后续 symbol | fallback 链不再包含 Tiger/TigerOpen 源 |

### 1.6 P1-6 调度线程池配置

**修改**：无新增用例（纯配置），由 `ScreeningScheduler`/`DataFillScheduler` 的冒烟 + 回归覆盖；若实现配置属性绑定，加 `@SpringBootTest` 断言 `TaskSchedulingAutoConfiguration` 池大小 = 4。

### 1.7 P1-7 管理接口参数生效

**修改**：`ScreeningServiceTest`（`runScreening` 重载）、`BarsControllerIntegrationTest` 或新增 `AdminControllerTest`（`@WebMvcTest` + mock service）。

| 用例 | 场景 | 断言要点 |
|---|---|---|
| `windowDays_limits_windows` | `runScreening(date, 3, null)` | 只评估 3 天窗口（`patternEvaluateService` 仅收到 3d 切片）；不评估 2/4/5/6/7d |
| `limit_caps_symbols` | `runScreening(date, null, 5)` | 最多处理 5 个 symbol（mock repository 数据 ≥ 10 个 symbol） |
| `null_params_default_all_windows` | `runScreening(date, null, null)` | 行为等于旧 `runScreening(date)` |
| `controller_passes_params` | `POST /api/admin/trigger-screening?limit=3&windowDays=5` | mock service 收到 `(date, 5, 3)` |
| `invalid_window_rejected_or_clamped` | `windowDays=1` 或 `=99` | 回退全窗口或返回 400（按实现约定断言） |

### 1.8 P1-8 scanExecutor 拒绝策略

**修改**：`AdminControllerTest`（`@WebMvcTest`）。

| 用例 | 场景 | 断言要点 |
|---|---|---|
| `rejected_task_returns_503` | mock `scanExecutor.execute` 抛 `TaskRejectedException` | HTTP 503 + 明确提示 |
| `busy_returns_busy_message` | 互斥标记为 running | 409 或消息含"已在运行" |

### 1.9 P1-9 HTTP 网络层重试

**修改**：`ResilientHttpExecutorBackoffTest`（新增）。

| 用例 | 场景 | 断言要点 |
|---|---|---|
| `connect_refused_retries` | MockWebServer 端口未监听（`ConnectException`） | 重试 maxRetries 次后抛 `ResourceAccessException`；调用次数 == maxRetries |
| `socket_timeout_retries` | server 吞请求不响应（读超时） | 重试生效，最终成功/抛错符合预期 |
| `dns_failure_retries` | `UnknownHostException` | 同断言 |
| `network_error_backoff_sequence` | 记录两次重试间隔 | 间隔符合指数退避 + jitter 范围 |
| `max_retries_respected` | 一直失败 | 总调用次数 == 1 + maxRetries |

**AlpacaRestClient**：`AlpacaRestClientTest` 新增 `read_timeout_set`（反射断言 `HttpRequest.timeout()` 非空）与 `timeout_triggers_fallback`（不响应 server → `IOException` 由调用方处理）。

---

## 2. 集成测试

### 2.1 MySQL 环境（application-integration.yml，本地 3307 或 Testcontainers）

覆盖 P2-1/P2-2/P2-3/P2-5/P2-6 的 SQL 变更：

| 用例 | 场景 | 断言要点 |
|---|---|---|
| `flyway_migrates_fresh_db` | 空库启动（或 `flyway clean` 后 migrate） | V1 全部表创建成功：`data_fill_task`（单数、含 `retry_date`/`day_count`）、`screening_match.algorithm`、唯一约束齐全；无报错 |
| `flyway_migrates_existing_db` | 模拟存量库（先建旧结构再 migrate） | 增量迁移执行成功，列/约束对齐；`flyway_schema_history` 记录正确 |
| `trading_calendar_upsert_idempotent` | 同 (market, date) 两次 upsert | 第二次为更新而非插入；行数不变（回归 P2-2） |
| `priority_unique_constraint` | 同 (symbol, source) 并发 updatePriority | 无唯一冲突、最终一条记录且 last_success_time 为较新者（回归 P2-3） |
| `screening_match_unique` | 同 (trade_date, symbol, window_days, algorithm) 重复插入 | 仅一条（回归 P2-5；依赖实现采用唯一约束 + 冲突忽略） |
| `decimal_columns` | 插入小数价格再读取（P2-6 落地后） | 精度保持（如 12.3456 → 12.3456）无浮点漂移 |

### 2.2 数据源客户端（MockWebServer / WireMock + fixture）

| 用例 | 场景 | 断言要点 |
|---|---|---|
| `twelvedata_non_numeric_value` | 响应中 `open` 为 `"abc"` | 不抛 `NumberFormatException`，该字段置 0 或按约定处理（回归 P2-18） |
| `twelvedata_api_error_status` | 响应 `status: "error"` | 返回空/null 且**分类为瞬态**（联动 P1-3） |
| `tiingo_non_404_error` | HTTP 500 | 抛瞬态异常而非返回空（联动 P1-3） |
| `list_symbols_error_vs_empty` | 接口错误 vs 合法空列表 | 可区分（回归 P2-18） |
| `tiger_quota_error_classified` | Python 脚本输出 `{"error": {"code": "4000", ...}}`（P2-15 后协议统一） | Java 侧抛 `ACCOUNT_LEVEL`（联动 P1-5） |

### 2.3 Tiger API / Python 脚本

- 走本地 pytest：`tests/test_tigeropen_channel.py` 新增（需 mock `TigerOpenClient` 或打桩）：

| 用例 | 断言要点 |
|---|---|
| `after_hours_ny_date_key`（P2-16） | 美东 20:30 的盘后 bar（UTC 时间 = 次日 00:30）合并到**美东当日**日 K 行，不落入次日 |
| `failure_emits_error_json`（P2-15） | 模拟 `client.get_bars` 抛异常 → stdout 输出 `{"error": {...}}`，exit code 1 |
| `permission_denied_code_carried`（P1-5） | 错误 JSON 的 `code` 字段为 `4000`，Java 解析出 `ACCOUNT_LEVEL` |
| `volume_nan_handled` | 回归：NaN 成交量置 0（现有行为） |

### 2.4 PythonScriptExecutor 集成（真实子进程）

见 1.1（`PythonScriptExecutorTest` 已覆盖真实进程启动；`DataGapFillerIntegrationTest` 可增加"Python 脚本挂起时补缺任务整体可中断"端到端用例——用超时注入短值）。

---

## 3. 回归测试清单

以下场景在 M1/M2/M3 每个里程碑结束时全量执行：

1. **补缺全链路**：`POST /api/admin/trigger-data-fill` → 进度接口轮询 → 数据落库 `stock_daily_bar` → retry 任务生成/完成（H2 + MySQL）
2. **筛选全链路**：`POST /api/admin/trigger-screening` + async → `GET /api/notification/latest`、`/history`、`/batch/{id}` 数据一致
3. **K 线接口**：`GET /api/bars/single/query`、`/pages/query`（分页/排序/筛选）、`/{symbol}/candles`（days 边界）
4. **交易日历**：`GET /api/v1/trading-calendar/is-open`、`/list`、`POST /fetch-full-year`（频控生效）
5. **黑名单**：`GET /api/blacklist/list`、`POST /api/blacklist/clear` 语义不变
6. **数据源优先级**：补缺成功后 `GET /api/admin/stock-data-source-priority` 反映更新，无重复记录
7. **MCP 工具**：`tiger_import_watchlist`、`screening_run`、`trading_calendar_is_open` 走 Service 互斥后行为一致（触发不并发）
8. **Watchlist 导入**：`POST /api/ingest/tiger-watchlist` 成交量解析（纯数字/万/亿/全角逗号/科学计数法）不回归
9. **通知**：`GET /api/notification/latest`（P3-3 死分支删除后返回不变）
10. **异常映射**：非法日期/非法 sortBy/超分页/方法不支持分别返回 400/400/400/405（P2-8、P2-9 落地后）
11. **前端冒烟**（可选，若改动 API 契约）：`npm run dev` + 浏览器走查 K 线页、筛选结果页、数据源状态页

---

## 4. 补缺 / 定时任务相关修复的专项测试

### 4.1 并发互斥专项

- **目标**：P1-2 互斥生效，任意触发路径（定时 19:00 / 手动 REST / MCP）同一时刻最多一份补缺（筛选同理）。
- **用例设计**（`DataGapFillerConcurrencyTest`，新增）：
  1. **双线程并发**：`CountDownLatch` 卡住首个 `fillGaps`（在第一个 symbol 处理处设 barrier），第二个线程调用 → 断言立即返回（`running` CAS 拒绝）；释放 latch → 首个完成后再次调用成功；
  2. **定时 + 手动**：`DataFillScheduler.runDataFill()` 与 `AdminController.triggerDataFill` 并发（服务层直接调用）→ 仅一份执行；
  3. **筛选并发**：`ScreeningScheduler` 与 `trigger-screening-async` 并发 → 仅一份执行；
  4. **异常释放**：`fillGaps` 中途抛未捕获异常 → `finally` 释放互斥 → 下次调用可进入（不永久锁死）；
  5. **执行时长断言**：互斥拒绝路径的返回时间 < 1s（证明未排队等锁）。
- **通过标准**：上述 5 项全绿；并发下数据源 mock 调用次数 == 单次执行次数（无翻倍）。

### 4.2 事务回滚专项

- **目标**：P1-2 事务边界收缩后，单点失败不回滚整批、不白耗配额。
- **用例设计**（`DataGapFillerPersistTest` 扩展）：
  1. **部分失败保留**：5 个 symbol，第 3 个失败（模拟 `persist` 抛异常）→ 断言前 2 个的 `save` 已提交（无 `@Transactional` 包裹时 mock repository 调用即视为提交，集成层面用 H2 真实事务断言数据存在）；
  2. **批次可继续/可终止**：按实现约定断言——失败 symbol 生成 retry 任务，第 4/5 个 symbol 继续处理（推荐）或批次终止且已提交部分保留；
  3. **API 配额不浪费**：失败 symbol 不重复尝试已失败的源（P1-5 熔断 + P1-2 联动断言）；
  4. **H2 集成**：`@Transactional` 移除后，`fillGaps` 全流程在 H2 上执行，中途抛异常 → `stock_daily_bar` 中已插入行仍存在（真实回滚行为验证）。
- **通过标准**：4 项全绿；集成用例证明无整体回滚。

### 4.3 not-found 误判专项

- **目标**：P1-3 三态判定后，瞬态故障不产生黑名单、真实股票不再被误伤。
- **用例设计**（`DataGapFillerServiceImplTest` / `SymbolBlacklistServiceTest` 扩展）：
  1. **全源瞬态 → 不黑名单**：5 源全部抛 `TRANSIENT_FAILURE` → 断言 `symbolBlacklistService.recordNotFound` 未被调用；retry 任务生成；
  2. **确认 not-found ≥2 → 黑名单**：2 源 `CONFIRMED_NOT_FOUND` → 入黑名单（保留现有正确行为）；
  3. **混合场景**：2 确认 + 3 瞬态 → 入黑名单；1 确认 + 4 瞬态 → 不入；
  4. **存量黑名单恢复**（若实现自动衰减）：黑名单记录超期后再次补缺可重试；
  5. **空结果语义**：请求成功但空 items（EMPTY）→ 不计入计数（按实现约定）。
- **通过标准**：5 项全绿；用 `verify(blacklistService, never()).recordNotFound(...)` 强断言瞬态路径。

### 4.4 findMissingTradeDates 顺序专项

见 1.4（`DataSourceDateRangeTest`），补充**端到端**用例：`fillGapsForSymbol` 用含中间空洞的 bars（DESC 序 mock 数据）→ 空洞日期出现在 `missingDates` 中并成功补缺。

### 4.5 Python 超时专项

见 1.1 + `DataGapFillerIntegrationTest` 扩展：Python 脚本挂起（注入 `test_script.py` sleep 长于超时）→ `fillGaps` 该 symbol 标记失败（瞬态）、不永久阻塞调度线程、`scanExecutor` 线程可回收（断言后续任务可执行）。

---

## 5. 测试执行步骤与通过标准

### 5.1 执行步骤

1. **环境准备**：本地 MySQL 3307（与 `application-integration.yml` 对齐）或启用 Testcontainers；Python `.venv` 就绪（`tests/` 依赖）；
2. **单元 + 服务层**：
   ```bash
   mvn test -Ptest            # 默认 H2 profile，全部单测 + 服务层测试
   ```
3. **集成（MySQL）**：
   ```bash
   mvn verify -Pintegration   # application-integration.yml，含 Flyway 迁移 + SQL 语义用例
   ```
4. **Python 脚本测试**（本地 pytest，`.venv` 内）：
   ```bash
   cd tests && ../.venv/bin/python -m pytest test_tigeropen_channel.py test_data_sources_integration.py -v
   ```
5. **专项并发/事务/not-found**：随 `mvn test` 执行（`DataGapFillerConcurrencyTest` 等位于 test 目录）；并发用例连续跑 3 次确认无 flaky：
   ```bash
   mvn test -Dtest=DataGapFillerConcurrencyTest -Dsurefire.rerunFailingTestsCount=0
   ```
6. **冒烟**（每里程碑结束）：启动应用 → 手动触发补缺/筛选 → 观察进度、日志（无 ANSI 转义残留、无 stdout 全量刷屏）、数据落库。

### 5.2 通过标准

1. `mvn test -Ptest` 全绿（含新增用例，无 `@Disabled`）；
2. `mvn verify -Pintegration` 全绿（MySQL 环境）；
3. pytest 全绿（Python 侧变更）；
4. 专项测试（4.1–4.5）全绿且连续 3 次稳定；
5. 回归清单（第 3 节）逐项人工确认通过；
6. 修复项自测证明：每个用例在修复前"红"、修复后"绿"（抽查 3 个关键用例即可，如 `timeout_kills_hung_process`、`fillGaps_running_guard_blocks_second_call`、`transient_failure_never_counts`）。

---

## 6. 修复项 ↔ 测试覆盖映射速查

| 修复项 | 单测 | 集成 | 专项/回归 |
|---|---|---|---|
| P1-1 | `PythonScriptExecutorTest` 新增 5 用例 | `DataGapFillerIntegrationTest` 扩展 | 4.5 |
| P1-2 | `DataGapFillerServiceImplTest`、`ScreeningServiceTest` 新增 | `DataGapFillerPersistTest`（H2） | 4.1、4.2 |
| P1-3 | `DataGapFillerServiceImplTest` 新增 6 用例 | — | 4.3 |
| P1-4 | `DataSourceDateRangeTest`、`DataGapFillerServiceImplTest` | — | 4.4 |
| P1-5 | `CircuitBreakerTest`（新增）、`DataGapFillerServiceImplTest` | 2.2（tiger quota） | 4.2 |
| P1-6 | 配置断言（可选） | — | 回归 3 |
| P1-7 | `ScreeningServiceTest`、`AdminControllerTest`（新增） | — | 回归 2 |
| P1-8 | `AdminControllerTest` | — | 回归 2 |
| P1-9 | `ResilientHttpExecutorBackoffTest`、`AlpacaRestClientTest` | 2.2 | — |
| P2-1 | — | 2.1（flyway ×2） | 回归 1–6 |
| P2-2 | — | 2.1（upsert 幂等） | 回归 4 |
| P2-3 | `StockDataSourcePriorityServiceTest` | 2.1（唯一约束） | 回归 6 |
| P2-4 | `DataFillTaskConstraintTest` 扩展 | — | 回归 1 |
| P2-5 | — | 2.1（唯一约束） | 回归 2 |
| P2-6 | `StockDailyBarFieldTest`、计算点测试 | 2.1（decimal） | 回归 1、3 |
| P2-7 | Repository 查询断言 | 2.1 | 回归 3 |
| P2-8 | `GlobalExceptionHandler` 新增测试（`@WebMvcTest`） | — | 回归 10 |
| P2-9 | `BarsControllerCandlesTest`、`BarsControllerIntegrationTest` | — | 回归 3、10 |
| P2-10 | `TradingCalendarControllerTest` | — | 回归 4 |
| P2-11 | `TradingCalendarFallbackTest` | — | 回归 4、1 |
| P2-12 | 进度服务直测（新增） | — | 回归 2 |
| P2-13 | — | 2.1 | 回归 4 |
| P2-14 | `AlpacaCalendarServiceTest` 等 3 类扩展 | — | 回归 4 |
| P2-15 | pytest（`test_tigeropen_channel.py`） | 2.3 | — |
| P2-16 | pytest（`test_tigeropen_channel.py`） | 2.3 | — |
| P2-17 | `PythonScriptExecutorTest` 日志断言 | — | — |
| P2-18 | `TwelveDataRestClientCandleTest` 扩展 | 2.2 | — |
| P2-19 | `WatchlistVolumeParserTest` 新增 | — | 回归 8 |
| P3-1~P3-9 | 涉及行为变更的（P3-5 DTO、P3-7、P3-8）对应测试类更新 | — | 回归 2、3 |
