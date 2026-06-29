# K 线蜡烛图功能 — 完整测试方案

## 测试原则

1. **每条测试用例必须支持双模式运行**：Mock 模式（CI/离线）和 Real 模式（连接真实数据源验证）
2. **Mock 模式**：断言行为正确性（字段映射、异常处理、边界条件）
3. **Real 模式**：验证实际 API/数据库端到端链路的正确性
4. **覆盖率目标**：Java 核心逻辑 90%+，Python 脚本 90%+，前端关键路径 90%+

---

## 测试范围总览

| 层级 | 测试对象 | 方法 | 数据源模式 | 覆盖率目标 |
|------|---------|------|-----------|-----------|
| 实体/模型 | StockDailyBar, StockDailyBarCandleDto, KLineIterator | 单元测试 | Mock | 行 100% |
| Service | StockDailyBarService.getRecentCandles() | 单元测试 | Mock Repository | 分支 100% |
| Service | DataGapFillerServiceImpl.persist() + mergeAfterHoursIfAvailable() | 单元测试 | Mock Repository + Mock Tiger SDK | 分支 100% |
| Service | DataGapFillerServiceImpl.fetchAndPersist() 日志改进 | 单元测试 | Mock Repository | 日志格式验证 |
| Controller | BarsController GET /api/bars/{symbol}/candles | 单元测试 + 集成 | Mock MVC + 真实 DB | 分支 100% |
| **数据源 — YFinance** | `stock_info_yfinance.py` get_stock_info() | 单元 + 集成 | Mock yfinance + 真实 yfinance | 分支 95%+ |
| **数据源 — TwelveData** | `stock_info_twelvedata.py` get_stock_info() + `TwelveDataRestClient` | 单元 + 集成 | Mock HTTP + 真实 API | 分支 95%+ |
| **数据源 — Tiingo** | `TiingoRestClient` + `TiingoDataSourceStrategy.getStockInfo()` | 单元 + 集成 | Mock HTTP + 真实 API | 分支 95%+ |
| **数据源 — Tiger SDK** | `TigerStockServiceImpl` + `getAfterHoursKLineDataByDateRange()` | 单元 + 集成 | Mock TigerClient + 真实 SDK | 分支 90%+ |
| **数据源 — TigerOpen** | `tigeropen_channel.py` afterhours_bars + `TigerOpenPythonBridge` | 单元 + 集成 | Mock tigeropen + 真实 SDK | 分支 90%+ |
| 日志改进 | 5 个文件的日志追加 | 单元验证 | Mock Logger | 日志格式正确性 |
| 前端 API | `bars.ts` fetchCandles | 单元 + 集成 | Mock Axios + 真实后端 | 分支 100% |
| **前端页面** | ScreenerView ECharts K 线交互 | E2E + 组件 | Mock API + 真实后端 | 全流程覆盖 |
| **行情页面** | MarketView 5 字段展示 | E2E + 单元 | Mock API + 真实后端 | 字段可见性 |
| 数据库 | ALTER TABLE + SQL 回填 | 集成测试 | 真实 MySQL | 字段存在性 |

---

## 一、后端单元测试（Mock 模式）

### 1.1 StockDailyBarCandleDto 构造测试

**文件**: `src/test/java/com/stock/invest/enums/dto/StockDailyBarCandleDtoTest.java`

| TC-ID | 用例 | 输入 | 预期输出 |
|-------|-----|------|---------|
| DTO-001 | 全字段构造 | date="2026-06-22", open=100.0, high=105.0, low=98.0, close=102.0, changePercent=2.0, afterHours=101.0, afterHoursChangePercent=-0.98, volume=1000000L | 9 个字段值与输入一致 |
| DTO-002 | afterHours 为 null | afterHours=null, afterHoursChangePercent=null | null 字段正确存储 |
| DTO-003 | changePercent 为 0 | changePercent=0.0 | 值为 0 |
| DTO-004 | volume 为 0 | volume=0L | 值为 0 |
| DTO-005 | 盘后字段全 null | afterHours=null, afterHoursChangePercent=null | 两个字段均为 null |

### 1.2 StockDailyBar 实体新增字段测试

**文件**: `src/test/java/com/stock/invest/entity/StockDailyBarFieldTest.java`

| TC-ID | 用例 | 输入 | 预期输出 |
|-------|-----|------|---------|
| ENT-001 | highPrice set/get | setHighPrice(105.5) | getHighPrice() = 105.5 |
| ENT-002 | lowPrice set/get | setLowPrice(98.3) | getLowPrice() = 98.3 |
| ENT-003 | changePercent set/get | setChangePercent(2.15) | getChangePercent() = 2.15 |
| ENT-004 | afterHours set/get | setAfterHours(101.2) | getAfterHours() = 101.2 |
| ENT-005 | afterHoursChangePercent set/get | setAfterHoursChangePercent(-0.85) | getAfterHoursChangePercent() = -0.85 |
| ENT-006 | changePercent nullable | setChangePercent(null) | getChangePercent() = null |
| ENT-007 | afterHours nullable | setAfterHours(null) | getAfterHours() = null |
| ENT-008 | afterHoursChangePercent nullable | setAfterHoursChangePercent(null) | getAfterHoursChangePercent() = null |

### 1.3 KLineIterator 新增字段测试

**文件**: `src/test/java/com/stock/invest/model/KLineIteratorFieldTest.java`

| TC-ID | 用例 | 输入 | 预期输出 |
|-------|-----|------|---------|
| KLI-001 | changePercent set/get | setChangePercent(3.5) | getChangePercent() = 3.5 |
| KLI-002 | afterHours set/get | setAfterHours(99.5) | getAfterHours() = 99.5 |
| KLI-003 | afterHoursChangePercent set/get | setAfterHoursChangePercent(-1.2) | getAfterHoursChangePercent() = -1.2 |
| KLI-004 | 默认值 | new KLineIterator() | 三个新字段均为 0.0 |
| KLI-005 | toString 包含新字段 | changePercent=3.5, afterHours=99.5, afterHoursChangePercent=-1.2 | toString() 含 "changePercent=3.5" "afterHours=99.5" "afterHoursChangePercent=-1.2" |
| KLI-006 | 构造器含新字段 | new KLineIterator("AAPL", 1000L, 1.0, 2.0, 0.5, 1.5, 1000L, 1500.0, 3.5, 99.5, -1.2) | 全部 11 参数正确赋值 |

### 1.4 StockDailyBarService.getRecentCandles() 测试

**文件**: `src/test/java/com/stock/invest/service/StockDailyBarServiceTest.java`

**方法**: Mock `StockDailyBarRepository`

**Real 模式**: 连接真实 MySQL 数据库，查询真实数据验证

| TC-ID | 用例 | Mock 数据 | 参数 | 预期 | 覆盖路径 |
|-------|-----|----------|------|------|---------|
| SVC-001 | 7 条数据，changePercent 有值 | 7 条含完整字段 | AAPL, 7 | 返回 7 条，日期正序，changePercent 用数据库值 | 主路径 |
| SVC-002 | changePercent 为 null 时透传 | 7 条，changePercent=null | AAPL, 7 | 返回 7 条，DTO 中 changePercent=null | null 透传 |
| SVC-003 | 数据库返回 >7 条 | 10 条 | AAPL, 7 | 只返回最近 7 条 | subList 截断 |
| SVC-004 | 数据库返回 <7 条 | 3 条 | AAPL, 7 | 返回全部 3 条 | 不足截断 |
| SVC-005 | 空结果 | 空列表 | UNKNOWN, 7 | 返回空列表 | 空集合 |
| SVC-006 | afterHours 有值透传 | afterHours=101.5 | AAPL, 7 | DTO 中 afterHours=101.5 | 非 null |
| SVC-007 | afterHours 为 null 透传 | afterHours=null | AAPL, 7 | DTO 中 afterHours=null | null 透传 |
| SVC-008 | 单条数据 | 1 条 | AAPL, 7 | 返回 1 条 | 单元素路径 |
| SVC-009 | **Real 模式：真实 DB 查询** | 连接 MySQL 查询 AAPL | AAPL, 7 | 返回含 highPrice/lowPrice/changePercent 的 DTO | End-to-end |

### 1.5 BarsController GET /candles 端点测试

**文件**: `src/test/java/com/stock/invest/controller/BarsControllerCandlesTest.java`

**Mock 方法**: `@WebMvcTest(BarsController.class)` + `@MockBean(StockDailyBarService.class)`

**Real 方法**: Spring Boot 集成测试，启动完整上下文，连接真实 MySQL

| TC-ID | 用例 | Mock 数据 | 请求 | 预期 |
|-------|-----|----------|------|------|
| CTRL-001 | 正常 3 条，含 changePercent | 3 条 DTO（含 changePercent=2.0, afterHours=101.0） | `GET /api/bars/AAPL/candles?days=7` | 200，data.length=3，第 1 条含 changePercent=2.0 |
| CTRL-002 | 默认 days=7 | 7 条 DTO | `GET /api/bars/AAPL/candles` | 200，data.length=7 |
| CTRL-003 | 自定义 days=10 | 10 条 DTO | `GET /api/bars/AAPL/candles?days=10` | 200，data.length=10 |
| CTRL-004 | 空结果 | 空列表 | `GET /api/bars/UNKNOWN/candles?days=7` | 200，data=[] |
| CTRL-005 | Service 异常 | 抛 RuntimeException | `GET /api/bars/X/candles` | 500 |
| CTRL-006 | days=1（边界） | 1 条 DTO | `GET /api/bars/AAPL/candles?days=1` | 200，data.length=1 |
| CTRL-007 | **Mock: 返回字段完全性验证** | DTO 含 all 9 fields | `GET /api/bars/AAPL/candles` | 响应 JSON 包含 date/open/high/low/close/changePercent/afterHours/afterHoursChangePercent/volume |
| CTRL-008 | **Mock: afterHours=null** | 第 2 条 afterHours=null | `GET /api/bars/AAPL/candles` | JSON 中第 2 条 afterHours 字段为 null |
| CTRL-009 | **Real: 真实 DB + 真实 Service** | 无 Mock，启动完整 Spring Context | `GET /api/bars/AAPL/candles?days=7` | 200，返回来自真实数据库的 K 线数据，含 high/low/changePercent |
| CTRL-010 | **Real: 连接真实数据源验证** | 无 Mock | `GET /api/bars/AAPL/candles` | 验证 changePercent 与数据库中一致 |

### 1.6 DataGapFillerServiceImpl.persist() 测试

**文件**: `src/test/java/com/stock/invest/service/DataGapFillerPersistTest.java`

**Mock 方法**: Mock `StockDailyBarRepository`，验证 save() 调用参数

**Real 方法**: 连接真实 MySQL，验证数据持久化结果

| TC-ID | 用例 | KLineIterator 数据 | 预期 |
|-------|-----|------------------|------|
| FILL-001 | 新记录写全部字段 | open=1.0, high=1.2, low=0.8, close=1.1, changePercent=10.0, afterHours=1.05, afterHoursChangePercent=-4.55, volume=100000 | save() 被调用，bar 含完整 9 字段 |
| FILL-002 | 更新已有记录 | 同 symbol+tradeDate 已存在 | save() 调用，changePercent 被覆盖 |
| FILL-003 | changePercent=null 透传 | item.getChangePercent()=null | bar.setChangePercent(null) |
| FILL-004 | afterHours/afterHoursChangePercent 为 0 透传 | item 中 afterHours=0.0 | bar.setAfterHours(0.0) |
| FILL-005 | **日志格式验证** | 任意有效数据 | 日志输出包含 "high=" "low=" "changePct=" "afterHours=" "afterHoursChg=" |
| FILL-006 | **Real: 真实 DB 验证** | 调用真实 persist() | 查询 MySQL，确认 5 个字段已正确写入 |

### 1.7 DataGapFillerServiceImpl.mergeAfterHoursIfAvailable() 测试

**文件**: `src/test/java/com/stock/invest/service/DataGapFillerAfterHoursTest.java`

**Mock 方法**: Mock `TigerStockServiceImpl` + Mock `StockDailyBarRepository`

**Real 方法**: 连接真实 Tiger SDK（需凭证），调用真实盘后价接口

| TC-ID | 用例 | 数据源 | Mock 数据 | 预期 |
|-------|-----|-------|----------|------|
| AH-001 | Tiger SDK 有盘后数据 | tiger | afterHours Kline: close=102.5, regularClose=100.0 | afterHours=102.5, afterHoursChangePercent=2.5%, save() 被调用 |
| AH-002 | Tiger SDK 无盘后数据 | tiger | afterHours Kline: items 为空 | 不调用 save()，afterHours 保持 null |
| AH-003 | 非 Tiger 数据源跳过 | tiingo/yfinance | — | mergeAfterHours 立即返回，不调用 Tiger SDK |
| AH-004 | Tiger 数据源盘后价计算 | tiger | regularClose=100.0, ahClose=99.0 | afterHoursChangePercent = -1.0% |
| AH-005 | TigerOpen 数据源盘后合并 | tigeropen | afterHours Kline: close=103.0 | afterHours=103.0，changePercent 正确计算 |
| AH-006 | **Real: 真实 Tiger SDK** | tiger | 调用真实 getAfterHoursKLineDataByDateRange("AAPL", today) | 返回合法 KLineData（不论是否有盘后交易） |
| AH-007 | afterHours API 异常 | tiger | 抛异常 | mergeAfterHours 打印 warning，不传播异常 |

### 1.8 TiingoDataSourceStrategy.getStockInfo() 测试

**文件**: `src/test/java/com/stock/invest/datasource/TiingoStockInfoTest.java`

**Mock 方法**: Mock `TiingoRestClient`

**Real 方法**: 连接真实 Tiingo API

| TC-ID | 用例 | Mock 数据 | 预期 |
|-------|-----|----------|------|
| TG-INFO-001 | 2+ 条数据，标准涨跌幅计算 | items: [{close=102.0}, {close=100.0}(prev), ...] | changePercent=(102-100)/100*100=2.0 |
| TG-INFO-002 | 1 条数据（无前一日） | items: [{close=102.0}] | changePercent=0（无法计算） |
| TG-INFO-003 | open=0 防御 | items: [{close=102.0, open=0.0}] | 不抛除零异常 |
| TG-INFO-004 | **Real: 真实 Tiingo API** | 调用 fetchDailyBars("AAPL", 5) | 返回含 high/low 的数据 |
| TG-INFO-005 | **Real: 涨跌幅计算验证** | 真实数据，手动验证 | changePercent ≈ (close - prevClose) / prevClose * 100 |

### 1.9 TigerStockServiceImpl.getAfterHoursKLineDataByDateRange() 测试

**文件**: `src/test/java/com/stock/invest/service/TigerAfterHoursTest.java`

**Mock 方法**: Mock `TigerHttpClient`

**Real 方法**: 连接真实 Tiger SDK（需 API 凭证）

| TC-ID | 用例 | Mock 数据 | 预期 |
|-------|-----|----------|------|
| TIG-AH-001 | Mock: 正常返回盘后 K 线 | 构造 QuoteKlineResponse，含 KlineItem | KLineData 含 items，symbol 正确 |
| TIG-AH-002 | Mock: 响应为空 | response 为 null | 返回空 KLineData |
| TIG-AH-003 | Mock: 响应失败 | response.isSuccess()=false | 返回空 KLineData |
| TIG-AH-004 | Mock: 异常处理 | client.execute 抛异常 | 返回空 KLineData，日志记录 warning |
| TIG-AH-005 | **Real: 真实 Tiger SDK** | 调用真实 getAfterHoursKLineDataByDateRange("AAPL", tradeDate) | 返回合法 KLineData（可能有 item 也可能空） |
| TIG-AH-006 | **Real: 区分 Regular vs AfterHours** | 对比 getDailyKLineDataByDateRange vs getAfterHoursKLineDataByDateRange | 两调用返回不同的 KLineData 实例 |

### 1.10 TigerStockServiceImpl.convertTigerKlinePointToKLineIterator 测试

**文件**: `src/test/java/com/stock/invest/service/TigerStockServiceKlineTest.java`

| TC-ID | 用例 | Mock 数据 | 预期 |
|-------|-----|----------|------|
| KLINE-PT-001 | 正常点位转换 | KlinePoint: open=1.0, high=1.2, low=0.8, close=1.1, volume=100000, amount=110000 | KLineIterator: open=1.0, high=1.2, low=0.8, close=1.1, changePercent=0.0 |
| KLINE-PT-002 | volume=0 边界 | KlinePoint: volume=0 | KLineIterator: volume=0 |
| KLINE-PT-003 | 盘后字段默认 0 | — | convertTigerKlinePointToKLineIterator 输出的 KLineIterator 中 afterHours=0.0 |

---

## 二、数据源层测试（Mock + Real 双模式）

所有数据源测试必须同时提供 Mock 和 Real 两种测试方法。Mock 测试通过注解/flag (`@Tag("mock")` / `@Tag("integration")`) 区分。

### 2.1 YFinance Python 脚本测试

**文件**: `tests/test_yfinance_get_stock_info.py`

**Mock 模式**: 用 `unittest.mock.patch` Mock `yfinance.Ticker` 和 `stock.info` / `stock.history`

**Real 模式**: 用 `pytest.mark.integration` 标记，通过 `--run-integration` 参数执行

| TC-ID | 用例 | Mock 数据/Real 调用 | 预期输出 |
|-------|-----|-------------------|---------|
| PY-YF-001 | **Mock: 五字段齐全** | info: { regularMarketChangePercent=2.5, postMarketPrice=105.0, postMarketChangePercent=-1.0 }; history 1 行含 High/Low | changePercent=2.5, afterHours=105.0, afterHoursChangePercent=-1.0, highPrice=hist["High"], lowPrice=hist["Low"] |
| PY-YF-002 | **Mock: 无盘后数据** | info 无 postMarketPrice/key | afterHours 字段不在结果中（可选缺失），changePercent 仍正确 |
| PY-YF-003 | **Mock: history 为空** | history.empty=True | 返回 error JSON |
| PY-YF-004 | **Mock: regularMarketChangePercent 不存在** | info 无 regularMarketChangePercent | changePercent 为 info.get() 默认值 0 |
| PY-YF-005 | **Mock: 异常捕获** | yfinance 抛异常 | 返回 error JSON 含异常信息 |
| PY-YF-006 | **Mock: 多只股票连续调用** | 分别 Mock AAPL, MSFT | 每只都返回完整字段 |
| PY-YF-007 | **Real: AAPL 真实调用** | `yf.Ticker("AAPL").info` | regularMarketChangePercent 不为 null（真实验证） |
| PY-YF-008 | **Real: afterHours 字段存在** | 同上 | postMarketPrice 字段存在（值可 null） |
| PY-YF-009 | **Real: 五字段完整性验证** | 同上 | 同时验证 high(history), low(history), changePercent(info), postMarketPrice(info), postMarketChangePercent(info) |
| PY-YF-010 | **Real: 多 symbol 验证** | AAPL, MSFT, GOOGL 依次调用 | 每只都能拿到 changePercent 和盘后字段 |

### 2.2 TwelveData Python 脚本测试

**文件**: `tests/test_twelvedata_get_stock_info.py`

| TC-ID | 用例 | Mock 数据/Real 调用 | 预期输出 |
|-------|-----|-------------------|---------|
| PY-TD-001 | **Mock: 正常报价** | API 返回 symbol, close=150.0, open=149.0, high=152.0, low=148.5, volume=1000000, change=1.0, percent_change=0.67 | changePercent=0.67, highPrice=152.0, lowPrice=148.5 |
| PY-TD-002 | **Mock: 无 API Key** | env 未设置 TWELVEDATA_API_KEY | error JSON |
| PY-TD-003 | **Mock: API 429 限流** | HTTPError 429 | error JSON |
| PY-TD-004 | **Mock: URLError** | 网络异常 | error JSON |
| PY-TD-005 | **Mock: percent_change=0** | percent_change=0.0 | changePercent=0.0 |
| PY-TD-006 | **Real: 真实 /quote 调用** | 调用真实 TwelveData /quote 端点 | 返回 percent_change（值可 null 但字段存在） |
| PY-TD-007 | **Real: /time_series 调用** | 调用日线端点 | 返回 high/low/close/open/volume |

### 2.3 TwelveData Java REST Client 测试

**文件**: `src/test/java/com/stock/invest/client/TwelveDataRestClientTest.java`

| TC-ID | 用例 | Mock HTTP 响应 | 预期输出 |
|-------|-----|---------------|---------|
| JAVA-TD-001 | **Mock: 正常 time_series** | 含 values 数组，含 open/high/low/close/volume/datetime | KLineData 含 items，正确解析 |
| JAVA-TD-002 | **Mock: API error** | status="error", message="xxx" | return null |
| JAVA-TD-003 | **Mock: values 为空** | values=[] | data.items 为空 |
| JAVA-TD-004 | **Mock: 字段缺失（无 high）** | 某 item 缺失 high | high 解析为 0 |
| JAVA-TD-005 | **Mock: HTTP 异常** | http.get 抛异常 | 异常向上传播 |
| JAVA-TD-006 | **Real: 真实 TwelveData API** | 调用 fetchDailyBars("AAPL", 7) | 返回含 high/low 的 KLineData |

### 2.4 Tiingo Java REST Client 测试

**文件**: `src/test/java/com/stock/invest/client/TiingoRestClientTest.java`

| TC-ID | 用例 | Mock HTTP 响应 | 预期输出 |
|-------|-----|---------------|---------|
| JAVA-TG-001 | **Mock: 正常 daily prices** | 返回含 date/open/high/low/close/volume | KLineData 正常解析，含 high/low |
| JAVA-TG-002 | **Mock: 空数组** | [] | items 为空 |
| JAVA-TG-003 | **Mock: API error** | 非 200 状态码 | 异常传播 |
| JAVA-TG-004 | **Real: 真实 Tiingo API** | 调用 fetchDailyBars("AAPL", 7) | 返回含 high/low 的 KLineData |

### 2.5 TigerOpen Python Bridge 测试

**文件**: `tests/test_tigeropen_channel.py`

| TC-ID | 用例 | Mock 数据/Real 调用 | 预期输出 |
|-------|-----|-------------------|---------|
| PY-TO-001 | **Mock: 正常 bars** | get_bars 返回 DataFrame 含 open/high/low/close/volume | 返回含 high/low |
| PY-TO-002 | **Mock: 空数据** | get_bars 返回空 DataFrame | 空结果 |
| PY-TO-003 | **Mock: 异常** | 客户端抛异常 | error JSON |
| PY-TO-004 | **Mock: afterhours_bars 命令** | get_bars 被 trade_session=AFTER_HOURS 调用 | 命令分发正确，调用 get_bars 含 trade_session 参数 |
| PY-TO-005 | **Real: 真实 TigerOpen bars** | 调用真实 afterhours_bars 命令 | 返回合法 JSON（可能 items 为空） |
| PY-TO-006 | **Real: 区分 Regular vs AfterHours** | 对比 bars vs afterhours_bars 输出 | 两调用返回不同的 DataFrame |

### 2.6 TigerOpenPythonBridge Java 测试

**文件**: `src/test/java/com/stock/invest/client/TigerOpenPythonBridgeAfterHoursTest.java`

| TC-ID | 用例 | Mock 数据 | 预期输出 |
|-------|-----|----------|---------|
| BRIDGE-001 | Mock: afterhours_bars 正常返回 | Mock executePythonScript 返回合法 JSON | KLineData 含 items |
| BRIDGE-002 | Mock: 异常处理 | executePythonScript 抛异常 | 返回空 KLineData |

---

## 三、端点测试（Real 模式）

通过 `@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)` 启动真实应用上下文，连接真实 MySQL，调用数据源。

### 3.1 BarsController 端点集成测试

**文件**: `src/test/java/com/stock/invest/controller/BarsControllerIntegrationTest.java`

| TC-ID | 用例 | 操作 | 验证点 |
|-------|-----|------|-------|
| INT-CTRL-001 | 真实 DB 查询 AAPL K 线 | `GET /api/bars/AAPL/candles?days=7` | 200 OK，返回 JSON 含 date/open/high/low/close/changePercent 字段 |
| INT-CTRL-002 | 验证 changePercent 与 DB 一致 | 查询 DB 对应行 | DTO 中 changePercent = DB 中 change_percent |
| INT-CTRL-003 | 空 symbol 查询 | `GET /api/bars/ZZZZZ/candles` | 200 OK，data=[] |
| INT-CTRL-004 | 验证返回条数不超过 days | 指定 days=3 | data.length ≤ 3 |
| INT-CTRL-005 | afterHours 为 null 兼容 | 查 DB 中 after_hours=null 的行 | JSON 中 afterHours=null |

### 3.2 DataGapFiller 端点（触发补缺）集成测试

**文件**: `src/test/java/com/stock/invest/scheduler/DataGapFillerIntegrationTest.java`

| TC-ID | 用例 | 操作 | 验证点 |
|-------|-----|------|-------|
| INT-FILL-001 | 手动触发补缺 | 调用 fillGaps() | 补缺完成后查 DB，新写入的数据含 high_price/low_price/change_percent |
| INT-FILL-002 | changePercent 计算正确性 | 补缺后对比同一股票相邻交易日 | change_percent = ROUND((close - prev_close)/prev_close*100, 4) |

### 3.3 Python 脚本集成测试

**文件**: `tests/test_data_sources_integration.py`

| TC-ID | 用例 | 调用方式 | 验证点 |
|-------|-----|---------|-------|
| INT-YF-001 | YFinance AAPL 真实调用 | `yf.Ticker("AAPL").info` | regularMarketChangePercent 不为 null |
| INT-YF-002 | YFinance afterHours 字段存在 | 同上 | postMarketPrice 字段存在（值可 null） |
| INT-YF-003 | YFinance 五字段完整性 | 同上 | 同时返回 five fields |
| INT-YF-004 | YFinance 多 symbol | AAPL, MSFT, GOOGL 依次调用 | 每只都能拿到 changePercent |
| INT-TD-001 | TwelveData /quote 真实调用 | 调用 `/quote` 端点 | 返回 percent_change |
| INT-TD-002 | TwelveData /time_series | 日线端点 | 返回 high/low/close/open/volume |

---

## 四、前端测试

### 4.1 bars.ts API 单元测试

**文件**: `frontend/src/__tests__/bars.test.ts`

**Mock 模式**: Mock `axios`，模拟 HTTP 响应

**Real 模式**: 启动真实开发服务器，调用真实后端

| TC-ID | 用例 | Mock/Real | 预期 |
|-------|-----|----------|------|
| FE-API-001 | 成功获取 7 条 CandleData | Mock 返回 7 条含 changePercent/afterHours | data.length=7，第一条含 changePercent |
| FE-API-002 | 空数据 | Mock 返回 data=[] | data=[] |
| FE-API-003 | 网络异常 | Mock 抛 axios error | catch 到错误 |
| FE-API-004 | symbol 含特殊字符 | symbol="BRK.A" | URL 正确编码 |
| FE-API-005 | **Real: 后端真实数据** | 启动 Vite proxy + Spring Boot | 返回真实 CandleData 数组 |
| FE-API-006 | **Real: 字段完整性验证** | 调用真实后端 | 每个 CandleData 含 9 个字段 |

### 4.2 ScreenerView 组件单元测试

**文件**: `frontend/src/__tests__/ScreenerViewCandle.test.ts`

**方法**: 使用 `@vue/test-utils` 渲染组件，Mock API 调用

| TC-ID | 用例 | 输入 | 预期 |
|-------|-----|------|------|
| FE-VIEW-001 | 点击触发加载 | 点击 symbol 列 | fetchCandles 被调用，参数 symbol 正确 |
| FE-VIEW-002 | 加载状态 | fetchCandles 未返回 | 显示 loading spin |
| FE-VIEW-003 | 成功后渲染图表 | fetchCandles 返回 7 条 | 图表容器（v-chart）可见 |
| FE-VIEW-004 | 失败状态 | fetchCandles 抛异常 | 空状态，无图表 |
| FE-VIEW-005 | 关闭按钮 | 点击关闭 | showCandleChart=false，selectedSymbol=null |
| FE-VIEW-006 | 再次点击同一股票（toggle） | 第一次点击→加载→第二次点击 | 图表关闭 |
| FE-VIEW-007 | 初始无图表 | 页面未点击任何股票 | 图表容器不可见 |
| FE-VIEW-008 | changePercent 显示在 tooltip | 数据含 changePercent=2.5 | tooltip 中显示 "2.50%" |
| FE-VIEW-009 | afterHours null 显示 — | afterHours=null | tooltip 中显示 "盘后价: —" |

### 4.3 ScreenerView E2E 测试

**文件**: `frontend/e2e/screener-kline.spec.ts`

**方法**: 使用 Playwright 启动真实浏览器

**Mock 后端模式**: 使用 `page.route()` 拦截 API 返回 Mock 数据

**真实后端模式**: 启动 Spring Boot + MySQL + Vite dev server

| TC-ID | 用例 | 操作步骤 | Mock/Real | 预期结果 |
|-------|-----|---------|----------|---------|
| **E2E-001** | **完整流程：点击→加载→显示 K 线** | ① 进入筛选页 ② 展开某批结果 ③ 点击股票代码 | **Mock** | 发起 `/api/bars/*/candles` 请求，K 线图表渲染，含 title，x 轴日期，y 轴价格 |
| **E2E-002** | **完整流程（真实后端）** | 同上 | **Real** | 请求真实后端，图表渲染，数据来自真实数据库 |
| **E2E-003** | toggle 关闭图表 | 点击已展开的股票代码 | Mock | 图表关闭，不渲染 |
| **E2E-004** | 切换股票 | 点 AAPL→加载→点 MSFT | Mock | 图表切换为 MSFT，新数据请求 |
| **E2E-005** | 加载失败兜底 | Mock 后端返回 500 | Mock | 显示提示信息，不渲染图表 |
| **E2E-006** | 空数据兜底 | Mock 返回 data=[] | Mock | 显示"暂无 K线数据" |
| **E2E-007** | 缩放交互 | 渲染后鼠标滚轮缩放 | Mock | dataZoom 生效，x 轴范围变化 |
| **E2E-008** | tooltip 显示全部新字段 | 数据含 changePercent/afterHours | Mock | tooltip 中可见"涨跌幅""盘后价""盘后涨跌" |
| **E2E-009** | 盘后价 null 时 tooltip 显示 — | afterHours=null | Mock | tooltip 显示"盘后价: —" |
| **E2E-010** | 成交量柱状图可见 | 渲染后 | Mock | 图表底部有成交量柱状图区域 |

### 4.4 行情页面字段展示测试

**文件**: `frontend/e2e/market-fields.spec.ts` + `frontend/src/__tests__/MarketViewFields.test.ts`

| TC-ID | 用例 | 操作步骤 | Mock/Real | 预期结果 |
|-------|-----|---------|----------|---------|
| **E2E-MK-001** | MarketView 新增字段列 | 打开 MarketView 页面 | **Mock** | 表格列包含：最高价、最低价、涨跌幅、盘后价、盘后涨跌 |
| **E2E-MK-002** | MarketView 新增字段（真实后端） | 同上 | **Real** | 列存在，数据来自真实后端 |
| **E2E-MK-003** | 字段值为 null 显示 — | 某行 afterHours=null | Mock | 该格显示"—" |
| **E2E-MK-004** | changePercent 红涨绿跌 | changePercent=2.5（涨） | Mock | 颜色为红色，显示 "+2.50%" |
| **E2E-MK-005** | changePercent 负值 | changePercent=-1.5（跌） | Mock | 颜色为绿色，显示 "-1.50%" |
| **E2E-MK-006** | 最高价列排序 | 点击"最高价"列头 | Mock | 表格按 high 值排序 |
| **E2E-MK-007** | CSV 导出含新字段 | 点击导出按钮 | Mock | CSV 文件含 9 列（含 5 个新增列） |

### 4.5 K 线 API 数据字段完整性对照表（前后端对齐）

| 前端 CandleData 字段 | 后端 DTO 字段 | 数据库列 | 测试用例 |
|---------------------|--------------|---------|---------|
| date | date | trade_date | FE-API-001, CTRL-007 |
| open | open | open_price | FE-API-001, CTRL-007 |
| high | high | high_price | FE-API-001, ENT-001, E2E-008 |
| low | low | low_price | FE-API-001, ENT-002, E2E-008 |
| close | close | close_price | FE-API-001 |
| changePercent | changePercent | change_percent | FE-API-001, PY-YF-001, INT-YF-001 |
| afterHours | afterHours | after_hours | FE-API-005, AH-001, E2E-009 |
| afterHoursChangePercent | afterHoursChangePercent | after_hours_change_percent | FE-API-005, AH-004, E2E-009 |
| volume | volume | volume | FE-API-001 |

---

## 五、日志改进测试

### 5.1 日志格式验证

| TC-ID | 文件 | 验证点 | 方法 |
|-------|------|-------|------|
| LOG-001 | DataGapFillerServiceImpl.fetchAndPersist() | item 日志含 "high=" "low=" "changePercent=" | 验证日志输出字符串包含这些关键字 |
| LOG-002 | DataGapFillerServiceImpl.persist() | 持久化日志含 "high=" "low=" "changePct=" "afterHours=" "afterHoursChg=" | 验证日志输出 |
| LOG-003 | YFinanceStockServiceImpl | 响应日志含 "high=" "low=" "close=" | 验证日志输出 |
| LOG-004 | TwelveDataStockServiceImpl | 响应日志含 "high=" "low=" | 验证日志输出 |
| LOG-005 | TigerWatchlistIngestServiceImpl | 日志含 "high=null, low=null, changePct=null" | 验证日志输出 |

---

## 六、数据库集成测试

| TC-ID | 用例 | 操作 | 预期结果 |
|-------|-----|------|---------|
| DB-001 | 新增字段存在性 | `SHOW COLUMNS FROM stock_daily_bar` | high_price, low_price, change_percent, after_hours, after_hours_change_percent 均在 |
| DB-002 | 字段类型 NULLABLE | `SHOW CREATE TABLE` | 5 个字段均为 DOUBLE（可 NULL） |
| DB-003 | high/low 回填正确性 | 回填后 SELECT | high >= GREATEST(open,close), low <= LEAST(open,close) |
| DB-004 | changePercent 回填计算 | 回填后 SELECT | change_percent = ROUND((close - prev_close)/prev_close*100, 4) |
| DB-005 | 唯一约束未破坏 | 回填后 INSERT 重复 | Duplicate entry 错误 |
| DB-006 | after_hours/ah_change 为 NULL | 查询已有数据 | after_hours 和 after_hours_change_percent 字段存在且可为 NULL |

---

## 七、总分支覆盖率达标方案

### 后端 Java（目标 >90%）

| 模块 | 总分支 | 覆盖分支 | 覆盖率 | 关联 TC-ID |
|------|-------|---------|-------|-----------|
| StockDailyBarService.getRecentCandles | 6 | 6 | 100% | SVC-001 ~ SVC-008 |
| BarsController.getCandles | 5 | 5 | 100% | CTRL-001 ~ CTRL-008 |
| DataGapFillerServiceImpl.persist | 6 | 6 | 100% | FILL-001 ~ FILL-006 |
| DataGapFillerServiceImpl.mergeAfterHoursIfAvailable | 8 | 8 | 100% | AH-001 ~ AH-007 |
| TwelveDataRestClient.fetchDailyBars | 6 | 6 | 100% | JAVA-TD-001 ~ JAVA-TD-006 |
| TiingoRestClient.fetchDailyBars | 4 | 4 | 100% | JAVA-TG-001 ~ JAVA-TG-004 |
| TigerStockServiceImpl.getAfterHoursKLineDataByDateRange | 6 | 6 | 100% | TIG-AH-001 ~ TIG-AH-006 |
| TigerStockServiceImpl.convertTigerKlinePointToKLineIterator | 2 | 2 | 100% | KLINE-PT-001 ~ KLINE-PT-003 |
| TiingoDataSourceStrategy.getStockInfo | 4 | 4 | 100% | TG-INFO-001 ~ TG-INFO-005 |
| StockDailyBar (5 new fields) | 10 | 10 | 100% | ENT-001 ~ ENT-008 |
| KLineIterator (3 new fields) | 8 | 8 | 100% | KLI-001 ~ KLI-006 |
| StockDailyBarCandleDto | 2 | 2 | 100% | DTO-001 ~ DTO-005 |
| TigerOpenPythonBridge.fetchAfterHoursBars | 4 | 4 | 100% | BRIDGE-001 ~ BRIDGE-002 |

### Python 脚本（目标 >90%）

| 模块 | 总分支 | 覆盖分支 | 覆盖率 | 关联 TC-ID |
|------|-------|---------|-------|-----------|
| stock_info_yfinance.get_stock_info | 10 | 10 | 100% | PY-YF-001 ~ PY-YF-010 |
| stock_info_twelvedata.get_stock_info | 8 | 8 | 100% | PY-TD-001 ~ PY-TD-007 |
| tigeropen_channel._cmd_bars | 4 | 4 | 100% | PY-TO-001 ~ PY-TO-003 |
| tigeropen_channel._cmd_afterhours_bars | 4 | 4 | 100% | PY-TO-004 ~ PY-TO-006 |

### 前端（目标 >90%）

| 模块 | 总分支 | 覆盖分支 | 覆盖率 | 关联 TC-ID |
|------|-------|---------|-------|-----------|
| bars.ts fetchCandles | 4 | 4 | 100% | FE-API-001 ~ FE-API-006 |
| ScreenerView 蜡烛图交互 | 10 | 10 | 100% | FE-VIEW-001 ~ FE-VIEW-009 |
| MarketView 新增列 | 10 | 10 | 100% | E2E-MK-001 ~ E2E-MK-007 |
| E2E 页面流程 | 15 | 15 | 100% | E2E-001 ~ E2E-010 |

---

## 八、测试执行命令

```bash
# ============================================
# 后端单元测试（Mock 模式，CI 可运行）
# ============================================
cd /home/allon/application/stock-invest

mvn test -Dtest=" \
StockDailyBarCandleDtoTest,\
StockDailyBarFieldTest,\
KLineIteratorFieldTest,\
StockDailyBarServiceTest,\
BarsControllerCandlesTest,\
DataGapFillerPersistTest,\
DataGapFillerAfterHoursTest,\
TwelveDataRestClientTest,\
TiingoRestClientTest,\
TigerAfterHoursTest,\
TigerStockServiceKlineTest,\
TiingoStockInfoTest,\
TigerOpenPythonBridgeAfterHoursTest\
" -Dgroups="mock"

# ============================================
# 后端集成测试（Real 模式，需要 MySQL + API Key）
# ============================================
mvn test -Dtest="\
BarsControllerIntegrationTest,\
DataGapFillerIntegrationTest,\
StockDailyBarServiceTest#SVC-009,\
BarsControllerCandlesTest#CTRL-009_CTRL-010\
" -Dgroups="integration" -Dspring.profiles.active=test

# ============================================
# Python 单元测试（Mock 模式）
# ============================================
cd /home/allon/application/stock-invest
source .venv/bin/activate
python -m pytest tests/test_yfinance_get_stock_info.py \
  tests/test_twelvedata_get_stock_info.py \
  tests/test_tigeropen_channel.py \
  -v -m "not integration"

# ============================================
# Python 集成测试（Real 模式，需要 API Key）
# ============================================
source .venv/bin/activate
python -m pytest tests/test_data_sources_integration.py -v --run-integration
python -m pytest tests/test_yfinance_get_stock_info.py -v -m integration --run-integration

# ============================================
# 前端单元测试
# ============================================
cd /home/allon/application/stock-invest/frontend
npx vitest run --reporter=verbose

# ============================================
# 前端 E2E 测试（Mock 后端模式）
# ============================================
cd /home/allon/application/stock-invest/frontend
npx playwright test --config e2e/playwright.config.ts --project=chromium

# ============================================
# 前端 E2E 测试（真实后端模式）
# ============================================
# 先启动后端：cd /home/allon/application/stock-invest && mvn spring-boot:run
# 再启动前端：cd frontend && npm run dev
# 然后运行：
npx playwright test --config e2e/playwright.config.ts --project=chromium --grep "Real"

# ============================================
# 数据库验证
# ============================================
mysql -h 127.0.0.1 -P 3307 -u root -p \
  -e "SHOW COLUMNS FROM stock_invest.stock_daily_bar;" \
  | grep -E "high_price|low_price|change_percent|after_hours"
```

---

## 九、Mock 与 Real 模式切换机制

### Java 后端

```java
// 使用 JUnit 5 Tags 区分模式
@Tag("mock")         // Mock 模式测试（CI 默认）
@Tag("integration")  // Real 模式测试（需 API Key / 数据库）

// 在 pom.xml 中配置 Surefire 分组：
// mvn test -Dgroups="mock"        → 只跑 Mock 测试
// mvn test -Dgroups="integration" → 只跑集成测试
// mvn test                        → 跑全部
```

### Python

```python
# 使用 pytest markers
@pytest.mark.integration
def test_yfinance_real():
    ...

# pytest -m "not integration"  → 只跑 Mock
# pytest --run-integration     → 跑全部（含 Real）
```

### 前端

```typescript
// 使用环境变量区分
// VITE_TEST_MODE=mock → Mock Axios
// VITE_TEST_MODE=real → 连接真实后端
```

---

## 十、Mock 数据参考

### 标准 Mock KLineIterator

```java
KLineIterator createMockItem(String symbol, double open, double high,
                              double low, double close, double changePercent,
                              double afterHours, double afterHoursChangePercent,
                              long volume) {
    KLineIterator item = new KLineIterator();
    item.setSymbol(symbol);
    item.setTime(System.currentTimeMillis());
    item.setOpen(open);
    item.setHigh(high);
    item.setLow(low);
    item.setClose(close);
    item.setChangePercent(changePercent);
    item.setAfterHours(afterHours);
    item.setAfterHoursChangePercent(afterHoursChangePercent);
    item.setVolume(volume);
    return item;
}
```

### 标准 Mock StockDailyBar

```java
StockDailyBar createMockBar(String symbol, LocalDate date,
                             double open, double high, double low, double close,
                             Double changePercent, Double afterHours,
                             Double afterHoursChangePercent, long volume) {
    StockDailyBar bar = new StockDailyBar();
    bar.setSymbol(symbol);
    bar.setTradeDate(date);
    bar.setOpenPrice(open);
    bar.setHighPrice(high);
    bar.setLowPrice(low);
    bar.setClosePrice(close);
    bar.setChangePercent(changePercent);
    bar.setAfterHours(afterHours);
    bar.setAfterHoursChangePercent(afterHoursChangePercent);
    bar.setVolume(volume);
    return bar;
}
```

### 标准 Mock StockDailyBarCandleDto

```java
StockDailyBarCandleDto createMockDto(String date, double open, double high,
                                      double low, double close,
                                      Double changePercent, Double afterHours,
                                      Double afterHoursChangePercent, long volume) {
    return new StockDailyBarCandleDto(date, open, high, low, close,
                                      changePercent, afterHours,
                                      afterHoursChangePercent, volume);
}
```
