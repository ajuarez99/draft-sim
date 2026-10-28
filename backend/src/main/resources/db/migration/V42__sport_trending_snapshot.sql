-- specs/051-trending-tony-award, data-model.md, research R3.
--
-- V42, after V41: V41 was the highest on main when this was written, and no origin branch or
-- worktree carried a V42+ (re-checked, lesson #43). Migrations here are append-only. Never edit
-- this file once applied -- any change is V43.
--
-- Why a history table: sport_trending (V26) is replaced wholesale on every fetch, so it can only
-- say what is trending NOW. The Trending Tony judges a pickup against what was trending AT THE
-- TIME, which needs every past list. One successful fetch writes one snapshot, in the same
-- transaction as the current-list replace.
--
-- No backfill is possible: Sleeper exposes no historical trending list, so history starts the day
-- this migration is deployed and earlier pickups can never be judged.
--
-- entry_count = 0 means Sleeper returned an empty list; such a snapshot judges nothing.
-- There is deliberately no FK to sport_trending_fetch, so history survives anything done to the
-- current-list row.

create table sport_trending_snapshot (
    id              bigserial   primary key,
    sport           text        not null,   -- 'nfl' | 'nba', same codes as sport_trending_fetch.sport
    fetched_at      timestamptz not null,   -- same value written to sport_trending_fetch
    lookback_hours  int         not null,   -- window the counts cover; stored, never assumed by the reader
    entry_count     int         not null    -- 0 = Sleeper returned an empty list
);

create index sport_trending_snapshot_sport_fetched_at on sport_trending_snapshot (sport, fetched_at);

create table sport_trending_snapshot_entry (
    snapshot_id        bigint not null references sport_trending_snapshot (id) on delete cascade,
    rank               int    not null,     -- 1-based, Sleeper's order
    sleeper_player_id  text   not null,
    add_count          int    not null,     -- platform-wide adds over lookback_hours
    primary key (snapshot_id, rank)
);
