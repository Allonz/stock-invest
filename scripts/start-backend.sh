#!/usr/bin/env bash                  
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROFILE="${1:-twelvedata}"
PORT="${SERVER_PORT:-8090}"

cd "${PROJECT_ROOT}"

VENV_ACTIVATE="${PROJECT_ROOT}/.venv/bin/activate"
if [[ -f "${VENV_ACTIVATE}" ]]; then
  # shellcheck source=/dev/null
  source "${VENV_ACTIVATE}"
  echo "[backend] venv: ${PROJECT_ROOT}/.venv (python: $(command -v python))"
else
  echo "[backend] WARN: no .venv at ${PROJECT_ROOT}/.venv — Python scripts may use system python"
fi

echo "[backend] project root: ${PROJECT_ROOT}"                      ·      
echo "[backend] active profile: ${PROFILE}"
echo "[backend] target port: ${PORT}"

EXISTING_PID="$(lsof -tiTCP:${PORT} -sTCP:LISTEN 2>/dev/null || true)"
if [[ -n "${EXISTING_PID}" ]]; then
  echo "[backend] port ${PORT} is in use by PID ${EXISTING_PID}, stopping it..."
  kill "${EXISTING_PID}" || true
  sleep 1
fi

echo "[backend] starting Spring Boot..."

mvn spring-boot:run -Dspring-boot.run.profiles="${PROFILE}"
