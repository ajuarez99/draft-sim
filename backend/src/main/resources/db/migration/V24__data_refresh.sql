-- specs/009-auto-data-refresh.
--
-- V24, after V23: V23 was the highest applied when this was written (re-checked),
-- and migrations here are append-only.
--
-- Four small state tables for the refresh feature. None alters an existing table.

-- One row per league-season: when it was last refreshed, and whether it is fully
-- loaded (research R3, R5). "Running" is deliberately not stored -- it lives in
-- memory (single-flight, R4) so a restart can never leave a row stuck.
create table league_refresh (
    league_id       bigint primary key references league(id) on delete cascade,
    last_success_at timestamptz null,
    last_failure_at timestamptz null,
    -- short reason for logs and the failed state; never shown raw
    last_failure    text null,
    loaded_complete boolean not null default false
);

comment on column league_refresh.loaded_complete is
    'Fully loaded (decided): status = complete and a refresh finished successfully after that status was first seen. Only then does the season get loaded_complete = true, after which nothing fetches it again (FR-011). A refresh that fails, or that saw the league still in_season, never sets it. (specs/009 research R3, verbatim)';

-- Which per-game weeks have been fetched for a sport-season, and whether each is
-- final (research R6). Shared across leagues, like player_game.
create table sport_week_stats (
    sport      text        not null,
    season     int         not null,
    week       int         not null,
    fetched_at timestamptz not null,
    -- true once fetched >= 48 h after the week's last scheduled game with every
    -- game in it settled. Hand-set, arbitrary (research R6). Never reverts.
    final      boolean     not null default false,
    primary key (sport, season, week)
);

-- When each league week's transactions / points were fetched, and whether that
-- fetch is final (FR-016, research R14). Replaces the "has rows" skip, so a week
-- stored partial before this feature existed is refetched once.
create table league_week_fetch (
    league_id  bigint      not null references league(id) on delete cascade,
    kind       text        not null check (kind in ('TRANSACTIONS', 'POINTS')),
    week       int         not null,
    fetched_at timestamptz not null,
    final      boolean     not null default false,
    primary key (league_id, kind, week)
);

-- The daily job's once-a-day record (FR-009). Enforces Sleeper's once-a-day
-- player-list request even when the job is re-run by hand, and makes a missed
-- day visible as a gap in capture_date (SC-006).
create table daily_capture (
    sport        text        not null,
    -- UTC date
    capture_date date        not null,
    -- ADP runs inside BOARD (research R15)
    kind         text        not null check (kind in ('PLAYERS', 'BOARD')),
    completed_at timestamptz not null,
    detail       text        null,
    primary key (sport, capture_date, kind)
);
