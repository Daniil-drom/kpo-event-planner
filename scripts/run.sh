#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if docker compose version >/dev/null 2>&1; then
  compose=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  compose=(docker-compose)
else
  printf '%s\n' 'Нужен Docker Compose v2 (docker compose или docker-compose).'
  exit 1
fi

if [ ! -f .env ]; then
  cp .env.example .env
  printf '%s\n' 'Создан .env для локального запуска.'
fi
mkdir -p test-reports
cleanup_tests() {
  "${compose[@]}" --profile test rm -sf tests db-test >/dev/null 2>&1 || true
}
trap cleanup_tests EXIT

"${compose[@]}" --profile test up --build --abort-on-container-exit --exit-code-from tests tests
"${compose[@]}" up -d --build --wait app
printf '%s\n' 'Приложение запущено. Адрес: http://localhost:8080 (или APP_PORT из .env).'
printf '%s\n' 'Отчёты тестов: test-reports/'
