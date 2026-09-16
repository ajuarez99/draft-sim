-- claude/league-analysis.md Phase 1. Rest-of-season projected points, the one
-- data source the League analysis page needs and nothing else in this app has.
--
-- Deliberately NOT modelled on roster_week_points, whose caching rule is
-- "fetch once, never refetch" because a scored week is final. A projection is
-- not: next week's number moves every time somebody tweaks an ankle. So this
-- table carries fetched_at and the reader decides what counts as stale, rather
-- than a storedWeeks() set that means "settled forever".
--
-- Keyed by Sleeper's own player id rather than our player.id: measured
-- 2026-09-15 that the undocumented projections endpoint keys on exactly the
-- id already in player.sleeper_id, and a row for a player this app has never
-- ingested is still worth storing -- resolving it to a local player row is the
-- reader's job, not the cache's.
create table player_projection (
    id                bigserial primary key,
    sport             text not null,
    season            int  not null,
    week              int  not null,
    sleeper_player_id text not null,
    -- Sleeper gives all three scorings in one payload, so all three are stored
    -- and the league's own scoring_json picks the column at read time. Storing
    -- only the one today's league uses would bake a league setting into a
    -- cache shared by every league.
    pts_ppr           numeric(7,2),
    pts_half_ppr      numeric(7,2),
    pts_std           numeric(7,2),
    -- Who produced the number ("rotowire" as measured). Kept so a later change
    -- of provider is visible in the data rather than inferred from a date.
    source            text,
    fetched_at        timestamptz not null default now(),
    unique (sport, season, week, sleeper_player_id)
);

-- The read is always "these weeks, this sport/season" -- a whole rest-of-season
-- window at once, never one player.
create index player_projection_window_idx on player_projection (sport, season, week);
