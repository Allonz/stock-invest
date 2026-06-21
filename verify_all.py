import subprocess
r = subprocess.run(
    ['mysql', '-h127.0.0.1', '-P3307', '-uroot', '-pallon23', 'stock_invest',
     '-e',
     "SELECT symbol, trade_date, created_at FROM stock_daily_bar ORDER BY RAND() LIMIT 5"],
    capture_output=True, text=True)
print(r.stdout)
r2 = subprocess.run(
    ['mysql', '-h127.0.0.1', '-P3307', '-uroot', '-pallon23', 'stock_invest',
     '-e',
     "SELECT symbol, created_at, updated_at FROM data_fill_task ORDER BY RAND() LIMIT 3"],
    capture_output=True, text=True)
print(r2.stdout)
r3 = subprocess.run(
    ['mysql', '-h127.0.0.1', '-P3307', '-uroot', '-pallon23', 'stock_invest',
     '-e',
     "SELECT NOW(), CURRENT_TIMESTAMP, 'current time' as note"],
    capture_output=True, text=True)
print(r3.stdout)
