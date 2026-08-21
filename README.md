# 股票投资应用 (stock-invest)

Spring Boot 股票投资分析应用，支持从多个数据源获取股票数据并进行技术分析。

## 项目结构

```
stock-invest/
├── src/
│   ├── main/
│   │   ├── java/com/stock/invest/
│   │   │   ├── client/          # 数据源客户端（TigerOpen Python Bridge / Alpaca REST）
│   │   │   ├── config/          # 配置属性类（@ConfigurationProperties）、拦截器与安全配置
│   │   │   ├── constant/        # 常量（筛选窗口定义等）
│   │   │   ├── controller/      # REST API 控制器（9个）
│   │   │   ├── datasource/      # 数据源能力模型与可用性规则
│   │   │   ├── dto/             # 请求/响应 DTO（record，含统一 ApiResponse）
│   │   │   ├── entity/          # JPA 实体
│   │   │   ├── exception/       # 全局异常处理（GlobalExceptionHandler）
│   │   │   ├── mcp/             # MCP Server 工具（供 OpenClaw 调用）
│   │   │   ├── model/           # 领域模型（KLineData, StockInfo 等）
│   │   │   ├── repository/      # Spring Data JPA Repository
│   │   │   ├── scheduler/       # 定时任务（数据补缺 / 筛选 / 交易日历）
│   │   │   ├── security/        # API 鉴权（admin/ingest key）
│   │   │   ├── service/         # 服务接口 + 实现类（fallback 链补缺、筛选、字段增补等）
│   │   │   ├── util/            # 工具类（Python 脚本执行器等）
│   │   │   └── verifier/        # 数据库结构校验
│   │   └── resources/
│   │       ├── application.yml           # 主配置（多 profile）
│   │       ├── application-test.yml      # 测试配置（H2）
│   │       ├── python/                   # Python 桥接脚本（yfinance / twelvedata / tiingo / tigeropen）
│   │       ├── db/migration/             # Flyway 迁移脚本（V1~V5）
│   │       └── logback-spring.xml        # 日志配置
│   └── test/java/             # 单元测试与集成测试
├── pom.xml
├── README.md
└── docs/api-docs.md           # API 接口文档
```

## 数据源

| 数据源 | Profile | 说明 |
|--------|---------|------|
| **TwelveData**（默认） | `twelvedata` | REST API，支持多 API Key 轮换（429/限流时自动换 key 重试，`twelvedata.api.apiKeys` 逗号分隔配置） |
| **YFinance (Yahoo)** | `yfinance` | Python 桥接脚本 |
| **Tiger Brokers** | `tiger` | TigerOpen Python 通道 |
| **Tiingo** | — | 备选数据源，仅在主源失败时激活 |

补缺（DataGapFiller）链路由 `FallbackChainBuilder` 构建：优先按个股历史成功记录排序，
无历史记录时按默认优先级 **yfinance → twelvedata → tiingo → tigeropen** 链式回退；
账户级错误触发 30 分钟熔断冷却，≥2 源确认 not-found 的 symbol 自动入黑名单。

## 构建与启动

### 环境要求

- JDK 21+
- Maven 3.6+
- Python 3.8+（数据采集经 Python 子进程桥接四个数据源，非仅 Tiger）
- MySQL（生产环境）/ H2（测试环境）

### 构建

```bash
mvn clean package -DskipTests
```

### 启动

```bash
# 默认（twelvedata）
mvn spring-boot:run

# 指定数据源
mvn spring-boot:run -Dspring.profiles.active=tiger
mvn spring-boot:run -Dspring.profiles.active=yfinance
```

### 测试

```bash
mvn test
```

## API 概览

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/bars/single/query` | GET | 按股票代码查询日 K 线 |
| `/api/bars/pages/query` | GET | 分页查询 K 线（可按 symbol/tradeDate/source 筛选） |
| `/api/bars/{symbol}/candles` | GET | 蜡烛图数据（K线） |
| `/api/bars/sources` | GET | 数据源列表 |
| `/api/screening/latest`、`/by-date`、`/history`、`/batch/{batchId}` | GET | 筛选结果查询 |
| `/api/admin/trigger-screening-async` | POST | 异步全量筛选 |
| `/api/admin/run-screening-async` | POST | 异步高级筛选（{limit, windowDays}） |
| `/api/admin/trigger-data-fill` | POST | 异步触发数据补缺 |
| `/api/admin/trigger-retry-tasks` | POST | 触发失败任务重试 |
| `/api/admin/screening-progress` 等 | GET | 任务进度 / 补缺任务 / 统计查询 |
| `/api/blacklist/list`、`/clear` | GET/POST | 黑名单查询与清理 |
| `/api/datasource/status`、`/health` | GET | 数据源状态与健康检查 |
| `/api/notification/latest`、`/by-date`、`/history`、`/batch/{batchId}` | GET | 通知查询 |
| `/api/orchestration/step` | POST | 编排执行（webhook 接力） |
| `/api/ingest/tiger-watchlist` | POST | 导入老虎自选股截图数据 |
| `/api/v1/trading-calendar/is-open` 等 | GET/POST | 交易日历查询与全年抓取 |

详细 API 说明见 [docs/api-docs.md](docs/api-docs.md)。

## 配置

所有配置集中在 `application.yml`，通过 profile 切换数据源和环境。

关键配置项：

| 配置前缀 | 说明 |
|----------|------|
| `scanner.*` | 选股扫描参数（价格范围、候选数、定时） |
| `http.client.*` | HTTP 客户端超时、重试、代理 |
| `ingest.apiKey` | 截图导入接口鉴权（可选） |

## 近期的优化

### P0 — 删除冗余配置类
- 删除了 `AppConfig.java`（Spring Boot 自动配置 ObjectMapper）
- 删除了 `ApplicationConfig.java`（重复的 `@ComponentScan` + 引用了不存在的包）

### P1 — 精简样板代码
- 15 个 DTO / 配置属性 / 模型类改用 Lombok `@Data`/`@Getter`，消除 ~400 行 getter/setter
- `KLineRequest` 也纳入 Lombok（Builder 模式保留不变）
- `KLineItem` 因复杂的 `BigDecimal` 同步逻辑保留手动实现

### P2 — 接口拆分与清理
- `StockService` 拆分为 3 个领域接口：
  - `KLineService` — K 线数据查询
  - `StockInfoService` — 股票信息查询
  - `StockScannerService` — 股票筛选扫描
  - 原 `StockService` 保留作为统合接口，`PriorityStockServiceImpl（@Primary）`实现全部 4 个接口
- `TigerWatchlistRowDto` 合并 `symbol`/`code` 为单一 `symbol` 字段（`@JsonAlias` 兼容旧请求）
- `StockPatternUtil` 移除无人调用的 `matchesVolumePattern(Map)` 方法

### P3 — 配置与文档
- `application.yml` 消除了 profile 间默认值的冗余重复，移除无效属性 `spring.mandatoryFileEncoding`
- 添加 `spring-boot-configuration-processor` 依赖 → 自动生成 Spring Boot 配置元数据，消除 IDE YAML 未知属性警告
- 补充 `PythonScriptProperties` 使 `python.scripts.*` 配置可被 IDE 识别
- 新增 `docs/api-docs.md` 详细接口文档（老虎开放平台风格）
