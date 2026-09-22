-- specs/006-deeper-history-both-sports research R2.
--
-- Sleeper's league object carries a top-level `status` field --
-- pre_draft / drafting / in_season / complete -- and it is NOT part of the
-- `settings` sub-object. Confirmed: `select settings_json ? 'status' from
-- league` is false for all nine rows in this database today. LeagueMapper
-- forwards only `settings` and `scoring_settings` into settings_json /
-- scoring_json, so `status` has been dropped on every ingest since V1.
--
-- Without it, the only way to ask "has this season finished" is positional --
-- LeagueHistoryController.history() passes i == 0 ("newest in the chain") to
-- PowerRankingService.finalRankForSeason -- which silently becomes wrong the
-- moment a chain's newest season completes, and is *already* wrong today:
-- Sleeper's `metadata.latest_league_winner_roster_id` on an in-season league
-- names the *previous* season's winner, and LeagueHistoryIngestService reads
-- it unconditionally, crowning popsharky and gregmullen champions of their
-- 2026 seasons after one week.
--
-- alter add column, not a new table: this is one fact about one league row,
-- read the same way sport and season already are.
alter table league add column status text;

-- NULLABLE ON PURPOSE. Rows ingested before V21 have no status until the next
-- ingest walk re-fetches them from Sleeper -- there is nothing to backfill
-- from data already in this database. A null must never be read as
-- "complete": that conflation is the exact defect this feature exists to fix
-- (a live season's champion getting crowned), so every reader treats null the
-- same as any other not-yet-known value. See LeagueRepository.LeagueRow#complete().
comment on column league.status is
    'Sleeper''s top-level league status: pre_draft / drafting / in_season / complete; null means not yet known and must never be treated as complete';
