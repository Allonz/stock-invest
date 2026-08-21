# stock-invest 项目全面源码级评审报告

> 说明：评审过程中派出的 3 个并行子代理均启动失败，以下全部结论均由本人逐文件亲自阅读源码得出，所有引用的文件路径与行号均已核实；部分结论辅以 `logs/` 运行日志与 `git 历史` 交叉验证。

---

## 一、项目概况与总体评分

**项目全貌**：Spring Boot 3.5.16 / Java 21 + Maven 的多数据源股票数据后端（yfinance/twelvedata/tiingo/tigeropen 四源 fallback 链补缺 + 模式筛选 + 截图导入 + MCP 工具暴露），数据采集经 `PythonScriptExecutor` 以"每请求一个 Python 子进程"方式桥接 4 个 Python 脚本；Vue3 + Naive UI + ECharts 前端；Flyway V1–V5 管理 MySQL。整体工程化意识较强：互斥锁、熔断冷却、乐观锁、恒定时间密钥比较、SSRF 白名单、进程输出截断等防御机制齐备，注释普遍带问题编号溯源。

| 维度 | 评分 | 一句话理由 |
|------|------|-----------|
| 架构 | 7.0 | controller→service→repository 分层严格、DTO 化彻底，但 `enums.dto` 包名错位、4 个核心协作类手工 `new`、README 与两份 cursor 规则文件互相矛盾且过期 |
| 安全 | 6.0 | 鉴权/SSRF/日志脱敏设计高于平均水平，但 Tiger 私钥真实泄露进 git 历史且仍会打进发布 jar，属硬伤 |
| 容量/性能 | 6.0 | 并发上限、信号量、输出截断等防御齐备，但 TwelveData 限速因进程架构完全失效、`cancel(true)` 无法杀僵尸任务、筛选长事务全量载入 |
| 可用性 | 7.0 | fallback 链/账户级熔断/重试退避/独立事务粒度设计成熟，卡点在 Python 30s 硬超时与脚本内退避预算冲突、线程池混用 |
| 可读性/可维护性 | 7.0 | 注释密度与溯源编号是亮点，但 GapFetcher 空行日志 + 每条目 INFO 洪泛、魔法数、常量两处重复定义 |
| 潜在 bug | 6.0 | CAS 互斥、乐观锁、原子更新等并发防护意识强，但存在 symbol 长度不一致、tigeropen 日期边界、`deleteOnExit` 泄漏、前端 null 崩溃等具体缺陷 |

**综合：6.5 / 10** —— 一个明显经过多轮认真整改的个人/内网项目，防御性设计成熟度超出同类项目，但有一处 P0 级凭证泄露和数条会实际造成“补不到数据 / 打爆配额”的 P1 缺陷。

---

## 二、按维度列出问题

### A. 架构

| 级别 | 文件:行号 | 问题描述 | 修复建议 |
|------|-----------|----------|----------|
| P2 | `src/main/java/com/stock/invest/service/impl/DataGapFillerServiceImpl.java:117-134` | FallbackChainBuilder / GapFetcher / MissingFieldFiller / RetryTaskProcessor 四个核心协作类在构造器中手工 `new`，非 Spring Bean：无法被 AOP/事务代理覆盖，测试只能整体构造，依赖关系隐藏 | 改为 `@Component` + 构造注入（保持包内可见性不变），或在此处注释说明"有意绕开容器" |
| P3 | `src/main/java/com/stock/invest/enums/dto/`（整个包） | DTO 全部放在 `enums.dto` 包下，包名与内容严重不符（ApiResponse、StockDailyBarDto 等都不是枚举） | 迁移到顶层 `dto` 包 |
| P3 | `src/main/java/com/stock/invest/controller/BarsController.java:45-56,63-103,109-115` | `getBars/queryBars/getSources` 返回 `ResponseEntity<Map<String,Object>>`，与项目统一的 `ApiResponse<T>` 约定（.cursorrules 第 5 条）不一致 | 统一改用 `ApiResponse.ok(...)` 包装 |
| P3 | `src/main/java/com/stock/invest/controller/AdminController.java:148-151` | `@RequestBody Map<String,Object>` + `parsePositiveInt` 手工解析参数，未用 DTO record（违反 .cursorrules DTO 规约） | 定义 `ScreenerAsyncRequestDto(Integer limit, Integer windowDays)` record + compact 构造器校验 |
| P3 | `.cursorrules:40` vs `.cursor/rules/stock-invest.mdc:46` | 两份规则文件互相矛盾：一处要求构造器注入、另一处要求 `@Autowired` 无构造器；且声称 Java 8 而 pom.xml:19 为 Java 21；`@Data` 用于实体的规则被项目实际（更优的 `@Getter/@Setter`）推翻 | 合并为一份规则并更新技术栈描述，实体规则改为 `@Getter/@Setter/@EqualsAndHashCode(onlyExplicitlyIncluded=true)` |
| P3 | `README.md:14,22,28-30` vs 实际 | README 严重过期：称 5 个 controller（实际 9 个）、python 目录仅 Tiger 脚本（实际 4 个）、`db/schema.sql`（实际已废弃为 `db/migration`）、`api-docs.md` 根目录链接（实际在 `docs/`） | 更新 README 与目录结构说明 |
| P3 | `service/impl/TradingCalendarFallback.java:19-22` vs `:41-44` | javadoc 声称"三个数据源：Tiger Java SDK / TigerOpen Python / Alpaca"，实际构造器只注册 2 个（Tiger Java SDK 已删除） | 更新注释 |
| P3 | `repository/SymbolBlacklistRepository.java:28`、`repository/StockDataSourcePriorityRepository.java:16`、`enums/dto/ScreeningMatchProjection.java`（全文件）、`model/TradingCalendarResult.java:47-50`、`client/TigerOpenPythonBridge.java:86-95`、`controller/AdminController.java:89` | 六处死代码：`deleteBySymbol`、`deleteBySymbolAndDataSource`、`ScreeningMatchProjection`、`defaultTradingDay()`、`listCandidates()` 全项目零生产引用；`limit = Integer.MAX_VALUE` 赋值后从未使用 | 全部删除 |

### B. 安全

| 级别 | 文件:行号 | 问题描述 | 修复建议 |
|------|-----------|----------|----------|
| **P0** | `src/main/resources/tiger_openapi_config.properties`（当前文件 + git 历史 commit `be27441`） | **真实 Tiger 凭证泄露**：该文件含真实 `private_key_pk1/pk8`、`tiger_id=20155300`、`account=910964`、`license=TBNZ`。虽在 `.gitignore` 中且已从 HEAD 移除（`eb3d9f9` 删除），但初始提交 `be27441` 的历史中可完整恢复（已实测 `git show be27441:src/main/resources/tiger_openapi_config.properties` 返回全部明文私钥）；且该文件当前仍位于 `src/main/resources/` → **每次 `mvn package` 都会把私钥打进发布 jar** | ① 立即到 Tiger 开放平台轮换私钥；② 用 `git filter-repo`/BFG 清除历史；③ pom `<resources>` 加 exclude 或将本地凭证文件移出 resources 目录（改用绝对路径外部配置） |
| P1 | `frontend/.env:2` + `frontend/src/api/request.ts:15-17` | `VITE_ADMIN_API_KEY` 经 Vite 构建会**明文内联进 JS bundle**（当前 dist 是未带 key 时构建的，一旦带 .env 重新 build 即泄露）——任何能打开页面的浏览器用户都可提取管理密钥，直接调用 `/api/admin/**`（触发筛选/补缺/清黑名单/全年日历抓取） | 管理操作改为后端 BFF/Session 鉴权，或明确此前端仅限本机单人使用并接受该前提 |
| P2 | `scripts/check_timestamp.py:8`、`scripts/clean_retry.py:15`、`scripts/verify_all.py:8` | mysql 命令行 `-p' + MYSQL_PASSWORD` 拼接——密码出现在进程列表（`ps` 可见）；且三处硬编码 `127.0.0.1:3307/-uroot` | 改用 `--defaults-extra-file` 凭证文件；host/port/user 参数化 |
| P3 | `config/WebConfig.java:26-28` | `/api/bars/**`、`/api/screening/**`、`/api/notification/**`、`/api/datasource/**` 读接口完全无鉴权；服务绑定后局域网可全量读业务数据（内网工具可接受，需明确边界） | 按需加只读 API key，或仅绑定 127.0.0.1 |
| P3 | `src/main/resources/python/stock_info_twelvedata.py:39-40` | TwelveData API key 拼入 URL 查询串，上游代理/访问日志可能记录完整 URL 造成泄露（TwelveData 主要支持 query param，属提示项） | 关注代理日志策略；密钥已用 `@ToString.Exclude` 保护（TwelveDataProperties.java:22-28 做得对） |

> 安全正面确认：恒定时间比较 + fail-closed（`IngestApiGuard.java:34-38`、`AdminAuthInterceptor.java:46-50`）、webhook SSRF 白名单 + 禁重定向（`WebhookUrlValidator.java` + `WebhookHttpConfig.java:36-38`）、Jackson Throwable mixin 防堆栈泄露（`JacksonConfig.java`）、actuator 仅 health 且 show-details=never（`application.yml:129-135`）——设计合格。

### C. 容量 / 性能

| 级别 | 文件:行号 | 问题描述 | 修复建议 |
|------|-----------|----------|----------|
| **P1** | `service/impl/TigerOpenCalendarService.java:63-71`、`service/impl/AlpacaCalendarService.java:61-70` | `future.cancel(true)` 对 `CompletableFuture.supplyAsync` **没有中断效果**（CompletableFuture 的 cancel 不打扰已在执行的任务）——注释声称“避免僵尸任务占用 scanExecutor 线程”实际做不到；日历查询超时后底层 HTTP/Python 任务继续占用与筛选/补缺共用的 scanExecutor（core 8/max 16/queue 50）线程，反复超时后僵尸线程耗尽线程池 → 全站 503 QUEUE_FULL | 改用可真正取消的执行方式（如 `HttpClient.sendAsync` 的 `cancel`，或把超时下沉到 HTTP client 本身——Alpaca 已有 15s 请求级超时，Python 桥接侧已由 executor 30s 杀进程兜底，实际上层 future 超时可缩短为“只放弃等待”） |
| **P1** | `src/main/resources/python/stock_info_twelvedata.py:21-31` | **限速器完全失效**：`last_request_time` 是进程内全局变量，而 Java 每次请求新起一个 Python 子进程 → elapsed 恒为巨大值、`rate_limit()` 从不 sleep，“Free tier 8 req/min” 形同虚设；补缺循环逐 symbol 调用会直接打爆 TwelveData 分钟配额（429） | 在 Java 侧（`TwelveDataStockServiceImpl` 或 `PythonScriptExecutor`）实现跨进程令牌桶；长期方案是把 Python 脚本服务化（常驻进程） |
| P2 | `service/impl/ScreeningServiceImpl.java:75-87,103` | `runScreening` 整体 `@Transactional`：全部 symbol×6 窗口评估 + 每窗口日历查询 + saveAll 都在一个长事务占用一个 DB 连接；且 `findByTradeDateBetweenOrderByTradeDateDesc`（`repository/StockDailyBarRepository.java:46-49`）把日期区间内**所有 symbol 的全部 bar** 一次性载入内存 | 评估阶段去事务化（只在 saveAll 时开短事务）；bar 查询按 symbol 分批 |
| P2 | `service/impl/ScreeningServiceImpl.java:448` | `isWindowConsecutiveTradingDays` 每个 symbol 每个窗口各查一次 `trading_calendar` 范围（约 200 symbol × 6 窗口 ≈ 1200 次查询，且都在上述长事务内） | 筛选开始前一次性取整个日期区间的日历并在内存判断 |
| P2 | `util/PythonScriptExecutor.java:124` | `Files.copy(resource.getInputStream(), tempFile, ...)` **未关闭** ClassPathResource 输入流——每次脚本执行泄漏一个文件句柄，依赖 GC 兜底；补缺一夜数百次执行会持续累积 | `try (InputStream in = resource.getInputStream()) { Files.copy(in, ...) }` |
| P2 | `logback-spring.xml:27-37` + `service/impl/GapFetcher.java:88,100,115-117,133,141` | RollingFileAppender 只有 `maxHistory=30`，**无 maxFileSize / totalSizeCap**；而 GapFetcher 每源每 symbol 打约 8 行 INFO（含 `log.info("")` 空行、每条 item 明细），单日日志可无限膨胀撑爆磁盘（logs 目录现已有 32MB） | 改 `SizeAndTimeBasedRollingPolicy`（如 100MB/天 + totalSizeCap 2GB）；删除空行日志，item 级明细降 DEBUG |
| P2 | `util/PythonScriptExecutor.java:35,53-56,106` + `src/main/resources/python/stock_info_yfinance.py:36,104` + `backoff max_time=30` | 执行器 30s 硬超时 vs 脚本内部随机 sleep 2-4s + yfinance 退避最长 30s：网络劣化时合法请求被强杀并记为瞬态失败；且信号量 `tryAcquire(timeoutSeconds)`（:106）与脚本执行共用 30s 预算，最坏排队 30s+执行 30s=60s，恰好击穿上层日历查询 60s 超时 | 分离“排队超时”与“执行超时”两个配置；执行超时上调至 60-90s，或压缩脚本内部退避预算 |
| P3 | `repository/DataFillTaskRepository.java:47-48` | `findRetryableTasks` 无 LIMIT：`retrying` 任务全部载入并逐个外部 API 重试，任务积压时单次调度耗时不可控 | 加 `Pageable` 限量（如 100/批） |
| P3 | `repository/StockDailyBarRepository.java:82-87` | `findUnchecked` / `findByFieldFillStatus` 依赖 `field_fill_status` 列但 V5 迁移未建索引，大表全表扫描 | `ALTER TABLE stock_daily_bar ADD KEY idx_sdb_field_fill_status (field_fill_status)` |

### D. 可用性

| 级别 | 文件:行号 | 问题描述 | 修复建议 |
|------|-----------|----------|----------|
| P2 | `service/OrchestrationService.java:33-42` + `service/impl/TigerOpenCalendarService.java:64` | **线程池混用自饥饿风险**：日历查询用 `supplyAsync(scanExecutor)` 提交，而编排/筛选/补缺长任务也占 scanExecutor 线程并在内部同步等待日历结果（`future.get(60s)`）——当 ≥8 个核心线程都被“等待日历的任务”占满且日历任务排进 queue 50 时，形成结构性自饥饿，全部 60s 超时返回 null → 补缺静默跳过当天 | 为日历查询建独立小线程池（如 `calendarExecutor` core 2）；或日历查询直接同步执行（本身已有 15s/30s 级下层超时） |
| P2 | `repository/TradingCalendarRepository.java`（`upsert` default 方法）+ `service/TradingCalendarDbService.java:66-67` | upsert 的“捕获 DataIntegrityViolationException 后重试一次”发生在外层 `transactionTemplate` 事务内：插入冲突已把事务标记 rollback-only，重试看似成功但**提交时抛 UnexpectedRollbackException**（并发窗口窄但真实存在；`StockDataSourcePriorityService.java:88-96` 特意用独立事务重试就是为了避开这个坑，此处没有） | 冲突重试放到 `REQUIRES_NEW` 新事务，或改用 MySQL 原生 `INSERT ... AS new ON DUPLICATE KEY UPDATE`（8.0.19+）/ H2 `MERGE` |
| P2 | `util/PythonRuntimeSupport.java:113-118` | `isPythonRunnable` 的 `process.waitFor()` **无超时**——解释器挂起时调用线程无限阻塞（该探活至少每 60s 被触发一次） | `waitFor(5, TimeUnit.SECONDS)` + 超时返回 false |
| P2 | `service/impl/DataGapFillerServiceImpl.java:158` | `findAllSymbols()` 全量载入后只处理前 200 个（`MAX_SYMBOLS_PER_RUN`），DB 返回顺序无业务语义——symbol 超过 200 后部分股票可能长期得不到补缺 | 按 `updated_at` 或上次补缺时间排序，做轮转（游标）公平调度 |
| P3 | `service/OrchestrationService.java:34-36` | `runWebhookUrls` 仅在链路终点/失败时清理，Hermes 中断接力后条目永久滞留，缓慢内存泄漏 | 加 TTL 或容量上限 |
| P3 | `service/ScreeningProgressService.java:11` + `controller/AdminController.java:129` vs `service/DataFillProgressService.java:20` | 两套进度语义不一致：筛选完成即删条目（最后一轮轮询拿到 `windows=[]`，前端瞬间抹掉 DONE 状态），补缺进度保留 24h TTL；且 ScreeningProgressMap 无 TTL 兜底（进程崩溃遗留条目） | 统一为“完成后短暂保留 + 定时 TTL 清理” |

### E. 可读性 / 可维护性

| 级别 | 文件:行号 | 问题描述 | 修复建议 |
|------|-----------|----------|----------|
| P2 | `service/impl/GapFetcher.java:76-80,88,100,133,141` | `log.info("")` 输出空行 + 每个 K 线 item 一行 INFO 明细（symbol/epoch/timeString/open/close 全打）——日志洪泛淹没有效信息（与 C-6 日志无上限问题叠加） | 删除全部空行日志；item 明细降为 DEBUG |
| P3 | `service/impl/PatternEvaluateServiceImpl.java:154` | `avgBeforeLast * 5.0 < lastVol` 魔法数 5.0 无命名无出处 | 提取常量 `VOLUME_SPIKE_MULTIPLIER` 并注释业务依据 |
| P3 | `service/impl/DataGapFillerServiceImpl.java:54` vs `service/impl/GapDateScanner.java:26`；`DataGapFillerServiceImpl.java:61` vs `MissingFieldFiller.java:37` | `MAX_LOOKBACK_DAYS=7`、`FILL_WINDOW_CALENDAR_DAYS=45` 各在两个类重复定义，改一处漏一处 | 收敛到常量类或由构造器注入 |
| P3 | `model/KLineIterator.java:63-76` | ① 类名"Iterator"实为数据项，严重误导；② `setTime(String)` 解析失败静默置 0 只留 System.Logger 警告，坏数据无声传播 | 重命名 `KLineItem`；解析失败时抛异常或显式标记 |
| P3 | `entity/TradingCalendarEntity.java`（@PrePersist/@PreUpdate + LocalDateTime）vs 其余实体（AuditingEntityListener + Instant） | 审计字段两套风格并存 | 统一 JPA Auditing |
| P3 | `pom.xml:117-122` | H2 以 `runtime` scope 打进生产 jar（仅测试需要），无谓增大构件 | 改 `<scope>test</scope>` |
| P3 | `config/TigerApiConfig.java:54` | 日志输出 `tiger_id={}`——账户标识符写入日志（半敏感） | 打码（如保留前 4 位） |

### F. 潜在 bug

| 级别 | 文件:行号 | 问题描述 | 修复建议 |
|------|-----------|----------|----------|
| **P1** | `src/main/resources/python/tigeropen_channel.py:79-80`（`_fetch_after_hours_close_range`，bars range 命令同界） | **端界排除目标日数据**：`begin_ms/end_ms` 取当日 00:00 ET——目标交易日的日线 bar（时间戳在盘中）与盘后分钟 bar（16:00-20:00 ET）全部 > `end_ms`，被范围查询结构性排除；运行日志佐证：`logs/stock-invest/` 中 tigeropen 的 `dateRange response target` 仅 2 条且均为 "NOT FOUND in 0 items"。对比其余三源均做了端界扩张（yfinance +1 天、tiingo SDK 含端日、twelvedata 显式 ±1 天并注明单日 400 问题） | `end_ms` 改为 `tradeDate+1 天 00:00`；补一条针对边界的集成测试 |
| **P1** | `config/TwelveDataProperties.java:24-28` + `service/impl/TwelveDataStockServiceImpl.java:170-176` + `README.md:40` | **多 API Key 轮换未实现**：`resolvedKeys()` 返回列表但 `apiKeyEnv()` 只取 `keys.get(0)`——配置注释“用于在限流时轮换”与 README“支持多 API Key 轮换”均不成立，配 key 也是白配 | 实现 429/失败时换 key 重试；或删除 `apiKeys` 配置项与相关文档声明 |
| P2 | `db/migration/V1__baseline.sql`（`symbol_blacklist.symbol VARCHAR(20)`、`stock_data_source_priority.symbol VARCHAR(20)`）vs `TigerWatchlistIngestServiceImpl.java:29`（SYMBOL_PATTERN 1-32）vs `stock_daily_bar.symbol VARCHAR(32)` | symbol 长度约束不一致：21-32 字符 symbol 截图导入成功后，黑名单写入（`GapFetcher.java:195`）与优先级写入（`GapFetcher.java:129`）会触发 DataIntegrityViolation（MySQL 严格模式），该 symbol 补缺永久失败且报错难懂 | 迁移统一为 VARCHAR(32)，或 SYMBOL_PATTERN 收紧到 20 |
| P2 | `frontend/src/views/ScreenerView.vue:549` | `row.lastClose.toFixed(4)` 与排序器 `a.lastClose - b.lastClose`：`lastClose` 在实体中可空（`ScreeningMatch.lastClose` 列 nullable），null 触发 TypeError 使整表渲染崩溃、排序返回 NaN | `(row.lastClose ?? 0).toFixed(4)`，排序器空值兜底 |
| P2 | `util/PythonScriptExecutor.java:126` | `deleteOnExit()` 每次执行向 JVM 关闭钩子注册列表**永久追加** File 对象（finally 已删文件但注册不撤销）——长期运行的服务进程缓慢内存泄漏 + 关闭时遍历巨量已删文件 | 删除 `deleteOnExit`，仅依赖 finally 中的删除 |
| P2 | `scripts/backfill_history.sql:8-13` | change_percent 回填 JOIN `DATE_SUB(s1.trade_date, INTERVAL 1 DAY)`（上一**自然日**）——周一/节假日后首个交易日永远 JOIN 不到记录，回填漏算 | 用自 JOIN 取上一交易日（窗口函数或相关子查询） |
| P2 | `src/test/resources/application-integration.yml:1-8` | 集成测试直连**生产 MySQL**（127.0.0.1:3307/stock_invest）：虽有 `@Tag("integration")` 默认排除（pom.xml:216-220），误传 `-Dgroups=integration` 即对生产库执行迁移与写入（FlywayMigrationIT / DataGapFillerIntegrationTest） | 建独立 `stock_invest_it` schema，每次测试前重建 |
| P2 | `src/main/resources/python/stock_info_yfinance.py:104-128` | `get_stock_list`/`scan_stocks` 依赖 `etf.holdings`——现代 yfinance 无此属性，每指数 5 次退避后必然失败，两条命令实为死路径；且 scan 每标的 sleep 2-4s 远超 30s 执行器超时 | 删除两条命令及 Java 侧对应死代码；或改用官方成分股源 |
| P3 | `service/impl/GapFetcher.java:151` | `fallbackChainBuilder.isSourceCooledDown(source.name());` 返回值被丢弃的死调用（紧接 `putCooldown`），其上注释还误导性地解释了这条废代码 | 删除该行及误导注释 |
| P3 | `service/impl/TigerWatchlistIngestServiceImpl.java:100-108` | “成交量与前一日完全相同即跳过”启发式有双重缺陷：`prevDate = tradeDate.minusDays(1)` 是自然日而非上一交易日（周一比对周日必空、周二遇周一休市也漏）；合法的相同成交量会被误杀 | 用 `findPreviousTradingDay` 取上一交易日；复核该规则的业务依据 |
| P3 | `src/main/resources/python/stock_info_yfinance.py:88-89` | `changePercent` 缺失时置 `0.0`——“无数据”与“零涨跌”语义混淆，下游无法区分 | 置 null/缺省键 |
| P3 | `src/main/resources/python/tigeropen_channel.py:56-57,89-90` | `except Exception: return {}` 静默吞掉盘后查询全部异常（含账户级错误），盘后合并无声失败 | 至少向 stderr 输出异常摘要 |
| P3 | `src/main/resources/python/stock_info_tiingo.py:111` | `datetime.now().date()` 用服务器本地时区（Asia/Shanghai）作窗口端点，跨时区边界时错位 1 天（窗口宽松 n*2+10 天，实际影响小） | 显式用 `ZoneInfo("America/New_York")` |
| P2 | 测试覆盖缺口：`service/OrchestrationService.java`（重试/接力/webhook 绑定核心逻辑）无任何单元测试（仅 `OrchestrationControllerAuthTest` 鉴权）；`TwelveDataStockServiceImpl`、`TradingCalendarDbService`、3 个 Scheduler 类无直接测试 | — | 补 OrchestrationService 单测（重试退避、chain=false、webhook 动态绑定三场景） |

> 其余验证通过、无需修复的高风险点（供参考）：`AdminController.triggerScreeningAsync` 完成后进度条目删除不会导致前端死轮询（`AdminController.java:205-213` 有 running=false 兜底）；`KLineIterator.getVolume()` 为原始 long 无 NPE；`DataFillProgressService` 有 24h TTL + 每小时兜底清理；`RetryTaskProcessor` 用原子 UPDATE + 乐观锁重放处理并发；`StockDataSourcePriorityService` 的冲突重试在独立新事务中（正确）；调度线程池已设 4（application.yml:8-11）避免长任务堵死调度。

---

## 三、问题统计汇总

| 维度 | P0 | P1 | P2 | P3 | 小计 |
|------|----|----|----|----|------|
| 架构 | 0 | 0 | 1 | 7 | 8 |
| 安全 | 1 | 1 | 1 | 2 | 5 |
| 容量/性能 | 0 | 2 | 5 | 2 | 9 |
| 可用性 | 0 | 0 | 4 | 2 | 6 |
| 可读性/可维护性 | 0 | 0 | 1 | 6 | 7 |
| 潜在 bug | 0 | 2 | 6 | 6 | 14 |
| **合计** | **1** | **5** | **18** | **25** | **49** |

---

## 四、优先修复清单（Top 10，按风险收益排序）

1. **【P0】轮换 Tiger 私钥并清除 git 历史**（`src/main/resources/tiger_openapi_config.properties`）：私钥在 commit `be27441` 中永久可取且每次打包进 jar——先轮换凭证，再 `git filter-repo` 清史，最后让构建产物排除该文件。这是唯一必须在“任何其他事”之前处理的项。
2. **【P1】修复 tigeropen 范围查询端界**（`tigeropen_channel.py:79-80`）：end 改为 `tradeDate+1 天`，否则该源补缺/盘后合并结构性拿不到目标日数据（日志已证 "NOT FOUND in 0 items"）。
3. **【P1】修掉 `future.cancel(true)` 的无效取消**（`TigerOpenCalendarService.java:69`、`AlpacaCalendarService.java:67`）+ 为日历查询独立线程池——消除 scanExecutor 僵尸线程与自饥饿，保住全站异步任务的可用性。
4. **【P1】Java 侧实现 TwelveData 跨进程限速 + 多 key 轮换**（`TwelveDataStockServiceImpl.java:170-176`、`stock_info_twelvedata.py:21-31`）：进程内限速已失效，配额打爆只是时间问题；顺带把宣称存在的轮换真正实现。
5. **【P1】停止把 admin key 交给浏览器**（`frontend/src/api/request.ts:15-17`）：管理面板鉴权改为服务端会话/BFF，或书面接受“单机单人”前提。
6. **【P2】统一 symbol 列宽 VARCHAR(32)**（V1 迁移 + 新增 V6 迁移）：消除 21-32 字符 symbol 在黑名单/优先级写入时的 DataIntegrityViolation 隐患。
7. **【P2】日志体系整改**（`GapFetcher.java` 空行/明细日志 + `logback-spring.xml` 加 maxFileSize/totalSizeCap）：当前 INFO 洪泛 + 无大小上限的组合是磁盘打爆的定时炸弹。
8. **【P2】`PythonScriptExecutor` 资源卫生**（`:124` 流未关、`:126` deleteOnExit 累积、`:106` 排队/执行超时分离；`PythonRuntimeSupport.java:117` waitFor 加超时）：三处小改消除长期运行的句柄/内存泄漏与挂死。
9. **【P2】筛选事务瘦身**（`ScreeningServiceImpl.java:75-87,448`）：去长事务 + 日历范围一次取回 + bar 分批载入——当前实现是“全表进内存 + 千次日历查询 + 单长事务”三连。
10. **【P2】补 OrchestrationService 单测 + 集成测试脱离生产库**（`application-integration.yml`）：编排重试/接力是无人测试的核心链路，而集成测试配置一念之差就写生产库。

**总体结论**：代码经过了多轮可辨识的认真整改（大量 P1-x/P2-x 编号注释与对应的真实修复），防御性设计成熟度高于绝大多数同规模项目；当前最需要的是——按上面 1-5 清掉一个 P0 和四个 P1 中的“数据拿不到 / 配额打爆 / 密钥外泄”三类实质风险，其余 P2 多为可控的工程质量债。
EXIT_CODE=0
