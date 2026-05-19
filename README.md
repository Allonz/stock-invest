# 股票投资应用 (stock-invest)

Spring Boot 股票投资分析应用，支持从多个数据源获取股票数据并进行技术分析。

## 项目结构

```
stock-invest/
├── src/
│   ├── main/
│   │   ├── java/com/stock/invest/
│   │   │   ├── client/          # 数据源客户端（Yahoo / TwelveData / Tiingo / Tiger Python Bridge）
│   │   │   ├── config/          # 配置属性类（@ConfigurationProperties）
│   │   │   ├── controller/      # REST API 控制器（5个）
│   │   │   ├── dto/             # 请求/响应 DTO
│   │   │   ├── entity/          # JPA 实体
│   │   │   ├── enums/           # 枚举
│   │   │   ├── http/            # 弹性 HTTP 执行器
│   │   │   ├── model/           # 领域模型（KLineData, StockInfo 等）
│   │   │   ├── repository/      # Spring Data JPA Repository
│   │   │   ├── security/        # API 鉴权
│   │   │   ├── service/         # 服务接口 + 10个实现类
│   │   │   └── util/            # 工具类
│   │   └── resources/
│   │       ├── application.yml           # 主配置（多 profile）
│   │       ├── application-test.yml      # 测试配置
│   │       ├── python/                   # Python 脚本（Tiger OpenAPI 通道）
│   │       ├── db/schema.sql             # 数据库建表语句
│   │       └── logback-spring.xml        # 日志配置
│   └── test/java/             # 单元测试（10个）
├── pom.xml
├── README.md
└── api-docs.md                # API 接口文档
```

## 数据源

| 数据源 | Profile | 说明 |
|--------|---------|------|
| **TwelveData**（默认） | `twelvedata` | REST API，支持多 API Key 轮换 |
| **YFinance (Yahoo)** | `yfinance` | REST API，缓存支持 |
| **Tiger Brokers** | `tiger` | Java SDK (tigeropen) + Python 通道 |
| **Tiingo** | — | 备选数据源，仅在主源失败时激活 |

数据源通过 `PriorityStockServiceImpl`（`@Primary`）按优先级链式回退：tigeropen → tiger → yfinance → twelvedata → tiingo。

## 构建与启动

### 环境要求

- JDK 8+
- Maven 3.6+
- Python 3.8+（仅 Tiger 数据源需要）
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
| `/api/tiger/kline/daily/{symbol}` | GET | 获取日K线（JSON 字符串） |
| `/api/tiger/kline/daily/object/{symbol}` | GET | 获取日K线（对象） |
| `/api/tiger/stock/{symbol}` | GET | 获取股票信息 |
| `/api/tiger/scan/low-price-volume-pattern` | GET | 低价放量模式扫描 |
| `/api/scanner/custom` | POST | 自定义筛选 |
| `/api/ingest/tiger-watchlist` | POST | 导入老虎自选股截图数据 |
| `/api/screener/run-from-snapshot` | POST | 基于截图快照运行筛选 |
| `/api/screener/query` | GET | 查询筛选结果 |
| `/screening` | GET | 筛选页面（Thymeleaf） |

详细 API 说明见 [api-docs.md](api-docs.md)。

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
- `KLineIterator` 因复杂的 `BigDecimal` 同步逻辑保留手动实现

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
