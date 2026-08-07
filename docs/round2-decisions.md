# round2 修复 —— 行为语义决策记录

- **文档日期**：2026-08-07
- **依据**：`docs/round2-optimization-plan.md`（P3-8 / P3-10 / P1-3 涉及对外行为语义，需留档确认）
- 本文件为新增决策记录，不改动既有 4 份方案/报告文档。

---

## 1. P3-8：`fill-tasks` 搜索 symbol 前缀匹配语义（无代码改动）

- `DataFillTaskRepository.findByFilters` 的 symbol 过滤为**前缀匹配**（`LIKE CONCAT(:symbol, '%')`），
  语义从历史"包含"（`%...%`）改为"前缀"（可命中索引）。
- **决策**：维持前缀语义（股票代码按前缀搜索符合常规），本轮无代码改动；
  前端 `frontend/src/api/admin.ts fetchFillTasks` 传参原样透传，无需修改。
- 若产品后续需要 contains 语义，需重新评估索引方案（`%...%` 前导通配无法命中索引）。

## 2. P3-10：`gap-fill.min-price-threshold` 阈值语义（无代码改动）

- `DataGapFillerServiceImpl.fillGapsForSymbol`：最新收盘价 > `min-price-threshold`（默认 1.00）
  时跳过补缺，仅对低价股补缺（main 遗留语义，疑似"低价股优先补缺"）。
- **决策**：本轮**默认保留现状**，仅在 `application.yml` 补充配置注释；
  是否全量补缺待产品确认后另行评估（全量补缺会显著增加外部数据源配额消耗）。
- 相关配置：`application.yml` → `gap-fill.min-price-threshold: 1.00`。

## 3. P1-3：`trigger-screening` 一键路径语义恢复全窗口全量

- `POST /api/admin/trigger-screening` 的 `limit`/`windowDays` 已改为 `required=false`（无默认值）。
- **语义**：无参调用 = 全窗口 2~7 天 + 全量 symbol（与 `trigger-screening-async` 一致）；
  前端 `frontend/src/api/admin.ts triggerScreening()` 为无参调用，行为自动恢复为全量。
- **前端注意**：同步全量筛选可能耗时数分钟，前端需有等待/超时预期；
  若产品希望一键路径仍是"7 天窗口"，应在前端显式传参（`windowDays=7&limit=20`）而非依赖后端默认。

## 4. P3-4：changePercent 精度归一位置（代码改动 + 透传注释）

- 计算型涨跌幅（TigerStockServiceImpl / TiingoDataSourceStrategy / 盘后合并）统一
  `setScale(4, HALF_UP)`，与 DB `DECIMAL(12,4)` 对齐。
- 透传路径（数据源原始值 → 落库）不做服务层圆整：由 `DECIMAL(12,4)` 列在入库时隐式四舍五入，
  读取侧恒为 ≤4 位小数；`DataGapFillerServiceImpl.persist` 已注释说明。
- `StockDailyBarService` 出站 `strip` 仅去尾零，不做精度裁切（与 DB 精度约定一致）。
