# stock-invest 代码评审报告

- **评审对象**：分支 `fix/code-review-optimization`（HEAD `ad38325`）相对 `main` 的全部变更（22 commits，114 文件，+4850/-1590）
- **技术栈**：Java 17 + Spring Boot 3.5.16 + Spring Data JPA + Flyway + MySQL 8.0.46（本机 127.0.0.1:3307）+ Python 数据源脚本
- **评审方式**：只读静态评审 + 本地 DB（127.0.0.1:3307）只读核对；未执行构建/测试；未修改任何代码；未推送分支
- **评审日期**：2026-08-07

---

## 1. 总评

### 质量结论

这是一次**质量明显提升**的整改分支：核心缺陷（错误分类、事务边界、补缺顺序依赖、DDL 三套并行、价格浮点精度、Python 子进程管道死锁）被系统性修复，且**每个修复都配套了针对性测试**，测试断言与实现高度一致。分层保持 Controller → Service（接口 + Impl）→ Repository 的单向依赖，DTO 化（record + 校验）落实到位，文档（`docs/optimization-plan.md`、`docs/p2-6-decimal-migration-plan.md`）先行、代码跟进，工程纪律良好。

主要风险集中在三处：**① 安全基线薄弱**（管理端无鉴权、Tiger 私钥与 API 密钥入库——均为 main 上遗留问题，本分支未恶化但也未处理）；**② 迁移脚本的"守卫式"设计存在条件失效风险**（唯一约束在重复数据场景下可能永久不添加；V2 版本号复用是潜在定时炸弹）；**③ 一处默认行为回归**（`trigger-screening` 参数从"被忽略"变为"生效"后，无参调用的筛选范围从全窗口全量收窄为单窗口 20 只，需与前端确认）。

### 严重度分布

| 级别 | 数量 | 说明 |
|------|------|------|
| P0（必须立即处理） | 1 | Tiger OpenAPI 私钥入库（main 遗留） |
| P1（高） | 5 | 鉴权缺失、硬编码密钥、筛选默认行为回归、Flyway V2 版本号复用、唯一约束守卫式失效风险 |
| P2（中） | 9 | 乐观锁冲突吞掉、AbortPolicy 500、测试配置进生产 jar、V2 列对齐不全、drain 线程池偏小、慢测试、进度 TTL 清理缺口、失败仍冷却、遗留死文件 |
| P3（低） | 13 | 命名/死代码/一致性小项 |

> 标注"main 遗留"的问题**非本分支引入**，但属"完整代码评审"范围内必须暴露的项；标注"本分支"的问题为本次变更引入或未解决。

---

## 2. 问题清单

### P0 —— 必须立即处理

#### P0-1 Tiger OpenAPI 私钥入库（main 遗留，紧急）
- **位置**：`src/main/resources/tiger_openapi_config.properties`（git 跟踪，自 Initial commit 起；`git check-ignore` 确认未被忽略）
- **问题**：文件中含 `private_key_pk1` / `private_key_pk8` 等 Tiger OpenAPI 私钥明文，随仓库分发。任何获得仓库访问权者均可冒充账户调用行情/交易接口。
- **根因**：凭据与代码同仓，无密钥管理。
- **建议**：立即在 Tiger 后台**轮换该密钥**；将私钥迁移至环境变量/密钥管理（如 `TIGER_PRIVATE_KEY`），配置文件改为占位符并加入 `.gitignore`；用 `git filter-repo` 清理历史或接受历史泄露并按"已泄露"处理。

---

### P1 —— 高

#### P1-1 管理端 API 与 MCP 工具无鉴权（main 遗留）
- **位置**：`src/main/java/com/stock/invest/controller/AdminController.java`（`/api/admin/trigger-screening`、`trigger-data-fill`、`trigger-retry-tasks`、`fetch-full-year` 等）、`src/main/java/com/stock/invest/mcp/StockInvestMcpTools.java`；pom.xml 无 spring-security 依赖
- **问题**：上述端点无任何认证。任意可达该服务的调用方都能触发付费数据源配额消耗（Tiger/Tiingo/TwelveData）、修改黑名单与重试任务、执行整年日历抓取。MCP 侧仅 `IngestApiGuard`（`ingest.api-key` 未配置时直接放行，`IngestApiGuard.java:16-18`）。
- **根因**：项目从未引入鉴权层，管理面与查询面共用端口。
- **建议**：至少为 `/api/admin/**` 与 MCP 管理工具引入 API Key（`X-ADMIN-API-KEY`）或 Spring Security 基础鉴权；MCP ingest 的 `keyRequired` 默认应为 true。

#### P1-2 硬编码密钥与默认口令（main 遗留，本分支沿用）
- **位置**：`src/main/resources/application.yml`：`twelvedata.api.api-key`（明文）、`tiingo.api.token`（明文）、`spring.datasource.password: ${MYSQL_PASSWORD:allon23}`（默认口令）
- **问题**：付费 API 密钥与 DB 口令随仓库分发；本地库（127.0.0.1:3307）实测以该默认口令可直连。
- **根因**：配置默认值落库。
- **建议**：密钥改为无默认值环境变量（缺失即启动失败），DB 口令默认值移除；与本分支"密钥从命令行参数透传"的整改方向（P1-9 相关提交）保持一致。

#### P1-3 `trigger-screening` 默认参数生效导致行为收窄（本分支）
- **位置**：`src/main/java/com/stock/invest/controller/AdminController.java:71-76`（`limit` 默认 20、`windowDays` 默认 7）+ `src/main/java/com/stock/invest/service/impl/ScreeningServiceImpl.java:96`（`windowDays==null || <2` 才全窗口）
- **问题**：main 上该端点虽声明了 `windowDays/limit` 参数但**从不使用**（调用 1 参 `runScreening(targetDate)` → 全窗口 2~7 天、全量 symbol）；本分支使参数生效后，**无参默认调用变为仅 7 天窗口、最多 20 个 symbol**。若前端依赖默认值，筛选结果集从"全部 symbol × 6 窗口"骤降为"20 只 × 1 窗口"；且与 `trigger-screening-async`（全窗口 2~7d，`AdminController.java:81-83`）语义不一致。
- **根因**：P1-7 让死参数生效时未保留默认语义（默认值应为 null → 全窗口）。
- **建议**：与前端确认调用方式；若默认应保持全窗口，将 controller 默认改为 `windowDays=null`（或 `0`）并仅在显式传参时收窄；同时在 API 文档中写明默认语义变化。

#### P1-4 Flyway V2 版本号复用 / 旧迁移文件删除（本分支，条件性风险）
- **位置**：`src/main/resources/db/migration/`：删除 `V2__create_symbol_blacklist.sql`（main 上存在），新建 `V2__align_existing.sql`；`application.yml:34-44`
- **问题**：两个不同内容、不同描述的迁移共用了**版本号 2**。任何已通过 Flyway 应用过旧 V2 的环境，升级后 `validate` 会因 checksum/description 不匹配而启动失败。本机实测未触发（`flyway_schema_history` 仅有 baseline/align existing/decimal price columns 三条，说明 Flyway 为本分支首次启用、旧 V2 从未被应用），但该风险是**永久定时炸弹**：任何手工跑过旧迁移、或未来与其它环境合并的库都会挂。
- **根因**：迁移版本号应单调递增、内容不可变。
- **建议**：旧 `V2__create_symbol_blacklist.sql` 内容合并进新文件并保持原版本号+描述，或将本次变更整体推到 `V4__...`（保留旧 V2 文件原样）。新增迁移永远用新版本号。

#### P1-5 `screening_match` 唯一约束守卫式添加可能永久不生效（本分支）
- **位置**：`src/main/resources/db/migration/V2__align_existing.sql:44-59` + `ScreeningServiceImpl.java:185-193`
- **问题**：V2 仅在"约束不存在 **且** 无历史重复行"时才 `ADD CONSTRAINT`；注释明确生产库 2026-08-06 存在 **2566 组重复**，届时该约束被跳过。Flyway 只执行一次，之后即使人工清理重复，**约束也永远不会被自动添加**（需手工 ALTER 或 `flyway repair` 后重跑 V2）。本机实测（重复组=0）约束已添加，但生产环境仍可能长期缺失——应用层查重（仅防 `targetDate` 当日、且依赖互斥）成为唯一防线。
- **根因**：把"数据清理"这一人工步骤编进了迁移的执行条件，而迁移没有重跑机制。
- **建议**：发布说明中显式列出生产步骤（清理重复 → 手工执行 `ALTER TABLE ... ADD CONSTRAINT uk_screening_match_trade_symbol_window_algorithm UNIQUE (...)` → 校验）；后续清理完成后再加约束。长期看应在约束缺失时由监控告警，而非静默降级。

---

### P2 —— 中

#### P2-1 乐观锁冲突被静默吞掉，dayCount 上限可能失效（本分支）
- **位置**：`src/main/java/com/stock/invest/service/impl/DataGapFillerServiceImpl.java:871-879`（`saveTaskWithOptimisticLock` catch 后仅 warn）
- **问题**：`processRetryingTasksInternal` 中 `retryCount/dayCount/status` 的读-改-写若发生 `ObjectOptimisticLockingFailureException`，本次更新被**丢弃**——`dayCount` 不增长 → "当日重试上限（dayCount>=5）"失效，任务可能持续重试。当前由 `running` 互斥把概率压得很低（同类批次不并发），但 `fillGaps` 的 `createRetryTask`（同文件 573-597 行）与 `processRetryingTasks` 的更新是两条独立写路径，且 JPQL 批量 `updateStatusBySymbolAndStatusIn` 不校验版本——一旦未来放开并发即踩坑。
- **根因**：用"跳过"换取简单，牺牲了重试语义的确定性。
- **建议**：冲突时**重读+重放一次**（幂等操作，安全），仍失败再告警；至少将冲突日志提升为 error 并计数，便于观测。

#### P2-2 AbortPolicy 拒绝以 500 呈现（本分支）
- **位置**：`src/main/java/com/stock/invest/config/AsyncConfig.java:23-24` + `AdminController.java`（`scanExecutor.execute` 未捕获 `TaskRejectedException`）
- **问题**：队列满时 `TaskRejectedException` 在 HTTP 线程抛出 → 落入全局兜底 500。测试 `AdminControllerTest.triggerScreeningAsync_rejectedTaskSurfacesError` 明确断言 5xx，说明是"预期"，但 HTTP 语义应为 503（服务忙）或 429。
- **建议**：`scanExecutor.execute` 包 try/catch，`TaskRejectedException` → `ResponseEntity.status(503)` 返回"任务队列已满"；同步端点（`triggerScreening`）本就是同步执行，不受影响。

#### P2-3 测试配置进入生产 jar（本分支）
- **位置**：`src/main/resources/application-test.yml`（原 `src/test/resources/application-test.yml` 被删除并迁入）
- **问题**：测试专用配置（H2 内存库、`ddl-auto: create-drop`、`show-sql: true`、h2-console 开启）随 `src/main` 打进生产包；生产环境若误用 `--spring.profiles.active=test` 会以 H2 + create-drop 启动，且暴露 h2-console。该文件放在 main resources 无法被测试 classpath 优先覆盖（同名文件只保留一份，见迁移后仓库现状）。
- **根因**：为了给测试环境补 `hikari.connection-init-sql` 与 `flyway.enabled=false` 而移动文件，未考虑产物边界。
- **建议**：改回 `src/test/resources/application-test.yml`（保留原内容 + 新增的两项覆盖）；`connection-init-sql` 覆盖与 Flyway 禁用本就只属于测试场景。

#### P2-4 V2 只对齐两列，`data_fill_task` 全列对齐无校验（本分支）
- **位置**：`src/main/resources/db/migration/V2__align_existing.sql`（仅补 `screening_match.algorithm`、`data_fill_task.version`）
- **问题**：V1 对存量库是 no-op（`CREATE TABLE IF NOT EXISTS`），V2 只补两列。若某个环境的历史 DDL 缺 `data_fill_task.retry_date/day_count/last_error`（旧 schema.sql 的 `data_fill_tasks` 是另一套列定义），V2 不会补齐，应用启动后查询/写入即失败。本机实测列齐全，但"仓库外历史 DDL"的版本差异未被迁移层保证。
- **建议**：V2 中为 `data_fill_task` 的其余列也加守卫式 `ADD COLUMN`；或增加启动期只读校验并给出明确报错。

#### P2-5 PythonScriptExecutor 排空线程池容量不足（本分支）
- **位置**：`src/main/java/com/stock/invest/util/PythonScriptExecutor.java:47-59`（`DRAIN_POOL` 固定 4 线程 = 2 进程 × 2 流）
- **问题**：并发执行 ≥3 个 Python 脚本时，部分排空任务排队；调用线程 `waitFor(30s)` 超时强杀后 `awaitDrain` 仅等 5s，排队中的 drain 任务可能尚未开始 → 返回空输出（数据丢失）。当前调度路径互斥，但 MCP/REST/定时器存在跨路径并发可能。
- **建议**：线程池扩到 8（或按"进程数×2+1"动态），或改用每执行实例独立短命线程；`awaitDrain` 超时日志从 warn 提级并带上 scriptName 上下文。

#### P2-6 测试套件存在真实等待型用例，拖慢 CI（本分支）
- **位置**：`src/test/java/com/stock/invest/util/PythonScriptExecutorTest.java`（超时用例真实等待 30s）、`src/test/java/com/stock/invest/http/ResilientHttpExecutorBackoffTest.java`（退避用例真实 sleep 1.5~2s）
- **问题**：`DEFAULT_TIMEOUT_SECONDS` 不可注入，测试用真实 30s 验证超时；退避测试用真实 sleep 验证时序。整套测试显著变慢。
- **建议**：超时值改为可注入（构造参数/系统属性），测试注入 1~2s；退避时序改为断言"调用次数 + 时间下限"并用 Clock 抽象或缩短基数。

#### P2-7 DataFillProgressService TTL 清理依赖下一次手动触发（本分支）
- **位置**：`src/main/java/com/stock/invest/service/DataFillProgressService.java:27-28`（`sweepExpired` 仅在 `startFill()` 调用）
- **问题**：定时器路径（`DataFillScheduler → fillGaps()`）不调用 `startFill`，因此长期"仅定时运行"时，手动触发遗留的已完成条目不会被清理（`progressMap` 只增不删，直到下一次手动触发）。量级当前可控（手动触发频率低），但属内存泄漏隐患。
- **建议**：`sweepExpired` 移到 `getProgress()`/`getProgress(taskId)` 读取路径（读时惰性清理），或由定时任务周期触发。

#### P2-8 fetch-full-year 失败后仍被冷却 30 分钟（本分支）
- **位置**：`src/main/java/com/stock/invest/controller/TradingCalendarController.java:103-118`
- **问题**：冷却时间戳在**执行前**写入；外部源故障导致同步失败后，30 分钟内无法重试，运维需等冷却过期。
- **建议**：执行成功后再写冷却时间戳（失败不冷却）；或冷却窗口缩短并对失败场景放行。

#### P2-9 遗留死文件与一次性脚本（main 遗留 + 本分支未清理）
- **位置**：`src/main/resources/db/v002_stock_data_source_priority.sql`（小写 `v002` 不匹配 Flyway 命名，永不执行，与 V1 重复定义同一张表）；根目录 `fix_blacklist_status.sql`（一次性修复脚本）
- **问题**：两套 DDL 事实来源残留，后续维护者易被误导（误以为 `v002` 会被执行）。
- **建议**：删除或移入 `docs/` 归档区，并在 V1 注释中标注"已并入 V1"。

---

### P3 —— 低

1. **`RetryProgressService.current` 非 volatile（跨线程可见性）**：`RetryProgressService.java:10`，HTTP 线程可能读到 stale null 而显示 IDLE；`DataFillProgressService.latestKey` 已是 volatile，此处应一致。→ `volatile` 或 `AtomicReference`。
2. **`isNotFoundError` 未使用参数 `klineData`**：`DataGapFillerServiceImpl.java:811`，路径 A（空结果）已移除但签名未清理。→ 删参。
3. **`SymbolNotFoundException` 死代码 + 分类语义陷阱**：`StockDataException.java:144-147` 无任何调用点；且其 3 参构造默认分类为 `TRANSIENT_FAILURE`——若未来启用会绕过黑名单判定。→ 删除或改为 `CONFIRMED_NOT_FOUND` 语义。
4. **`changePercent` 精度不一致**：服务层计算 `divide(...,8).multiply(100)` 后不 setScale（如 `TigerStockServiceImpl` 返回 8 位小数），入库 `DECIMAL(12,4)` 又四舍五入为 4 位——API 返回与 DB 存储精度不一致；`DataGapFillerServiceImpl` 的盘后涨跌幅已 setScale(4)，两处风格不统一。→ 统一在模型层 setScale(4)。
5. **实体 `@Data` → `@Getter/@Setter` 丢失 `toString`**：`DataFillTask/StockDailyBar/ScreeningMatch` 日志可观测性下降（打印实体只出哈希）；`equals/hashCode` 仅含 id 是 JPA 正确实践，但需注意 id 为 null 的新实体放入 HashSet 会随 persist 改变哈希。→ 需要时补 `@ToString`。
6. **实体 `@UniqueConstraint` 名称与 V1 不一致**：`DataFillTask.java:30` 声明 `uk_data_fill_task_symbol_trade_date`，V1 实际约束为 `uk_data_fill_task_symbol_missing_date`（仅影响 ddl-auto 生成路径，当前 ddl-auto=none 无运行时影响）。→ 对齐命名。
7. **V1 相对旧 schema.sql 丢弃索引**：`idx_screening_match_window_days`、`idx_screening_match_batch_id_window_days` 未进 V1；当前查询无单独按 window_days 过滤（`countByBatchIdGroupByWindowDays` 走 batch_id 索引），暂无实际影响。→ 确认查询面后补索引或文档化。
8. **`DataFillTaskRepository.findByFilters` symbol 匹配语义变化**：`LIKE %x%`（包含）→ `LIKE x%`（前缀），注释已说明但属于对外行为变更，需前端/管理端确认。→ 文档 + 前端核对。
9. **`triggerDataFill` TOCTOU**：`AdminController.java` 的 `isRunning()` 检查与 `scanExecutor.execute` 之间存在竞态窗口，调度器可能已启动补缺 → 请求返回 200 + taskId 但任务被服务层 CAS 静默跳过。→ 提交后异步回读任务状态或让服务层返回"已拒绝"。
10. **`fillGapsForSymbol` 仅对 `close <= minPriceThreshold` 的股票补缺**：`DataGapFillerServiceImpl.java:224-227`，语义存疑（补缺应面向所有股票？），main 遗留逻辑，Double→BigDecimal 时原样保留。→ 与产品确认该阈值语义。
11. **`WatchlistVolumeParser` 大数值 `longValue()` 静默溢出**：`WatchlistVolumeParser.java`，超 long 的成交量（如 `99999999999999999999` 亿）截断失真。→ 溢出时抛异常或返回 Long.MAX_VALUE + 告警。
12. **`DataFillTaskRepository.findRetryableTasks` 无 status 索引**：本地库 `data_fill_task` 仅有 PRIMARY + 唯一键（实测），709 行无压力；数据增长后 `WHERE status='retrying' ORDER BY created_at` 全扫。→ V1 补 `KEY idx_dft_status(status)`。
13. **`.cursorrules`、`excluded-test-files/` 入库**：工具配置与被排除测试文件留在仓库内，易混淆。→ 移出或文档化用途。

---

## 3. 亮点与优点

1. **错误三态分类 + 源级熔断（P1-3/P1-5）**：`StockDataException.ErrorCategory` 区分"确认不存在/瞬态失败/账户级错误"，黑名单只统计 CONFIRMED_NOT_FOUND——修复了"限流/超时被包装成空结果误伤真实股票"的根因；账户级错误 30 分钟熔断并终止 fallback 链，配 `CircuitBreakerTest` 行为级断言（反射核冷却表）。
2. **事务边界收缩 + 批次容错（P1-2）**：单次持久化独立事务（`runInTx`），单 symbol 失败不再回滚整批；`AtomicBoolean` 互斥覆盖定时/REST/MCP 三路；`DataGapFillerConcurrencyTest` 用 latch 卡住执行路径验证"仅一份执行、拒绝立即返回、异常不锁死"。
3. **迁移体系重构（P2-1）**：三套并行 DDL（schema.sql / fix_constraint_name.sql / DatabaseInitializer）收敛为 Flyway V1 幂等基线 + V2 守卫式对齐 + V3 就地类型转换；`baseline-on-migrate` 兼容存量库。**本机 DB 实测**：V1/V2/V3 全部 success，价格列已为 `DECIMAL(12,4)`，五个唯一约束全部就位。
4. **价格精度全链路 BigDecimal（P2-6）**：实体/DTO/模型/客户端全量 Double→BigDecimal，出站统一去尾零（`strip`/`strip3`）避免 `152.5000` 与 `1.5E+2` 科学计数；测试断言全部改为 `compareTo`/scale 感知，并新增序列化专项。
5. **真实 bug 修复**：`findMissingTradeDates` 依赖调用方传序的隐藏缺陷（newest/oldest 取反导致内部空洞永不发现）改为内部显式排序；`TradingCalendarFallback` 全挂时不再默认"开盘"（P2-11 宁可漏一天不可错打配额）；`DataFillTaskRepository` 索引友好的前缀 LIKE；`WatchlistVolumeParser` 支持科学计数/全角逗号。
6. **Python 执行器健壮化（P1-1/P2-15/P2-16）**：并行排空 stdout/stderr 防管道死锁、超时强杀、8MB 输出截断、统一 `{"error":{"code","message"}}` 错误协议、美东时区盘后合并；配套真实进程级测试（含挂起/洪泛脚本）。
7. **HTTP/网络层**：Alpaca 15s 总超时、`ResilientHttpExecutor` 网络层指数退避 + jitter、日历查询超时 `future.cancel(true)` 防僵尸任务。
8. **输入校验与错误语义**：`BarsController` 分页/天数 clamp、sortBy 白名单；`GlobalExceptionHandler` 补齐 400/405/409 分类；日历年份范围 ±1 + 30 分钟频控。
9. **卫生清理**：删除 `.bak` 文件、`TigerWatchlistIngestServiceImpl` 硬编码股票列表、`NotificationController` 死分支、`DatabaseInitializer`；Controller 层 DTO 化（黑名单/数据源优先级不再直出实体）。
10. **测试工程**：测试与实现同步演进（FILL-004 断言随 P1-3 语义更新、AfterHours 断言改 anyMatch 适配保存顺序变化），新增 6 个专项测试文件；文档（`docs/optimization-plan.md`、`docs/p2-6-decimal-migration-plan.md`、`docs/test-plan.md`）完整记录决策与偏差。

---

## 4. 建议的后续改进项

1. **安全基线（最高优先）**：轮换 Tiger 私钥并迁出仓库；`/api/admin/**` 与 MCP 管理工具加鉴权；密钥类配置改为无默认值环境变量；DB 口令默认值移除。
2. **生产数据治理**：生产库 `screening_match` 重复数据清理后手工补建唯一约束（参照 P1-5）；发布清单中显式记录该手工步骤；清理脚本入库管理。
3. **迁移版本纪律**：按 P1-4 处理 V2 版本号问题；后续所有 schema 变更一律新版本号 + 事前 `flyway validate` 检查（CI 中加 `migrate validate` 步骤）。
4. **默认语义回归确认**：与前端核对 `trigger-screening` 的 windowDays/limit 默认调用路径，必要时将默认改回全窗口（P1-3）。
5. **分布式化预留**：当前互斥/熔断/冷却均为进程内状态（`AtomicBoolean`/`ConcurrentHashMap`），多实例部署即失效；若有多实例计划，需迁移到 DB 分布式锁/Redis。
6. **可观测性**：为熔断触发、乐观锁冲突、日历未知态、脚本超时增加结构化告警（当前多为 warn 日志）；`RetryProgressService` 引用可见性修复。
7. **测试提速**：注入化超时/退避参数（P2-6），使 CI 在 30s 量级完成 Python 执行器与退避用例。
8. **清理**：`v002_stock_data_source_priority.sql`、`fix_blacklist_status.sql`、`excluded-test-files/`、`.cursorrules` 归档或移除；`SymbolNotFoundException` 死代码删除或修正分类。

---

## 附录：只读 DB 核对记录（127.0.0.1:3307，仅 SELECT）

| 核查项 | 结果 |
|--------|------|
| `flyway_schema_history` | 3 条全 success：V1 baseline → V2 align existing → V3 decimal price columns |
| `stock_daily_bar` 价格列 | 全部 `decimal(12,4)`（open/close NOT NULL，high/low/change_percent/after_hours* NULL） |
| `screening_match` | `algorithm varchar(32) NOT NULL`；唯一约束 `uk_screening_match_trade_symbol_window_algorithm` 已建；重复组 = 0（共 16,842 行） |
| `data_fill_task` | `version int NOT NULL` 已补；列齐全（含 retry_date/day_count/last_error）；709 行（retrying=4 / stopped=481 / completed=224） |
| 唯一约束全集 | `data_fill_task`、`screening_match`、`stock_daily_bar`、`stock_data_source_priority`、`symbol_blacklist`、`trading_calendar` 各 1 个，名称与 V1 一致 |
| 索引 | `data_fill_task` 仅 PRIMARY + 唯一键（无 status 索引）；`screening_match` 有 batch_id / trade_date_price / 唯一键 |
| 未执行 | 无任何 INSERT/UPDATE/DELETE/DDL；未运行构建与测试；未推送分支 |

---

*评审人：Oh My Pi 代码评审任务（只读）*
