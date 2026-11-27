#!/usr/bin/env python3
"""Build and measure multiple application refs with one fixed load-test harness.

This script is intended for Kaggle/Linux. The harness checkout remains on the
measurement-infrastructure branch while a separate application checkout moves
between tags or branches. Each ref gets a clean PostgreSQL schema and its own
API process, seed data, runner output, and application log.
"""

import argparse
import datetime as dt
import json
import os
import pathlib
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request


HARNESS_ROOT = pathlib.Path(__file__).resolve().parent.parent
LOAD_ROOT = HARNESS_ROOT / "load-test"
RESULTS_ROOT = LOAD_ROOT / "results"
LOCAL_DATABASE_HOSTS = {"127.0.0.1", "localhost", "::1"}


def run(command, *, cwd=None, env=None, capture=False):
    return subprocess.run(
        command,
        cwd=cwd,
        env=env,
        text=True,
        check=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
    )


def git_value(repo, *args):
    return run(["git", *args], cwd=repo, capture=True).stdout.strip()


def default_repo_url():
    try:
        return git_value(HARNESS_ROOT, "remote", "get-url", "origin")
    except subprocess.CalledProcessError:
        return "https://github.com/taclna/BTL-1-21-phase-2.git"


def safe_name(value):
    normalized = re.sub(r"[^A-Za-z0-9._-]+", "-", value).strip("-.")
    return normalized or "ref"


def ensure_app_checkout(repo_url, app_dir):
    if not app_dir.exists():
        app_dir.parent.mkdir(parents=True, exist_ok=True)
        # Use a normal clone so the index and working tree start in sync.
        # A --no-checkout clone reports every tracked file as deleted, which
        # correctly trips the dirty-worktree safety check below.
        run(["git", "clone", repo_url, str(app_dir)])
    if not (app_dir / ".git").exists():
        raise RuntimeError(f"application directory is not a Git checkout: {app_dir}")
    dirty = git_value(app_dir, "status", "--porcelain", "--untracked-files=no")
    if dirty:
        checked_out_entries = [path for path in app_dir.iterdir() if path.name != ".git"]
        if not checked_out_entries:
            raise RuntimeError(
                "application checkout has no working-tree files; remove the dedicated "
                f"checkout and rerun: {app_dir}"
            )
        raise RuntimeError(f"application checkout has tracked changes; clean it first:\n{dirty}")
    run([
        "git", "fetch", "--prune", "origin",
        "+refs/heads/*:refs/remotes/origin/*",
    ], cwd=app_dir)
    run(["git", "fetch", "--force", "--tags", "origin"], cwd=app_dir)


def resolve_ref(app_dir, app_ref):
    candidates = (
        f"refs/tags/{app_ref}",
        f"refs/remotes/origin/{app_ref}",
        app_ref,
    )
    for candidate in candidates:
        result = subprocess.run(
            ["git", "rev-parse", "--verify", f"{candidate}^{{commit}}"],
            cwd=app_dir,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
        if result.returncode == 0:
            return result.stdout.strip()
    raise RuntimeError(f"cannot resolve application ref: {app_ref}")


def checkout_ref(app_dir, app_ref):
    dirty = git_value(app_dir, "status", "--porcelain", "--untracked-files=no")
    if dirty:
        raise RuntimeError(f"application checkout became dirty before {app_ref}:\n{dirty}")
    commit = resolve_ref(app_dir, app_ref)
    run(["git", "checkout", "--detach", commit], cwd=app_dir)
    return commit


def postgres_values():
    jdbc_match = re.match(
        r"^jdbc:postgresql://([^/:?#]+)(?::(\d+))?/([^?]+)",
        os.environ.get("DB_URL", ""),
    )
    host = os.environ.get("PGHOST") or (jdbc_match.group(1) if jdbc_match else None)
    port = os.environ.get("PGPORT") or (jdbc_match.group(2) if jdbc_match and jdbc_match.group(2) else "5432")
    database = os.environ.get("PGDATABASE") or (jdbc_match.group(3) if jdbc_match else None)
    user = os.environ.get("PGUSER") or os.environ.get("DB_USERNAME")
    if not all((host, port, database, user)):
        raise RuntimeError("set DB_URL/DB_USERNAME or PGHOST/PGPORT/PGDATABASE/PGUSER")
    return host, port, database, user


def reset_test_schema(psql):
    host, _, database, _ = postgres_values()
    if host not in LOCAL_DATABASE_HOSTS or not database.startswith("cinema_load"):
        raise RuntimeError(
            "refusing to reset a non-local or non-load-test database "
            f"(host={host!r}, database={database!r})"
        )
    env = os.environ.copy()
    env["PGAPPNAME"] = "phase2-matrix"
    sql = (
        "DROP SCHEMA IF EXISTS public CASCADE; "
        "CREATE SCHEMA public AUTHORIZATION CURRENT_USER; "
        "GRANT ALL ON SCHEMA public TO PUBLIC;"
    )
    run([psql, "-X", "-w", "-v", "ON_ERROR_STOP=1", "-c", sql], env=env)


def build_application(app_dir):
    run(["bash", "./mvnw", "-B", "-DskipTests", "clean", "package"], cwd=app_dir)
    jars = [
        path for path in (app_dir / "target").glob("*.jar")
        if not path.name.endswith("-sources.jar") and not path.name.endswith("-javadoc.jar")
    ]
    if len(jars) != 1:
        raise RuntimeError(f"expected exactly one runnable JAR, found: {jars}")
    return jars[0]


def wait_for_api(process, base_url, log_path, timeout_seconds):
    deadline = time.monotonic() + timeout_seconds
    readiness = base_url.rstrip("/") + "/actuator/health/readiness"
    while time.monotonic() < deadline:
        if process.poll() is not None:
            break
        try:
            with urllib.request.urlopen(readiness, timeout=2) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError):
            pass
        time.sleep(1)
    tail = ""
    try:
        tail = log_path.read_text(encoding="utf-8", errors="replace")[-12000:]
    except OSError:
        pass
    raise RuntimeError(f"API did not become ready at {readiness}\n{tail}")


def stop_process(process):
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()


def resolve_seed_scope(seed_scope, scenario):
    if seed_scope != "auto":
        return seed_scope
    return "movie" if scenario == "movie-read" else "all"


def seed_application(env, movie_count, deleted_count, max_vus, seed_scope):
    seed_env = env.copy()
    seed_env.update({
        "MOVIE_COUNT": str(movie_count),
        "DELETED_COUNT": str(deleted_count),
        "SEED_SCOPE": seed_scope,
        "VUS": str(max_vus),
        "FIXTURES": str(LOAD_ROOT / "fixtures.local.json"),
    })
    run(["node", "load-test/seed.mjs"], cwd=HARNESS_ROOT, env=seed_env)
    return seed_env


def seed_evidence(seed_scope):
    """Dataset facts for the manifest; never copies tokens from the fixture file."""
    evidence = {"scope": seed_scope}
    try:
        saved = json.loads((LOAD_ROOT / "fixtures.local.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return evidence
    evidence["movies"] = len(saved.get("movieIds") or [])
    if saved.get("largeStats"):
        evidence["largeStats"] = saved["largeStats"]
    return evidence


def result_directories():
    if not RESULTS_ROOT.exists():
        return set()
    return {
        path.resolve() for path in RESULTS_ROOT.iterdir()
        if path.is_dir() and not path.name.startswith("matrix-")
    }


def run_measurement(args, env, app_ref, app_commit, api_pid):
    before = result_directories()
    command = [
        sys.executable,
        str(LOAD_ROOT / "run-baseline.py"),
        "--scenario", args.scenario,
        "--levels", *(str(level) for level in args.levels),
        "--repeats", str(args.repeats),
        "--api-pid", str(api_pid),
        "--database-pid", str(args.database_pid),
        "--app-ref", app_ref,
        "--app-commit", app_commit,
    ]
    if args.skip_postgres:
        command.append("--skip-postgres")
    if args.skip_prometheus:
        command.append("--skip-prometheus")
    run(command, cwd=HARNESS_ROOT, env=env)
    created = sorted(result_directories() - before)
    if len(created) != 1:
        raise RuntimeError(f"expected one runner result directory, found: {created}")
    return created[0]


def write_manifest(path, harness_commit, entries):
    payload = {
        "createdAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "harnessGitCommit": harness_commit,
        "applications": entries,
    }
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-ref", action="append", required=True,
                        help="application tag/branch; repeat for each variant")
    parser.add_argument("--repo-url", default=default_repo_url())
    parser.add_argument("--app-dir", type=pathlib.Path,
                        default=pathlib.Path(os.environ.get("APP_CHECKOUT", "/kaggle/working/cinema-app")))
    parser.add_argument("--scenario", choices=("movie-read", "booking", "contention", "stress-read"), default="contention")
    parser.add_argument("--seed-scope", choices=("auto", "movie", "all", "large"), default="auto",
                        help="seed.mjs scope; auto = movie for movie-read, all otherwise. "
                             "Use large for E4/S5 (tune with LARGE_* environment variables)")
    parser.add_argument("--levels", type=int, nargs="+", default=(2,))
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--database-pid", type=int,
                        default=int(os.environ.get("DATABASE_PID", "0")))
    parser.add_argument("--psql", default=os.environ.get("PSQL", "psql"))
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--movie-count", type=int, default=1000)
    parser.add_argument("--deleted-count", type=int, default=50)
    parser.add_argument("--api-timeout", type=int, default=120)
    parser.add_argument("--skip-postgres", action="store_true")
    parser.add_argument("--skip-prometheus", action="store_true")
    args = parser.parse_args()

    if not pathlib.Path("/proc/self/stat").exists():
        parser.error("run-matrix.py requires Linux/Kaggle")
    if args.repeats < 3:
        parser.error("use at least three repeats")
    seed_scope = resolve_seed_scope(args.seed_scope, args.scenario)
    if args.scenario == "stress-read" and seed_scope == "movie":
        parser.error("stress-read needs showtimes; use --seed-scope all or large")
    if any(level < 1 or level > 50 for level in args.levels):
        parser.error("VU levels must be from 1 to 50")
    if args.movie_count < 1 or args.deleted_count < 0 or args.deleted_count >= args.movie_count:
        parser.error("use movie-count > deleted-count >= 0")
    if not args.database_pid:
        parser.error("set DATABASE_PID or pass --database-pid")

    harness_dirty = git_value(
        HARNESS_ROOT, "status", "--porcelain", "--untracked-files=all", "--",
        "src/main", "pom.xml", "load-test",
    )
    if harness_dirty:
        parser.error("commit harness changes before measuring multiple refs")
    harness_commit = git_value(HARNESS_ROOT, "rev-parse", "HEAD")

    ensure_app_checkout(args.repo_url, args.app_dir)
    RESULTS_ROOT.mkdir(parents=True, exist_ok=True)
    matrix_root = RESULTS_ROOT / ("matrix-" + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d-%H%M%S"))
    matrix_root.mkdir(parents=False, exist_ok=False)
    manifest_path = matrix_root / "matrix.json"
    entries = []
    write_manifest(manifest_path, harness_commit, entries)

    for app_ref in args.app_ref:
        app_commit = checkout_ref(args.app_dir, app_ref)
        ref_root = matrix_root / safe_name(app_ref)
        ref_root.mkdir(parents=True, exist_ok=False)
        api_log_path = ref_root / "api.log"
        process = None
        log_stream = None
        entry = {
            "appRef": app_ref,
            "appGitCommit": app_commit,
            "status": "running",
            "resultDirectory": None,
            "apiLog": str(api_log_path.relative_to(HARNESS_ROOT)),
        }
        entries.append(entry)
        write_manifest(manifest_path, harness_commit, entries)
        try:
            reset_test_schema(args.psql)
            jar = build_application(args.app_dir)
            app_env = os.environ.copy()
            base_url = f"http://127.0.0.1:{args.port}"
            app_env.update({
                "SPRING_PROFILES_ACTIVE": app_env.get("SPRING_PROFILES_ACTIVE", "local"),
                "SERVER_ADDRESS": "127.0.0.1",
                "SERVER_PORT": str(args.port),
                "BASE_URL": base_url,
                "PROMETHEUS_URL": base_url + "/actuator/prometheus",
                "APP_REF": app_ref,
                "APP_GIT_COMMIT": app_commit,
            })
            log_stream = api_log_path.open("w", encoding="utf-8")
            process = subprocess.Popen(
                ["java", "-Xms256m", "-Xmx768m", "-jar", str(jar)],
                cwd=args.app_dir,
                env=app_env,
                stdout=log_stream,
                stderr=subprocess.STDOUT,
            )
            wait_for_api(process, base_url, api_log_path, args.api_timeout)
            measurement_env = seed_application(
                app_env, args.movie_count, args.deleted_count, max(args.levels), seed_scope,
            )
            entry["seed"] = seed_evidence(seed_scope)
            write_manifest(manifest_path, harness_commit, entries)
            result_dir = run_measurement(
                args, measurement_env, app_ref, app_commit, process.pid,
            )
            entry["status"] = "completed"
            entry["resultDirectory"] = str(result_dir.relative_to(HARNESS_ROOT))
        except Exception as error:
            entry["status"] = "failed"
            entry["error"] = str(error)
            raise
        finally:
            stop_process(process)
            if log_stream is not None:
                log_stream.close()
            write_manifest(manifest_path, harness_commit, entries)

    print(f"Matrix results: {matrix_root}")


if __name__ == "__main__":
    main()
