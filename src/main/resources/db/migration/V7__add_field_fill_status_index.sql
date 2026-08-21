-- ============================================================
-- V7: stock_daily_bar.field_fill_status 查询索引（评审 C-9）
-- 背景：findUnchecked / findByFieldFillStatus 依赖 field_fill_status 列
--   （V5 引入）但未建索引，大表上全表扫描；补缺增补调度按状态批量拉取
--   时每次全扫，数据量增长后耗时不可控。
-- 说明：MySQL 8 语法 ADD KEY 等价于 ADD INDEX；Flyway 版本记录保证只执行一次。
-- ============================================================

ALTER TABLE stock_daily_bar
  ADD KEY idx_sdb_field_fill_status (field_fill_status);
