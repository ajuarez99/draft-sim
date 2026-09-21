-- specs/005-daily-weekly-top-players US1.
--
-- One row per player per game. The Weekly Report can already say a player
-- scored 58.5 in a week; it cannot say that was one Tuesday against Chicago.
-- These rows are what let it.
--
-- NOT league-scoped, deliberately. Two leagues in the same sport and season
-- share every game and differ only in how they score it -- measured 2026-09-19,
-- the two ingested NBA leagues score `dd` at 1.0 and 2.0 and only one of them
-- has bonus_ast_15p at all. Storing a precomputed points column would either
-- bake one league's settings into a cache every league reads, or need a row per
-- league per game. This is the same argument V15__player_projection.sql already
-- makes in its own comment, and it is the reason `stats` below stays raw:
-- scoring is applied at read time from the asking league's scoring_json.
--
-- V20, not a lower number: V19 is the latest applied and Flyway's outOfOrder is
-- unset (and therefore false), so a lower version added after a higher one has
-- been applied fails at boot.
create table player_game (
    id                bigserial primary key,
    sport             text not null,
    season            int  not null,
    -- The fantasy week this game belongs to, taken from the upstream entry's own
    -- `week` field and never derived from game_date. That is what makes a
    -- postponed game land in the week it was actually played rather than the one
    -- it was scheduled for, without this table knowing anything about calendars.
    week              int  not null,
    -- Keyed by Sleeper's own player id rather than our player.id, following V15:
    -- measured that the stats endpoints key on exactly the id already in
    -- player.sleeper_id, and a row for a player this app has never ingested is
    -- still worth storing. Resolving it to a local player row is the reader's job.
    sleeper_player_id text not null,
    game_id           text not null,
    game_date         date not null,
    -- Nullable on purpose: a payload that omits them must still store the game.
    -- A reader shows "unknown" rather than guessing, which is FR-006.
    opponent          text,
    is_away           boolean,
    stats             jsonb not null,
    -- Not a "fetched once, settled forever" table. Stat corrections move settled
    -- games -- measured ~25-50 points of drift on two rosters' season totals --
    -- so the backfill is re-runnable and this records when a row last changed.
    fetched_at        timestamptz not null default now()
);

-- The natural key. A game is one player's appearance in one fixture, so re-running
-- the backfill upserts rather than duplicates.
alter table player_game add constraint player_game_natural_key
    unique (sleeper_player_id, game_id);

-- The read path: both ranking sections load one week of one season.
create index player_game_week_idx on player_game (sport, season, week);

-- The backfill's own "what do I already have" check.
create index player_game_player_idx on player_game (sport, season, sleeper_player_id);

comment on column player_game.week is
    'fantasy week from the upstream entry''s own week field, never derived from game_date';
comment on column player_game.stats is
    'raw stat line; points are computed at read time from the asking league''s scoring_json';
