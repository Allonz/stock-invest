#!/usr/bin/env bash
set -euo pipefail

HOST="${1:-127.0.0.1}"
PORT="${2:-8090}"
PATH_NAME="${3:-/screener/daily}"
URL="http://${HOST}:${PORT}${PATH_NAME}"

echo "[frontend] this project uses Thymeleaf pages served by backend."
echo "[frontend] open: ${URL}"

if command -v xdg-open >/dev/null 2>&1; then
  xdg-open "${URL}" >/dev/null 2>&1 || true
elif command -v wslview >/dev/null 2>&1; then
  wslview "${URL}" >/dev/null 2>&1 || true
else
  echo "[frontend] auto-open not supported in current shell."
fi
