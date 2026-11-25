#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
config="$(cd "$script_dir/../nginx" && pwd)/nginx.kaggle.conf"
runtime="${PHASE2_INFRA_ROOT:-/kaggle/working/phase2-infrastructure}"
nginx_runtime="$runtime/nginx"
redis_runtime="$runtime/redis"
redis_port="${REDIS_PORT:-6379}"

if [[ -f "$nginx_runtime/nginx.pid" ]] && kill -0 "$(cat "$nginx_runtime/nginx.pid")" 2>/dev/null; then
    nginx -p "$nginx_runtime/" -c "$config" -s quit || true
fi

if [[ -f "$redis_runtime/redis.pid" ]] && kill -0 "$(cat "$redis_runtime/redis.pid")" 2>/dev/null; then
    redis-cli -h 127.0.0.1 -p "$redis_port" shutdown nosave || true
fi

echo "Kaggle nginx and Redis stopped"
