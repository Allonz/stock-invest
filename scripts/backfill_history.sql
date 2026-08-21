-- 回填历史数据 high/low/changePercent
UPDATE stock_daily_bar
SET high_price = GREATEST(open_price, close_price),
    low_price = LEAST(open_price, close_price)
WHERE high_price IS NULL OR low_price IS NULL;

-- 评审 F-6：change_percent 回填改用窗口函数取"上一交易日"（LAG over symbol 按 trade_date 排序），
-- 修复原实现 JOIN DATE_SUB(..., INTERVAL 1 DAY)（上一自然日）在周一/节假日后首个交易日
-- 永远 JOIN 不到记录的漏算问题。
UPDATE stock_daily_bar s1
JOIN (
    SELECT symbol, trade_date,
           LAG(close_price) OVER (PARTITION BY symbol ORDER BY trade_date) AS prev_close
    FROM stock_daily_bar
) s2 ON s1.symbol = s2.symbol AND s1.trade_date = s2.trade_date
SET s1.change_percent = ROUND((s1.close_price - s2.prev_close) / s2.prev_close * 100, 4)
WHERE s1.change_percent IS NULL
  AND s2.prev_close IS NOT NULL
  AND s2.prev_close <> 0;
