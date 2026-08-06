# stock-invest 代码评审优化方案

> 依据：`/tmp/stock-invest-review.md`（全量代码评审结论）。本文档中的文件:行号已于 2026-08-06 对照实际源码逐一核对，与评审报告有出入处以本文档为准。
>
> 约束：本文档仅描述方案，**不包含任何实际开发动作**。所有修复待另行确认后，在独立分支上执行。

---

## 0. 总则与约束

### 0.1 排除范围（本次不处理，不给修复建议）

以下问题按任务要求排除，后续与安全整改一并单独处理：

| 编号 | 内容 | 主要位置 |
|---|---|---|
| P0-1 | Tiger 交易账户 RSA 私钥明文入库并打进 jar | `src/main/resources/tiger_openapi_config.properties:3-16` |
| P0-2 | 全站零鉴权（admin/blacklist/ingest/mcp 全部匿名可调、`server.address` 未绑 127.0.0.1、无 CSRF、IngestApiGuard 默认放行、前端无 token） | `pom.xml`、`WebConfig.java`、`IngestApiGuard.java:18-21`、`application.yml` |
| P0-3 | 第三方密钥与数据库口令明文硬编码（`twelvedata.api-key`、`tiingo.token`、`MYSQL_PASSWORD` 默认值、`useSSL=false`） | `application.yml:27,63-68` |
| 数据源可用性 | `datasource/` 包下可用性信号失真（`TwelveDataAvailabilityRule` 恒可用、`YFinanceAvailabilityRule` 不检查 Python 运行时、`TigerAvailabilityRule` 回退不一致、`DataSourceAvailabilityChecker.hasKey` 短路逻辑错误） | `src/main/java/com/stock/invest/datasource/**` |

> 说明：P2 中"`TradingCalendarController /fetch-full-year` 无鉴权"与"IngestApiGuard 恒定时间比较"均属 P0-2 安全范畴，一并排除；本文档只处理其非安全部分（参数校验、频控、幂等）。

### 0.2 依赖冻结

以下依赖**保持当前版本不变，禁止升级**，所有方案不得以其升级为前提：

1. `tiger openapi-java-sdk` **2.2.6**（`pom.xml` `<tiger-api.version>`）——P1-5 熔断方案只能从响应错误码/消息侧识别账户级错误，不得依赖新版 SDK 行为。
2. `mysql-connector-j` **8.0.33**（`pom.xml` 显式版本）——P2-2 的 upsert 改造须避免依赖新版驱动特性，且兼容 8.0.33 的 `VALUES()` 弃用告警。

不在冻结范围内的依赖（如 Flyway、spring-boot-starter-validation）允许引入，但需在方案评审时确认版本由 Spring Boot 3.5.16 BOM 管理。

### 0.3 分支策略（写入文档，不实际执行）

- 当前分支：**`main`**（`git branch --show-current` 确认），仓库存在**未提交变更**：
  - 已修改：`pom.xml`、`src/main/java/com/stock/invest/controller/NotificationController.java`、`service/ScreeningService.java`、`service/impl/ScreeningServiceImpl.java`、`src/main/resources/application.yml`
  - 未跟踪：`src/main/java/com/stock/invest/mcp/`（MCP 集成，全新代码）
- 建议流程：
  1. 与仓库所有者确认未提交变更的归属（MCP 模块是否纳入本次修复分支）；
  2. 基于 `main` 创建新分支：`git checkout -b fix/code-review-optimization`（未提交变更随分支带入；若 MCP 模块不纳入，先 `git stash` 或单独提交）；
  3. **全部修复只在本分支上完成，不直接改动 `main`**；
  4. 按本文档 4 的里程碑分批提交，每批一个 commit（建议前缀 `fix:`），PR 合并前完成自测（见 `test-plan.md`）。

### 0.4 问题统计与优先级分布

- 可修复问题总数：**37 项**（P1：9 项 / P2：19 项 / P3：9 项）
- 排除项：4 组（P0-1、P0-2、P0-3、数据源可用性信号），见 0.1
- 优先级：**立即**（6 项，全部为 P1 高影响项）/ **近期**（12 项）/ **后续**（19 项），详见各条目与第 4 节路线图

---

## 1. P1 级问题（9 项）——高概率 bug / 高风险

### P1-1 Python 子进程超时机制失效 + 管道死锁

- **问题定位**：`src/main/java/com/stock/invest/util/PythonScriptExecutor.java:74-102`
  - `74-85`：先同步阻塞读 stdout 到 EOF；
  - `87-97`：再阻塞读 stderr 到 EOF；
  - `99`：**最后**才 `process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)`。
- **根因**：子进程挂起时 stdout/stderr 的 `readLine()` 永久阻塞，`waitFor(30s)` 与 `destroyForcibly()` 成为死代码；stderr 写满管道缓冲（约 64KB）时同样死锁。yfinance / twelvedata / tigeropen 三个数据源全部经由本类，任一挂起即永久占用调度线程。
- **修复方案**（关键变更）：
  1. 超时判定前置：先 `process.waitFor(timeout, SECONDS)`，等待期间**并行**读取两路输出；
  2. 超时 → `process.destroy()` → `process.destroyForcibly()` → 抛超时异常；
  3. 读流增加输出上限（如 8 MB / 20 万行截断），防止内存膨胀；stderr 仅保留尾部；
  4. stdout 全量日志由 INFO 降为 DEBUG（联动 P2-17）。
  ```java
  CompletableFuture<String> outF = CompletableFuture.supplyAsync(() -> drain(process.getInputStream(), LIMIT));
  CompletableFuture<String> errF = CompletableFuture.supplyAsync(() -> drain(process.getErrorStream(), LIMIT));
  boolean completed = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  if (!completed) {
      process.destroy();
      process.destroyForcibly();
      throw new IOException("Python脚本执行超时 (" + DEFAULT_TIMEOUT_SECONDS + "秒)，已强制终止进程");
  }
  String output = outF.get(5, TimeUnit.SECONDS);   // 进程已退出，读流必然 EOF，5s 仅为保险
  String stderr  = errF.get(5, TimeUnit.SECONDS);
  ```
  > 注：读流任务用公共线程池（现有 `scanExecutor` 或专用小池），不可用 `ForkJoinPool.commonPool` 之外自建无界线程。
- **优先级**：立即（数据源调度全局最大健壮性风险）。**工作量**：0.5–1 人日。

### P1-2 补缺批处理巨型事务 + 无并发互斥

- **问题定位**：
  - `service/impl/DataGapFillerServiceImpl.java:97-98`（`@Transactional public void fillGaps()`）、`501-502`（`@Transactional public void processRetryingTasks()`）；
  - 触发路径：`scheduler/DataFillScheduler.java:27`（19:00 cron）、`controller/AdminController.java:187`（trigger-data-fill）、`213`（trigger-retry-tasks）、`mcp/StockInvestMcpTools.java`（screening_run 触发筛选，补缺经 ingest 联动）。
- **根因**：事务边界覆盖全部外部 I/O（约 200 symbols × 5 日期 × 5 源，可达数小时）：DB 连接长期占用；循环内任一未捕获异常使**整批已落库数据全部回滚**、API 配额作废；无 running 互斥，定时与手动（乃至 MCP）可并发执行，重复补缺、双倍配额。
- **修复方案**：
  1. **事务边界收缩**：移除 `fillGaps` / `processRetryingTasks` 上的 `@Transactional`；单次持久化（`persist()`、`createRetryTask()`、优先级更新）用 `TransactionTemplate`（注入 `PlatformTransactionManager`）包独立事务，失败不回滚批次。注意：自调用 `this.persist()` 时 Spring AOP 代理不生效，故用 `TransactionTemplate` 而非 `@Transactional(REQUIRES_NEW)` 注解。
  2. **运行互斥**：`DataGapFillerServiceImpl` 增加 `AtomicBoolean running`，`fillGaps` / `processRetryingTasks` 入口 `compareAndSet(false,true)`，失败则 warn 并直接返回（或返回"已在运行"信号）；`finally` 释放。定时、REST、MCP 三路共用同一 Service 实例，天然互斥。`ScreeningServiceImpl.runScreening` 同样加互斥。
  3. **批内容错**：`fillGapsForSymbol` 外层 try-catch 单 symbol 失败不中断批次（当前 `fetchAndPersist` 内部已 catch 单源，需再包一层）。
  4. `AdminController.triggerDataFill` 等端点识别"已在运行"状态并返回明确提示（409 或消息字段）。
- **优先级**：立即。**工作量**：1–2 人日。

### P1-3 瞬时故障被判定为 not-found，真实股票被永久黑名单

- **问题定位**：`service/impl/DataGapFillerServiceImpl.java`
  - `669-673`：`isNotFoundError` 路径 A——`klineData == null` 或 items 为空一律 `return true`；
  - `314-315`：数据源返回空结果时 `sourceNotFoundResults.put(source.name, true)`；
  - `357-359`：catch 块把异常也交给 `isNotFoundError`；
  - `363-373`：`notFoundCount >= 2` → 记黑名单（阈值 `application.yml` `not-found-threshold: 2`，`skip-count-threshold: 3`）。
  - 数据源失败全部表现为"空"：YFinance 异常返回 `new KLineData()`、TigerOpen 异常返回空、Tiingo 非 404 异常返回空 items、Tiger 捕获异常返回空。
- **根因**：数据源"确认不存在 / 瞬态失败 / 请求成功但无数据"三态混为一谈（评审第 5 条架构问题的根因）。一次全源瞬时故障期（限流/超时/Python 崩溃）即可让股票永久失去补缺。
- **修复方案**：
  1. 新增异常分类：`exception/StockDataException.java` 增加错误分类枚举（`CONFIRMED_NOT_FOUND` / `TRANSIENT_FAILURE` / `ACCOUNT_LEVEL`），数据源客户端在失败路径**抛带分类的异常**而非返回空 KLineData（YFinance/Tiger/TigerOpen/Tiingo/TwelveData 的 catch 块逐一改造）。
  2. `isNotFoundError` 改为三态判定：
     - 仅当错误消息明确匹配 404 / not found / invalid symbol / is missing / no data 等关键词（现路径 B 白名单）→ `CONFIRMED_NOT_FOUND`；
     - 请求层失败（超时、连接、5xx、解析异常、配额）→ `TRANSIENT_FAILURE`，**不计数**；
     - 成功但 items 空 → `EMPTY`，默认不计（除非数据源显式标记确认无数据）。
  3. 黑名单计数只统计 `CONFIRMED_NOT_FOUND`。
  4. 可选增强：黑名单记录增加 `lastError` 与自动衰减（如 7 天未再命中则自动解除），降低历史误伤影响。
- **优先级**：立即（数据污染风险）。**工作量**：1 人日。

### P1-4 `findMissingTradeDates` 顺序反转，历史空洞永不补

- **问题定位**：`service/impl/DataGapFillerServiceImpl.java`
  - `184-190`：`fillGapsForSymbol` 调用 `findBySymbolOrderByTradeDateDesc` 后 `Collections.reverse(bars)`，以 **ASC** 序传入；
  - `240-244`：`findMissingTradeDates` 按 javadoc（`236-239`，声明 DESC）取 `newestInBars = get(0)`、`oldestInBars = get(size-1)`——实际得到的是最旧 bar / 最新 bar；
  - `249-259`：`rangeEnd` 用"实际最旧 bar"与 yesterday/today 比较 → 扫描区间坍缩为 `[最新bar日期, 昨天]`。
- **根因**：顺序契约与实现不一致，窗口**内部**的历史空洞永远不会被发现；当前能补 08-03 这类尾部缺口纯属巧合。
- **修复方案**：
  1. `findMissingTradeDates` 开头显式排序，消除对调用方顺序的隐式依赖：
  ```java
  List<StockDailyBar> sorted = existingBars.stream()
      .sorted(Comparator.comparing(StockDailyBar::getTradeDate))
      .toList();
  LocalDate newestInBars = sorted.get(sorted.size() - 1);
  LocalDate oldestInBars = sorted.get(0);
  ```
  2. 同步清理调用方：`fillGapsForSymbol` 去掉 `Collections.reverse`，`latest = bars.get(0)`（当前 `bars.get(size-1)` 仍正确，去 reverse 后改为 `get(0)`）；
  3. 更新方法 javadoc 与调用处注释。
- **优先级**：立即（与 P1-3 联动，直接决定补缺正确性）。**工作量**：0.5 人日。

### P1-5 Tiger 配额错误无熔断，逐 symbol 打满 fallback 链

- **问题定位**：`service/impl/DataGapFillerServiceImpl.java:299-373`（`fillWithFallback` 循环，每 symbol 依次尝试 5 源）、`buildFallbackChainForSymbol`（`649-659`）；客户端 `service/impl/TigerOpenStockServiceImpl.java`、`TigerStockServiceImpl.java` 的错误抛出点。
- **根因**：账户级权限/配额错误（日志实证 `4000:permission denied ... 20 symbols`）被当作普通单 symbol 失败，逐 symbol 重复打满 5 源 fallback 链，无降级/熔断。
- **修复方案**：
  1. 识别账户级错误：Tiger/TigerOpen 客户端在错误消息/错误码命中 `4000`、`permission`、`quota`、`配额` 时抛 `StockDataException(ACCOUNT_LEVEL)`（依赖冻结下只能从错误码/消息侧识别）；
  2. `fillWithFallback` 捕获 `ACCOUNT_LEVEL`：立即终止该 symbol 的后续源（不再 fallback），并向 `DataGapFillerServiceImpl` 报告账户级熔断（如 30 分钟冷却期内跳过该源）；
  3. 实现轻量熔断器：`datasource/` 下新增 `CircuitBreaker` 组件（状态机 OPEN / HALF_OPEN / CLOSED，失败阈值 + 冷却时间 + half-open 试探），或直接在 Service 内维护 `Map<String, Long> sourceCooldownUntil`；
  4. 熔断与 P1-3 的 `ACCOUNT_LEVEL` 分类共用异常体系，配额错误**不进入** not-found 计数。
- **优先级**：立即。**工作量**：1 人日。

### P1-6 调度器单线程 + 数小时长任务互相阻塞

- **问题定位**：`application.yml` 无 `spring.task.scheduling` 配置（Spring Boot 默认 pool-size=1）；`scheduler/DataFillScheduler.java:27`（19:00）、`ScreeningScheduler.java:27`（21:30）、`TradingCalendarScheduler.java:28`（周一 4:30）。
- **根因**：单线程调度池，19:00 补缺若跑数小时，21:30 筛选与 4:30 日历同步被堵死。
- **修复方案**：
  1. `application.yml` 增加：
  ```yaml
  spring:
    task:
      scheduling:
        pool:
          size: 4        # 补缺/筛选/日历三类任务可并行
        thread-name-prefix: sched-
  ```
  2. 依赖 P1-2 互斥保证同类任务不并发；池大小 3–4 即可覆盖三类任务错峰。
- **优先级**：立即（纯配置，成本最低）。**工作量**：0.5 人日（含验证）。

### P1-7 管理接口触发参数形同虚设

- **问题定位**：`controller/AdminController.java`
  - `63-71`：`triggerScreening` 的 `limit`/`windowDays` 只进日志，最终只调 `screeningService.runScreening(targetDate)`；
  - `125-146`：`runScreeningAsync` 的 `limit`/`windowDays` 只进进度对象，`limit = Integer.MAX_VALUE` 无实际作用；
  - `376-389`：`runScreening` 同；`service/impl/ScreeningServiceImpl.java:96-97` `runScreening(LocalDate)` 单参数，内部硬编码遍历 `WindowConstants.ALL_WINDOW_DAYS`（`constant/WindowConstants.java:23`）。
- **根因**：参数从未透传到筛选逻辑；四个重叠端点（trigger/run × sync/async）行为不一致，语义混乱。
- **修复方案**：
  1. `ScreeningService` 增加重载：`runScreening(LocalDate tradeDate, Integer windowDays, Integer limit)`；
  2. `windowDays` 生效：内部窗口循环改为
  ```java
  List<Integer> windows = (windowDays == null || windowDays < WindowConstants.MIN_WINDOW_DAYS)
          ? WindowConstants.ALL_WINDOW_DAYS : List.of(windowDays);
  for (int w : windows) { ... }
  ```
  3. `limit` 生效：候选 symbol 按 `processed >= limit` 截断（评估循环顶部 `break`）；
  4. 端点收敛（推荐）：保留 `trigger-screening`（同步、参数生效）与 `trigger-screening-async`（全窗口），删除 `run-screening` / `run-screening-async` 或将其行为与 trigger 对齐——**动手前先 grep 前端调用**，确认无调用方后再删；有调用方则统一语义并在文档/API 说明标注差异；
  5. `AdminController` 与 `MCP` 的 `screening_run` 参数同样透传。
- **优先级**：近期。**工作量**：0.5–1 人日。

### P1-8 `scanExecutor` 饱和时任务内联进 HTTP 线程

- **问题定位**：`config/AsyncConfig.java:14-23`（core=8/max=16/queue=200，`setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy())`）。
- **根因**：`CallerRunsPolicy` 在队列满时把数小时任务内联进提交线程（Tomcat worker），Web 线程池被拖垮。
- **修复方案**：
  1. 拒绝策略改为 `AbortPolicy`：`executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy())`；
  2. `AdminController` 捕获 `TaskRejectedException` 返回 503/提示"任务队列已满，请稍后"；
  3. 依赖 P1-2 互斥：正在运行同一任务时直接拒绝新触发，避免队列堆积到饱和；
  4. 队列容量下调至 50（当前 200 放大堆积窗口）。
- **优先级**：近期。**工作量**：0.5 人日。

### P1-9 HTTP 客户端不重试网络层故障

- **问题定位**：
  - `http/ResilientHttpExecutor.java:59-76`：`get()` 仅 catch `HttpStatusCodeException`（429/5xx/408 重试），`ResourceAccessException`（连接拒绝、`SocketTimeoutException`、DNS 失败）直接抛出；
  - `client/AlpacaRestClient.java:57-62`：`HttpRequest.newBuilder()` 仅 client 级 `connectTimeout(10s)`，请求无 `.timeout()`（读超时缺失）。
- **根因**：名为 Resilient，最常见的瞬时网络故障恰恰无重试；Alpaca 无读超时可能永久挂起线程。
- **修复方案**：
  1. `ResilientHttpExecutor` catch 分支增加 `ResourceAccessException`，按现有指数退避重试（受 `HttpClientProperties.maxRetries` 约束）；该类只做 GET，重试幂等安全；
  ```java
  } catch (ResourceAccessException ex) {
      if (attempts < max) {
          long backoffMs = (long) (500 * Math.pow(2, attempts - 1)) + jitter(attempts);
          log.warn("[ResilientHttp] get: network error, retry in {} ms (attempt {}/{})", backoffMs, attempts, max);
          sleepQuietly(backoffMs);
          continue;
      }
      throw ex;
  }
  ```
  2. `AlpacaRestClient` 的 `HttpRequest.newBuilder()` 增加 `.timeout(Duration.ofSeconds(15))`（总超时，含读）；
  3. 若 `HttpClientProperties` 无相关配置则新增 `connect-timeout` / `read-timeout` 配置项（`config/HttpClientProperties.java`）。
- **优先级**：近期。**工作量**：0.5–1 人日。

---

## 2. P2 级问题（19 项）——设计缺陷 / 边界 / 健壮性

### P2-1 Schema 管理三套并行且互相矛盾（数据层最严重）

- **问题定位**：
  - `src/main/resources/db/schema.sql:22-36` 与 `63-77`：`data_fill_tasks` 被 `CREATE TABLE IF NOT EXISTS` 定义**两次且结构完全不同**（第二段永不生效）；
  - `entity/DataFillTask.java:23`：实体映射**单数表** `data_fill_task`，schema.sql 从未创建该表（生产表完全由仓库外历史 DDL 创建）；`retryDate` / `dayCount` 列在脚本中不存在；
  - `screening_match` 缺 `algorithm` 列，靠 `config/DatabaseInitializer.java:17-36` 启动时 `ALTER TABLE` 补列，异常被 `catch` 静默吞掉；
  - `src/main/resources/db/fix_constraint_name.sql:1-2`：`DROP INDEX uk_data_fill_task_symbol_missing_date` 引用不存在的约束名；
  - `db/migration/` 仅孤立的 `V2__create_symbol_blacklist.sql`，`pom.xml` 无 Flyway。
- **根因**：无版本化迁移；JPA ddl-auto + 启动补丁 + 手工脚本三套并行。**全新环境按脚本初始化必然跑挂**。
- **修复方案**：
  1. 引入 Flyway：`pom.xml` 增加 `flyway-core` + `flyway-mysql`（版本由 Boot 3.5.16 BOM 管理，不在冻结范围）；
  2. `db/migration/V1__baseline.sql`：幂等（`CREATE TABLE IF NOT EXISTS`）重建全部表，**对齐生产实际结构**——`data_fill_task` 单数表（含 `retry_date`、`day_count`）、`screening_match`（含 `algorithm` 列）、`symbol_blacklist`（吸收现有 V2）、`stock_daily_bar`、`trading_calendar`、唯一约束齐全；
  3. `V2__align_existing.sql`：对存量库 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 补齐缺失列（MySQL 8.0 支持 `ADD COLUMN IF NOT EXISTS` 需 8.0.29+，若不可用则用存储过程或保留 DatabaseInitializer 兜底直至数据对齐）；
  4. 删除 `schema.sql`、`fix_constraint_name.sql`；`DatabaseInitializer` 移除或降级为纯日志（列已由 Flyway 保证）；
  5. `application.yml`：`spring.flyway.enabled: true`、`locations: classpath:db/migration`；存量库首次启动需 `baseline-on-migrate: true` 或先手工 `flyway baseline`；
  6. 测试环境（H2）走同一迁移脚本——**因此所有迁移脚本不得含 MySQL 专有语法**（联动 P2-2）。
- **优先级**：近期（全新环境初始化前提）。**工作量**：1–2 人日。

### P2-2 TradingCalendarRepository MySQL 专有 upsert 语法

- **问题定位**：`repository/TradingCalendarRepository.java:33-43`：`INSERT ... ON DUPLICATE KEY UPDATE ... VALUES(is_open)` 等。
- **根因**：`VALUES()` 函数在 MySQL 8.0.20+ 已弃用；语法在 H2 测试环境不兼容，导致集成测试无法覆盖该路径。
- **修复方案**：改为跨库兼容的"查-改-插"（消除方言依赖）：
  ```java
  // 服务层或 repository 默认方法
  default int upsert(...) {
      Optional<TradingCalendarEntity> existing = findByMarketAndTradeDate(market, tradeDate);
      if (existing.isPresent()) { /* 更新字段后 save */ return 1; }
      save(new TradingCalendarEntity(...)); return 1;
  }
  ```
  并发安全由 `(market, trade_date)` 唯一约束兜底（冲突时捕获 `DataIntegrityViolationException` 重试一次）。若坚持 native upsert，改用 MySQL 8.0.19+ 别名语法 `AS new ON DUPLICATE KEY UPDATE is_open = new.is_open`，但 H2 集成测试仍需另行兼容——**推荐前者**。
- **优先级**：近期。**工作量**：0.5 人日。

### P2-3 StockDataSourcePriorityService 伪 upsert 并发丢更新

- **问题定位**：`service/StockDataSourcePriorityService.java:77-84`：`deleteBySymbolAndDataSource` + `flush` + `save`。
- **根因**：删除与插入非原子，并发调用互相覆盖或触发唯一约束冲突。
- **修复方案**：
  1. 表加唯一约束 `UNIQUE (symbol, data_source)`（Flyway 迁移，联动 P2-1）；
  2. 改为"存在则更新，不存在则插入"：
  ```java
  @Modifying
  @Query("UPDATE StockDataSourcePriority p SET p.lastSuccessTime = :t "
       + "WHERE p.symbol = :symbol AND p.dataSource = :ds")
  int touch(...);
  // 影响行数为 0 时再 insert；仍冲突则捕获 DataIntegrityViolationException 重试一次
  ```
  或 repository `findBySymbolAndDataSource` → update / save 二选一。
- **优先级**：近期。**工作量**：0.5 人日。

### P2-4 无乐观锁，retryCount/dayCount 读-改-写丢更新

- **问题定位**：`entity/DataFillTask.java`（无 `@Version`）；`service/impl/DataGapFillerServiceImpl.java:583-584`（`task.setRetryCount(task.getRetryCount() + 1)` 等读改写）。
- **根因**：无版本控制，并发更新互相覆盖。
- **修复方案**：
  1. `DataFillTask` 增加 `@Version private Integer version;`；
  2. `processRetryingTasks` 保存冲突抛 `OptimisticLockException` → catch 后重读任务重试一次或跳过（P1-2 互斥已大幅缩小并发窗口，乐观锁仅兜底）；
  3. 其他高频读改写实体（`StockDataSourcePriority`）按需追加。
- **优先级**：后续（P1-2 互斥后风险显著下降）。**工作量**：0.5 人日。

### P2-5 `screening_match` 无唯一约束，重复触发插入重复行

- **问题定位**：`entity/ScreeningMatch.java:28-43`（仅索引、无唯一约束）、`db/schema.sql:5-20` 同；写入点在 `ScreeningServiceImpl.runScreening`（`screeningMatchRepository.saveAll(allRows)`）。
- **根因**：重复触发产生重复行，表无限膨胀且无清理任务。
- **修复方案**：
  1. 加唯一约束 `UNIQUE (trade_date, symbol, window_days, algorithm)`（同交易日同股票同窗口同算法仅一条；Flyway 迁移，存量重复行需先清理）；
  2. `saveAll` 改为防重写（捕获 `DataIntegrityViolationException` 忽略重复，或先按 batch 查重）；
  3. 新增清理任务：按 `tradeDate` 保留最近 N 天（如 30 天）或保留最近 K 个批次（batch 维度），挂到现有调度器（新 cron 或复用 21:30 筛选后）。
- **优先级**：近期。**工作量**：0.5–1 人日。

### P2-6 价格字段全 Double → DECIMAL

- **问题定位**：`entity/StockDailyBar.java:39-66`（openPrice/highPrice/lowPrice/closePrice/changePercent/afterHours 等全 `Double`）；`db/schema.sql` `stock_daily_bar` 的 DOUBLE 列；链路还包括 `model/KLineData.java`、`KLineIterator.java`、`enums/dto/StockDailyBarDto.java`、`StockDailyBarCandleDto.java`。
- **根因**：金融数据浮点误差随涨跌幅计算累积。
- **修复方案**（大改，建议单独立项）：
  1. 实体/DTO 价格字段改 `BigDecimal`，列改 `DECIMAL(12,4)`（Flyway ALTER）；
  2. 转换点统一 `BigDecimal.valueOf(double)`（精确表示现有 double 值）；
  3. `changePercent` 计算改 BigDecimal 运算（`PatternEvaluateServiceImpl`、persist 处）；
  4. 前端 JSON 序列化：BigDecimal 输出数字，Jackson 默认兼容；
  5. 影响面广，分两步落地：先列类型 + 实体/DTO，再计算点；每步跑全量回归。
- **优先级**：后续。**工作量**：2–3 人日（含回归）。

### P2-7 仓库查询语义缺陷（注释不符 / LIKE 索引失效）

- **问题定位**：
  - `repository/StockDailyBarRepository.java:98-99`：`findBySymbolInAndNameIsNotNull` 注释"取每个 symbol 最新的一条"，实为返回全部匹配行（靠调用方 `toMap` 合并键兜底）；
  - `repository/DataFillTaskRepository.java:47-55`：`findByFilters` 用 `t.symbol LIKE CONCAT('%', :symbol, '%')` 无法命中索引。
- **根因**：注释与实现不符；前导通配 LIKE 索引失效。
- **修复方案**：
  1. `findBySymbolInAndNameIsNotNull` 改子查询精确取最新：
  ```java
  @Query("SELECT b FROM StockDailyBar b WHERE b.symbol IN :symbols AND b.name IS NOT NULL "
       + "AND b.tradeDate = (SELECT MAX(b2.tradeDate) FROM StockDailyBar b2 "
       +   "WHERE b2.symbol = b.symbol AND b2.name IS NOT NULL)")
  ```
  2. `findByFilters` 的 symbol 改为前缀匹配：`t.symbol LIKE CONCAT(:symbol, '%')`（调用方传 `symbol%`），可命中 `idx_data_fill_tasks_symbol`；保留现有行为需在文档标注语义变化。
- **优先级**：后续。**工作量**：0.5 人日。

### P2-8 全局异常处理缺口

- **问题定位**：`exception/GlobalExceptionHandler.java:19-63`：仅处理 `NoResourceFoundException` / `IllegalArgumentException` / `HttpMessageNotReadableException` / `MethodArgumentTypeMismatchException` / 兜底 500。
- **根因**：未覆盖 `DateTimeParseException`（`AdminController.java:68,384`、`BarsController.java:75` 非法日期 → 500 而非 400）、`DataIntegrityViolationException`、`MissingServletRequestParameterException`、`HttpRequestMethodNotSupportedException` 全落通用 500；`IllegalArgumentException` handler 把内部消息原样回显。
- **修复方案**：
  1. 新增 handler 映射：`DateTimeParseException` → 400；`MissingServletRequestParameterException` → 400；`HttpRequestMethodNotSupportedException` → 405；`DataIntegrityViolationException` → 409（约束冲突）或 400；`MethodArgumentNotValidException` → 400（若引入 `spring-boot-starter-validation`，不在冻结范围）；
  2. `IllegalArgumentException` → 400 但**消息脱敏**：去除异常消息中的类名/包名/文件路径/堆栈行号（正则过滤 `com.stock.invest.*`、`\\(.*\\.java:\\d+\\)` 等模式）并截断（如 200 字符）；或引入业务异常 `BusinessException`（携带"可回显消息"）与系统 `IllegalArgumentException` 区分。**注意**：现有业务代码（如 `WatchlistVolumeParser` 的"无法解析成交量: xxx"）依赖 IAE 消息回显，改前需逐点核对，推荐 BusinessException 方案。
- **优先级**：近期。**工作量**：0.5–1 人日。

### P2-9 BarsController 参数边界

- **问题定位**：`controller/BarsController.java`
  - `71-72`：`sortBy` 用户可控直接 `Sort.by(sortBy)`（非法属性 → 500）；
  - `78`：`pageSize` 无上限（超大分页拖垮 DB）；
  - `75`：`LocalDate.parse(tradeDate)` 无 try-catch（非法日期 → 500，联动 P2-8）；
  - `80-88`：`getCandles` 的 `days` 无边界。
- **根因**：参数直接透传框架。
- **修复方案**：
  1. `sortBy` 白名单（`symbol` / `tradeDate` / `source` / `closePrice` / `volume` / `id`），非法值回退 `tradeDate` 或返回 400；
  2. `pageSize` clamp 至 `[1, 500]`、`page >= 0`；
  3. `tradeDate` try-catch → 400（或复用全局 handler）；
  4. `days` clamp `[1, 365]`，且不超过数据量（`getRecentCandles` 联动，见 P3-8）。
- **优先级**：近期。**工作量**：0.5 人日。

### P2-10 `/fetch-full-year` 无参数校验、无频控（非安全部分）

- **问题定位**：`controller/TradingCalendarController.java:77-90`：`POST /api/v1/trading-calendar/fetch-full-year`，`year` 可空、无范围校验、无频控。（鉴权部分归 P0-2 排除。）
- **根因**：手动触发全年 365 天外部抓取无任何防护，可被高频调用打空配额。
- **修复方案**：
  1. `year` 校验：`null` → 当年；范围限制 `[当前年-1, 当前年+1]`，非法 → 400（联动 P2-8 的 400 映射）；
  2. 频控：内存固定窗口（`AtomicLong` 记录最近一次全量同步时间，N 分钟内重复触发返回 429/提示"同步进行中"）；
  3. 幂等：依赖 P2-13 的事务拆分，同步中重复触发直接返回当前进度。
- **优先级**：近期。**工作量**：0.5 人日。

### P2-11 TradingCalendarFallback 全源失败默认 tradingDay=true 且缓存 24h

- **问题定位**：`service/impl/TradingCalendarFallback.java:89-93`：全源失败 → `TradingCalendarResult.defaultTradingDay()`（tradingDay=true）+ `cache.put(cacheKey, defaultResult)`（24h）。
- **根因**：数据源不可用时默认"开盘"，配合补缺会按错误日历白打配额；且失败结果缓存 24h 放大影响。
- **修复方案**：
  1. 全源失败返回"未知"标记（`TradingCalendarResult` 增加 `unknown` 状态或返回 `null`），**不缓存**；
  2. 调用方适配：`DataGapFillerServiceImpl.fillGapsForSymbol` 中 `calendarDbService.isTradingDay` 为 unknown → 跳过该日期（宁可漏一天，不可错补），并记录统计（`unknownSkipped`）；
  3. 成功结果缓存保留 24h 不变；失败路径只告警不缓存；
  4. 注意 `mcp/StockInvestMcpTools.java` 与 `TradingCalendarDbService.isTradingDay` 的 null 语义同步修改（当前 `isOpen == null` 时置 true）。
- **优先级**：后续。**工作量**：0.5–1 人日。

### P2-12 补缺/筛选进度服务缺陷

- **问题定位**：
  - `service/DataFillProgressService.java:13-17`：`startFill()` 固定 key `"manual"`，第二次触发覆盖第一次进度；
  - `service/ScreeningProgressService.java:39-41`：`removeProgress` 无任何调用点，`progressMap` 只增不删（每次手动/异步触发泄漏一条）；
  - `ScreeningProgressService.java:75-81`：`WindowProgress` 的 `days`/`status`/`matched` 非 volatile，跨线程可见性无保证。
- **根因**：进度存储无生命周期管理。
- **修复方案**：
  1. `DataFillProgressService.startFill()` 生成 UUID key（与筛选对齐），返回 `taskId`；保留无参 `getProgress()` 返回最近一次以兼容现有端点，`AdminController.triggerDataFill` 返回真实 taskId；
  2. `ScreeningProgressService`：任务结束 `finally` 中 `removeProgress(taskId)`（`AdminController.java:125-146,150-170` 两处异步 lambda 加）；兜底：TTL 清理（ScheduledExecutor 定期移除超过 24h 的条目）；
  3. `WindowProgress` 字段加 `volatile`。
- **优先级**：后续。**工作量**：0.5 人日。

### P2-13 日历同步单事务循环 365 天

- **问题定位**：`service/TradingCalendarDbService.java:42-46`（`@Transactional public int fetchAndStoreFullYear(...)`，循环内逐日调用外部源 + upsert）。
- **根因**：单事务持有 DB 连接数小时；中途异常整年回滚。
- **修复方案**：移除 `@Transactional`；循环内每日期独立事务（`TransactionTemplate.execute` 包裹 upsert），失败计数继续，返回成功条数；与 P2-10 频控联动。
- **优先级**：近期。**工作量**：0.5 人日。

### P2-14 日历服务 CompletableFuture 超时不取消底层任务

- **问题定位**：`service/impl/AlpacaCalendarService.java:61-63`、`TigerCalendarService.java:56-58`、`TigerOpenCalendarService.java:63-65`：`CompletableFuture.supplyAsync(...).get(TIMEOUT.getSeconds(), SECONDS)`，catch `TimeoutException` 后返回 null，**未 cancel 底层任务**。
- **根因**：超时后底层调用继续占用 `scanExecutor` 线程，僵尸任务累积挤占线程池。
- **修复方案**：保存 future 引用，超时后取消：
  ```java
  CompletableFuture<TradingCalendarResult> future =
      CompletableFuture.supplyAsync(() -> doQuery(market, date), executor);
  try {
      return future.get(TIMEOUT.getSeconds(), TimeUnit.SECONDS);
  } catch (TimeoutException e) {
      future.cancel(true);   // 中断底层任务（HttpClient 调用可响应中断）
      log.warn(...);
      return null;
  }
  ```
- **优先级**：近期。**工作量**：0.5 人日。

### P2-15 Python 失败协议不一致

- **问题定位**：`src/main/resources/python/tigeropen_channel.py`（失败路径 `sys.exit(1)` 无 stdout JSON）对比 `stock_info_twelvedata.py` / `stock_info_yfinance.py`（失败输出 `{"error": ...}`）。
- **根因**：Java 侧统一按 JSON 解析 stdout，tigeropen 失败时解析崩溃。
- **修复方案**：
  1. `tigeropen_channel.py` 全部命令分支加 try/except，失败输出统一 `{"error": {"code": "...", "message": "..."}}` 到 stdout 后 `sys.exit(1)`；
  2. Java 侧（`PythonScriptExecutor` 或调用方）：exit code != 0 时优先尝试解析 stdout 中的 error JSON，解析失败再用 stderr 与通用消息构造异常；
  3. 联动 P1-5：tigeropen 的账户级错误（`4000/permission denied`）在 Python 侧识别并放入 error JSON 的 `code` 字段，Java 侧据此抛 `ACCOUNT_LEVEL`。
- **优先级**：后续。**工作量**：0.5 人日。

### P2-16 tigeropen_channel.py 盘后合并用 UTC 日期做 key

- **问题定位**：`src/main/resources/python/tigeropen_channel.py:60-75`：盘后 bar 与日 K 行均用 `datetime.fromtimestamp(t / 1000, tz=timezone.utc)` 取日期。
- **根因**：美东 20:00 后的盘后 bar 落入 UTC 次日，与日 K 行日期错位，盘后价并入错误日期或丢失。
- **修复方案**：统一使用美东时区转换（`zoneinfo.ZoneInfo("America/New_York")`）作为盘后合并 key 与 item 日期，与日 K 行对齐；`tigeropen_channel.py` 顶部统一定义 `NY_TZ`。
- **优先级**：后续。**工作量**：0.5 人日。

### P2-17 PythonScriptExecutor 日志/探活开销

- **问题定位**：`util/PythonScriptExecutor.java:99`（INFO 级打印完整 stdout，全量 K 线 JSON 每次落盘）、`62`（每次执行前 `PythonRuntimeSupport.isPythonRunnable(pythonExec, true)` 探活）。
- **根因**：日志体积膨胀；探活进程每次执行都启动一次。
- **修复方案**：
  1. stdout 日志降为 DEBUG（或 INFO 仅打印前 1KB 摘要 + 长度）；
  2. 探活结果进程内缓存（`static volatile` 缓存 60s；失败不缓存，下次重探）；
  3. 联动 P1-1 的读流截断。
- **优先级**：后续。**工作量**：0.5 人日。

### P2-18 TwelveDataRestClient 解析容错与静默空

- **问题定位**：`client/TwelveDataRestClient.java:101`（`Double.parseDouble(close.asText())` 无容错）、`150`（`parseDouble` 内部 `Double.parseDouble(n.asText())` 无 try-catch）、`listUsStockSymbols`（错误仅 warn 返回空列表）。
- **根因**：非数字响应值 → `NumberFormatException` 中断整批；空列表无法区分"没有股票"与"接口挂了"。
- **修复方案**：
  1. `parseDouble` 包 try-catch，解析失败返回 0D 并 debug 记录（或抛带 symbol 上下文的 `StockDataException`）；
  2. `fetchLastClose` 解析失败返回 null + warn；
  3. `listUsStockSymbols` 失败抛 `StockDataException`（调用方决定是否降级），或返回 `List<String>` 的显式错误标记。
- **优先级**：后续。**工作量**：0.5 人日。

### P2-19 WatchlistVolumeParser 解析缺陷

- **问题定位**：`util/WatchlistVolumeParser.java:22-25`：纯数字 `(long) base` 直接截断小数；`17` 仅替换半角逗号；正则（`14`）不支持科学计数法与全角逗号。
- **根因**：Tiger 客户端成交量展示多样（如 `12.5万`、全角 `，`、`1.2E5`），解析不准导致成交量失真。
- **修复方案**：
  1. 无单位分支改为 `Math.round(base)`；
  2. 全角逗号：`replace(",", "")` → `replaceAll("[,\uFF0C]", "")`，并支持前后空白/千分位；
  3. 科学计数法：用 `BigDecimal` 解析后 `longValueExact`/`round`；
  4. 同步补充单元测试（现有 `WatchlistVolumeParserTest`）。
- **优先级**：后续。**工作量**：0.5 人日。

---

## 3. P3 级问题（9 项）——风格 / 小问题

### P3-1 残留文件清理

- **问题定位**：`controller/NotificationController.java.bak`、`service/ScreeningService.java.bak`、`service/impl/ScreeningServiceImpl.java.bak`、`service/impl/TigerWatchlistIngestServiceImpl.java.bak`、`service/impl/Untitled`（已 gitignore、不参与编译）。
- **方案**：确认无内容需要保留后删除（`git rm` 不适用——.bak 未被跟踪则直接删除）。
- **优先级**：后续。**工作量**：0.1 人日。

### P3-2 注释与实现不符 / ANSI 日志

- **问题定位**：`service/impl/ScreeningServiceImpl.java:52-55`（javadoc 声称"并行评估"实为串行双层循环）；`service/impl/DataGapFillerServiceImpl.java:218-219,306-307` 等（`\033[31m...\033[0m` ANSI 转义进日志文件）。
- **方案**：更新 javadoc（若未真正并行化）；移除 ANSI 转义或仅在控制台 profile 输出（logback 过滤器）。
- **优先级**：后续。**工作量**：0.2 人日。

### P3-3 NotificationController 死分支

- **问题定位**：`controller/NotificationController.java:40-51`：`getLatestNotification` 两分支返回相同（`result.containsKey("message")` 判断无意义）。
- **方案**：删除死分支。
- **优先级**：后续。**工作量**：0.1 人日。

### P3-4 getRetryProgress 恒返回 IDLE 死代码

- **问题定位**：`controller/AdminController.java:225-239`：注释自述"暂未接入进度追踪，始终返回 IDLE"。
- **方案**：接入真实进度（复用 DataFillProgressService 或 `RetryProgressService`——注意 `service/RetryProgressService.java` 已存在但未被使用），或删除端点。推荐接入，成本低。
- **优先级**：后续。**工作量**：0.2 人日。

### P3-5 Controller 直接返回实体违反 DTO 约定

- **问题定位**：`controller/BlacklistController.java:24-26`（返回 `List<SymbolBlacklist>` 实体）、`controller/AdminController.java:250-259`（返回 `List<StockDataSourcePriority>` 实体）。
- **方案**：新增对应 DTO record（`SymbolBlacklistDto`、`StockDataSourcePriorityDto`），Controller 映射后返回。
- **优先级**：后续。**工作量**：0.3 人日。

### P3-6 注入风格约定与实现不一致（约定落后）

- **问题定位**：`.cursorrules` 约定字段注入，实际代码为构造注入（`AdminController.java:43-55` 等）。
- **方案**：**改约定不改代码**——构造注入是正确方向，更新 `.cursorrules` 约定为构造注入。
- **优先级**：后续。**工作量**：0.1 人日。

### P3-7 TigerStockServiceImpl 静默返回硬编码股票列表

- **问题定位**：`service/impl/TigerStockServiceImpl.java:294-313`：API 失败时静默返回硬编码知名股票（AAPL/MSFT/...），调用方无法区分真假数据。
- **方案**：失败时抛 `StockDataException` 或返回带标记的结果（`scanResult.isFallback=true`），由调用方（筛选/前端）显式提示"数据源降级"。
- **优先级**：后续。**工作量**：0.3 人日。

### P3-8 getRecentCandles 硬编码 findTop7

- **问题定位**：`service/StockDailyBarService.java:25-36`：`getRecentCandles(symbol, days)` 硬编码 `findTop7BySymbolOrderByTradeDateDesc`，`days` 参数仅在 ≤7 时截断。
- **方案**：改为 `findBySymbolOrderByTradeDateDesc(symbol, PageRequest.of(0, min(days, 365)))`（联动 P2-9 的 days 边界）。
- **优先级**：后续。**工作量**：0.2 人日。

### P3-9 @Data equals/hashCode 含可变字段

- **问题定位**：全部实体使用 Lombok `@Data`（如 `entity/StockDailyBar.java:24`），equals/hashCode 含可变业务字段。
- **方案**：对进入 Set/缓存场景的实体（`StockDailyBar`、`DataFillTask`、`ScreeningMatch`）改 `@Getter @Setter` + 基于 id 的手写 equals/hashCode，或用 `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` + `@EqualsAndHashCode.Include` 标注 id。
- **优先级**：后续。**工作量**：0.5 人日。

---

## 4. 修复路线图

| 阶段 | 内容 | 依赖 |
|---|---|---|
| **M1 立即（1 周内）** | P1-1（Python 子进程）、P1-2（事务+互斥）、P1-3（not-found 三态）、P1-4（日期顺序）、P1-5（Tiger 熔断）、P1-6（调度池） | 无 |
| **M2 近期（2–4 周）** | P1-7（参数生效）、P1-8（线程池策略）、P1-9（网络重试）、P2-1（Flyway 基线）、P2-2（upsert）、P2-3（伪 upsert）、P2-5（唯一约束）、P2-8（异常处理）、P2-9（参数边界）、P2-10（频控）、P2-13（日历长事务）、P2-14（超时取消） | M1（P2-1 依赖 P1-2 的事务改造思路一致；P2-2/3/5 迁移脚本挂在 P2-1 的 Flyway 上） |
| **M3 后续（1–2 月）** | P2-4、P2-6（DECIMAL 大改）、P2-7、P2-11、P2-12、P2-15、P2-16、P2-17、P2-18、P2-19、P3 全部 | M1、M2 |

### 里程碑验收

- **M1 完成标准**：补缺/筛选在任意触发路径并发下最多执行一份；任何单点故障不再导致整批回滚；全源瞬时故障不再产生新的黑名单记录；历史空洞日期能被识别并补齐；调度任务互不阻塞。
- **M2 完成标准**：全新环境 Flyway 迁移可一次成功初始化全部表；所有 4xx 类参数错误返回明确状态码；无方言 SQL 残留于共享代码。
- **M3 完成标准**：价格计算无浮点误差；进度接口无内存泄漏；前端无降级数据误导。

## 5. 风险与注意事项

1. **依赖冻结约束**：P1-5 熔断依赖错误码/消息识别，Tiger SDK 2.2.6 的错误输出格式变化可能导致识别失效——熔断器需可配置关键词，并保留"识别失败按瞬态处理"的兜底。
2. **P1-2 互斥范围**：互斥在 Service 实例内生效；若未来部署多实例需换分布式锁（如 ShedLock），当前单实例部署下 `AtomicBoolean` 足够。
3. **P2-1 Flyway 基线风险**：生产库为仓库外历史 DDL 创建，V1 基线脚本必须逐列对齐生产实际结构（建议先 `mysqldump --no-data` 对拍）；`baseline-on-migrate` 的版本号选择需谨慎。
4. **P2-6 DECIMAL 影响面**：涉及全链路模型与计算点，建议独立分支合入，避免与 M1/M2 混批。
5. **P2-8 IAE 消息回显**：改动前需 grep 所有抛 `IllegalArgumentException` 的业务点，避免破坏前端依赖的 400 提示文案（推荐 BusinessException 方案）。
6. **P1-7 端点收敛**：删除端点前必须确认前端与外部调用方（含 MCP 工具）无依赖。
