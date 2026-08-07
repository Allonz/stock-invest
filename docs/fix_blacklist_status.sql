-- ============================================================
-- 归档：一次性数据修复脚本（R2 P2-9 从仓库根目录移入 docs/ 归档）
-- 状态：已执行，勿再执行。
-- 背景：symbol_blacklist 存量数据中 status='cleared' 但连续 404 计数 > 0 的
--       记录需回置为 'active'（原业务状态机修正）。
-- 内容保持原样，仅作历史留档。
-- ============================================================
UPDATE symbol_blacklist SET status = 'active' WHERE status = 'cleared' AND consecutive_404_count > 0
