#!/usr/bin/env bash
# G-4：手动集成回归 —— 跑平时被跳过的真实外部调用测试。
# 用法：
#   export TWELVEDATA_API_KEY=... TIINGO_API_TOKEN=...   # 可选，按需
#   bash scripts/run-integration-tests.sh [--skip-mvn]
# 说明：
#   - pytest 集成用例默认 skip（conftest.py --run-integration），本脚本显式打开；
#     无 key 的用例仍会自行 skip，不会失败。
#   - mvn test 含 @Tag("integration") 集成测试（需本地 MySQL，连接配置见
#     src/test/resources/application-integration.yml，密码经 MYSQL_PASSWORD 注入）。
set -euo pipefail
cd "$(dirname "$0")/.."

echo "=== [1/2] pytest（含 --run-integration 真实 API 用例） ==="
.venv/bin/python -m pytest tests/ -v --run-integration

if [[ "${1:-}" != "--skip-mvn" ]]; then
    echo "=== [2/2] mvn test（H2 单测 + MySQL 集成测试） ==="
    MYSQL_PASSWORD="${MYSQL_PASSWORD:?need MYSQL_PASSWORD for integration tests}" mvn test -B
fi

echo "=== 集成回归完成 ==="
