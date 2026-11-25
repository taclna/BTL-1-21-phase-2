#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
config="$(cd "$script_dir/../nginx" && pwd)/nginx.kaggle.conf"
runtime="${PHASE2_INFRA_ROOT:-/kaggle/working/phase2-infrastructure}"
nginx_runtime="$runtime/nginx"
redis_runtime="$runtime/redis"
nginx_cpu="${NGINX_CPU:-3}"
redis_cpu="${REDIS_CPU:-2}"
redis_port="${REDIS_PORT:-6379}"

mkdir -p \
    "$nginx_runtime/logs" \
    "$nginx_runtime/temp/client" \
    "$nginx_runtime/temp/proxy" \
    "$redis_runtime"

for cpu in "$redis_cpu" "$nginx_cpu"; do
    if ! taskset -c "$cpu" true 2>/dev/null; then
        echo "CPU $cpu is unavailable; choose valid REDIS_CPU and NGINX_CPU values" >&2
        exit 1
    fi
done

if [[ -f "$redis_runtime/redis.pid" ]] && kill -0 "$(cat "$redis_runtime/redis.pid")" 2>/dev/null; then
    echo "Redis is already running"
else
    taskset -c "$redis_cpu" redis-server \
        --bind 127.0.0.1 \
        --protected-mode yes \
        --port "$redis_port" \
        --save "" \
        --appendonly no \
        --daemonize yes \
        --pidfile "$redis_runtime/redis.pid" \
        --logfile "$redis_runtime/redis.log" \
        --dir "$redis_runtime"
fi

for _ in {1..20}; do
    if [[ "$(redis-cli -h 127.0.0.1 -p "$redis_port" ping 2>/dev/null || true)" == "PONG" ]]; then
        break
    fi
    sleep 0.25
done
[[ "$(redis-cli -h 127.0.0.1 -p "$redis_port" ping)" == "PONG" ]]
if [[ ! -f "$redis_runtime/redis.pid" ]] || ! kill -0 "$(cat "$redis_runtime/redis.pid")" 2>/dev/null; then
    echo "The pinned Redis process did not start; check $redis_runtime/redis.log" >&2
    exit 1
fi

if [[ -f "$nginx_runtime/nginx.pid" ]] && kill -0 "$(cat "$nginx_runtime/nginx.pid")" 2>/dev/null; then
    echo "nginx is already running"
else
    nginx -t -p "$nginx_runtime/" -c "$config"
    taskset -c "$nginx_cpu" nginx -p "$nginx_runtime/" -c "$config"
fi

for _ in {1..20}; do
    if curl -fsS http://127.0.0.1:8080/nginx-health >/dev/null 2>&1; then
        break
    fi
    sleep 0.25
done
curl -fsS http://127.0.0.1:8080/nginx-health >/dev/null
if [[ ! -f "$nginx_runtime/nginx.pid" ]] || ! kill -0 "$(cat "$nginx_runtime/nginx.pid")" 2>/dev/null; then
    echo "The pinned nginx process did not start; check $nginx_runtime/logs/error.log" >&2
    exit 1
fi

echo "Redis is listening on 127.0.0.1:$redis_port (CPU $redis_cpu)"
echo "nginx is listening on 127.0.0.1:8080 (CPU $nginx_cpu)"
