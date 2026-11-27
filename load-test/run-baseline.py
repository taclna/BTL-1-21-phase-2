#!/usr/bin/env python3
"""Run repeatable k6 workloads and sample Linux, PostgreSQL, and Prometheus metrics."""
import argparse
import csv
import datetime as dt
import json
import os
import pathlib
import platform
import re
import subprocess
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
LOAD = ROOT / "load-test"

POSTGRES_ACTIVITY_QUERY = """
SELECT
    count(*) FILTER (WHERE wait_event_type = 'Lock'),
    count(*) FILTER (WHERE state = 'active'),
    count(*) FILTER (WHERE state = 'idle in transaction')
FROM pg_stat_activity
WHERE datname = current_database()
  AND application_name IS DISTINCT FROM 'phase2-runner'
"""

POSTGRES_DEADLOCK_QUERY = """
SELECT deadlocks
FROM pg_stat_database
WHERE datname = current_database()
"""


def git_value(*args):
    result = subprocess.run(["git", *args], cwd=ROOT, text=True, capture_output=True, check=True)
    return result.stdout.strip()


def cpu_model():
    try:
        for line in pathlib.Path("/proc/cpuinfo").read_text().splitlines():
            if line.lower().startswith("model name"):
                return line.split(":", 1)[1].strip()
    except OSError:
        pass
    return platform.processor() or "unknown"


def total_memory_mib():
    try:
        for line in pathlib.Path("/proc/meminfo").read_text().splitlines():
            if line.startswith("MemTotal:"):
                return round(int(line.split()[1]) / 1024, 1)
    except OSError:
        pass
    return None


def cgroup_limits():
    cpu_quota = None
    memory_limit_mib = None
    try:
        quota, period = pathlib.Path("/sys/fs/cgroup/cpu.max").read_text().split()
        if quota != "max":
            cpu_quota = round(int(quota) / int(period), 3)
    except (OSError, ValueError, ZeroDivisionError):
        pass
    try:
        limit = pathlib.Path("/sys/fs/cgroup/memory.max").read_text().strip()
        if limit != "max":
            memory_limit_mib = round(int(limit) / (1024 * 1024), 1)
    except (OSError, ValueError):
        pass
    return cpu_quota, memory_limit_mib


def version(command):
    try:
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=10)
        return result.stdout.strip().splitlines()[0]
    except (OSError, subprocess.SubprocessError, IndexError):
        return "not available"


def utc_now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def jdbc_postgres_parts(url):
    if not url:
        return {}
    match = re.match(r"^jdbc:postgresql://([^/:?#]+)(?::(\d+))?/([^?]+)", url)
    if not match:
        return {}
    return {
        "host": match.group(1),
        "port": match.group(2) or "5432",
        "database": match.group(3),
    }


def postgres_environment(args):
    jdbc = jdbc_postgres_parts(os.environ.get("DB_URL"))
    env = os.environ.copy()
    values = {
        "PGHOST": args.db_host or os.environ.get("PGHOST") or jdbc.get("host"),
        "PGPORT": str(args.db_port or os.environ.get("PGPORT") or jdbc.get("port") or "5432"),
        "PGDATABASE": args.db_name or os.environ.get("PGDATABASE") or jdbc.get("database"),
        "PGUSER": args.db_user or os.environ.get("PGUSER") or os.environ.get("DB_USERNAME"),
        "PGPASSWORD": os.environ.get("PGPASSWORD") or os.environ.get("DB_PASSWORD"),
        "PGAPPNAME": "phase2-runner",
    }
    for key, value in values.items():
        if value:
            env[key] = value
    public = {
        "host": values["PGHOST"],
        "port": values["PGPORT"],
        "database": values["PGDATABASE"],
        "user": values["PGUSER"],
    }
    return env, public


def run_psql(psql, env, query, timeout_seconds):
    try:
        result = subprocess.run(
            [psql, "-X", "-w", "-q", "-A", "-t", "-F", ",", "-v", "ON_ERROR_STOP=1", "-c", query],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout_seconds,
            env=env,
        )
    except OSError as error:
        raise RuntimeError(f"cannot start psql: {error}") from error
    except subprocess.TimeoutExpired as error:
        raise RuntimeError(f"psql timed out after {timeout_seconds}s") from error
    if result.returncode:
        message = result.stderr.strip().replace("\n", " ") or f"exit code {result.returncode}"
        raise RuntimeError(f"psql failed: {message}")
    return result.stdout.strip()


def read_deadlocks(psql, env, timeout_seconds):
    value = run_psql(psql, env, POSTGRES_DEADLOCK_QUERY, timeout_seconds)
    try:
        return int(value)
    except ValueError as error:
        raise RuntimeError(f"unexpected deadlock counter from psql: {value!r}") from error


def sample_postgres(psql, env, timeout_seconds, writer):
    timestamp = utc_now()
    try:
        value = run_psql(psql, env, POSTGRES_ACTIVITY_QUERY, timeout_seconds)
        fields = value.split(",")
        if len(fields) != 3:
            raise RuntimeError(f"unexpected pg_stat_activity row: {value!r}")
        lock_waiters, active_connections, idle_in_transaction = (int(field) for field in fields)
        row = {
            "timestampUtc": timestamp,
            "available": True,
            "lockWaiters": lock_waiters,
            "activeConnections": active_connections,
            "idleInTransaction": idle_in_transaction,
            "sampleError": "",
        }
    except (RuntimeError, ValueError) as error:
        row = {
            "timestampUtc": timestamp,
            "available": False,
            "lockWaiters": "",
            "activeConnections": "",
            "idleInTransaction": "",
            "sampleError": str(error),
        }
    writer.writerow(row)
    return bool(row["available"])


def fetch_prometheus(url, timeout_seconds):
    request = urllib.request.Request(url, headers={"Accept": "text/plain"})
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            return response.status, response.read().decode("utf-8", errors="replace")
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise RuntimeError(f"Prometheus scrape failed: {error}") from error


def sample_prometheus(url, timeout_seconds, output):
    timestamp = utc_now()
    try:
        status, body = fetch_prometheus(url, timeout_seconds)
        output.write(f"# runner_sample timestampUtc={timestamp} status={status}\n")
        output.write(body)
        if body and not body.endswith("\n"):
            output.write("\n")
        output.write("\n")
        output.flush()
        return True
    except RuntimeError as error:
        message = str(error).replace("\n", " ")
        output.write(f"# runner_sample_error timestampUtc={timestamp} error={message}\n\n")
        output.flush()
        return False


def proc_table():
    table = {}
    for entry in pathlib.Path("/proc").iterdir():
        if not entry.name.isdigit():
            continue
        try:
            stat = (entry / "stat").read_text()
            fields = stat[stat.rfind(")") + 2:].split()
            parent = int(fields[1])
            ticks = int(fields[11]) + int(fields[12])
            rss_kib = 0
            for line in (entry / "status").read_text().splitlines():
                if line.startswith("VmRSS:"):
                    rss_kib = int(line.split()[1])
                    break
            table[int(entry.name)] = (parent, ticks, rss_kib)
        except (OSError, ValueError, IndexError):
            continue
    return table


def process_group(root_pid, table):
    if not root_pid or root_pid not in table:
        return {}
    pids = {root_pid}
    changed = True
    while changed:
        changed = False
        for pid, (parent, _, _) in table.items():
            if parent in pids and pid not in pids:
                pids.add(pid)
                changed = True
    return {pid: table[pid] for pid in pids if pid in table}


def sample_resources(k6_pid, api_pid, db_pid, writer, previous, previous_time, cpu_count):
    now = time.monotonic()
    table = proc_table()
    timestamp = dt.datetime.now(dt.timezone.utc).isoformat()
    for name, pid in (("api", api_pid), ("database", db_pid), ("k6", k6_pid)):
        group = process_group(pid, table)
        ticks = sum(values[1] for values in group.values())
        rss_kib = sum(values[2] for values in group.values())
        last_ticks = previous.get(name, ticks)
        elapsed = max(now - previous_time, 0.001)
        cpu_seconds = max(ticks - last_ticks, 0) / os.sysconf("SC_CLK_TCK")
        writer.writerow({
            "timestampUtc": timestamp,
            "group": name,
            "available": bool(group),
            "processCount": len(group),
            "cpuPercentMachine": round(cpu_seconds / elapsed / cpu_count * 100, 3),
            "summedRssMiB": round(rss_kib / 1024, 3),
        })
        previous[name] = ticks
    return now


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=("movie-read", "booking", "contention", "stress-read"), default="booking")
    parser.add_argument("--app-ref", default=os.environ.get("APP_REF"),
                        help="application tag/branch being measured")
    parser.add_argument("--app-commit", default=os.environ.get("APP_GIT_COMMIT"),
                        help="resolved application commit being measured")
    parser.add_argument("--k6", default=os.environ.get("K6", "k6"))
    parser.add_argument("--levels", type=int, nargs="+", default=(10, 25, 50))
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--api-pid", type=int, default=int(os.environ.get("API_PID", "0")))
    parser.add_argument("--database-pid", type=int, default=int(os.environ.get("DATABASE_PID", "0")))
    parser.add_argument("--psql", default=os.environ.get("PSQL", "psql"),
                        help="psql executable used for monitoring (default: psql)")
    parser.add_argument("--db-host", default=None, help="monitoring database host; defaults to PGHOST/DB_URL")
    parser.add_argument("--db-port", type=int, default=None, help="monitoring database port; defaults to PGPORT/DB_URL")
    parser.add_argument("--db-name", default=None, help="monitoring database name; defaults to PGDATABASE/DB_URL")
    parser.add_argument("--db-user", default=None, help="monitoring database user; defaults to PGUSER/DB_USERNAME")
    parser.add_argument("--postgres-interval", type=float, default=1.0,
                        help="seconds between pg_stat_activity samples (default: 1)")
    parser.add_argument("--prometheus-url", default=os.environ.get(
        "PROMETHEUS_URL",
        os.environ.get("BASE_URL", "http://127.0.0.1:8080").rstrip("/") + "/actuator/prometheus",
    ))
    parser.add_argument("--prometheus-interval", type=float, default=5.0,
                        help="seconds between Prometheus scrapes (default: 5)")
    parser.add_argument("--monitor-timeout", type=float, default=2.0,
                        help="timeout for each monitoring operation (default: 2)")
    parser.add_argument("--skip-postgres", action="store_true",
                        help="disable PostgreSQL activity/deadlock evidence")
    parser.add_argument("--skip-prometheus", action="store_true",
                        help="disable Prometheus evidence")
    args = parser.parse_args()
    if args.repeats < 3:
        parser.error("use at least three repeats")
    if args.scenario != "stress-read" and any(level < 1 or level > 50 for level in args.levels):
        parser.error("use VU levels from 1 to 50")
    if args.scenario == "stress-read":
        # S5 ramps its own arrival-rate stages (STAGES env); one k6 run covers every stage.
        args.levels = [None]
    if args.scenario == "contention" and len(args.levels) * args.repeats > 9:
        parser.error("contention fixture has nine independent seats; use the default 10/25/50 levels and three repeats")
    if not pathlib.Path("/proc/self/stat").exists():
        parser.error("resource sampling currently requires Linux (Kaggle)")
    if args.postgres_interval <= 0 or args.prometheus_interval <= 0 or args.monitor_timeout <= 0:
        parser.error("monitor intervals and timeout must be positive")

    harness_commit = git_value("rev-parse", "HEAD")
    app_commit = args.app_commit or harness_commit
    app_ref = args.app_ref or "current-worktree"
    dirty = git_value("status", "--porcelain", "--untracked-files=all", "--", "src/main", "pom.xml", "load-test")
    if dirty:
        parser.error("commit source and load-test scripts first so the report identifies the measured revision")

    cpu_count = os.cpu_count() or 1
    cpu_quota, memory_limit_mib = cgroup_limits()
    postgres_env, postgres_public = postgres_environment(args)
    postgres_enabled = not args.skip_postgres
    prometheus_enabled = not args.skip_prometheus

    if postgres_enabled:
        try:
            read_deadlocks(args.psql, postgres_env, args.monitor_timeout)
        except RuntimeError as error:
            parser.error(f"PostgreSQL monitoring preflight failed: {error}")
    if prometheus_enabled:
        try:
            fetch_prometheus(args.prometheus_url, args.monitor_timeout)
        except RuntimeError as error:
            parser.error(f"Prometheus monitoring preflight failed: {error}")

    run_root = LOAD / "results" / dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d-%H%M%S")
    run_root.mkdir(parents=True, exist_ok=False)
    metadata = {
        "collectedAtUtc": utc_now(),
        # Keep gitCommit for compatibility with existing summary scripts. It
        # now identifies the application under test, not the harness checkout.
        "gitCommit": app_commit,
        "appRef": app_ref,
        "appGitCommit": app_commit,
        "harnessGitCommit": harness_commit,
        "scenario": args.scenario,
        "levels": args.levels,
        "repeats": args.repeats,
        "warmupSeconds": 30 if args.scenario in ("movie-read", "booking", "stress-read") else 0,
        "measureSeconds": (120 if args.scenario in ("movie-read", "booking")
                           else "STAGES x STAGE_SECONDS plus ramps" if args.scenario == "stress-read"
                           else "one iteration per VU"),
        "stressStages": os.environ.get("STAGES", "250,500,1000,1500,2000,3000") if args.scenario == "stress-read" else None,
        "cpuModel": cpu_model(),
        "logicalCpuCount": cpu_count,
        "containerCpuQuota": cpu_quota,
        "totalRamMiB": total_memory_mib(),
        "containerRamLimitMiB": memory_limit_mib,
        "os": platform.platform(),
        "python": platform.python_version(),
        "k6": version([args.k6, "version"]),
        "java": version(["java", "-version"]),
        "postgres": version(["postgres", "--version"]),
        "psql": version([args.psql, "--version"]),
        "apiPid": args.api_pid or None,
        "databasePid": args.database_pid or None,
        "resourceNote": "summed RSS can double-count shared pages; CPU is normalized to logical CPUs",
        "monitoring": {
            "postgres": {
                "enabled": postgres_enabled,
                "intervalSeconds": args.postgres_interval if postgres_enabled else None,
                **postgres_public,
            },
            "prometheus": {
                "enabled": prometheus_enabled,
                "url": args.prometheus_url if prometheus_enabled else None,
                "intervalSeconds": args.prometheus_interval if prometheus_enabled else None,
            },
            "timeoutSeconds": args.monitor_timeout,
        },
    }
    (run_root / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")

    contention_index = 0
    for level in args.levels:
        for repeat in range(1, args.repeats + 1):
            name = f"{args.scenario}-run{repeat}" if level is None else f"{args.scenario}-{level}vu-run{repeat}"
            raw_path = run_root / f"{name}-raw.json"
            summary_path = run_root / f"{name}.json"
            resource_path = run_root / f"{name}-resources.csv"
            postgres_path = run_root / f"{name}-postgres.csv"
            deadlock_path = run_root / f"{name}-deadlocks.json"
            prometheus_path = run_root / f"{name}-prometheus.txt"
            env = os.environ.copy()
            warmup_seconds = 30 if args.scenario in ("movie-read", "booking", "stress-read") else 0
            env.update({
                "WARMUP_SECONDS": str(warmup_seconds),
                "GIT_COMMIT": app_commit,
                "SUMMARY_PATH": str(summary_path),
            })
            if level is not None:
                env.update({
                    "VUS": str(level),
                    "DURATION_SECONDS": "120" if args.scenario in ("movie-read", "booking") else "1",
                })
            if args.scenario == "contention":
                contention_index += 1
                env["CONTENTION_RUN_INDEX"] = str(contention_index)
            command = [args.k6, "run", "--quiet", "--include-system-env-vars", "--out", f"json={raw_path}", str(LOAD / f"{args.scenario}.js")]

            deadlocks_before = None
            deadlocks_before_at = None
            deadlocks_before_error = None
            if postgres_enabled:
                deadlocks_before_at = utc_now()
                try:
                    deadlocks_before = read_deadlocks(args.psql, postgres_env, args.monitor_timeout)
                except RuntimeError as error:
                    deadlocks_before_error = str(error)

            with (run_root / f"{name}.log").open("w") as stdout, \
                    (run_root / f"{name}-error.log").open("w") as stderr, \
                    resource_path.open("w", newline="") as resource_file, \
                    postgres_path.open("w", newline="") as postgres_file, \
                    prometheus_path.open("w", encoding="utf-8") as prometheus_file:
                resource_writer = csv.DictWriter(resource_file, fieldnames=("timestampUtc", "group", "available", "processCount", "cpuPercentMachine", "summedRssMiB"))
                resource_writer.writeheader()
                postgres_writer = csv.DictWriter(postgres_file, fieldnames=("timestampUtc", "available", "lockWaiters", "activeConnections", "idleInTransaction", "sampleError"))
                postgres_writer.writeheader()
                process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=stdout, stderr=stderr)
                previous = {}
                previous_time = time.monotonic()
                started_at = previous_time
                next_resource_sample = started_at
                next_postgres_sample = started_at
                next_prometheus_sample = started_at
                while process.poll() is None:
                    now = time.monotonic()
                    if now >= next_resource_sample:
                        previous_time = sample_resources(
                            process.pid, args.api_pid, args.database_pid, resource_writer,
                            previous, previous_time, cpu_count,
                        )
                        resource_file.flush()
                        next_resource_sample = time.monotonic() + 1.0
                    if postgres_enabled and now >= next_postgres_sample:
                        sample_postgres(args.psql, postgres_env, args.monitor_timeout, postgres_writer)
                        postgres_file.flush()
                        next_postgres_sample = time.monotonic() + args.postgres_interval
                    if prometheus_enabled and now >= next_prometheus_sample:
                        sample_prometheus(
                            args.prometheus_url, args.monitor_timeout, prometheus_file,
                        )
                        next_prometheus_sample = time.monotonic() + args.prometheus_interval
                    deadlines = [next_resource_sample]
                    if postgres_enabled:
                        deadlines.append(next_postgres_sample)
                    if prometheus_enabled:
                        deadlines.append(next_prometheus_sample)
                    delay = max(min(deadlines) - time.monotonic(), 0.05)
                    time.sleep(min(delay, 0.5))
                code = process.wait()

            deadlocks_after = None
            deadlocks_after_at = None
            deadlocks_after_error = None
            if postgres_enabled:
                deadlocks_after_at = utc_now()
                try:
                    deadlocks_after = read_deadlocks(args.psql, postgres_env, args.monitor_timeout)
                except RuntimeError as error:
                    deadlocks_after_error = str(error)
            deadlock_result = {
                "enabled": postgres_enabled,
                "before": deadlocks_before,
                "beforeSampledAtUtc": deadlocks_before_at,
                "beforeError": deadlocks_before_error,
                "after": deadlocks_after,
                "afterSampledAtUtc": deadlocks_after_at,
                "afterError": deadlocks_after_error,
                "delta": (
                    deadlocks_after - deadlocks_before
                    if deadlocks_before is not None and deadlocks_after is not None
                    else None
                ),
            }
            deadlock_path.write_text(json.dumps(deadlock_result, indent=2) + "\n", encoding="utf-8")
            if code:
                raise SystemExit(f"k6 failed for {name}; inspect logs in {run_root}")
            print(f"Completed {name}", flush=True)
    print(f"Results: {run_root}")


if __name__ == "__main__":
    main()
