# K 线蜡烛图功能 — 测试方案

## 测试范围总览

| 层级 | 测试对象 | 方法 | 覆盖率目标 |
|------|---------|------|-----------|
| 实体/模型 | StockDailyBar, StockDailyBarCandleDto, KLineIterator | 单元测试 | 行 100% |
| Service 后端 | StockDailyBarService.getRecentCandles() | 单元测试 (Mock Repository) | 分支 100% |
| Service 后端 | DataGapFillerServiceImpl.persist() | 单元测试 (Mock Repository) | 分支 100% |
| Controller | BarsController GET /api/bars/{symbol}/candles | 单元测试 (Mock MVC) | 分支 95%+ |
| **数据源 — YFinance** | Python 脚本 `stock_info_yfinance.py` | 单元 + 集成 | 分支 90%+ |
| **数据源 — TwelveData** | Python 脚本 `stock_info_twelvedata.py` + Java REST `TwelveDataRestClient` | 单元 + 集成 | 分支 90%+ |
| **数据源 — Tiingo** | Java REST `TiingoRestClient` | 单元测试 (Mock HTTP) | 分支 90%+ |
| **数据源 — Tiger Java SDK** | Java `TigerStockServiceImpl`（`TradeSession.AfterHours` P1） | 单元测试 | 分支 90%+ |
| **数据源 — TigerOpen Python Bridge** | Python 脚本 `tigeropen_channel.py` | 单元 + 集成 | 分支 90%+ |
| 前端 API 层 | `bars.ts` fetchCandles | 单元测试 | 分支 100% |
| **前端页面 E2E** | ScreenerView 完整 K 线交互流程 | E2E (Cypress/Playwright) | 全流程覆盖 |
| **行情页面字段展示** | MarketView / BarsView 增补字段 | E2E + 单元 | 字段可见性 |
| 数据库 | ALTER TABLE + SQL 回填 | 集成测试 | 字段存在性 |

---

## 一、后端单元测试

### 1.1 StockDailyBarCandleDto 构造测试

**文件**: `src/test/java/com/stock/invest/dto/StockDailyBarCandleDtoTest.java`

| TC-ID | 用例 | 输入 | 预期输出 |
|-------|-----|------|---------|
| DTO-001 | 全字段构造 | date="2026-06-22", open=100.0, high=105.0, low=98.0, close=102.0, changePercent=2.0, afterHours=101.0, afterHoursChangePercent=-0.98, volume=1000000L | 9 个字段值与输入一致 |
| DTO-002 | afterHours 为 null | afterHours=null, afterHoursChangePercent=null | null 字段正确存储 |
| DTO-003 | volume=0 | volume=0L | 值为 0 |
| DTO-004 | changePercent=0 | changePercent=0.0 | 值为 0 |

### 1.2 StockDailyBar 实体字段测试

**文件**: `src/test/java/com/stock/invest/entity/StockDailyBarFieldTest.java`

| TC-ID | 用例 | 输入 | 预期输出 |
|-------|-----|------|---------|
| ENT-001 | 5 个新增字段 set/get | highPrice=105.5, lowPrice=98.3, changePercent=2.15, afterHours=101.2, afterHoursChangePercent=-0.85 | 每个 getter 正确返回 setter 值 |
| ENT-002 | changePercent nullable | setChangePercent(null) → getChangePercent() | null |
| ENT-003 | afterHours nullable | setAfterHours(null) → getAfterHours() | null |
| ENT-004 | afterHoursChangePercent nullable | setAfterHoursChangePercent(null) → getAfterHoursChangePercent() | null |

### 1.3 KLineIterator 新增字段测试

**文件**: `src/test/java/com/stock/invest/model/KLineIteratorFieldTest.java`

| TC-ID | 用例 | 输入 | 预期输出 |
|-------|-----|------|---------|
| KLI-001 | changePercent set/get | setChangePercent(3.5) → getChangePercent() | 3.5 |
| KLI-002 | afterHours set/get | setAfterHours(99.5) → getAfterHours() | 99.5 |
| KLI-003 | afterHoursChangePercent set/get | setAfterHoursChangePercent(-1.2) → getAfterHoursChangePercent() | -1.2 |
| KLI-004 | 默认值 | new KLineIterator() | 三个新字段均为 null |
| KLI-005 | toString | changePercent=3.5, afterHours=99.5, afterHoursChangePercent=-1.2 | toString() 含 "changePercent=3.5" 等字符串 |

### 1.4 StockDailyBarService.getRecentCandles() 测试

**文件**: `src/test/java/com/stock/invest/service/StockDailyBarServiceTest.java`

**方法**: Mock `StockDailyBarRepository`

| TC-ID | 用例 | Mock 数据 | 参数 | 预期 | 覆盖路径 |
|-------|-----|----------|------|------|---------|
| SVC-001 | 7 条数据，changePercent 有值 | 7 条含完整字段 | AAPL, 7 | ✅ 返回 7 条，日期正序，changePercent 用数据库值 | 主路径 |
| SVC-002 | changePercent 为 null 时回退计算 | 7 条，changePercent=null | AAPL, 7 | ✅ 返回 7 条，changePercent=(close-open)/open*100 | 回退计算 |
| SVC-003 | 数据库返回 >7 条 | 10 条 | AAPL, 7 | ✅ 只返回最近 7 条 | subList 截断 |
| SVC-004 | 数据库返回 <7 条 | 3 条 | AAPL, 7 | ✅ 返回全部 3 条 | 不足截断 |
| SVC-005 | 空结果 | 空列表 | UNKNOWN, 7 | ✅ 返回空列表 | 空集合 |
| SVC-006 | afterHours 有值透传 | afterHours=101.5 | AAPL, 7 | ✅ DTO 中 afterHours=101.5 | 非 null |
| SVC-007 | afterHours 为 null 透传 | afterHours=null | AAPL, 7 | ✅ DTO 中 afterHours=null | null 透传 |
| SVC-008 | 单条数据 | 1 条 | AAPL, 7 | ✅ 返回 1 条 | 单元素路径 |

### 1.5 BarsController GET /candles 端点测试

**文件**: `src/test/java/com/stock/invest/controller/BarsControllerCandlesTest.java`

**方法**: `@WebMvcTest(BarsController.class)` + `@MockBean(StockDailyBarService.class)`

| TC-ID | 用例 | Mock 数据 | 请求 | 预期 |
|-------|-----|----------|------|------|
| CTRL-001 | 正常 3 条 | 3 条 DTO | `GET /api/bars/AAPL/candles?days=7` | 200，symbol=AAPL，rows.length=3 |
| CTRL-002 | 默认 days | 7 条 DTO | `GET /api/bars/AAPL/candles` | 200，total=7 |
| CTRL-003 | 自定义 days=10 | 10 条 DTO | `GET /api/bars/AAPL/candles?days=10` | 200，total=10 |
| CTRL-004 | 空结果 | 空列表 | `GET /api/bars/UNKNOWN/candles?days=7` | 200，total=0，rows=[] |
| CTRL-005 | Service 异常 | 抛 RuntimeException | `GET /api/bars/X/candles` | 500 |
| CTRL-006 | 小写 symbol | 正常返回 | `GET /api/bars/aapl/candles` | 200 |
| CTRL-007 | days=1（边界） | 1 条 DTO | `GET /api/bars/AAPL/candles?days=1` | 200，total=1 |
| CTRL-008 | 含空格 symbol | 正常返回 | `GET /api/bars/AAPL/candles` | 200 |

### 1.6 DataGapFillerServiceImpl.persist() 测试

**文件**: `src/test/java/com/stock/invest/service/DataGapFillerPersistTest.java`

| TC-ID | 用例 | KLineIterator 数据 | 预期 |
|-------|-----|------------------|------|
| FILL-001 | 新记录写全部字段 | open=1.0, high=1.2, low=0.8, close=1.1, changePercent=10.0, volume=100000 | save() 被调用，bar 含完整字段 |
| FILL-002 | 更新已有记录 | 同 symbol+tradeDate 已存在 | save() 调用，changePercent 被覆盖 |
| FILL-003 | changePercent=null | item.getChangePercent()=null | bar.setChangePercent(null) |
| FILL-004 | afterHours/afterHoursChangePercent | item 中盘后字段有值 | bar 中盘后字段透传写入 |

---

## 二、五数据源全面测试

### 2.1 YFinance — Python 脚本测试

**文件**: `tests/test_yfinance_get_stock_info.py`

**方法**: Mock `yfinance` 库，验证输出 JSON 各字段。

| TC-ID | 用例 | Mock 数据 | 预期输出 |
|-------|-----|----------|---------|
| PY-YF-001 | 正常：五字段齐全 | info: { longName, regularMarketChangePercent=2.5, postMarketPrice=105.0, postMarketChangePercent=-1.0, regularMarketDayHigh=106.0, regularMarketDayLow=98.0, previousClose=100.0 }; history 1 行 | changePercent=2.5, afterHours=105.0, afterHoursChangePercent=-1.0, highPrice=106.0, lowPrice=98.0 |
| PY-YF-002 | 无盘后数据 | info 无 postMarketPrice | afterHours=None, afterHoursChangePercent=None |
| PY-YF-003 | history 为空 | history.empty=True | 返回 error JSON |
| PY-YF-004 | info 无 previousClose | previousClose 字段缺失 | change 回落至 `close-open` |
| PY-YF-005 | regularMarketDayHigh 缺失 | 仅 history 有 High | highPrice=hist["High"] 回落值 |
| PY-YF-006 | 异常捕获 | yfinance 抛异常 | 返回 error JSON 含异常信息 |
| PY-YF-007 | 多只股票连续调用 | 分别 Mock AAPL, MSFT, GOOGL | 每只都返回完整字段 |

### 2.2 TwelveData — Python 脚本测试

**文件**: `tests/test_twelvedata_get_stock_info.py`

| TC-ID | 用例 | Mock 数据 | 预期输出 |
|-------|-----|----------|---------|
| PY-TD-001 | 正常报价含 percent_change | API 返回 symbol, close=150.0, open=149.0, high=152.0, low=148.5, volume=1000000, change=1.0, percent_change=0.67 | changePercent=0.67, highPrice=152.0, lowPrice=148.5 |
| PY-TD-002 | 无 API Key | env 未设置 | error JSON |
| PY-TD-003 | API 429 限流 | code=429 | error JSON |
| PY-TD-004 | URLError | 网络异常 | error JSON |
| PY-TD-005 | percent_change=0 | percent_change=0.0 | changePercent=0.0 |

### 2.3 TwelveData — Java REST Client 测试

**文件**: `src/test/java/com/stock/invest/client/TwelveDataRestClientTest.java`

**方法**: Mock `ResilientHttpExecutor`，验证 `fetchDailyBars()` 解析

| TC-ID | 用例 | Mock HTTP 响应 | 预期输出 |
|-------|-----|---------------|---------|
| JAVA-TD-001 | 正常 time_series 响应 | 含 values 数组，含 open/high/low/close/volume/datetime | KLineData 含 items，正确解析 |
| JAVA-TD-002 | API 返回 error | status="error", message="xxx" | return null |
| JAVA-TD-003 | values 为空数组 | values=[] | data.items 为空 |
| JAVA-TD-004 | 字段缺失（无 high） | 某 item 缺失 high | high 解析为 0 |
| JAVA-TD-005 | HTTP 异常 | http.get 抛异常 | 异常向上传播 |

### 2.4 Tiingo — Java REST Client 测试

**文件**: `src/test/java/com/stock/invest/client/TiingoRestClientTest.java`

**方法**: Mock `ResilientHttpExecutor`

| TC-ID | 用例 | Mock HTTP 响应 | 预期输出 |
|-------|-----|---------------|---------|
| JAVA-TG-001 | 正常 daily prices | 返回含 date/open/high/low/close/volume | KLineData 正常解析，含 high/low |
| JAVA-TG-002 | 空数组 | [] | items 为空 |
| JAVA-TG-003 | API error | 非 200 状态码 | 异常传播 |
| JAVA-TG-004 | changePercent 计算验证 | prevClose=100.0, close=102.0 | 外部计算 (102-100)/100*100=2.0% |

### 2.5 Tiger Java SDK — KlinePoint 测试

**文件**: `src/test/java/com/stock/invest/service/TigerStockServiceKlineTest.java`

| TC-ID | 用例 | Mock 数据 | 预期输出 |
|-------|-----|----------|---------|
| JAVA-TG-001 | KlinePoint 含 high/low | TigerKlinePoint: open=1.0, high=1.2, low=0.8, close=1.1 | convertTigerKlinePointToKLineIterator 输出 high=1.2, low=0.8 |
| JAVA-TG-002 | changePercent 计算 | close=1.1, prevClose=1.0 | changePercent=(1.1-1.0)/1.0*100=10.0 |

### 2.6 TigerOpen Python Bridge 测试

**文件**: `tests/test_tigeropen_channel.py`

**方法**: Mock `tigeropen` 客户端

| TC-ID | 用例 | Mock 数据 | 预期输出 |
|-------|-----|----------|---------|
| PY-TO-001 | 正常 bars | get_bars 返回 DataFrame 含 open/high/low/close/volume | 返回含 high/low |
| PY-TO-002 | 空数据 | get_bars 返回空 | 空结果 |
| PY-TO-003 | 异常 | 客户端抛异常 | error JSON |

### 2.7 数据源集成测试（真实 API 调用）

**文件**: `tests/test_data_sources_integration.py`

| TC-ID | 用例 | 调用方式 | 验证点 |
|-------|-----|---------|-------|
| INT-YF-001 | YFinance AAPL 真实调用 | `yf.Ticker("AAPL").info` | regularMarketChangePercent 不为 null |
| INT-YF-002 | YFinance — afterHours 字段存在 | 同上 | postMarketPrice 字段存在（值可 null） |
| INT-YF-003 | YFinance — 五字段完整性 | 同上 | 同时返回 regularMarketDayHigh, DayLow, ChangePercent, postMarketPrice, postMarketChangePercent |
| INT-YF-004 | YFinance 多 symbol | AAPL, MSFT, GOOGL 依次调用 | 每只都能拿到 changePercent |
| INT-TD-001 | TwelveData /quote 真实调用 | `/quote` 端点 | 返回 percent_change |
| INT-TD-002 | TwelveData /time_series | 日线端点 | 返回 high/low/close/open/volume |

---

## 三、前端 E2E 全面测试

### 3.1 bars.ts API 单元测试

**文件**: `frontend/src/__tests__/bars.test.ts`

| TC-ID | 用例 | Mock | 预期 |
|-------|-----|------|------|
| FE-API-001 | 成功获取 7 条 | Mock 返回 7 条 CandleData | rows.length=7, 含 all 字段 |
| FE-API-002 | 空数据 | Mock 返回 total=0, rows=[] | rows=[] |
| FE-API-003 | 网络异常 | Mock 抛 axios error | catch 到错误 |
| FE-API-004 | symbol 含特殊字符 | symbol="BRK.A" | URL 正确编码 |

### 3.2 ScreenerView 页面 E2E 测试

**文件**: `frontend/e2e/screener-kline.spec.ts`

**方法**: 使用 Playwright 启动真实页面，模拟用户完整操作链路。

| TC-ID | 用例 | 操作步骤 | 预期结果 |
|-------|-----|---------|---------|
| **E2E-001** | **完整流程：点击 → 加载 → 显示 K 线** | ① 进入筛选页面 ② 展开某批筛选结果 ③ 点击股票代码 "AAPL" | ✅ 发起 `/api/bars/AAPL/candles` 请求 ✅ 出现 loading 状态 ✅ K 线图表渲染，含 title="AAPL K线图"，x 轴显示日期，y 轴显示价格 ✅ tooltip 悬浮显示 9 个字段 |
| **E2E-002** | **toggle 关闭图表** | ① 点击已展开的股票代码 ② 再次点击 | ✅ 图表关闭，不渲染 |
| **E2E-003** | **切换股票** | ① 点 AAPL 加载图表 ② 点 MSFT | ✅ 图表切换为 MSFT，新数据请求，旧图表消失 |
| **E2E-004** | **加载失败兜底** | Mock 后端返回 500 | ✅ 显示提示信息，不渲染图表 |
| **E2E-005** | **空数据兜底** | Mock 后端返回 total=0 | ✅ 显示"暂无 K线数据" |
| **E2E-006** | **缩放交互** | 图表渲染后，鼠标滚轮缩放 | ✅ dataZoom 生效，x 轴范围变化 |
| **E2E-007** | **tooltip 显示全部新字段** | 后端返回含 changePercent/afterHours 数据 | ✅ tooltip 中可见"涨跌幅"、"盘后价"、"盘后涨跌" |
| **E2E-008** | **盘后价 null 时 tooltip 显示 —** | afterHours=null, afterHoursChangePercent=null | ✅ tooltip 显示 "盘后价: —"、"盘后涨跌: —" |

### 3.3 行情页面字段展示 E2E 测试

**文件**: `frontend/e2e/market-fields.spec.ts`

| TC-ID | 用例 | 操作步骤 | 预期结果 |
|-------|-----|---------|---------|
| **E2E-MK-001** | BarsView K 线页面新增字段 | 打开 BarsView 页面，加载数据列表 | ✅ 表格列包含：最高、最低、涨跌幅、盘后价、盘后涨跌 |
| **E2E-MK-002** | 字段值为 null 时显示 — | 某行 afterHours=null | ✅ 该格显示 "—" |
| **E2E-MK-003** | changePercent 格式化 | changePercent=2.5 | ✅ 显示 "2.50%" |
| **E2E-MK-004** | higher 列排序 | 点击"最高"列头 | ✅ 表格按 high 值排序 |
| **E2E-MK-005** | 响应式布局 | 缩小浏览器宽度 | ✅ 列不会溢出，表格自适应 |

### 3.4 ScreenerView 组件单元测试

**文件**: `frontend/src/__tests__/ScreenerViewCandle.test.ts`

**方法**: 渲染组件，Mock API，验证响应式状态和 DOM。

| TC-ID | 用例 | 输入 | 预期 |
|-------|-----|------|------|
| FE-VIEW-001 | 点击触发加载 | 点击 symbol 列 | fetchCandles 被调用，参数 symbol 正确 |
| FE-VIEW-002 | 加载状态 | fetchCandles 未返回 | 显示 loading spin |
| FE-VIEW-003 | 成功后渲染 | fetchCandles 返回 7 条 | 图表容器可见 |
| FE-VIEW-004 | 失败状态 | fetchCandles 抛异常 | 空状态，无图表 |
| FE-VIEW-005 | 关闭按钮 | 点击关闭 | selectedSymbol=null |
| FE-VIEW-006 | 再次点击同一股票 | 第一次点击→加载→第二次点击 | 图表关闭（toggle） |
| FE-VIEW-007 | 初始无图表 | 页面未点击任何股票 | 图表容器不可见 |

---

## 四、数据库集成测试

| TC-ID | 用例 | 操作 | 预期结果 |
|-------|-----|------|---------|
| DB-001 | 新增字段存在性 | `SHOW COLUMNS FROM stock_daily_bar` | high_price, low_price, change_percent, after_hours, after_hours_change_percent 均在 |
| DB-002 | 字段类型 | `SHOW CREATE TABLE` | high_price DOUBLE NULL, low_price DOUBLE NULL 等 |
| DB-003 | high/low 回填正确性 | 回填后 SELECT | high >= GREATEST(open,close), low <= LEAST(open,close) |
| DB-004 | changePercent 回填计算 | 回填后 SELECT | change_percent = ROUND((close - prev_close)/prev_close*100, 4) |
| DB-005 | 唯一约束未破坏 | 回填后 INSERT 重复 | Duplicate entry 错误 |

---

## 五、总分支覆盖率达标方案

### 后端 Java（目标 >90%）

| 模块 | 总分支 | 覆盖分支 | 覆盖率 |
|------|-------|---------|-------|
| StockDailyBarService.getRecentCandles | 6 | 6 | 100% |
| BarsController.getCandles | 5 | 5 | 100% |
| DataGapFillerServiceImpl.persist | 4 | 4 | 100% |
| TwelveDataRestClient.fetchDailyBars | 6 | 6 | 100% |
| TiingoRestClient.fetchDailyBars | 4 | 4 | 100% |
| TigerStockService KlinePoint 转换 | 4 | 4 | 100% |
| KLineIterator / StockDailyBar 字段 | 10 | 10 | 100% |

### Python 脚本（目标 >90%）

| 模块 | 总分支 | 覆盖分支 | 覆盖率 |
|------|-------|---------|-------|
| stock_info_yfinance.get_stock_info | 7 | 7 | 100% |
| stock_info_twelvedata.get_stock_info | 5 | 5 | 100% |
| tigeropen_channel._cmd_bars | 4 | 4 | 100% |

### 前端（目标 >90%）

| 模块 | 总分支 | 覆盖分支 | 覆盖率 |
|------|-------|---------|-------|
| bars.ts fetchCandles | 4 | 4 | 100% |
| ScreenerView 蜡烛图交互 | 8 | 8 | 100% |
| E2E 页面流程 | 13 | 13 | 100% |

---

## 六、测试执行命令

```bash
# === 后端单元测试 ===
cd /home/allon/application/stock-invest
mvn test -Dtest="StockDailyBarCandleDtoTest,StockDailyBarFieldTest,KLineIteratorFieldTest,StockDailyBarServiceTest,BarsControllerCandlesTest,DataGapFillerPersistTest,TwelveDataRestClientTest,TiingoRestClientTest,TigerStockServiceKlineTest"

# === Python 单元测试 ===
cd /home/allon/application/stock-invest
source .venv/bin/activate
python -m pytest tests/test_yfinance_get_stock_info.py tests/test_twelvedata_get_stock_info.py tests/test_tigeropen_channel.py -v

# === Python 集成测试（真实 API） ===
source .venv/bin/activate
python -m pytest tests/test_data_sources_integration.py -v --run-integration

# === 前端单元测试 ===
cd /home/allon/application/stock-invest/frontend
npx vitest run --reporter=verbose

# === 前端 E2E 测试（Playwright） ===
cd /home/allon/application/stock-invest/frontend
npx playwright test --config e2e/playwright.config.ts
```
