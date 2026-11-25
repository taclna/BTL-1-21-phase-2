#!/usr/bin/env bash
set -euo pipefail

missing_packages=()
command -v nginx >/dev/null 2>&1 || missing_packages+=(nginx)
command -v redis-server >/dev/null 2>&1 || missing_packages+=(redis-server)
command -v redis-cli >/dev/null 2>&1 || missing_packages+=(redis-tools)
command -v taskset >/dev/null 2>&1 || missing_packages+=(util-linux)

if ((${#missing_packages[@]})); then
    sudo apt-get update -qq
    sudo DEBIAN_FRONTEND=noninteractive apt-get install -y "${missing_packages[@]}"
fi

# Package installation can start distro-managed services. The measurement
# scripts own these processes so that their configuration and CPU pinning are
# reproducible.
sudo service nginx stop >/dev/null 2>&1 || true
sudo service redis-server stop >/dev/null 2>&1 || true

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
config="$(cd "$script_dir/../nginx" && pwd)/nginx.kaggle.conf"
runtime="${PHASE2_INFRA_ROOT:-/kaggle/working/phase2-infrastructure}"
nginx_runtime="$runtime/nginx"
redis_runtime="$runtime/redis-verify"

mkdir -p \
    "$nginx_runtime/logs" \
    "$nginx_runtime/temp/client" \
    "$nginx_runtime/temp/proxy" \
    "$redis_runtime"

nginx -t -p "$nginx_runtime/" -c "$config"

redis_socket="$redis_runtime/redis.sock"
redis_pid="$redis_runtime/redis.pid"
rm -f "$redis_socket" "$redis_pid"

cleanup() {
    if [[ -S "$redis_socket" ]]; then
        redis-cli -s "$redis_socket" shutdown nosave >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

redis-server \
    --port 0 \
    --unixsocket "$redis_socket" \
    --unixsocketperm 700 \
    --save "" \
    --appendonly no \
    --daemonize yes \
    --pidfile "$redis_pid" \
    --logfile "$redis_runtime/redis.log" \
    --dir "$redis_runtime"

for _ in {1..20}; do
    if [[ "$(redis-cli -s "$redis_socket" ping 2>/dev/null || true)" == "PONG" ]]; then
        break
    fi
    sleep 0.25
done

if [[ "$(redis-cli -s "$redis_socket" ping 2>/dev/null || true)" != "PONG" ]]; then
    echo "Redis verification failed" >&2
    exit 1
fi

taskset -c 0 true

echo "nginx: $(nginx -v 2>&1)"
echo "redis: $(redis-server --version)"
echo "taskset: $(taskset --version | head -n 1)"
echo "logical CPUs: $(nproc)"
echo "Kaggle infrastructure verification passed"
