-- 回填历史数据 high/low/changePercent
UPDATE stock_daily_bar
SET high_price = GREATEST(open_price, close_price),
    low_price = LEAST(open_price, close_price)
WHERE high_price IS NULL OR low_price IS NULL;

UPDATE stock_daily_bar s1
JOIN stock_daily_bar s2 ON s1.symbol = s2.symbol 
    AND s2.trade_date = DATE_SUB(s1.trade_date, INTERVAL 1 DAY)
SET s1.change_percent = ROUND((s1.close_price - s2.close_price) / s2.close_price * 100, 4)
WHERE s1.change_percent IS NULL;
