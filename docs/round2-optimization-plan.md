# stock-invest 第二轮优化开发方案（round2-optimization-plan）

- **依据**：`docs/code-review-report.md`（评审对象：分支 `fix/code-review-optimization`，HEAD `ad38325`）
- **本方案性质**：仅文档。所有代码/迁移/测试改动**待确认后另行执行**，本方案不修改任何源码、测试、配置与依赖。
- **文档日期**：2026-08-07
- **代码核对**：全部"问题定位"的行号已于 2026-08-07 对照分支 `fix/code-review-optimization`（HEAD `ad38325`）磁盘内容逐一核实；与评审报告行号有出入处已按实际修正并标注。

---

## 1. 范围

### 1.1 纳入本次修复（25 条）

| 级别 | 条目 |
|------|------|
| P1（3 条） | P1-3 `trigger-screening` 默认参数行为收窄；P1-4 Flyway V2 版本号复用；P1-5 `screening_match` 唯一约束守卫式添加可能永久不生效 |
| P2（9 条） | P2-1 乐观锁冲突静默吞掉；P2-2 AbortPolicy 拒绝以 500 呈现；P2-3 测试配置进生产 jar；P2-4 V2 全列对齐无校验；P2-5 Python 排空线程池容量不足；P2-6 慢测试拖 CI；P2-7 进度 TTL 清理缺口；P2-8 `fetch-full-year` 失败后仍冷却；P2-9 遗留死文件 |
| P3（13 条） | P3-1 ~ P3-13（可见性 / 死代码 / 命名 / 精度 / 卫生类） |

### 1.2 明确排除（后续单独立项，本方案不给出任何修复建议）

- P0-1 Tiger OpenAPI 私钥入库
- P1-1 管理端 API 与 MCP 工具无鉴权
- P1-2 硬编码密钥与默认口令

### 1.3 依赖冻结约束（硬约束，禁止升级）

- `openapi-java-sdk`（tiger）：`pom.xml:87`，版本 `${tiger-api.version}` = **2.2.6**（`pom.xml:24`）
- `mysql-connector-j`：`pom.xml:68-69`，版本 **8.0.33**
- 其余依赖（spring-boot 3.5.16 / java 17）保持不变；本方案不引入任何新依赖。

### 1.4 分支策略（写入文档，本轮不执行）

- 修复基于**当前分支 `fix/code-review-optimization`（HEAD `ad38325`）继续开发**，不直接改动 `main`。
- 若单条改动涉及迁移版本重排（P1-4），可临时创建子分支 `fix/round2-flyway-version` 隔离验证，验证通过后合回当前分支。
- 所有开发动作（建分支、改代码、跑构建/测试、推送）待后续确认后执行。

---

## 2. 修复总览与工作量

| 条目 | 优先级 | 工作量估计 | 主要风险 |
|------|--------|-----------|----------|
| P1-3 trigger-screening 默认语义 | P1 | 0.5 人日（含前端确认） | 同步端点耗时变长（恢复全窗口全量） |
| P1-4 Flyway V2 版本号复用 | P1 | 0.5 人日 | 本地/生产库 flyway_schema_history 需 repair |
| P1-5 唯一约束守卫式失效 | P1 | 1.0 人日 | 生产数据清理仍需人工步骤，不可全自动 |
| P2-1 乐观锁冲突静默吞掉 | P2 | 1.0 人日 | 重放语义需谨慎（计数增量 vs 状态覆盖） |
| P2-2 AbortPolicy 拒绝 500 | P2 | 0.5 人日 | 拒绝路径需清理已创建的进度条目 |
| P2-3 测试配置进生产 jar | P2 | 0.25 人日 | 需确认无代码引用 main resources 下的该文件 |
| P2-4 V2 全列对齐无校验 | P2 | 0.5 人日 | 与 P1-4 迁移版本重排耦合，须同批执行 |
| P2-5 Python 排空线程池容量 | P2 | 0.5 人日 | 并发脚本测试需真实进程，注意测试耗时 |
| P2-6 慢测试拖 CI | P2 | 0.5 人日 | 超时/退避注入化需保持生产默认值不变 |
| P2-7 进度 TTL 清理缺口 | P2 | 0.5 人日 | 惰性清理与定时清理需避免并发迭代问题 |
| P2-8 fetch-full-year 失败仍冷却 | P2 | 0.25 人日 | 无（冷却后置为纯逻辑移动） |
| P2-9 遗留死文件 | P2 | 0.25 人日 | 确认无引用后删除/归档 |
| P3-1 ~ P3-13 | P3 | 合计约 2.0 人日 | 多为小改；P3-10 需产品确认阈值语义 |
| 集成/回归验证 | — | 1.5 人日 | Flyway 集成测试须跑 MySQL（禁 H2） |
| **合计** | | **约 9.75 人日** | |

执行顺序建议：**P1-4 + P2-4 先行**（迁移版本重排是所有迁移类改动的基座）→ P1-3 → P1-5 → P2-1/P2-2 → 其余 P2 → P3。

---

## 3. P1 修复方案

### P1-3 `trigger-screening` 默认参数生效导致行为收窄

**问题定位（已核对）**
- `src/main/java/com/stock/invest/controller/AdminController.java:68-77`：`triggerScreening` 声明 `@RequestParam(value = "limit", defaultValue = "20") Integer limit`、`@RequestParam(value = "windowDays", defaultValue = "7") Integer windowDays`（:71-72），无参调用即固定为 `limit=20, windowDays=7`，透传 `screeningService.runScreening(targetDate, windowDays, limit)`（:76）。
- `src/main/java/com/stock/invest/service/impl/ScreeningServiceImpl.java:95-98`：`(windowDays == null || windowDays < WindowConstants.MIN_WINDOW_DAYS) ? WindowConstants.ALL_WINDOW_DAYS : List.of(windowDays)`；`WindowConstants.java:18` `MIN_WINDOW_DAYS=2`、`:24` `ALL_WINDOW_DAYS=[2,3,4,5,6,7]`。
- limit 生效点：`ScreeningServiceImpl.java:149-153`（`processed >= limit` 时 break）。
- 对照语义：`trigger-screening-async`（`AdminController.java:83+`）调 1 参 `runScreening(tradeDate)` → `null,null` → 全窗口全量。
- 前端事实：`frontend/src/api/admin.ts:6-8` 的 `triggerScreening()` **无参调用** `/api/admin/trigger-screening` —— 默认值直接作用于前端"一键筛选"路径。

**根因**
P1-7 让死参数生效时，controller 默认值（20/7）被当作"默认语义"保留，与 main 上"参数被忽略 → 全窗口全量"的历史行为不一致；且与 `trigger-screening-async` 语义分裂。

**修复方案（推荐：默认改 null，恢复全窗口全量）**
1. `AdminController.java:71-72` 移除 `defaultValue`，改为 `required = false`：
   - `@RequestParam(value = "limit", required = false) Integer limit`
   - `@RequestParam(value = "windowDays", required = false) Integer windowDays`
2. Service 层无需改动：`null` 已天然映射为全窗口（:95-98）、limit 不限（:149-153 条件 `limit != null && limit > 0`）。
3. 同步更新方法 javadoc 与日志：标注"无参 = 全窗口 2~7 天、全量 symbol，与 trigger-screening-async 一致"。
4. 与前端确认：`admin.ts:triggerScreening()` 无参路径语义恢复全量后，同步触发可能耗时数分钟（原 main 即如此），前端需有等待/超时预期；若产品希望一键路径仍是"7 天窗口"，则应在前端显式传参而非依赖后端默认。
5. 不改动 `run-screening`（`AdminController.java:413-437`，默认 windowDays=2）与 `run-screening-async`（:133+，默认 7/60）——它们是有参高级路径，前端已显式传参（`ScreenerView.vue:295,419-421`）。

**验收要点**
- 无参调用 → 评估窗口集合 = {2,3,4,5,6,7}、候选 symbol 不设上限；
- 显式 `windowDays=3` → 仅 3 天窗口；显式 `limit=5` → 至多 5 个 symbol；
- `trigger-screening` 与 `trigger-screening-async` 无参语义一致。

---

### P1-4 Flyway V2 版本号复用 / 旧迁移文件删除

**问题定位（已核对）**
- git 历史确认：`6242cfd`（M2 P2-1 Flyway 版本化迁移提交）删除了 `src/main/resources/db/migration/V2__create_symbol_blacklist.sql`（内容为 `symbol_blacklist` 建表，已被 V1 吸收——`V1__baseline.sql:95-106` 注释"吸收原 V2__create_symbol_blacklist.sql"），并新建 `V2__align_existing.sql`（59 行）——**两个不同内容、不同描述的文件共用版本号 2**。
- `application.yml:40-44`：flyway `enabled: true`、`baseline-on-migrate: true`、`baseline-version: 0`。
- 本机库 `flyway_schema_history` 仅 3 条（V1/V2/V3，均 success）——旧 V2 从未被本机应用；但任何手工跑过旧 V2、或与其它环境合并的库升级后 `validate` 会因 checksum/description 不匹配启动失败。

**根因**
迁移版本号应单调递增、内容不可变；重构时删旧文件复用了版本号。

**修复方案（推荐：恢复旧 V2 文件 + 本次变更整体后移）**
1. 从历史恢复旧 V2 文件原样：`git show 6242cfd^:src/main/resources/db/migration/V2__create_symbol_blacklist.sql` 写回 `V2__create_symbol_blacklist.sql`（其内容为 `CREATE TABLE IF NOT EXISTS symbol_blacklist`，幂等；全新库在 V1 之后执行无害）。
2. 将现有 `V2__align_existing.sql` **重命名为 `V4__align_existing.sql`**（V3 已被 `decimal_price_columns` 占用，不得复用）。
3. 本机/已应用"新 V2"的库修复（发布 runbook 必含）：
   - 对 `flyway_schema_history` 已记录"新 V2 checksum"的库：执行 `mvn flyway:repair`（仅重记 checksum/描述，不动数据），随后 `mvn flyway:validate` + `mvn flyway:migrate` 应用 V4。
   - 若无法 repair：手工删除 `flyway_schema_history` 中 version=2 的行后重跑 migrate（V2 恢复为旧脚本，对已存在表为 no-op；对齐内容由 V4 承担）。
4. CI 增加 `mvn flyway:validate` 步骤（或 `mvn verify` 前置校验），防止版本号复用再犯。
5. 纪律写入文档：新迁移一律使用新版本号；迁移文件内容一旦执行不可修改。

**验收要点**
- 全新库：V1 → V2(旧) → V3 → V4 全 success；
- 模拟旧 V2 已应用库：`validate` 通过；
- `flyway_schema_history` 中版本号唯一、无复用。

---

### P1-5 `screening_match` 唯一约束守卫式添加可能永久不生效

**问题定位（已核对）**
- `V2__align_existing.sql:37-59`：约束守卫块；`ADD CONSTRAINT uk_screening_match_trade_symbol_window_algorithm` 在 :54；守卫条件 `@constraint_exists = 0 AND @screening_dup = 0`（:46-51 查重复组，:42-45 查约束）；文件头注释（:5-8）明确生产库 2026-08-06 存在 **2566 组重复**。
- Flyway 迁移只执行一次：生产库因重复行跳过 ADD CONSTRAINT 后，即使后续人工清理重复，**约束也永远不会被自动添加**。
- 应用层唯一防线：`ScreeningServiceImpl.java:181-196` 按 `findByTradeDate(targetDate)` 收集 `symbol|windowDays|algorithm` 键做同日查重（只防当日、依赖互斥）。

**根因**
把"数据清理"这一人工步骤编进了迁移执行条件，而迁移没有重跑机制 → 条件性失效永久化。

**修复方案（迁移不可变 + 启动期校验 + 运维 runbook）**
1. **新增启动期只读 SchemaVerifier**（`@Component implements ApplicationRunner`，经 JdbcTemplate 查 `information_schema`）：
   - `screening_match` 唯一约束缺失 → `log.error`（结构化，含约束名）并输出指引：先执行清理脚本，再手工 `ALTER TABLE`；
   - 约束缺失但重复组 > 0 → `log.error`（含重复组数与清理脚本路径）；
   - 约束缺失且重复组 = 0 → `log.error`（提示可直接手工添加约束）；
   - 约束存在 → `log.info` 通过。
   - 同时承载 P2-4 的 `data_fill_task` 列校验与 P3-7/P3-12 的索引校验（合并为一个组件，见 4.4/5.7/5.12）。
2. **提供幂等清理脚本** `scripts/cleanup_screening_match_duplicates.sql`：按 `(trade_date, symbol, window_days, algorithm)` 分组，每组保留 `MIN(id)`，其余删除；脚本带事务与影响行数输出，供运维人工执行。
3. **发布 runbook**（写入发布说明）：清理重复 → 手工执行 `ALTER TABLE screening_match ADD CONSTRAINT uk_screening_match_trade_symbol_window_algorithm UNIQUE (trade_date, symbol, window_days, algorithm)` → 重启应用确认 SchemaVerifier 通过。
4. 应用层查重（`ScreeningServiceImpl:181-196`）**保留**，作为纵深防御；守卫式迁移内容保持不可变。
5. 可选：对约束缺失场景增加监控告警（日志级别 error 即视为告警源，后续接告警通道）。

**验收要点**
- 无约束 + 无重复：SchemaVerifier 启动报 ERROR 并提示手工步骤；
- 无约束 + 有重复：ERROR 含重复组数与脚本路径；
- 手工添加约束后：SchemaVerifier 通过，应用正常启动；
- 清理脚本在"已清理/未清理"两种库上均幂等可执行。

---

## 4. P2 修复方案

### P2-1 乐观锁冲突被静默吞掉，dayCount 上限可能失效

**问题定位（已核对）**
- `DataGapFillerServiceImpl.java:871-878`：`saveTaskWithOptimisticLock` catch `ObjectOptimisticLockingFailureException` 仅 `log.warn`，更新被丢弃。
- 影响链：`processRetryingTasksInternal`（:614+）中 `dayCount` 读-改-写（失败分支 :713 附近 `task.setDayCount(task.getDayCount() + 1)`）若冲突 → dayCount 不增长 → "当日重试上限 `dayCount >= 5`"（:649-653）可能永不触发。
- 独立写路径：`createRetryTask`（:573-598，`fillGapsForSymbol:259` 调用）与重试批次的更新是两条路径；`DataFillTaskRepository.java:50-57` 的 JPQL 批量 `updateStatusBySymbolAndStatusIn`（:156-160/:481-484 调用）不校验版本。

**根因**
用"跳过冲突"换取简单，牺牲重试语义的确定性；互斥（`DataGapFillerServiceImpl:87` `running` AtomicBoolean）把概率压低，但并非消除。

**修复方案**
1. `saveTaskWithOptimisticLock` 冲突后**重读 + 重放一次**（幂等场景安全）：
   - `dataFillTaskRepository.findById(task.getId())` 重读最新版本；
   - **计数类字段**（`retryCount`/`dayCount`）按增量合并：`latest.setRetryCount(latest.getRetryCount() + delta)`（delta = 本次变更相对调用方读取值的差），避免覆盖并发递增；
   - **终态字段**（`status`/`lastError`/`retryDate`）以本次意图为准直接覆盖（终态转换语义明确）；
   - 重放再冲突 → `log.error` + 原子计数器 `conflictCounter.incrementAndGet()`（暴露 getter 或日志周期输出），可观测而非静默。
2. 若评审认为增量合并语义复杂，备选：将计数递增改为 JPQL 原子自增（`UPDATE DataFillTask t SET t.retryCount = t.retryCount + 1, t.dayCount = t.dayCount + 1, t.status = :s, t.lastError = :e WHERE t.id = :id`），返回值 = 0 时重读重放一次；终态转换仍走实体保存。**二选一，推荐方案 1**（改动面最小、语义直观）。
3. 保留互斥现状；为未来放开并发预留：在 `processRetryingTasksInternal` 的计数递增处注释说明版本校验依赖。

**验收要点**
- mock `save` 首次抛 `ObjectOptimisticLockingFailureException`、二次成功 → `retryCount`/`dayCount` 最终落库且数值正确（含并发增量不丢失）；
- 冲突计数可见（日志/统计）；无死循环（最多一次重放）。

---

### P2-2 AbortPolicy 拒绝以 500 呈现

**问题定位（已核对）**
- `AsyncConfig.java:15-27`：`scanExecutor` core 8 / max 16 / queue 50，`AbortPolicy` 在 :24。
- `AdminController` 4 处 `scanExecutor.execute`：:91（triggerScreeningAsync）、:143（runScreeningAsync）、:214（triggerDataFill）、:242（triggerRetryTasks）——均未捕获 `TaskRejectedException`。
- `GlobalExceptionHandler.java:116-119`：catch-all `Exception` → 500。`AdminControllerTest.java:120-128` 的 `triggerScreeningAsync_rejectedTaskSurfacesError` 断言 5xx，即当前行为是"预期"但语义错误。

**根因**
拒绝语义未映射到 HTTP；队列满应表达"服务忙"而非"内部错误"。

**修复方案**
1. `AdminController` 增加私有 helper：`private ResponseEntity<ApiResponse<?>> submitOrBusy(Runnable task, String taskId)`——`scanExecutor.execute` 包 try/catch，`TaskRejectedException` → `ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.error("任务队列已满，请稍后重试", "QUEUE_FULL"))`。
2. 4 个端点统一走该 helper；**拒绝路径需清理已创建的进度条目**：`triggerScreeningAsync`/`runScreeningAsync` 已 `startScreening`、`triggerDataFill` 已 `startFill`，拒绝时调用 `screeningProgressService.removeProgress(taskId)` / `dataFillProgressService.removeProgress(taskId)`，避免 progressMap 残留幽灵条目。
3. `GlobalExceptionHandler` 增加 `@ExceptionHandler(TaskRejectedException.class)` → 503（兜底，防遗漏）。
4. 同步端点（`triggerScreening`/`run-screening`）本就走同步执行，不受影响。

**验收要点**
- 队列满时 4 个异步端点均返回 503 + `QUEUE_FULL`；
- 拒绝后对应 taskId 查询进度返回空（条目已清理）；
- `AdminControllerTest` 拒绝用例断言从 5xx 改为 503 + 错误码。

---

### P2-3 测试配置进入生产 jar

**问题定位（已核对）**
- `src/main/resources/application-test.yml`（21 行）含：H2 内存库、`ddl-auto: create-drop`、`show-sql: true`、h2-console 开启、`flyway.enabled: false`、`hikari.connection-init-sql: ""`。
- 随 `src/main` 打进生产包；生产误用 `--spring.profiles.active=test` 会以 H2 + create-drop 启动并暴露 h2-console。

**根因**
为补测试环境的两项覆盖（`connection-init-sql` 置空、Flyway 禁用）把测试配置移进了 main resources，未考虑产物边界。

**修复方案**
1. `git mv src/main/resources/application-test.yml src/test/resources/application-test.yml`，内容原样保留（含两项覆盖）。
2. 顺带关闭 `h2.console.enabled`（测试不需要 console），如保守可保留。
3. 全局 grep 确认无代码以 `classpath:application-test.yml` 方式引用（现有 `@ActiveProfiles("test")` 从测试 classpath 加载，不受影响）。
4. 验证：`mvn package` 后 `unzip -l target/*.jar` 确认 jar 内**无** `application-test.yml`。

**验收要点**
- 测试 profile 下现有测试全绿（H2 + create-drop 行为不变）；
- 生产 jar 产物不含测试配置。

---

### P2-4 V2 只对齐两列，`data_fill_task` 全列对齐无校验

**问题定位（已核对）**
- `V2__align_existing.sql` 仅两处列补齐：`screening_match.algorithm`（:13-23）、`data_fill_task.version`（:26-35）。
- `data_fill_task` 的 `retry_date`/`day_count`/`last_error` 等列（V1 定义见 `V1__baseline.sql:13-28`）在仓库外历史 DDL 缺失时 V2 不会补齐，应用启动后查询/写入即失败；本机库列齐全，属"仓库外历史 DDL"版本差异未被迁移层保证。

**根因**
V2 对齐清单不完整，且无启动期校验兜底。

**修复方案（与 P1-4 同批执行）**
1. 在**新迁移**（P1-4 重排后的 `V4__align_existing.sql`，或追加 V5）中，参照 V1 定义，为 `data_fill_task` 其余列增加守卫式 `ADD COLUMN`：
   - `retry_date DATE NULL`、`day_count INT NULL DEFAULT 0`、`last_error VARCHAR(512) NULL`；
   - 逐列核对 V1 全部列，凡历史 DDL 可能缺失的一并补齐（`status`/`retry_count` 带默认值，也按需守卫补齐）。
   - 沿用 V2 的 `information_schema` 探测 + `PREPARE/EXECUTE` 动态 SQL 模式（MySQL 不支持 `ADD COLUMN IF NOT EXISTS`）。
2. **SchemaVerifier**（P1-5 引入）扩展：启动期只读校验 `data_fill_task` 必需列齐全，缺失即 `log.error` 并给出明确修复指引（而非运行期 500）。
3. `V2__align_existing.sql` 内容保持不可变（已执行），新列补齐只进新版本号迁移。

**验收要点**
- legacy 库（缺列）应用新迁移后列齐全，应用可正常启动；
- 列齐全库上迁移为 no-op；
- SchemaVerifier 在缺列库上明确报错。

---

### P2-5 PythonScriptExecutor 排空线程池容量不足

**问题定位（已核对）**
- `PythonScriptExecutor.java:52-60`：`DRAIN_POOL = newFixedThreadPool(4)`（注释"2 进程 × 2 路输出"）；:33 `DEFAULT_TIMEOUT_SECONDS = 30`；:40 `DRAIN_GRACE_SECONDS = 5`；:118 `process.waitFor(30s)`；:165-176 `awaitDrain` 超时仅 `log.warn`。
- 风险链：并发 ≥3 个脚本时排空任务排队（6 个任务 > 4 线程）；调用线程 30s 超时强杀后，排队中的 drain 任务可能尚未开始，`awaitDrain` 5s 宽限内拿不到结果 → 返回空输出（数据丢失）。
- 当前调度路径互斥，但 MCP / REST / 定时器跨路径仍可能并发（且 P1-1 设计初衷即支持多进程并行）。

**根因**
池容量按"单执行实例"估算，未覆盖并发执行实例数；失败路径日志级别不足。

**修复方案**
1. `DRAIN_POOL` 容量改为 `2 * MAX_CONCURRENT_SCRIPTS`，`MAX_CONCURRENT_SCRIPTS = 4`（8 线程）——按"进程数 × 2 路输出"的并发上界配置，注释同步更新。
2. `awaitDrain` 超时日志从 warn 提级为 `error`，并携带 `scriptName` 与超时值（当前 :168-176 仅 warn 无 scriptName 上下文）。
3. 可选加固：调用线程强杀进程后，在 `awaitDrain` 前短轮询 `future.isDone()`（≤1s 间隔），尽早发现"任务尚未开始"并补日志。
4. 测试注入化（与 P2-6 配套）：超时可配后，并发排空用例可在短超时下快速验证。

**验收要点**
- 并发 3~4 个脚本同时执行，输出全部完整（无空输出）；
- 挂起脚本超时杀进程用例仍通过；
- 排空超时日志为 error 且含 scriptName。

---

### P2-6 测试套件存在真实等待型用例，拖慢 CI

**问题定位（已核对）**
- `PythonScriptExecutorTest.java:121-147` `timeout_kills_hung_process`：真实等待 30s 超时（`DEFAULT_TIMEOUT_SECONDS` 为 `private static final` 不可注入），断言 `elapsedMs >= 25_000`（:138）。
- `ResilientHttpExecutorBackoffTest.java:127-144`（`networkError_retriesThenThrows`）与 :161-177（`networkError_backoffSequence`）：真实 `Thread.sleep` 退避（基数 500ms × 2^(n-1) + jitter），断言 `elapsedMs >= 1450`。

**根因**
超时/退避参数不可注入，测试只能以真实时长验证时序。

**修复方案**
1. **PythonScriptExecutor 超时注入化**：`DEFAULT_TIMEOUT_SECONDS` 改为实例字段，新增构造重载 `PythonScriptExecutor(int timeoutSeconds)`（默认 30，`@Component` 无参构造保持生产默认）；同时支持系统属性/配置 `python.script.timeout.seconds`（可选，二选一即可，推荐构造注入以保持简单）。测试用 `new PythonScriptExecutor(2)` 验证超时（断言 `elapsed >= 1500ms`、进程被 kill、消息含"超时"）。
2. **ResilientHttpExecutor 退避基数注入化**：`HttpClientProperties` 增加 `backoffBaseMs`（默认 500）与 `jitterMaxMs`（默认 250）；`ResilientHttpExecutor` 的退避计算改读属性。测试注入 `backoffBaseMs=10`，断言**调用次数**（`verify(rt, times(n))`）+ 下界 `elapsed >= 基数×等比和`（毫秒级），删除真实 1.5~2s 等待。
3. 保留 1 条粗粒度真实退避 sanity 用例（可选，`@Tag("slow")` 默认跳过或纳入 nightly），确认真实时序未被注入化破坏。

**验收要点**
- Python 超时用例耗时 < 5s；退避用例 < 500ms；
- 生产默认值不变（构造缺省 30s / 500ms）；
- 全套件耗时显著下降（目标：单测 < 3min）。

---

### P2-7 DataFillProgressService TTL 清理依赖下一次手动触发

**问题定位（已核对）**
- `DataFillProgressService.java:27-34`：`startFill()` 在 :28 调用 `sweepExpired()`（:57-64 定义），是全仓唯一调用点。
- 定时路径不创建进度条目：`DataFillScheduler.java`（19:00 cron）直接调 `dataGapFillerService.fillGaps()`，且 `DataGapFillerServiceImpl.java:136` 定时路径 `getProgress()` 返回 null 被忽略——**手动触发遗留的已完成条目只增不删**（`progressMap` :22），直到下一次手动触发才清理。

**根因**
清理被绑定在"手动触发"这一非周期入口上；读取路径与定时路径均无清理。

**修复方案**
1. `getProgress()` / `getProgress(String taskId)`（:37-44）开头调用 `sweepExpired()`——读路径惰性清理（`ConcurrentHashMap` 迭代安全，`sweepExpired` 已用 iterator 且仅移除过期项）。
2. 新增周期兜底：`DataFillProgressService` 加 `@Scheduled(fixedDelay = 3_600_000)`（每小时）的 `sweepExpired` 定时任务（或独立小型 `@Component` 定时器调用之），保证"长期仅定时运行"场景也收敛。
3. 保留 `startFill()` 内的一次清理（启动路径即时收敛）。

**验收要点**
- 单测：插入 startTime 超过 TTL 的条目 → 调 `getProgress()` 后条目被清除；未过期条目保留；`latestKey` 指向已过期条目时 `getProgress()` 返回 null 且不抛错；
- 手动触发条目 24h 后自动消失（读路径或定时路径）；
- 定时路径不产生新残留。

---

### P2-8 `fetch-full-year` 失败后仍被冷却 30 分钟

**问题定位（已核对）**
- `TradingCalendarController.java:100-141`：`fetchFullYear`；冷却门控 :120-125（429 + remainingSec）；**冷却时间戳写入在 :126**（`lastFullYearSyncAt.put(cooldownKey, now)`），**外部同步调用在 :130**（`dbService.fetchAndStoreFullYear`）——写入先于执行。

**根因**
冷却时间戳在"开始执行"而非"执行成功"时写入；失败场景被误冷却 30 分钟，运维无法及时重试。

**修复方案**
1. 将 `lastFullYearSyncAt.put(cooldownKey, now)` 移动到 `dbService.fetchAndStoreFullYear` **成功返回之后**（`fetched` 赋值后、构建响应前）。
2. 失败路径（异常抛出）不写冷却 → 可立即重试；429 门控逻辑（:120-125）保持不变。
3. 并发注意：两个并发请求同时通过门控时，`put` 后置使二者都可能进入同步（现状亦然——`put` 前置只保证第二次请求 429，不保证第一次执行期间互斥）；如需严格互斥，可改为 `putIfAbsent` + 执行成功后覆盖时间戳（推荐一并做，语义更严谨）。

**验收要点**
- 成功 → 冷却写入，窗口内二次请求 429 且 remainingSec 正确；
- 失败（dbService 抛异常）→ 冷却未写入，立即重试成功；
- `putIfAbsent` 版本下：并发双请求仅一份执行、另一份 429。

---

### P2-9 遗留死文件与一次性脚本

**问题定位（已核对）**
- `src/main/resources/db/v002_stock_data_source_priority.sql`：小写 `v002` 不符合 Flyway 命名（且不在 `db/migration` 目录，永不执行），定义 `stock_data_source_priority` 表，与 `V1__baseline.sql:79-89` 重复。
- 根目录 `fix_blacklist_status.sql`：单行一次性修复（`UPDATE symbol_blacklist SET status='active' WHERE status='cleared' AND consecutive_404_count>0`）。

**根因**
DDL 事实来源残留与一次性脚本入库，误导后续维护者。

**修复方案**
1. 删除 `src/main/resources/db/v002_stock_data_source_priority.sql`（V1 已含同结构表；确认无代码/文档引用后删除）。
2. `fix_blacklist_status.sql` 移入 `docs/`（归档区，标注"一次性数据修复，已执行，勿再执行"），或确认无待执行环境后删除——**推荐归档**。
3. 全局 grep 确认无引用后执行；V1 头注释无需改动（已写明幂等基线）。

**验收要点**
- 目录/仓库中不再存在可误导的 DDL 文件；
- `mvn package` 正常、测试不受影响。

---

## 5. P3 修复方案（13 条）

### P3-1 `RetryProgressService.current` 非 volatile
- **定位**：`RetryProgressService.java:10` `private RetryProgress current;`（非 volatile；`:12-18` `startRetry` 写、`:21-23` `getProgress` 读）。
- **根因**：跨线程读写无可见性保证，HTTP 线程可能读到 stale null 显示 IDLE；`DataFillProgressService.latestKey`（:24）已是 volatile，此处不一致。
- **方案**：`current` 加 `volatile`（或改 `AtomicReference<RetryProgress>`，二选一，推荐 volatile 最小改动）。
- **验收**：`startRetry`（线程 A）后 `getProgress`（线程 B）在轮询下稳定可见；现有 `getRetryProgress` 端点行为不变。

### P3-2 `isNotFoundError` 未使用参数 `klineData`
- **定位**：`DataGapFillerServiceImpl.java:811-816` 声明 `isNotFoundError(KLineData klineData, String errorMessage)`，函数体只用 `errorMessage`；唯一调用点 :449 恒传 `null`。
- **根因**：路径 A（空结果）移除后签名未清理。
- **方案**：删除 `klineData` 参数，签名改 `isNotFoundError(String errorMessage)`，调用点同步。
- **验收**：编译通过、行为不变（路径 B 白名单判定逻辑不动）。

### P3-3 `SymbolNotFoundException` 死代码 + 分类语义陷阱
- **定位**：`StockDataException.java:144-149`，全仓 0 调用点（已核实）；2 参构造经父类 3 参构造（:36-38）默认分类为 `TRANSIENT_FAILURE`。
- **根因**：死代码；且若未来启用，not-found 语义会被归类为瞬态失败，绕过黑名单判定。
- **方案**：**删除**（YAGNI）；若业务需要，重写为显式 `CONFIRMED_NOT_FOUND` 语义（本轮默认删除）。
- **验收**：删除后编译通过、无引用残留。

### P3-4 `changePercent` 精度不一致
- **定位**：`TigerStockServiceImpl.java:272-275` `change.divide(prevClose, 8, HALF_UP).multiply(100)`（8 位小数，无 setScale）；`DataGapFillerServiceImpl.java:551-554` 盘后涨跌幅 `setScale(4, HALF_UP)`；:518 常规 changePercent 原样透传；DB 列 `DECIMAL(12,4)`（`V1__baseline.sql:57`、`V3__decimal_price_columns.sql:16`）。
- **根因**：两处计算风格不统一；API 返回精度与 DB 存储精度不一致。
- **方案**：全仓 grep 所有 `changePercent` 计算点（Tiger/TwelveData/Tiingo/YFinance 各 ServiceImpl），统一在模型层 `setScale(4, RoundingMode.HALF_UP)`；透传路径（:518）由数据源侧先归一化或落库时由 DECIMAL 隐式四舍五入（保持现状并注释说明）。
- **验收**：API 返回 changePercent 恒为 ≤4 位小数；`BigDecimalSerializationTest` 扩展断言 scale。

### P3-5 实体 `@Data` → `@Getter/@Setter` 丢失 `toString`
- **定位**：`DataFillTask.java:22-24`、`StockDailyBar.java:23-25`、`ScreeningMatch.java:24-26` 均为 `@Getter @Setter @EqualsAndHashCode(onlyExplicitlyIncluded=true)`，无 `@ToString`。
- **根因**：日志打印实体只出哈希，可观测性下降（三个实体均无懒加载关系，`@ToString` 安全）。
- **方案**：三个实体类补 `@ToString`；`equals/hashCode` 维持仅含 id 的现状（JPA 正确实践）。
- **验收**：日志中实体可读；现有实体相关测试（`DataFillTaskConstraintTest` 等）全绿。

### P3-6 实体 `@UniqueConstraint` 名称与 V1 不一致
- **定位**：`DataFillTask.java:29` 声明 `uk_data_fill_task_symbol_trade_date`；`V1__baseline.sql:25` 实际约束 `uk_data_fill_task_symbol_missing_date`（生产实际名）。
- **根因**：ddl-auto 生成路径与 Flyway 路径命名分叉（当前 ddl-auto=none，无运行时影响）。
- **方案**：实体名对齐 V1：`uk_data_fill_task_symbol_missing_date`；`DataFillTaskConstraintTest`（断言现名）同步更新。
- **验收**：实体元数据与 V1 约束名一致；约束测试断言更新后全绿。

### P3-7 V1 相对旧 schema.sql 丢弃索引
- **定位**：`ScreeningMatch.java:24-37` 实体声明 `idx_screening_match_window_days`、`idx_screening_match_batch_id_window_days`（仅 H2 create-drop 生成）；`V1__baseline.sql:47-48` 仅有 `idx_screening_match_trade_date_price`、`idx_screening_match_batch_id`。
- **根因**：V1 对拍生产库时未包含实体侧索引。
- **方案**：在 P1-4 重排后的新迁移中**守卫式补建** `KEY idx_screening_match_window_days (window_days)` 与 `KEY idx_screening_match_batch_id_window_days (batch_id, window_days)`（覆盖 `countByBatchIdGroupByWindowDays` 的 GROUP BY 路径；低风险低成本）；或若确认查询面不需要，在文档中显式说明（二选一，推荐补建）。
- **验收**：迁移后 `SHOW INDEX` 含两索引；全新/存量库均幂等。

### P3-8 `DataFillTaskRepository.findByFilters` symbol 匹配语义变化
- **定位**：`DataFillTaskRepository.java:37-44` `LIKE CONCAT(:symbol, '%')`（前缀）；javadoc（:33-35）已注明 P2-7 语义从"包含"改"前缀"。
- **根因**：前缀匹配可命中索引，属对外行为变更。
- **方案**：本轮**无代码改动**；与前端确认 `fill-tasks` 搜索输入预期（股票代码前缀搜索符合常规），并在 API 文档（`docs/`）标注语义；若前端需要 contains 语义，再评估索引方案。
- **验收**：前端确认结论记录；文档标注完成。

### P3-9 `triggerDataFill` TOCTOU
- **定位**：`AdminController.java:197-229`：`isRunning()` 检查（:202）与 `scanExecutor.execute`（:214）之间存在竞态窗口；调度器可能已启动补缺 → 请求返回 200 + taskId 但服务层 CAS（`DataGapFillerServiceImpl.java:121`）静默跳过。
- **根因**：检查与提交非原子；跳过发生在异步体内部，客户端不可见。
- **方案**：`DataGapFillerService.fillGaps()` 返回 `boolean`（是否实际执行：CAS 成功 true，互斥拒绝 false）；`AdminController` 异步 lambda 内若返回 false → `progress.setStage("SKIPPED")` + `log.warn`（客户端读进度可见"被拒绝"而非静默成功）。同步 409 检查保留（快速失败 UX）。同步更新：接口（`DataGapFillerService.java:23`）、实现（:119）、`DataFillScheduler`（忽略返回值或记日志）、相关 mock 测试。
- **验收**：竞态窗口下客户端能通过进度看到 SKIPPED；正常路径返回 boolean=true；全部调用方编译通过。

### P3-10 `fillGapsForSymbol` 仅对 `close <= minPriceThreshold` 的股票补缺
- **定位**：`DataGapFillerServiceImpl.java:228-231`：`close > minPriceThreshold`（默认 1.00，`application.yml` `gap-fill.min-price-threshold`）→ `return FillResult.empty()`。
- **根因**：main 遗留逻辑（疑似"低价股优先补缺"的阈值语义），Double→BigDecimal 迁移时原样保留。
- **方案**：本轮**默认保留现状**，仅补配置注释与文档说明语义；与产品确认后如需全量补缺，删除该条件（会显著增加外部数据源配额消耗，需先评估）——**待产品决策，不擅自改行为**。
- **验收**：文档记录决策；现有阈值行为测试（若有）保持。

### P3-11 `WatchlistVolumeParser` 大数值 `longValue()` 静默溢出
- **定位**：`WatchlistVolumeParser.java:16-17` 正则接受任意位数数字；:40/:44/:47 三条返回路径均 `BigDecimal...longValue()`——超过 `Long.MAX_VALUE` 时静默截断失真。
- **根因**：无溢出保护。
- **方案**：解析后与 `Long.MAX_VALUE` 比较，超出即抛 `IllegalArgumentException("成交量超出 long 范围: ...")`（与现有"无法解析成交量"异常语义一致）；或 clamp 到 `Long.MAX_VALUE` + warn（**推荐抛异常**，数据失真比报错更危险）。
- **验收**：`WatchlistVolumeParserTest` 增溢出用例（如 `99999999999999999999`、`100亿亿`）断言抛异常。

### P3-12 `DataFillTaskRepository.findRetryableTasks` 无 status 索引
- **定位**：`DataFillTaskRepository.java:47-48` `WHERE status='retrying' ORDER BY createdAt ASC`；`V1__baseline.sql:13-28` 中 `data_fill_task` 仅 PRIMARY + 唯一键（本机 `SHOW INDEX` 已核实，709 行无压力，增长后全扫）。
- **根因**：V1 未建 status 索引。
- **方案**：P1-4 重排后的新迁移中守卫式补建 `KEY idx_dft_status (status)`。
- **验收**：迁移后 `SHOW INDEX` 含 `idx_dft_status`；`findRetryableTasks`/`findByStatus` 可命中索引。

### P3-13 `.cursorrules`、`excluded-test-files/` 入库
- **定位**：仓库根 `.cursorrules`（3.6KB，未被 `.gitignore` 忽略）；`excluded-test-files/`（含 `TigerSnapshotGridServiceImplTest.java`、`TigerWatchlistIngestServiceImplTest.java`，2026-05-19 遗留）。
- **根因**：工具配置与被排除测试文件入库，易混淆。
- **方案**：`TigerSnapshotGridServiceImpl` 类已随分支清理删除（main 中不存在）→ 对应测试不可编译，**删除 `excluded-test-files/`**（`TigerWatchlistIngestServiceImplTest` 如仍有价值，先尝试移植到 `src/test` 修复编译，无法维护则一并删除）；`.cursorrules` 移入 `.cursor/rules/` 或加入 `.gitignore`（按团队偏好，二选一）。
- **验收**：目录清理完成；构建与测试不受影响。

---

## 6. 发布与运维 runbook（随修复一并输出）

1. **迁移重排（P1-4 + P2-4 + P3-7 + P3-12）**：恢复旧 V2 → 新 `V4__align_existing.sql`（补列）→ 新 `V5__indexes.sql`（补索引）或合并入 V4；本地/生产库 `flyway repair` → `validate` → `migrate`。
2. **唯一约束（P1-5）**：生产库 `scripts/cleanup_screening_match_duplicates.sql` 清理 → 手工 `ALTER TABLE ... ADD CONSTRAINT` → 重启确认 SchemaVerifier 通过。
3. **安全确认（P3-9）**：`fillGaps` 返回值变更影响 MCP/定时器调用方，回归前逐一核对。

---

## 7. 依赖冻结与纪律（硬约束）

- tiger `openapi-java-sdk` **2.2.6**、`mysql-connector-j` **8.0.33** 禁止升级；本轮不引入新依赖。
- 迁移版本号单调递增、内容不可变；CI 增加 `flyway validate`。
- 本方案仅文档；分支创建、代码改动、构建/测试、推送均待后续确认执行。
