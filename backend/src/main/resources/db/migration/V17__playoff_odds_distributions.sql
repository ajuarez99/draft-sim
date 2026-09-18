-- specs/004-ffwrapped-feature-parity US4.
--
-- PlayoffOddsSimulator already runs 10,000 seasons and already sorts a full
-- standings order every iteration. Everything a Season Forecast view wants --
-- per-seed odds, average seed, a win percentile range -- was computed and then
-- collapsed into made_pct/seed_one_pct/proj_wins one line later. This gives
-- those distributions somewhere to live so they are a read rather than a second
-- simulation, which is what keeps the forecast view and the Record cell showing
-- the same number from the same snapshot (FR-008).
--
-- V17, not V18: this story ships before US5's roster_week_points.starters, and
-- Flyway's outOfOrder is unset (and therefore false), so a lower version added
-- after a higher one has been applied fails at boot. Migration versions here
-- follow execution order, not story number.

-- seed -> count over the simulated seasons, 1-based seed keys ("1", "2", ...).
-- jsonb rather than a child table: it is written once per snapshot and always
-- read whole, and a 12-key object per roster-week is not a relation anyone
-- queries into.
alter table playoff_odds_entry add column seed_counts jsonb;

-- wins -> count, whole-win keys ("0", "1", ...). Fractional win totals (ties,
-- and Sleeper's league_average_match) are rounded into the nearest bucket:
-- this backs a percentile RANGE, which is reported in whole wins anyway.
alter table playoff_odds_entry add column win_counts jsonb;

-- Both are nullable on purpose. Snapshots written before this migration have
-- no distributions and can never get them -- the simulation that produced them
-- is gone, and re-running it now would answer a different question, since the
-- records and schedule it read have since moved. A forecast view must treat a
-- null here as "this snapshot predates distributions", not as zero.
comment on column playoff_odds_entry.seed_counts is
    'seed -> simulated-season count; null for snapshots taken before V17';
comment on column playoff_odds_entry.win_counts is
    'wins -> simulated-season count; null for snapshots taken before V17';
