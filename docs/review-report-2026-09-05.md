# stock-invest 代码评审报告（2026-09-05）

> 评审方式：只读评审 + 测试基线实测 + MySQL 对照验证。评审范围：后端 Java（114 文件 / 约 11k 行）、Python 桥接脚本（4 个）、前端（26 文件）、配置与迁移脚本。
> 严重度定义：**P0** 数据错误/安全漏洞 · **P1** 功能缺陷/可靠性风险 · **P2** 次要缺陷 · **P3** 建议。
> 每条问题均给出 `文件:行号` 证据；标【待验证】的为推测，需按给出的方法确认。

## 0. 验证基线

| 验证项 | 结果 |
|---|---|
| `mvn test`（H2 单测 + MySQL 集成，417 个测试） | 除 G-1 的 2 个陈旧类错误外全部通过；但 `mvn clean test` **编译失败**（见 G-1） |
| `pytest tests/`（Python 桥接） | 16 passed, 6 skipped |
| `npx vitest run`（前端） | 28 passed, 2 skipped（6 个测试文件） |
| MySQL 对照（8.0.46 @ 127.0.0.1:3307） | 集成 profile 每次从空库 DROP+CREATE `stock_invest_it`，Flyway V1~V7 在真实 MySQL 上干净执行，Hibernate `ddl-auto: validate` 通过。**结论：H2/MySQL 方言风险已被该机制系统性覆盖，未发现方言类缺陷** |
| 生产库只读抽查 | 仅执行 SELECT（黑名单表统计），未写入、未触碰 `stock_invest` 之外任何库 |

---

## A. 架构与设计 — ✅ 健康

**总评**：分层清晰（client / datasource / service / controller / mcp），数据源抽象对称，MCP 层正确复用 service 而非绕过，历史评审修复项落地质量高。

**健康面**：`DataSourceStrategy` 最小接口 + 4 实现自动收集（`FallbackChainBuilder.java:63-72`）；`AsyncConfig` 双线程池隔离（scan / calendar）有明确的自饥饿分析注释；`WebConfig.java:26-33` 鉴权路径注册集中。

- **A-1 (P3)** 控制器手工 Map 组装响应绕过 DTO 层。
  位置：`AdminController.java:425-439`、`TradingCalendarController.java:237-242`。
  影响：字段增减时编译器无法兜底（Map 无类型检查），与项目其余 8 个 controller 的 record DTO 风格不一致。建议：改用 record DTO。另 `BlacklistController` 是唯一不用 `ApiResponse` 包装的控制器（返回裸 `List`），响应协议不一致。

- **A-2 (P3)** 数据源实现类命名不对称：`YFinanceStockServiceImpl` / `TwelveDataStockServiceImpl` / `TigerOpenStockServiceImpl` vs `TiingoDataSourceStrategy`，三者统一 `*StockServiceImpl`、独一个 `*DataSourceStrategy`，新人检索成本高。

- **A-3 (P3)** README 结构描述过时：称 `application-test.yml` 在 `src/main/resources`（实际在 `src/test/resources`）、Flyway "V1~V5"（实际已到 V7）。见 H-3。

---

## B. 数据正确性与完整性 — ⚠️ 有隐患（最高优先级维度）

**总评**：清洗校验、幂等入库（`uk_stock_daily_bar_symbol_trade_date` + 先查后写 upsert）设计正确；但**多源复权口径无统一约束**与**黑名单永久隔离且已被垃圾 symbol 污染**两个问题会在真实行情下造成数据失真与补缺失效。

- **B-1 (P1)** 多数据源价格口径（复权/未复权）混存同一表，且 changePercent 跨源计算。
  证据：`GapFetcher.persist()` 将任一数据源的 OHLCV 直接写入同一行（`GapFetcher.java:221-228`）；yfinance 脚本**未显式传 `auto_adjust` 参数**（`stock_info_yfinance.py:108、207`，完全依赖库默认值——新版 yfinance 默认返回前复权价），而 twelvedata/tiingo 脚本直接取 API 原始 close（`stock_info_twelvedata.py:110`、`stock_info_tiingo.py:65`）；fallback 链意味着同一股票不同日期的 bar 可能来自不同口径的源。`GapFetcher.java:229-243` 还会用 DB 中前一日收盘（可能来自另一源）计算 changePercent。
  影响：股票发生拆股/分红后，yfinance 补的历史价与 twelvedata 时期的原始价在曲线上断裂，图表与筛选失真；这类错误**不可逆且用户难以察觉**。
  修复建议：所有脚本显式声明复权口径参数并在 `docs/` 记录决策；或 `stock_daily_bar` 增加 `adjustment` 列按源标注，图表层统一处理。

- **B-2 (P1)** 黑名单无过期机制，且已被垃圾 symbol 污染（生产库实证）。
  证据：`DataGapFillerServiceImpl.java:144-147` 无条件过滤 `status='active'`；`SymbolBlacklistService.java:35-37` 只查 active，无 TTL/自动恢复逻辑（`resetCount` 只在补缺成功时触发——但被拉黑的 symbol 已被过滤，**永远轮不到补缺，逻辑上死锁**）。生产库 `symbol_blacklist` 现有 **180 条 active**，最早 `first_404_date=2026-06-14`，其中 `NOT`、`WIN`、`LHSIN`、`XTNIT`、`MIYND` 等明显为截图导入产生的碎片 symbol（格式合法但语义错误，`SYMBOL_PATTERN = ^[A-Z0-9\-]{1,32}$` 无法拦截，`TigerWatchlistIngestServiceImpl.java:29`）。
  影响：真股票一旦在日历故障等场景被误拉黑（当轮全源空返回即 ≥2 源 not-found），**永久失去补缺资格**；垃圾 symbol 永久占用黑名单。
  修复建议：① `last_404_date` 超过 N 天（如 30 天）自动降级重试（一条调度任务即可）；② 手工清理现有垃圾条目；③ 截图导入考虑增加"待确认"状态而非直接入正式表。

- **B-3 (P2)** NaN 行未过滤，单行坏数据毒化整包响应。
  证据：yfinance 脚本 `float(row['Close'])` 对 NaN 不报错（`stock_info_yfinance.py:125-132`），`json.dumps` 默认输出 `NaN` 非法 token → Java Jackson 解析失败 → 整个响应作废。脚本错误分类里已存在 `PARSE_ERROR`/`"NaN"` 分支（`stock_info_yfinance.py:243-244`），**证明踩过此坑但只在异常分类侧缓解，未在数据侧修复**。
  修复建议：`hist.dropna(subset=["Open","High","Low","Close"])` 后再组装 items。

- **B-4 (P2)** 交易日历查不到时静默跳过该日期，补缺静默失效。
  证据：`GapDateScanner.java:76-79` `isOpen == null` 时直接 `continue`（既不算缺失也不尝试补）；`TradingCalendarDbService.java:79-81` 全部数据源不可用时返回 null 仅 `log.warn`。
 影响：日历源故障期间窗口内缺失日既不补数也不建 retry 任务，且无任何聚合告警——故障恢复后也不会自动补。
  修复建议：null 时保守按"交易日"处理（宁可多一次 API 调用不可漏补），或对 null 计数并在批次结束汇总告警。

- **B-5 (P3，需产品确认)** `min-price-threshold` 命名与语义反转，且实际补缺覆盖面极窄。
  证据：代码语义是"**最新收盘 > 2.10 则跳过补缺**"（`DataGapFillerServiceImpl.java:238-241`），即**只有 ≤$2.10 的低价股会被补缺**；而配置名与注释叫"最低价格阈值"（`GapFillProperties.java:18-20`）。git 考古确认方向自引入未变、三处文档一致、值经用户确认——**不是 bug**，但：① 名为 min 实为 max 的命名极易被后人当 bug "修反"；② 除截图导入外，正常股价股票的缺口在本系统内没有任何补缺路径（调度器仅此一处），请确认这是否为预期行为。
  建议：改名为 `max-price-threshold-for-gap-fill` 之类，或在注释中显式写明"仅对低价股补缺是设计意图"。

- **B-6 (P3)** 截图导入 high/low 缺失时回填 lastPrice（`TigerWatchlistIngestServiceImpl.java:96-97`），产生大量 doji 形态 bar，图表的影线分析失真。建议对回填行标记 provisional 或 missing_fields。

- **B-7 (P3)** 补缺窗口常量与注释不符：`GapFillConstants.MAX_LOOKBACK_DAYS = 7`，但 `DataGapFillerServiceImpl.java:281` javadoc 写 "today-30d"。实际只回看 7 天，历史空洞（含上轮失败遗留）依赖 retry 任务（仅保留 7 天）与每轮最多 5 个最新缺口（`GapDateScanner.java:27,92-94`）收敛，更老的洞无人认领。建议：注释改准；如需覆盖更长窗口调常量。

---

## C. 并发与事务 — ✅ 健康

**总评**：历史评审的修复（P1-2 互斥、P1-6 调度池、P1-1 管道排空、C-8 重试分批）落地到位，共享状态全部使用并发安全容器，事务边界全部为短事务。未发现竞态/死锁/长事务问题。

**健康面**：补缺/重试共用 `AtomicBoolean` 互斥（`DataGapFillerServiceImpl.java:79,117,340`）；筛选独立互斥（`ScreeningServiceImpl.java:54,79`）；调度池 `size: 4` 且注释说明了为什么（`application.yml` task.scheduling）；熔断冷却表 `ConcurrentHashMap`（`FallbackChainBuilder.java:40`）；乐观锁冲突重读重放（`RetryTaskProcessor.java:202-230`）；Python 子进程信号量限并发 + 双路排空防管道死锁（`PythonScriptExecutor.java:82-95,171-175`）。

- **C-1 (P3)** 熔断冷却表、Python 探活缓存均在 JVM 内存中——多实例部署时各实例独立熔断。当前单机部署可接受；上多实例时需外置（DB/Redis）。
- **C-2 (P3)** `isSourceCooledDown` 的 check-then-remove 竞态（`FallbackChainBuilder.java:83-85`）理论上两个线程可同时通过冷却检查——后果只是多一次注定失败的调用，良性，无需修。
- **C-3 (P3)** ingest 每行一次 `findBySymbolAndTradeDate` 查询（`TigerWatchlistIngestServiceImpl.java:91`），行数小时无所谓；若未来批量导入放大，可改 `IN` 查询预取。

---

## D. 性能与容量 — ✅ 健康（个人项目规模）

**总评**：查询全部命中 `(symbol, tradeDate)` 唯一约束与 symbol/tradeDate 索引；补缺批量有 200 symbol/次上限、重试 100 任务/批、字段增补 100 条/次；无 N+1 级别的失控路径。

- **D-1 (P3)** 每个 gap 日期的 `fetchAndPersist` 触发 1 次 `getPriorityList` 查询 + persist 内 2 次查询（`GapFetcher.java:84,212,233`）；极端晚高峰约 200×5=1000 个缺口 → ~3000 次小查询 + 1000 次 Python 子进程（每个 2-4s sleep）。单线程顺序执行约 1-2 小时，夜间窗口内可完成。若要提速，可按 symbol 缓存优先级列表。
- **D-2 (P3)** 前端 ECharts 数据量：后端有分页/单 symbol 查询（`BarsController`），未发现全量拉取路径。✅

---

## E. 安全 — ✅ 健康（同维度下质量最好的一章）

**总评**：鉴权 fail-closed、恒定时间比较、密钥零入库、无命令注入面、SSRF 有意识防护。作为本地个人项目，未发现 P0/P1。

**健康面**：`application.yml` 全部密钥走 `${ENV:}` 空默认（82,85,118,122,126 行）；`AdminAuthInterceptor`/`IngestApiGuard` 均用 `MessageDigest.isEqual` 恒定时间比较且未配置 key 时 503 fail-closed；actuator 仅暴露 health 且 `show-details: never`；未配置 CORS（同源策略）；`WebhookHttpConfig` 禁自动重定向防 SSRF 绕过 + 双超时；`PythonScriptExecutor` 用 ProcessBuilder **参数数组**（150-153 行）无 shell 拼接，API key 经**环境变量**传递不进 argv（ps 不可见）；repository 全部为派生查询/JPQL，无字符串拼接 SQL；`ApiLoggingInterceptor` 只记 method/path/status，不记头与参数；`getFillTasks` 排序字段白名单（`AdminController.java:404-415`）。

- **E-1 (P3)** `IngestApiGuard.verifyOptionalKey`（`IngestApiGuard.java:22`）名称带 "Optional" 但实际是**必选 fail-closed**（javadoc 已说明）。命名与行为相反，建议改名 `verifyKey`，避免后续维护者据名误改。
- **E-2 (P3)** 读端点无鉴权：`/api/bars/**`、`/api/screening/**`、`/api/blacklist/list`、`/api/datasource/status`、`/api/v1/trading-calendar/*`。本机部署可接受；若映射到公网（如内网穿透），需给读端点也加鉴权。
- **E-3 (P3)** `symbol_blacklist.source_errors`、`data_fill_task.last_error` 会原样透传到前端（含上游错误消息）——信息量不大，记录备查。

---

## F. 健壮性与故障处理 — ⚠️ 有隐患（轻微）

**总评**：超时全覆盖（Python 60s、Alpaca 10s/15s、webhook 3s/10s、日历 executor 自隔离、drain 5s 宽限），部分失败不中断批次，重试有三重止损（7 天过期/每日 5 次/30 分钟冷却）。主要残留问题是错误协议不统一。

- **F-1 (P2)** Python 脚本错误协议三套并存：`get_daily_kline` 返回 `{"error": "<字符串>"}`（`stock_info_yfinance.py:100`）；`get_daily_kline_range` 返回 `{"code":"NOT_FOUND","message":...}`（241-252 行）；tigeropen 用 `{"error":{"code","message"}}` 对象。Java 侧 `extractPythonError` 只认对象形式（`PythonScriptExecutor.java:310-328`），字符串形式的 error 退化为"通用失败"。
  影响：脚本侧精心分类的 not-found/account-level 语义在部分路径丢失 → 不计入黑名单（偏保守，安全）但 **P1-5 账户级熔断识别可能漏触发**，配额耗尽时继续打源。
  建议：统一为 `{"error":{"code","message"}}` 一个 schema，四个脚本对齐。

- **F-2 (P3)** 执行超时后 `destroy()` 与 `destroyForcibly()` 连发无宽限（`PythonScriptExecutor.java:182-183`），Python 无优雅退出机会；实际后果仅是子进程立即死，可接受。

---

## G. 测试质量 — ⚠️ 有一个 P1

**总评**：测试体系分层合理（H2 单测 → MySQL 集成 → pytest → vitest），集成测试从空库重建的对照机制优秀；但存在一个**clean 构建必红**的测试代码腐化。

- **G-1 (P1)** `mvn clean test` 编译失败：测试与主类构造函数不同步，此前靠陈旧 target 字节码掩盖。
  证据：`TigerWatchlistIngestServiceImplTest.java:43` 调用 3 参构造 `(repo, tradingCalendarDbService, txManager)`；主类 `TigerWatchlistIngestServiceImpl.java:35-39` 只有 2 参 `(repo, txManager)`。javap 确认编译产物与主类源码一致；`mvn clean test-compile` 实测报错 `cannot be applied to given types`（d73e1bf 重构时删掉了主类的 calendar 依赖，测试没跟上；本地一直用旧 test-classes 跑出"417 全绿"假象）。
 影响：任何 clean 构建/CI/他人克隆**必红**，掩盖真实测试结果。
  修复：测试去掉 `TradingCalendarDbService` mock、改 2 参构造；同时建议 CI 强制 clean 构建。

- **G-2 ✅** MySQL 对照验证结论：集成 profile 每次测试前 DROP+CREATE 空库（`IntegrationSchemaResetInitializer`），Flyway V1~V7 在真实 MySQL 8.0.46 干净执行 + Hibernate validate 通过。**H2 与生产 MySQL 的方言差异风险已被该机制系统性覆盖**——这是本项目测试体系最值得肯定的设计。无需另建对照库。

- **G-3 (P3)** 单测统一 mock 阈值 1.0（如 `DataGapFillerServiceImplTest.java:581`）而生产为 2.10，2.09/2.11 边界行为无测试覆盖。
- **G-4 (P3)** pytest 6 个 skipped（`@Tag("integration")` 类，如 AH-006），依赖真实 key 的路径平时不跑，建议保留一个手动触发的回归流程。

---

## H. 配置与工程化 — ⚠️ 有隐患（轻微）

- **H-1 (P2)** yfinance 版本三方漂移：`pyproject.toml`/`uv.lock` 钉死 `==1.3.0`，实际 `.venv` 装的是 **1.6.0**，`src/main/resources/python/requirements.txt` 又写 `>=0.2.36`。运行时经 `PythonRuntimeSupport` 用项目 .venv → **实际执行 1.6.0，锁文件形同虚设**。yfinance 大版本间抓取行为（复权默认值、字段名、限流）屡有变动，与 B-1 叠加会静默改变价格语义。
  修复：`uv sync`（或 `uv lock --upgrade` 后 sync）对齐 venv 与 lockfile；requirements.txt 删除或改为由 uv export 生成。
- **H-2 (P3)** `mysql-connector-j` 显式钉 8.0.33（pom.xml），Spring Boot 3.5 BOM 有管理版本——建议交给 BOM，避免连接器与服务端(8.0.46)/BOM 三方错位。
- **H-3 (P3)** README 过时两处（A-3 已述）；`docs/api-docs.md` 与实际端点的同步情况未逐条核对（待验证：抽查 2-3 个端点即可）。
- Flyway V1~V7 命名与只追加纪律 ✅（`baseline/create/decimal/align/capability/varchar/index`）。

---

## I. 可观测性 — ✅ 健康

**总评**：结构化 `[组件]` 前缀日志覆盖完整，关键路径（批次开始/结束/汇总、熔断、黑名单、慢请求）均有输出，stderr 噪声有识别与降级。

- **I-1 (P3)** 缺一个"汇总健康度"的告警钩子：黑名单已涨到 180 条且含垃圾数据（B-2），但没有任何指标或通知暴露这个增长趋势。项目已有 screening webhook 通知通道，复用它对"黑名单周增量 / 补缺失败率 / 日历 null 计数"做周报即可，成本很低。

---

## J. 代码质量与可维护性 — ✅ 健康

- **J-1 (P3)** `AdminController` 465 行 / 11 端点，是唯一接近"上帝控制器"的类；`MissingFieldFiller` 514 行但职责单一，可不动。建议 Admin 按补缺/筛选/进度拆两个。
- **J-2 (P3)** 评审痕迹注释密度偏高（大量 "2026-08-13 评审 F-11"、"P1-2" 标注）。短期有助于溯源，长期建议把"评审结论"收敛进 docs，代码注释只留约束说明。
- **J-3 (P3)** 根目录游离脚本 `analyze_dead.py`（已 gitignore）：移入 `scripts/` 或删除。
- 三个 Python 脚本的 changePercent 计算与 item 组装重复——脚本各自独立运行，可接受。

---

## Top 10 优先修复清单

| # | 问题 | 严重度 | 预估工作量 |
|---|---|---|---|
| 1 | G-1 修复 `TigerWatchlistIngestServiceImplTest` 构造函数不同步，恢复 clean 构建 | P1 | 10 分钟 |
| 2 | B-2 黑名单加 TTL 自动过期 + 清理生产库 180 条中的垃圾 symbol | P1 | 半天 |
| 3 | B-1 统一多源复权口径（脚本显式 auto_adjust + 决策入 docs） | P1 | 半天+验证 |
| 4 | B-3 yfinance 脚本 dropna 过滤 NaN 行 | P2 | 15 分钟 |
| 5 | B-4 日历 null 改为保守补缺或聚合告警 | P2 | 1 小时 |
| 6 | H-1 `uv sync` 对齐 yfinance 版本，消除 lockfile/venv 漂移 | P2 | 15 分钟 |
| 7 | F-1 四个 Python 脚本统一错误 JSON schema | P2 | 2 小时 |
| 8 | E-1 `verifyOptionalKey` 改名 `verifyKey` | P3 | 5 分钟 |
| 9 | B-5 确认"仅低价股补缺"是否预期，改名或文档固化 | P3 | 确认 5 分钟 |
| 10 | A-3/H-3 README 更新（V1~V7、test yml 路径） | P3 | 15 分钟 |

依赖关系：#3 依赖 #6 先行（版本对齐后才能谈口径）；#2 的垃圾 symbol 清理可与 #1 同批做。

## 值得肯定的设计（Top 5）

1. **空库重建式集成测试**：`stock_invest_it` 每次从零迁移，天然完成 H2/MySQL 方言对照（G-2），本次评审实测有效。
2. **Python 执行器加固**：参数数组、信号量限并发、双路排空防管道死锁、超时强杀、探活缓存（`PythonScriptExecutor` 全文）。
3. **鉴权细节**：恒定时间比较、fail-closed、排序白名单、密钥全环境变量零入库。
4. **失败止损三层设计**：熔断冷却（源级）/ 黑名单（symbol 级）/ 重试三重止损（任务级），职责分明。
5. **评审修复闭环**：注释中大量 P1-x/F-x 标记与 git 历史对应，历史问题可追溯——本次发现的 G-1 正是闭环中唯一的漏网环节。

## 待验证项汇总

| 编号 | 待验证内容 | 验证方法 |
|---|---|---|
| B-1 | venv 实际 yfinance 1.6.0 下 `history()` 的 auto_adjust 默认值与 1.3.0 是否一致 | `.venv/bin/python -c "import yfinance,inspect; print(yfinance.__version__); ..."` 对同一 symbol 对比两版本输出 |
| B-5 | "仅 ≤$2.10 股票补缺"是否为预期（正常股票缺口无补缺路径） | 与产品意图对照（只有你能确认） |
| B-2 | 180 条 active 黑名单中真退市与垃圾 symbol 的比例 | 抽 20 条到各数据源人工核实 |
| H-3 | `docs/api-docs.md` 与实际端点是否同步 | 抽查 2-3 个端点对比 |
| E-2 | 应用当前是否暴露到本机之外（公网/内网穿透） | 检查部署方式 |

## 附：评审环境说明

- 测试全部实跑：Maven（H2 单测 + MySQL 集成）、pytest、vitest；MySQL 对照复用项目自带集成测试机制，未新建库、未写入生产库、评审后无需清理。
- 评审为只读：未修改任何源码、配置或数据。

---

## 修复记录（2026-09-05 第二轮）

同日按评审结论完成一轮修复，覆盖 A-1/A-2/A-3、C-1/C-2/C-3、F-1/F-2、G-1/G-3/G-4、H-1/H-2/H-3、J-3，共 13 项（B/D/E/I 维度问题未在本轮范围）。**修复后验证：`mvn clean test` 420 全绿（BUILD SUCCESS，G-1 的 clean 构建失败已消除）、pytest 16 passed、vitest 28 passed + vue-tsc 通过。**

| 编号 | 修复方式 | 变更文件 |
|---|---|---|
| G-1 | 测试改用主类 2 参构造，删除多余 mock；clean 构建恢复绿色 | `TigerWatchlistIngestServiceImplTest.java` |
| C-2 | `isSourceCooledDown` 改为 `computeIfPresent` 单次原子操作 | `FallbackChainBuilder.java` |
| C-3 | ingest 预取：一次 IN 查询替代逐行查询；同请求重复 symbol 复用同一 bar 对象，**顺带修复原实现的唯一约束冲突隐患** | `TigerWatchlistIngestServiceImpl.java`、`StockDailyBarRepository.java` |
| F-1 | 四个 Python 脚本统一错误协议 `{"error":{"code","message"}}`（NOT_FOUND/ACCOUNT_LEVEL/PARSE_ERROR/USAGE_ERROR/PYTHON_ERROR）；Java 侧新增共享解析器 `StockDataException.fromPythonError`（code 优先于消息关键词），删除三处重复的 `extractErrorFromJson` | `stock_info_{yfinance,twelvedata,tiingo}.py`、`tigeropen_channel.py`、`StockDataException.java`、三个策略类 |
| F-2 | 超时终止改为 destroy → 3s 优雅宽限 → destroyForcibly | `PythonScriptExecutor.java` |
| A-1 | 新增 `FillTaskItemDto`/`FillTaskPageDto`/`TradingCalendarItemDto`/`FullYearSyncResultDto` 四个 record，替代手工 Map 组装（字段名/顺序与原 JSON 一致）；`BlacklistController` 统一 ApiResponse 包装，前端 `BlacklistView.vue` 同步解包；顺带修复存量 `bars.ts` 类型声明错误（vue-tsc 因此恢复通过） | 3 controller、dto/、`BlacklistView.vue`、`bars.ts` |
| A-2 | `TiingoDataSourceStrategy` → `TiingoStockServiceImpl`（git mv 保留历史），命名与另外三源对齐 | 类 + 测试同名迁移 |
| G-3 | 新增 2.11/2.09/2.10 三档阈值边界测试（2.10 恰等于阈值时确认不跳过） | `DataGapFillerServiceImplTest.java` |
| G-4 | 新增 `scripts/run-integration-tests.sh` 手动集成回归入口 | scripts/、README |
| H-1 | yfinance 声明对齐实际运行版本：pyproject/uv.lock → 1.6.0（运行时零变化）；requirements.txt 改为 `uv export` 生成的精确锁定清单；顺带补上缺失的 `[dependency-groups] dev`（pytest 此前从未被声明，靠手工 pip 安装） | `pyproject.toml`、`uv.lock`、`requirements.txt` |
| H-2 | **结论修正**：复核发现 8.0.33 是 `docs/optimization-plan.md` 0.2 节文档化的依赖冻结决策，"交由 BOM"建议不当；改为在 pom 依赖处补注释固化决策，防止后人重复误报 | `pom.xml` |
| H-3 | README 修正 test yml 路径、Flyway V1~V7；新增"部署假设"一节固化 C-1 单实例前提 | `README.md` |
| C-1 | 同 H-3 文档化（javadoc + README 部署假设），熔断表暂不外置 | `README.md` |
| J-3 | `analyze_dead.py` 移入 `scripts/`，.gitignore 同步 | scripts/、.gitignore |
