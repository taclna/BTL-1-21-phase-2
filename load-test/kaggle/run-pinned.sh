#!/usr/bin/env bash
set -euo pipefail

if (($# < 2)); then
    echo "Usage: $0 <cpu-list> <command> [args...]" >&2
    exit 2
fi

cpu_list="$1"
shift

if [[ ! "$cpu_list" =~ ^[0-9]+([,-][0-9]+)*$ ]]; then
    echo "Invalid CPU list: $cpu_list" >&2
    exit 2
fi

exec taskset -c "$cpu_list" "$@"
