import subprocess
# Check trading_calendar more carefully
r = subprocess.run(
    ['mysql', '-h127.0.0.1', '-P3307', '-uroot', '-pallon23', 'stock_invest',
     '-e',
     "SELECT trade_date, created_at, updated_at FROM trading_calendar WHERE trade_date='2026-05-31' OR trade_date='2026-05-30' OR trade_date='2026-06-01' ORDER BY trade_date"],
    capture_output=True, text=True)
print(r.stdout)

# Check if any stock_data_source_priority rows have mismatch
r2 = subprocess.run(
    ['mysql', '-h127.0.0.1', '-P3307', '-uroot', '-pallon23', 'stock_invest',
     '-e',
     "SELECT symbol, data_source, last_success_time, created_at FROM stock_data_source_priority WHERE ABS(TIMESTAMPDIFF(SECOND, last_success_time, created_at)) > 1 LIMIT 10"],
    capture_output=True, text=True)
print("Mismatched rows (created_at vs last_success_time diff > 1s):")
print(r2.stdout or "(none)")
