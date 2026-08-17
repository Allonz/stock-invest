#!/usr/bin/env python3
"""
clean_retry.py — 将黑名单 symbol 的补缺任务标记为 stopped。

用法:
  python3 clean_retry.py              # dry-run 模式，只打印影响行数
  python3 clean_retry.py --force      # 实际执行 UPDATE
"""
import os
import subprocess
import sys

MYSQL_PASSWORD = os.environ.get('MYSQL_PASSWORD')
if not MYSQL_PASSWORD:
    sys.exit('ERROR: MYSQL_PASSWORD environment variable is required')

DB_ARGS = ['-h127.0.0.1', '-P3307', '-uroot', '-p' + MYSQL_PASSWORD, 'stock_invest']
UPDATE_SQL = (
    "UPDATE data_fill_task t JOIN symbol_blacklist b ON t.symbol = b.symbol "
    "AND b.status = 'active' SET t.status = 'stopped', "
    "t.last_error = 'symbol is blacklisted' "
    "WHERE t.status IN ('pending', 'retrying')"
)
COUNT_SQL = (
    "SELECT count(*) as cnt FROM data_fill_task t "
    "JOIN symbol_blacklist b ON t.symbol = b.symbol AND b.status = 'active' "
    "WHERE t.status IN ('pending', 'retrying')"
)

def run_mysql(sql):
    return subprocess.run(
        ['mysql'] + DB_ARGS + ['-e', sql],
        capture_output=True, text=True
    )

if __name__ == '__main__':
    force = '--force' in sys.argv

    # 先查询影响行数
    r = run_mysql(COUNT_SQL)
    if r.returncode != 0:
        print(f'ERROR: 查询失败: {r.stderr}')
        sys.exit(1)
    print(f'将影响的行数: {r.stdout.strip()}')

    if not force:
        print('\n[DRY-RUN] 未执行 UPDATE。如需实际执行，请添加 --force 参数。')
        sys.exit(0)

    # 实际执行
    r2 = run_mysql(UPDATE_SQL)
    print(f'UPDATE 结果: {"ok" if r2.returncode == 0 else r2.stderr}')

    # 检查剩余
    r3 = run_mysql(
        "SELECT count(*) as cnt FROM data_fill_task WHERE status NOT IN ('completed','stopped')"
    )
    print(f'剩余未处理: {r3.stdout.strip()}')
