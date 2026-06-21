import os
for f in ['check_timestamp.py', 'fix_db_timezone.py', 'fix_db_timezone2.py', 'verify_time.py', 'verify_all.py', 'check_schema.py', 'check_types.py', 'check_volume.py', 'check_retry.py', 'check_retry2.py', 'check_blacklist.py', 'clean_retry.py', 'cleanup.py']:
    p = os.path.expanduser('~/application/stock-invest/' + f)
    if os.path.exists(p):
        os.remove(p)
