#!/usr/bin/env bash
set -euo pipefail

HOST="${1:-0.0.0.0}"
PORT=5174

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
FRONTEND_DIR="${PROJECT_DIR}/frontend"

# ── Node 环境（nvm） ────────────────────────────────
export NVM_DIR="${NVM_DIR:-$HOME/.nvm}"
if [ -s "$NVM_DIR/nvm.sh" ]; then
  . "$NVM_DIR/nvm.sh"
  nvm use 24 --silent 2>/dev/null || nvm use 20 --silent 2>/dev/null || true
fi

# ── 进入前端目录 ───────────────────────────────────
if [ ! -d "$FRONTEND_DIR" ]; then
  echo "[ERROR] frontend directory not found: $FRONTEND_DIR"
  exit 1
fi
cd "$FRONTEND_DIR"

# ── 检查依赖 ────────────────────────────────────────
if [ ! -d node_modules ]; then
  echo "[frontend] node_modules missing — running npm install..."
  npm install
fi

# ── 杀掉旧进程 ──────────────────────────────────────
OLD_PID="$(lsof -ti "tcp:${PORT}" 2>/dev/null || true)"
if [ -n "$OLD_PID" ]; then
  echo "[frontend] killing old process on port ${PORT} (PID: ${OLD_PID})..."
  kill "$OLD_PID" 2>/dev/null || true
  for i in 1 2 3; do
    if ! lsof -ti "tcp:${PORT}" >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
  echo "[frontend] waiting 3s for port release..."
  sleep 3
fi

# ── 启动 Vite 开发服务器 ────────────────────────────
echo "[frontend] starting Vite dev server at http://${HOST}:${PORT}/"
npx vite --host "${HOST}" --port "${PORT}" &
VITE_PID=$!

# ── 等待启动成功 ────────────────────────────────────
for i in $(seq 1 10); do
  if curl -s -o /dev/null "http://${HOST}:${PORT}/" 2>/dev/null; then
    echo "[frontend] ✓ server ready — http://${HOST}:${PORT}/"
    echo "[frontend] press Ctrl+C to stop"
    wait "$VITE_PID"
    exit 0
  fi
  sleep 1
done

echo "[frontend] ⚠ may not be ready — check http://${HOST}:${PORT}/ (PID: ${VITE_PID})"
wait "$VITE_PID"
