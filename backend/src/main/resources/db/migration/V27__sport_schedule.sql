-- specs/017-nba-schedule-grid, research R2 / R3, data-model.md.
--
-- V27, after V26: V26 was the highest applied when this was written (re-checked, T001);
-- migrations here are append-only. Never edit this file once applied -- any change is V28.
--
-- The season schedule, one row per Sleeper game, shared by every league in the sport
-- (like player_game and sport_week_stats), so it deliberately has no FK to league. Written
-- whole per (sport, season) by PlayerGameIngestService on every refresh, in one transaction.
--
-- week is Sleeper's own week number, never derived from game_date. game_date, home, away and
-- status are nullable because the parser stores an unparseable or missing value as null
-- rather than inventing one.
--
-- There is NO CHECK on status, deliberately. Sleeper's vocabulary is not documented (seen:
-- pre_game, in_game, complete, postponed, canceled), and a CHECK would turn the first new
-- value into a failed refresh instead of a stored row. Readers decide what a status means.

create table sport_schedule (
    sport       text        not null,          -- Sport.code(): nba / nfl
    season      int         not null,
    game_id     text        not null,          -- Sleeper game id, as a string
    week        int         not null,
    game_date   date,
    home        text,                          -- team code, either payload shape
    away        text,
    status      text,
    fetched_at  timestamptz not null,          -- the same value for every row of one write
    primary key (sport, season, game_id)
);

create index sport_schedule_week_idx on sport_schedule (sport, season, week);
