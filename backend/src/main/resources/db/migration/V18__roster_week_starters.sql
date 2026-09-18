-- specs/004-ffwrapped-feature-parity US5.
--
-- roster_week_points has stored starters_points (the roster's weekly TOTAL) and
-- players_points (every player's score) since V5. That is enough for potential
-- points and efficiency, which is why US2 needed no migration. It is not enough
-- for an award that must name the bench player who should have started: nothing
-- has ever recorded WHO was actually started.
--
-- V18, not V17: US4's playoff-odds distributions ship first, and Flyway's
-- outOfOrder is unset (and therefore false), so a lower version added after a
-- higher one has been applied fails at boot. Versions follow execution order.
--
-- Ordered array of sleeper_player_id, in Sleeper's own starters order -- the
-- slot order from roster_positions, so index 0 is the QB seat in football and
-- the PG seat in basketball. Kept as sent rather than normalised into
-- (slot, player) rows: it is written once per roster-week and always read
-- whole, and the slot meaning already lives in the league's roster_positions.
alter table roster_week_points add column starters jsonb;

-- NULLABLE ON PURPOSE, and this is the part that matters.
--
-- LeagueHistoryIngestService.ingestWeeklyPoints skips a week whose rows already
-- exist, so adding a column and re-running ingest does NOT backfill it -- the
-- gate is keyed on rows existing, not on columns being populated. That exact
-- bug has already shipped in this repo once: league_matchup was added on
-- 2026-09-14 and every season ingested before that date was skipped wholesale
-- on every subsequent run, measured at 204 stored scores against pairings for
-- exactly one week.
--
-- So this migration lands together with a change to that gate, and the backfill
-- is verified by COUNTING populated rows rather than by a successful build. A
-- week that is still null after that is a week Sleeper no longer serves, and
-- US5 requires the affected award to be omitted with a stated reason instead of
-- guessed at.
comment on column roster_week_points.starters is
    'ordered sleeper_player_ids actually started; null for a week ingested before V18 and never refetched';
