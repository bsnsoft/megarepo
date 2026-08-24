-- Repository Firewall, Phase 2 — the three background jobs quarantine and
-- exemptions need (osTicket #155155).
--
-- Same pattern as V8 (NVD mirror) and V15 (advisory sync): the handler is
-- registered in code against a task type, and a scheduled_tasks row supplies the
-- cron expression so an operator can retime or disable it from the Tasks page
-- without a redeploy.
--
-- next_run IS SET EXPLICITLY. MegaRepoTaskScheduler only picks up rows whose
-- next_run is non-null and due, and TaskRunner is the only other writer of that
-- column — a seeded row with next_run NULL sits inert until somebody triggers it
-- by hand, which is the state the rows seeded by V2 and V8 are still in. V15
-- established not relying on that; these three follow it.
--
-- NONE OF THESE TASKS CAN BLOCK A DOWNLOAD. Two of them only ever release or
-- expire — they move components towards being served. The third fetches
-- metadata. The only job that could ever turn a served download into a refused
-- one is a re-evaluation finding a genuine policy violation on a component that
-- was already being held, and a held component was not being served anyway.

-- ---------------------------------------------------------------------------
-- 1. Quarantine re-evaluation
-- ---------------------------------------------------------------------------
--
-- Runs the current policy against current data for every entry still in
-- QUARANTINED and releases the ones that have become acceptable — the age was
-- reached, the advisory data arrived, the policy changed, an exemption was
-- approved. This is the schedule half of the customer's "automatic release";
-- the other half is an event hook that runs the same sweep right after an
-- advisory sync, because that is the moment the answer most often changes.
--
-- Every 15 minutes rather than hourly: the entries this holds are the ones a
-- developer is actively waiting on, and "your build works again within the hour"
-- is the kind of latency that gets a firewall switched off.
INSERT INTO scheduled_tasks (name, type, cron_expression, config, next_run)
VALUES (
    'Firewall quarantine re-evaluation',
    'security.firewall.quarantine.reevaluate',
    '0 */15 * * * ?',
    '{}',
    NOW() + INTERVAL '15 minutes'
);

-- ---------------------------------------------------------------------------
-- 2. Exemption expiry
-- ---------------------------------------------------------------------------
--
-- Flips APPROVED exemptions whose expires_at has passed to EXPIRED, and sends
-- the "this lapses soon" notice for the ones approaching it.
--
-- The flip is a stored transition rather than something derived at read time on
-- purpose: an exemption that silently stops applying leaves the violation log
-- and the exemption list disagreeing about when it stopped, and the first person
-- to notice is whoever's build broke.
--
-- 06:00 daily — before the working day, so the notice lands before somebody
-- discovers the lapse the hard way, and far away from the advisory sync at 02:30
-- and the NVD mirror at 03:00.
INSERT INTO scheduled_tasks (name, type, cron_expression, config, next_run)
VALUES (
    'Firewall exemption expiry',
    'security.firewall.exemption.expiry',
    '0 0 6 * * ?',
    '{}',
    NOW() + INTERVAL '2 hours'
);

-- ---------------------------------------------------------------------------
-- 3. Component facts resolution
-- ---------------------------------------------------------------------------
--
-- Fills firewall_component_facts — publication dates and declared licenses —
-- for the components the MIN_AGE and LICENSE rules need to judge. The request
-- path never fetches: it reads the table, and a miss enqueues a row in state
-- UNKNOWN for this task to pick up.
--
-- The task is the sweeper, not the only path: the resolver also drains its queue
-- continuously in the background, because a developer whose download was held
-- for "we do not know how old this is" should not wait a quarter of an hour for
-- a cron tick. The sweep is what catches rows the in-process queue lost to a
-- restart.
--
-- Every 10 minutes, offset off the quarter-hour so it does not contend with the
-- re-evaluation sweep above.
INSERT INTO scheduled_tasks (name, type, cron_expression, config, next_run)
VALUES (
    'Firewall component facts resolution',
    'security.firewall.facts.resolve',
    '0 3/10 * * * ?',
    '{}',
    NOW() + INTERVAL '20 minutes'
);
