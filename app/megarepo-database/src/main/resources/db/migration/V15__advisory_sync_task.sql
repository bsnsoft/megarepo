-- Repository Firewall, Phase 1 — schedule the advisory sync.
--
-- Follows the pattern V8 established for the NVD mirror: the handler is
-- registered in code (AdvisorySyncTask, task type 'security.advisory.sync') and
-- a scheduled_tasks row supplies the cron expression.
--
-- NUMBERING: V13's header reserved V14 for the Phase 2 quarantine/exemption
-- tables, but V14 was taken by advisory_affected_name_index in the meantime,
-- exactly as that header allowed for ("or whatever the next free number is by
-- then"). Phase 2 continues at V16.
--
-- 02:30 daily, half an hour ahead of the NVD mirror sync at 03:00. The NVD
-- advisory source reads that mirror, so the order matters: syncing before it
-- would publish yesterday's CVEs, and syncing at the same time would put two
-- jobs on the same tables.
--
-- next_run IS SET EXPLICITLY, AND HAS TO BE. MegaRepoTaskScheduler only picks up
-- rows whose next_run is non-null and due; TaskRunner is the only writer of that
-- column and only writes it after a run. A seeded row with next_run NULL is
-- therefore inert until someone triggers it once by hand — which is the state
-- the rows seeded by V2 and V8 are in. This row does not rely on that.
--
-- The value is deliberately an hour out rather than NOW(): the first run is a
-- full import (OSV ships per-ecosystem zip archives) and must not land in the
-- middle of a deployment or a restart. After the first run TaskRunner takes over
-- and computes next_run from the cron expression.
--
-- Nothing this task feeds can block a download. Phase 1 evaluates in AUDIT mode
-- only; the advisory tables exist so that findings can be recorded and compared
-- against the current CPE matching, not so that anything can be refused.

INSERT INTO scheduled_tasks (name, type, cron_expression, config, next_run)
VALUES (
    'Advisory sync (repository firewall)',
    'security.advisory.sync',
    '0 30 2 * * ?',
    '{}',
    NOW() + INTERVAL '1 hour'
);
