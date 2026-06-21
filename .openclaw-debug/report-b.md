================================================================================
报告 B: 数据源竞争分析 & DataGapFiller 覆盖风险评估
================================================================================
日期: 2026-06-18
目标: 查明 BIOX 06-16/06-17 数据被覆盖的可能链路

================================================================================
1. 数据入库的三个写入路径
================================================================================

路径 1: TigerWatchlistIngestServiceImpl (老虎App截图导入)
  触发方式: REST API (POST /api/ingest/watchlist)
  source 值: "tiger_snap"
  写入模式: UPSERT
  写入字段: openPrice=closePrice=lastPrice (截图数据只有收盘价), volume, name
  覆盖策略: 查 find(symbol, tradeDate)，存在则更新，不存在则新建
  
  关键代码:
    StockDailyBar bar = stockDailyBarRepository
        .findBySymbolAndTradeDate(sym, tradeDate)
        .orElseGet(StockDailyBar::new);
    bar.setSymbol(sym);
    bar.setTradeDate(tradeDate);
    bar.setOpenPrice(px);       // = lastPrice
    bar.setClosePrice(px);      // = lastPrice
    bar.setVolume(vol);
    bar.setSource("tiger_snap");
    stockDailyBarRepository.save(bar);

  结论: ⚠️ 会对已存在 (symbol, tradeDate) 的记录做覆盖更新！

路径 2: DataGapFillerServiceImpl (数据补缺服务)
  触发方式: 定时任务 (DataFillScheduler, cron: 每天 19:00 ET = 北京时间 07:00)
            手动调用
  source 值: 成功的数据源名称 (yfinance/twelvedata/tiingo/tigeropen/tiger)
  写入模式: UPSERT
  写入字段: openPrice, closePrice, volume, source
  
  关键代码 (persist 方法):
    Optional<StockDailyBar> existing =
        stockDailyBarRepository.findBySymbolAndTradeDate(symbol, tradeDate);
    StockDailyBar bar;
    if (existing.isPresent()) {
        bar = existing.get();           // ★ 更新已存在的记录
    } else {
        bar = new StockDailyBar();      // ★ 新建记录
        bar.setSymbol(symbol);
        bar.setTradeDate(tradeDate);
    }
    bar.setOpenPrice(item.getOpen());
    bar.setClosePrice(item.getClose());
    bar.setVolume(item.getVolume());
    bar.setSource(source);
    stockDailyBarRepository.save(bar);

  结论: ⚠️ 会对已存在 (symbol, tradeDate) 的记录做覆盖更新！

路径 3: 无其他直接写入路径
  - YFinanceStockServiceImpl: 只从 yfinance API 读数据，不写DB
  - TwelveDataStockServiceImpl: 只从 TwelveData API 读数据，不写DB
  - TigerStockServiceImpl: 只从 Tiger SDK 读数据，不写DB
  - TigerOpenStockServiceImpl: 只从 Tiger Open API 读数据，不写DB
  - TiingoDataSourceStrategy: 只从 Tiingo API 读数据，不写DB

  这些数据源实现只实现了 DataSourceStrategy 接口，提供 read-only 数据。
  它们自身不做任何数据库写入操作！
  写入 stock_daily_bar 的唯一代码在:
    - DataGapFillerServiceImpl.persist()
    - TigerWatchlistIngestServiceImpl.ingest()

================================================================================
2. DataGapFiller 完整执行流程
================================================================================

2.1 定时触发
  DataFillScheduler:
    @Scheduled(cron = "0 0 19 * * ?", zone = "America/New_York")
    即: 每天美东时间 19:00 运行一次（北京时间次日 07:00 夏季 / 08:00 冬季）
  -> 调用 fillGaps()
  -> 然后调用 processRetryingTasks()

2.2 fillGaps() 执行流程:
  Step 1: 扫描 stock_daily_bar 表中所有 symbol
          -> stockDailyBarRepository.findAllSymbols()
  
  Step 2: 过滤黑名单 symbol (已标记为无效的股票)
  
  Step 3: 每个 symbol 取最近 30 天的 bars (MAX_LOOKBACK_DAYS=30)
  
  Step 4: 条件判断: 如果最新 bar 的 closePrice > minPriceThreshold(默认1.0)
          -> 跳过此 symbol（认为数据正常，不需要补缺）
          如果 closePrice <= 1.0 或 null
          -> 进入补缺流程
  
  Step 5: findMissingTradeDates()
          在 [oldestBar, today(NY时间)] 范围内找缺失的交易日
          - 通过 TradingCalendarDbService 跳过非开盘日
          - 最多补 5 个缺失日期 (MAX_MISSING_DATES_PER_SYMBOL=5)
  
  Step 6: 对每个缺失日期，调用 fetchAndPersist()
          构建 fallback 链 -> 依次尝试数据源 -> 成功则 persist() 写入DB
  
  Step 7: 最大处理 200 个 symbol (MAX_SYMBOLS_PER_RUN=200)

2.3 Fallback 链构建 (buildFallbackChainForSymbol):
  优先级顺序:
    a) 查询 StockDataSourcePriorityService.getPriorityList(symbol)
       - 有历史成功记录的数据源排在前面 (按 last_success_time DESC)
       - 无记录的使用默认顺序: yfinance -> twelvedata -> tiingo -> tigeropen -> tiger
    b) 过滤掉 isAvailable()==false 的数据源
    c) 按优先级排序

2.4 fetchAndPersist() 单日期补缺:
  对 fallback 链中的每个数据源按优先级依次尝试:
  -> 调用 ds.getDailyKLineDataByDateRange(symbol, tradeDate)
  -> 如果返回 empty/null -> 尝试下一个数据源
  -> 如果返回数据 -> 匹配 tradeDate -> 调用 persist() 写入DB
  -> 成功: 更新该股票该数据源的优先级成功时间
           重置黑名单计数
  -> 所有数据源都失败:
    - 如果有2个以上数据源返回"not found"(404)
      -> 加入黑名单，停止后续补缺
    - 否则 -> 创建 retry 任务

2.5 processRetryingTasks() 重试机制:
  - 每次 fillGaps 后自动执行
  - 重试条件: createdAt 未超过7天, 当天重试次数 <5, 冷却30分钟
  - 重试成功 -> status="completed"
  - 重试失败 -> retryCount+1, dayCount+1
  - 超7天或当天达5次 -> status="stopped"

================================================================================
3. 对 stock_daily_bar 是 INSERT 还是 UPDATE？
================================================================================

★ UPSERT (insert-or-update)

DataGapFillerServiceImpl.persist():
  1. 先查 findBySymbolAndTradeDate()
  2. 存在 -> 更新已有记录的 openPrice/closePrice/volume/source
  3. 不存在 -> 创建新记录

⚠️ DataGapFiller 覆盖已有记录时:
  会覆盖: openPrice, closePrice, volume, source
  不会覆盖: name (老虎截图导入设置的name会被保留)
  createdAt 不变, updatedAt 会自动更新 (@LastModifiedDate)

TigerWatchlistIngestServiceImpl.ingest():
  同样是 UPSERT:
  1. 先查 findBySymbolAndTradeDate()
  2. 存在 -> 更新已有记录的字段
  3. 不存在 -> 创建新记录
  4. source 永远设为 "tiger_snap"

================================================================================
4. 各数据源之间的冲突策略
================================================================================

4.1 默认优先级 (DEFAULT_DATA_SOURCE_ORDER):
  yfinance > twelvedata > tiingo > tigeropen > tiger

4.2 动态优先级 (StockDataSourcePriorityService):
  每支股票有自己的优先级列表:
  - 有历史成功记录的数据源排在前面
  - 按 last_success_time 降序排列
  - 示例: 如果某股票历史上只成功从 twelvedata 获取数据
    则 twelvedata 排在最前 -> yfinance -> tiingo -> tigeropen -> tiger

4.3 冲突发生场景:

  场景 A: DataGapFiller vs TigerWatchlistIngest
    - DataGapFiller 每天 19:00 ET 运行
    - TigerWatchlistIngest 随时可通过 API 手动触发
    - 两者写的 source 值不同 ("yfinance" vs "tiger_snap")
    - 后写入者覆盖先写入者

  场景 B: DataGapFiller 的 fallback 链
    - 按优先级尝试，第一个返回结果的数据源写入
    - 写入后不会再用其他数据源覆盖同一天
    - 但 DataGapFiller 的 persist() 方法会覆盖已存在记录

4.4 覆盖策略详细对比:

  | 表字段        | TigerWatchlistIngest | DataGapFiller |
  |--------------|---------------------|---------------|
  | symbol       | 不变                | 不变          |
  | tradeDate    | 不变                | 不变          |
  | openPrice    | = lastPrice (收盘价)| = API的open   |
  | closePrice   | = lastPrice (收盘价)| = API的close  |
  | volume       | = 截图成交量         | = API的volume |
  | source       | = "tiger_snap"      | = 数据源名称  |
  | name         | = 老虎股票名称       | 不写(保留原值)|
  | id           | 不变                | 不变          |
  | createdAt    | 不变                | 不变          |
  | updatedAt    | 更新                | 更新          |

================================================================================
5. BIOX 06-16 和 06-17 数据覆盖可能性分析
================================================================================

5.1 数据来源判定:
  - BIOX 是一只场外交易 (OTC) 股票，可能多个数据源有数据
  - 老虎 App 的截图导入会将 source 设为 "tiger_snap"
  - 如果当前数据 source != "tiger_snap"，说明可能被其他来源覆盖

5.2 DataGapFiller 覆盖链路分析:

  ★ 关键发现: minPriceThreshold 默认值是 1.00！
  
  条件判断逻辑:
  -> 取 BIOX 最近 30 天的 bars
  -> 检查最新 bar 的 closePrice
  -> 如果 closePrice > 1.0 (minPriceThreshold) -> 跳过，不补缺
  -> 如果 closePrice <= 1.0 或 null -> 进入补缺流程！
  
  BIOX 06-16 价格: 约 0.0150（远低于 1.0 阈值）
  BIOX 06-17 价格: 待确认（OTC micro-cap，大概率也 < 1.0）
  
  ⚠️ 所有股价低于 $1 的股票都会触发 DataGapFiller 的补缺逻辑！
  BIOX 价格 ~0.015 美元，远低于 $1 -> 必定触发 DataGapFiller！

5.3 BIOX 被覆盖的时间线分析:

  核心机制: findMissingTradeDates() 只返回数据库中 NOT 存在的日期
  
  场景 1: 先导入截图，后 DataGapFiller
    上午: 用户通过老虎截图导入 BIOX 06-16 和 06-17
          -> source = "tiger_snap", 数据写入DB
    晚上 19:00 ET: DataGapFiller 运行
          -> 取 BIOX 最近30天 bars
          -> findMissingTradeDates: 06-16和06-17都在bars里 -> 不算缺失
          -> ★ 不会对 06-16/06-17 做任何操作
          -> 但如果 06-13/06-14 等之前日期缺失 -> 会补缺
    结论: 不会被覆盖 ✓

  场景 2: 先 DataGapFiller，后导入截图
    DataGapFiller 在 06-17 收盘后(19:00 ET)运行
    -> BIOX 06-17 价格 0.015 < 1.0 -> 触发补缺
    -> 06-17 数据可能还不存在(如果还没导入) -> 列为缺失
    -> API 源头取数据 -> 写入 BIOX 06-17 -> source="yfinance"等
    然后用户手动导入老虎截图
    -> TigerWatchlistIngest UPSERT -> 覆盖！
    -> 06-17 的 openPrice/closePrice 都被改为截图中的 lastPrice
    结论: 会被截图导入覆盖 ⚠️

  场景 3: 两者同日运行
    06-17 19:00 ET: DataGapFiller 运行，补缺了 06-17（假设还没导入）
    06-18 上午: 用户导入老虎截图，覆盖了 06-17
    结果: 最终 source="tiger_snap"，价格也可能变化

  场景 4: 手动重新运行 DataGapFiller
    用户在 06-18 上午导入截图后
    如果手动触发了 fillGaps()
    -> findMissingTradeDates 只看是否有数据，不关心 source
    -> 06-16和06-17已存在 -> 不列为缺失 -> 不会覆盖 ✓
    (但 06-12~06-15 等缺失日期会被补)

5.4 结论:

  DataGapFiller 通过 findMissingTradeDates() 机制，只补数据库中「不存在」
  的 (symbol, tradeDate) 组合。如果数据已存在（不管什么 source），不会覆盖。
  
  ★ 更可能的覆盖路径是:
  TigerWatchlistIngest (截图导入) 在 DataGapFiller 之后运行。
  截图导入是手动触发的 API，随时可以运行，且一定会覆盖已有记录。

  ★ 次可能的覆盖路径:
  如果 DataGapFiller 在截图导入之后运行，但因为某些原因 06-16/06-17 的数据
  条数不足以通过 findMissingTradeDates 的检查（例如 bars 列表不够长），
  则可能会被列入缺失并再次补缺。

================================================================================
6. 关键配置项
================================================================================

gap-fill.min-price-threshold = 1.00      (低于此价格触发补缺，所有penny stock都会触发)
DataGapFiller.MAX_SYMBOLS_PER_RUN = 200  (每次最多处理200只股票)
DataGapFiller.MAX_LOOKBACK_DAYS = 30     (只检查最近30天的缺口)
DataGapFiller.MAX_MISSING_DATES_PER_SYMBOL = 5  (每只股票最多补5天)

================================================================================
7. 数据源可用性依赖
================================================================================

| 数据源       | 实现类                    | isAvailable() 条件          |
|-------------|--------------------------|-----------------------------|
| yfinance    | YFinanceStockServiceImpl  | 永远 true                   |
| twelvedata  | TwelveDataStockServiceImpl| twelveDataRestClient.hasApiKey() |
| tiingo      | TiingoDataSourceStrategy  | 永远 true                   |
| tigeropen   | TigerOpenStockServiceImpl | bridge.hasCredentials()     |
| tiger       | TigerStockServiceImpl     | client != null              |

注意: yfinance 和 tiingo isAvailable() 永远返回 true
      意味着只要这两个在 fallback 链前面，且 BIOX 在这两个平台有数据
      -> DataGapFiller 就会用 yfinance 或 tiingo 的数据补缺 BIOX

================================================================================
8. 建议排查步骤
================================================================================

1. 查询 BIOX 当前数据快照:
   SELECT symbol, tradeDate, open_price, close_price, volume, source, 
          created_at, updated_at
   FROM stock_daily_bar 
   WHERE symbol='BIOX' 
   ORDER BY tradeDate DESC;

2. 看 source 字段判断谁写了数据:
   如果 source='tiger_snap' -> 当前数据来自老虎截图
   如果 source='yfinance' 等 -> 当前数据来自 DataGapFiller/API补缺

3. 看 updated_at 时间:
   如果 updated_at 比截图导入时间晚很多 -> 说明被后续进程覆盖过

4. 查日志:
   grep -i BIOX /path/to/logs | grep -E '(TigerIngest|DataGapFiller|ingest)'

5. 防护建议:
   a) 将 BIOX 等OTC股票加入 SymbolBlacklistService 黑名单
   b) 修改 minPriceThreshold 降为 0.001，避免触发低价股补缺
   c) 给 DataGapFiller.persist() 添加逻辑: 如果已有记录 source='tiger_snap' -> skip
   d) 给 TigerWatchlistIngest 添加逻辑: 如果不是 'tiger_snap' source -> 也覆盖

================================================================================
9. 三条定时任务的时间线
================================================================================

| 任务                          | Cron              | 美东时间 | 北京时间(夏) |
|------------------------------|-------------------|---------|-------------|
| DataFillScheduler            | 0 0 19 * * ?      | 19:00   | 07:00(+1)   |
| ScreeningScheduler           | 0 30 21 * * ?     | 21:30   | 09:30(+1)   |
| TradingCalendarScheduler     | 0 30 4 * * MON    | 04:30(周一)| 16:30     |

================================================================================
10. 总结
================================================================================

DataGapFiller 的 persist() 方法是 UPSERT，但通过 findMissingTradeDates()
前置过滤，它只补数据库中「不存在」的日期数据，不会主动覆盖已有记录。

BIOX 数据被覆盖的最可能路径:
  TigerWatchlistIngest (截图导入, 手动触发) 在之后的某个时间点覆盖了
  之前 DataGapFiller 补缺写入的数据，或反之。

次可能路径:
  DataGapFiller 在截图导入后运行，因为某些边界条件（如 bars 不够/不完整），
  findMissingTradeDates 错误地将已有日期标记为缺失，触发了覆盖。

更可能的根本原因:
  BIOX 作为 OTC penny stock (价格 <$1)，minPriceThreshold=1.0 的配置会强制
  触发 DataGapFiller 对该股票做数据完整性检查。虽然 findMissingTradeDates
  不会覆盖已有日期，但持续的数据源竞争（TigerWatchlistIngest 与 DataGapFiller）
  可能在时序上产生覆盖。
