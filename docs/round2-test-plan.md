# stock-invest 第二轮优化测试方案（round2-test-plan）

- **依据**：`docs/code-review-report.md` + `docs/round2-optimization-plan.md`（25 条修复：P1×3、P2×9、P3×13）
- **本方案性质**：仅文档。不创建/切换分支，不修改源码/测试/配置，不运行构建与测试，不改动依赖。
- **文档日期**：2026-08-07
- **代码核对**：本方案引用的测试文件、行号、断言均已于 2026-08-07 对照分支 `fix/code-review-optimization`（HEAD `ad38325`）磁盘内容核实。

---

## 1. 概述

### 1.1 测试分层与环境

| 层 | 运行环境 | 说明 |
|----|----------|------|
| 单元测试 | H2（`@ActiveProfiles("test")`，`ddl-auto: create-drop`，Flyway 关闭） | Mockito mock Repository/Service；真实进程类测试（Python）例外 |
| 集成测试 | MySQL 8.0.46（本机 127.0.0.1:3307，**专用 schema**） | Flyway 迁移、SchemaVerifier、真实 JPA 行为；**禁用 H2 跑迁移**（迁移脚本含 MySQL 专有动态 SQL） |
| 打包校验 | 本地 `mvn package` | 产物边界（P2-3） |

### 1.2 现有测试资产（复用与改造基准）

- `AdminControllerTest`（`@WebMvcTest`，mock `scanExecutor`）：P1-7 参数透传、P1-8 409/拒绝（:120-128 拒绝断言 5xx）——P2-2 需改造。
- `ScreeningServiceTest`：窗口/limit 语义（`nullParams_defaultAllWindows`、`windowDays_limitsWindows`、`limit_capsSymbols` 等）——P1-3 需增补 controller 层默认语义用例。
- `DataGapFillerServiceTest`（T-1 dayCount 上限 / T-2 重置 / T-5 计数递增）、`DataGapFillerServiceImplTest`（重试流程）、`DataGapFillerConcurrencyTest`（互斥 5 用例）——P2-1 在其基础上扩展。
- `PythonScriptExecutorTest`（真实进程；:121-147 超时用例真实等 30s）、`ResilientHttpExecutorBackoffTest`（:127-144/:161-177 真实退避等待）——P2-6 改造。
- `TradingCalendarControllerTest`：**无** `fetchFullYear`/冷却相关用例（已核实）——P2-8 全新增。
- `WatchlistVolumeParserTest`——P3-11 增溢出用例。
- `DataFillTaskConstraintTest`——P3-6 断言名称需同步。
- `BaseMockTest`、`TestDataFactory`：测试基建复用。

### 1.3 新增测试文件清单（计划）

| 新文件 | 服务条目 |
|--------|----------|
| `SchemaVerifierTest` | P1-5 / P2-4 / P3-7 / P3-12 |
| `DataFillProgressServiceTest` | P2-7 |
| `FlywayMigrationIT`（集成，MySQL） | P1-4 / P1-5 / P2-4 / P3-7 / P3-12 |
| `ScreeningServiceImplDedupeTest`（可并入 ScreeningServiceTest） | P1-5 |
| `PythonScriptExecutorConcurrencyTest`（可并入现有类） | P2-5 |

---

## 2. 单元测试方案

### 2.1 P1-3 `trigger-screening` 默认语义恢复

**修改** `AdminControllerTest`（`@WebMvcTest`，mock `ScreeningService`）：
- 改 `triggerScreening_passesParams`：显式传 `windowDays=3&limit=5` → `verify(screeningService).runScreening(eq(date), eq(3), eq(5))`。
- 新增 `triggerScreening_noParams_passesNulls`：无参调用 → `verify(screeningService).runScreening(any(LocalDate.class), isNull(), isNull())`——**核心断言**：默认不再绑定 20/7。
- 新增 `triggerScreening_explicitWindowDaysOnly`：只传 `windowDays=4` → `runScreening(date, 4, null)`（limit 不绑定默认值）。

**修改** `ScreeningServiceTest`（无需大改，现有 `nullParams_defaultAllWindows` 已覆盖 Service 层；补充）：
- 新增 `windowDays7_defaultNoLongerApplied`（回归护栏）：`runScreening(tradeDate, 7, null)` 只评估 7 天窗口（证明"显式 7"与"默认全窗口"可区分）。
- 保留 `windowDays_limitsWindows` / `limit_capsSymbols` / `noLimit_evaluatesAllSymbols` 作为语义不回归证据。

**断言要点**：无参触发评估窗口集合恰为 `{2,3,4,5,6,7}`；候选 symbol 数不设上限；显式参数仍精确生效。

### 2.2 P1-4 Flyway V2 版本号复用 → 集成层（见 §3.1）；单元层无

### 2.3 P1-5 唯一约束守卫失效 → SchemaVerifier 单测 + 应用层查重回归

**新增 `SchemaVerifierTest`**（mock `JdbcTemplate` 返回值，验证分支逻辑）：
- 约束存在 → 通过分支（无 error 日志）。
- 约束缺失 + 重复组=0 → error 日志含"手工 ALTER"指引。
- 约束缺失 + 重复组>0 → error 日志含重复组数与清理脚本路径。
- `data_fill_task` 缺列（P2-4）→ error 日志列出缺失列名。
- 断言方式：捕获 `log.error`（Logback `ListAppender` 或注入日志断言器）匹配关键字；不依赖真实 DB。

**扩展 `ScreeningServiceTest`**（应用层查重纵深防御）：
- 新增 `duplicateSameDayRowsFiltered`：`findByTradeDate` 返回已存在键，`saveAll` 入参中重复键行被滤除（现有 181-196 逻辑的显式用例）。

### 2.4 P2-1 乐观锁冲突重放

**扩展 `DataGapFillerServiceImplTest`**（mock Repository + 真实 TransactionTemplate 或 mock 事务管理器）：
- 新增 `optimisticLockConflict_replaysOnceAndPersists`：
  - `dataFillTaskRepository.save` 首次抛 `ObjectOptimisticLockingFailureException`，`findById` 返回最新版本（如 dayCount=2），再次 save 成功；
  - 断言：`save` 恰好被调 2 次；重放后落库对象 `dayCount = 2 + delta`（增量合并，不覆盖并发递增）、`retryCount` 同理、`status/lastError` 为本次意图值。
- 新增 `optimisticLockConflict_secondConflictLogsError`：两次均冲突 → error 日志 + 冲突计数 +1，无第三次重试（无死循环）。
- 回归：T-1（dayCount>=5 跳过）、T-5（失败递增）保持全绿（重放逻辑不改变正常路径）。

### 2.5 P2-2 拒绝语义 503 + 进度清理

**修改 `AdminControllerTest`**：
- 改 `triggerScreeningAsync_rejectedTaskSurfacesError`（:120-128）：断言 `status().isServiceUnavailable()`（503）+ body `success=false` + `code=QUEUE_FULL`（原 5xx 断言替换）。
- 新增 4 端点拒绝路径参数化用例（`triggerScreeningAsync`/`runScreeningAsync`/`triggerDataFill`/`triggerRetryTasks`）：mock `scanExecutor.execute` 抛 `TaskRejectedException` → 均 503 + QUEUE_FULL。
- 新增 `rejectedTask_cleansProgressEntries`：`triggerScreeningAsync` 拒绝后 `verify(screeningProgressService).removeProgress(anyString())`；`triggerDataFill` 拒绝后 `verify(dataFillProgressService).removeProgress(anyString())`——防止幽灵进度条目。
- 回归：`triggerDataFill_successSubmitsToExecutor`、409 并发拒绝用例保持。

### 2.6 P2-3 测试配置移动 → 打包校验（§5）；单元层无

### 2.7 P2-4 全列对齐 → SchemaVerifierTest（§2.3）+ 集成层（§3.1）

### 2.8 P2-5 排空线程池扩容

**扩展 `PythonScriptExecutorTest`**（真实进程）：
- 新增 `concurrentExecutions_allOutputsComplete`：并发提交 4 个 `test_script.py`（不同参数），`CompletableFuture` 汇总，断言 4 个结果均为合法 JSON、无空串（防数据丢失）。
- 新增 `concurrentWithHungScript_timeoutKillsAndOthersComplete`：1 个 `hang_test.py`（短超时注入，见 P2-6）+ 3 个正常脚本并发 → 挂起者抛超时 IOException，其余输出完整。
- 回归：`timeout_kills_hung_process`（改造为短超时版）、洪泛/截断用例保持。

### 2.9 P2-6 慢测试注入化

**改造 `PythonScriptExecutorTest`**：
- `timeout_kills_hung_process`（:121-147）改构造注入 `new PythonScriptExecutor(2)`：断言 `elapsed >= 1500ms`（原 `>= 25_000` 删除）、进程被 `destroyForcibly`（PID 消亡断言保留）、消息含"超时"。
- 新增 `defaultTimeout_still30s`：`new PythonScriptExecutor()` 反射读取实例超时字段 == 30（生产默认值护栏）。

**改造 `ResilientHttpExecutorBackoffTest`**：
- 注入 `HttpClientProperties.backoffBaseMs=10`：`networkError_retriesThenThrows`/`networkError_backoffSequence` 断言 `verify(rt, times(3)).exchange(...)`（调用次数）+ `elapsed >= 基数等比和`（毫秒级，如 ≥ 20ms），删除 `elapsedMs >= 1450` 上界 4000 的等待断言。
- 新增 `backoffBaseDefault_500`：默认属性值为 500（生产护栏）。
- 保留调用次数、Retry-After 解析、成功恢复等纯逻辑用例不动。

### 2.10 P2-7 TTL 惰性清理

**新增 `DataFillProgressServiceTest`**：
- `expiredEntry_removedOnRead`：插入 startTime 超 TTL 的条目 → `getProgress()` 后 `progressMap` 无该条目。
- `unexpiredEntry_keptOnRead`：未过期条目读后保留。
- `latestKeyExpired_returnsNull`：`latestKey` 指向过期条目 → 无参 `getProgress()` 返回 null、不抛错。
- `scheduledSweep_removesExpired`：直接调用定时方法（或触发 `@Scheduled` 方法）→ 过期条目清除。
- `startFill_sweepsToo`：`startFill()` 前置清理行为保持（回归）。

### 2.11 P2-8 冷却后置

**扩展 `TradingCalendarControllerTest`**（mock `TradingCalendarDbService`，`@WebMvcTest`）：
- 新增 `fetchFullYear_success_setsCooldown`：mock `fetchAndStoreFullYear` 返回 N → 首次 200；同 (market, year) 二次请求 429 且 `remainingSec` 正确。
- 新增 `fetchFullYear_failure_noCooldown`：mock 抛异常 → 首次 500；**紧接着**第二次请求不再 429（可重试），返回 200。
- 新增 `fetchFullYear_concurrentDualRequest_onlyOneExecutes`（若采用 `putIfAbsent` 方案）：并发双请求 → 一份执行、另一份 429。
- 回归：year 范围校验 400 用例保持。

### 2.12 P2-9 死文件清理 → 文件校验（§5）；单元层无

### 2.13 P3 各条测试

| 条目 | 测试动作 |
|------|----------|
| P3-1 current volatile | 代码评审为主；可选并发 smoke：线程 A `startRetry` 后线程 B 轮询 `getProgress()` 非空（≤1s） |
| P3-2 isNotFoundError 删参 | 编译级验证：删参后无编译错误；调用点（:449）更新；现有 not-found 分类测试回归（`CircuitBreakerTest`/`DataGapFillerServiceTest`） |
| P3-3 SymbolNotFoundException 删除 | 编译级验证：删除后全仓无引用（grep）；负测试可选：确认无任何代码抛该异常 |
| P3-4 changePercent 精度统一 | 扩展 `BigDecimalSerializationTest`：`TigerStockServiceImpl.getStockInfo` 返回的 changePercent `scale() <= 4`；新增 `changePercentScaleAssertion`（各数据源 ServiceImpl 计算点 scale 断言） |
| P3-5 实体 @ToString | `DataFillTaskConstraintTest` 等实体测试回归；新增断言（可选）：实体 `toString()` 含 symbol/tradeDate 字段 |
| P3-6 约束名对齐 | **修改** `DataFillTaskConstraintTest` 两用例：断言名改为 `uk_data_fill_task_symbol_missing_date`（列仍 symbol+tradeDate） |
| P3-7 索引补建 | 集成层（§3.1）断言 `SHOW INDEX`；单元层无 |
| P3-8 前缀 LIKE | 保持现有 findByFilters 用例（前缀匹配断言）；新增（可选）`findByFilters_symbolPrefixOnly`：传 `SYM` 断言 JPQL 结果仅前缀匹配——标注为行为护栏 |
| P3-9 fillGaps 返回 boolean | 修改 `DataGapFillerServiceImplTest`/`DataGapFillerConcurrencyTest`：`fillGaps()` 断言返回 true（正常执行）与 false（互斥拒绝）；`AdminControllerTest` 增 `triggerDataFill_skippedSetsStage`（进度 stage=SKIPPED 断言）；`DataFillScheduler` 调用点编译回归 |
| P3-10 阈值语义 | 文档决策为主；可选：`DataGapFillerServiceTest` 增 `closeAboveThreshold_skipped` 行为护栏用例（固化现状） |
| P3-11 溢出保护 | 扩展 `WatchlistVolumeParserTest`：`hugeValue_overflowThrows`（`99999999999999999999`、`100000000亿` → 断言 `IllegalArgumentException`）；`nearMaxValue_ok`（`9223372036854775807` 正常返回） |
| P3-12 status 索引 | 集成层（§3.1）断言 `SHOW INDEX` 含 `idx_dft_status` |
| P3-13 目录清理 | 构建前 glob 校验：`excluded-test-files/` 不存在；`.cursorrules` 已移出或 gitignore（按方案二选一） |

---

## 3. 集成测试方案（MySQL / Flyway / 数据源）

### 3.1 Flyway 迁移集成测试 `FlywayMigrationIT`

**环境约束（关键）**：
- 迁移脚本含 MySQL 专有语法（`information_schema` 探测 + `PREPARE/EXECUTE` 动态 SQL），**必须跑 MySQL**，禁止 H2。
- 使用**专用 schema**（如 `stock_invest_round2_it`），测试自建自删；**绝不触碰** `stock_invest` 主库（`application-integration.yml` 现指向主库，需以独立配置覆盖）。
- 驱动保持 `mysql-connector-j 8.0.33`；MySQL 8.0.46（本机 127.0.0.1:3307 或 Testcontainers，二选一；优先 Testcontainers 以隔离主库）。

**用例矩阵**：

| 用例 | 前置 schema 状态 | 断言 |
|------|------------------|------|
| fresh 全版本迁移 | 空库 | V1→V2(旧)→V3→V4→V5 全 success；`flyway_schema_history` 版本唯一（无复用）；6 表 + 各唯一约束就位 |
| legacy 缺列库 | 手工按"仓库外历史 DDL"建 `data_fill_task`（缺 retry_date/day_count/last_error/version）与 `screening_match`（缺 algorithm） | migrate 后列齐全；`data_fill_task.version` 已补；应用启动校验通过 |
| legacy 含重复 + 无约束 | `screening_match` 含重复组（2 组）+ 无唯一约束 | 守卫不 ADD CONSTRAINT；SchemaVerifier 报 error（含组数） |
| 清理后补约束（模拟 runbook） | 上一步基础上删重复 + 手工 `ALTER TABLE` | SchemaVerifier 通过；重复插入被 DB 拒绝（`DataIntegrityViolationException`） |
| 幂等 | 全版本已迁移库 | 二次 `migrate` 为 no-op；`validate` 通过 |
| 旧 V2 已应用库（P1-4 回归） | 手工写入 `flyway_schema_history` 记录旧 `V2__create_symbol_blacklist.sql` 的 checksum/描述 | `validate` 通过；V4 正常应用 |
| 索引补建（P3-7/P3-12） | 迁移后 | `SHOW INDEX` 含 `idx_dft_status`、`idx_screening_match_window_days`、`idx_screening_match_batch_id_window_days` |
| 价格列（回归） | 迁移后 | `stock_daily_bar` 价格列 `decimal(12,4)`（V3 回归护栏） |

**实现建议**：JUnit `@Tag("integration")` + Flyway Java API（`Flyway.configure().dataSource(...).target("5")`）在 `@BeforeAll/@AfterAll` 中建/删 schema；或 `spring-boot-starter-test` + Testcontainers `MySQLContainer("mysql:8.0.46")`；断言经 JDBC `information_schema` 查询（与 SchemaVerifier 同源口径）。

### 3.2 数据源相关集成

- **Python 数据源**（`PythonScriptExecutor`）：沿用现有真实进程模式（`src/test/resources/python/` 的 hang/trim/flood 脚本），新增并发矩阵（§2.8）。
- **HTTP 客户端**（TwelveData/Tiingo/Alpaca/Tiger）：维持现有 mock `RestTemplate`/SDK 模式（`TwelveDataRestClientAuthTest` 等），本轮无新增外部调用。
- **Tiger SDK 2.2.6 冻结**：无新集成面，`TigerStockServiceImplTest`/`TigerAfterHoursTest` 回归即可。

---

## 4. 回归测试清单（重点场景）

| # | 场景 | 覆盖用例 | 对应修复 |
|---|------|----------|----------|
| R1 | `trigger-screening` 无参 = 全窗口全量 | AdminControllerTest 新增 + ScreeningServiceTest `nullParams_defaultAllWindows` | P1-3 |
| R2 | `trigger-screening` 显式参数精确生效 | `windowDays_limitsWindows`/`limit_capsSymbols`/新增 controller 用例 | P1-3 |
| R3 | Flyway 全新库启动（全版本 success） | `FlywayMigrationIT.fresh` | P1-4 |
| R4 | Flyway 存量/旧 V2 库 validate 通过 | `FlywayMigrationIT.legacyOldV2` | P1-4 |
| R5 | 唯一约束幂等（有/无重复两态） | SchemaVerifierTest + `FlywayMigrationIT.legacyDup` | P1-5 |
| R6 | 乐观锁冲突重放不丢计数 | `DataGapFillerServiceImplTest` 新增 2 用例 | P2-1 |
| R7 | 队列满 → 503 + QUEUE_FULL + 进度清理 | AdminControllerTest 新增 3 类用例 | P2-2 |
| R8 | 测试配置不在生产 jar | 打包校验（§5） | P2-3 |
| R9 | legacy 库列齐全可启动 | `FlywayMigrationIT.legacyMissingCols` | P2-4 |
| R10 | Python 并发排空无数据丢失 | `PythonScriptExecutorTest` 并发 2 用例 | P2-5 |
| R11 | 超时/退避用例快速化（<5s / <500ms） | 改造后的两个测试类 | P2-6 |
| R12 | TTL 惰性清理 + 定时兜底 | `DataFillProgressServiceTest` 5 用例 | P2-7 |
| R13 | 冷却：成功冷却 / 失败不冷却 / 窗口内 429 | TradingCalendarControllerTest 新增 3 用例 | P2-8 |
| R14 | 死文件清理无引用 | glob + 构建 | P2-9 |
| R15 | 409 互斥拒绝（补缺/重试/筛选）不回归 | `DataGapFillerConcurrencyTest` 5 用例 + AdminControllerTest 409 用例 | 全 |
| R16 | 价格精度 API=4 位、序列化不回归 | `BigDecimalSerializationTest` 扩展 | P3-4 |
| R17 | 黑名单/熔断（not-found 分类）不回归 | `CircuitBreakerTest` + `SymbolBlacklistServiceTest` | P3-2/P3-3 周边 |
| R18 | 成交量解析：科学计数/全角逗号/溢出 | `WatchlistVolumeParserTest` 全量 | P3-11 |
| R19 | `fill-tasks` 前缀搜索语义 | findByFilters 用例 + 前端确认记录 | P3-8 |
| R20 | fillGaps 返回值变更后调用方全绿 | 修改后的 3 个测试类 + Scheduler 编译回归 | P3-9 |

---

## 5. 专项测试

### 5.1 并发专项
- Python 排空：4 并发脚本完整性 + 挂起混跑（§2.8）。
- 乐观锁重放：首次冲突 → 重放成功；双冲突 → error + 计数（§2.4）。
- 互斥回归：`DataGapFillerConcurrencyTest`（fillGaps/processRetryingTasks/筛选三路互斥、异常释放锁）保持全绿；P3-9 返回值改造后断言同步更新。
- 冷却并发：双请求仅一份执行（§2.11，依赖 `putIfAbsent` 方案落地）。

### 5.2 迁移幂等专项
- 全版本迁移 ×2（no-op + validate 通过）；各守卫式 DDL 在"已具备/缺失"两态下结果一致（`FlywayMigrationIT`）。

### 5.3 TTL 清理专项
- `DataFillProgressServiceTest` 全量（过期清除/未过期保留/latestKey 语义/定时触发/startFill 前置清理）。

### 5.4 冷却逻辑专项
- 成功写冷却、失败不写冷却、窗口内 429 + remainingSec、并发双请求互斥（§2.11）。

---

## 6. 测试执行步骤与通过标准

### 6.1 执行步骤（待确认后执行）
1. 单元/集成双轨：
   - `mvn test`（默认 profile，H2 单测，含真实进程 Python 用例——注入化后超时用例 <5s）；
   - `mvn test -Dgroups=integration`（MySQL 专用 schema，`FlywayMigrationIT`）。
2. 迁移验证：`mvn flyway:validate`（CI 新增步骤）在干净与 legacy 两态下通过。
3. 打包校验：`mvn package && unzip -l target/stock-invest-*.jar | grep application-test`（应无输出）。
4. 文件校验：构建前 glob 确认 `excluded-test-files/` 移除、`v002_*.sql`/根目录 `fix_blacklist_status.sql` 已归档或删除。
5. 全量回归：`mvn verify`（含 integration 标签，若 CI 允许）一次通过。

### 6.2 通过标准
- **覆盖率**：25 条修复中，23 条有直接测试断言（P2-3/P2-9 为产物/文件级校验，非代码断言）；P1-3/P1-5/P2-1/P2-2/P2-5/P2-7/P2-8 必须**新增**用例（现有用例不足或语义已变）。
- **时间**：单测套件（不含 integration 标签）< 3 分钟；Python 超时用例 < 5s；退避用例 < 500ms。
- **不回归**：R1~R20 全绿；重点回归 `trigger-screening` 默认语义（R1/R2）、Flyway 启动（R3/R4）、唯一约束幂等（R5）、乐观锁冲突（R6）、队列拒绝（R7）。
- **产物**：生产 jar 无测试配置（R8）；无死文件（R14）。
- **依赖**：pom 中 tiger `2.2.6`、mysql-connector-j `8.0.33` 版本不变（校验 `mvn dependency:list` 或 grep pom）。

### 6.3 测试执行前的前置确认
- P1-3：与前端确认无参 `triggerScreening()` 使用方（`frontend/src/api/admin.ts:6-8`）对"全窗口全量"的接受度，避免回归测试与产品预期冲突。
- P3-10：产品对 `close <= minPriceThreshold` 阈值语义的决策（保留则固化护栏用例，放开则改行为+改用例）。
- 集成环境：Testcontainers（Docker）可用性或专用 schema 命名/权限，避免触碰 `stock_invest` 主库。

---

*本方案仅文档；一切测试执行待确认后进行。*
