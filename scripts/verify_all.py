import os
import subprocess
import sys

MYSQL_PASSWORD = os.environ.get('MYSQL_PASSWORD')
if not MYSQL_PASSWORD:
    sys.exit('ERROR: MYSQL_PASSWORD environment variable is required')

MYSQL_ARGS = ['-h127.0.0.1', '-P3307', '-uroot', '-p' + MYSQL_PASSWORD, 'stock_invest']
r = subprocess.run(
    ['mysql'] + MYSQL_ARGS + [
     '-e',
     "SELECT symbol, trade_date, created_at FROM stock_daily_bar ORDER BY RAND() LIMIT 5"],
    capture_output=True, text=True)
print(r.stdout)
r2 = subprocess.run(
    ['mysql'] + MYSQL_ARGS + [
     '-e',
     "SELECT symbol, created_at, updated_at FROM data_fill_task ORDER BY RAND() LIMIT 3"],
    capture_output=True, text=True)
print(r2.stdout)
r3 = subprocess.run(
    ['mysql'] + MYSQL_ARGS + [
     '-e',
     "SELECT NOW(), CURRENT_TIMESTAMP, 'current time' as note"],
    capture_output=True, text=True)
print(r3.stdout)
