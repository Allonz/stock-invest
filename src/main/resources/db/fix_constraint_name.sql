-- 配合 Y8: 重命名 data_fill_task 表唯一约束
ALTER TABLE data_fill_task DROP INDEX uk_data_fill_task_symbol_missing_date;
ALTER TABLE data_fill_task ADD UNIQUE KEY uk_data_fill_task_symbol_trade_date (symbol, trade_date);
