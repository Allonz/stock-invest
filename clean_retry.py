import subprocess

# Execute cleanup
r = subprocess.run(
    ['mysql', '-h127.0.0.1', '-P3307', '-uroot', '-pallon23', 'stock_invest', '-e',
     "UPDATE data_fill_task t JOIN symbol_blacklist b ON t.symbol = b.symbol AND b.status = 'active' SET t.status = 'stopped', t.last_error = 'symbol is blacklisted' WHERE t.status IN ('pending', 'retrying')"],
    capture_output=True, text=True)
print('UPDATE affected:', 'ok' if r.returncode == 0 else r.stderr)

# Check remaining
r2 = subprocess.run(
    ['mysql', '-h127.0.0.1', '-P3307', '-uroot', '-pallon23', 'stock_invest', '-e',
     "SELECT count(*) as cnt FROM data_fill_task WHERE status NOT IN ('completed','stopped')"],
    capture_output=True, text=True)
print('REMAINING:', r2.stdout)
