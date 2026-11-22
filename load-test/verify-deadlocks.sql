-- Run before and after executing S4 (overlap.js) to inspect deadlock statistics in PostgreSQL.
-- Expected: deadlocks count does not increase after overlap load testing when seat ordering is enforced.
SELECT
    datname AS database_name,
    deadlocks AS deadlock_count,
    conflicts AS conflict_count,
    stats_reset AS statistics_since
FROM pg_stat_database
WHERE datname = current_database();
