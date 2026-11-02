-- specs/053-historical-draft-game, data-model.md and its amendment box.
--
-- V43, after V42: V42 was the highest on origin/main and every local/remote branch when this was written
-- (re-checked 2026-11-02, specs/053-historical-draft-game/research-notes.md). Migrations here are
-- append-only. Never edit this file once applied -- any change is V44.
--
-- All tables are new; nothing existing is altered. The reference tables (historical_season ..
-- historical_player_game) are written only by the import; the game tables are written by play.
--
-- "to" (turnovers) is a reserved word in SQL: it is quoted wherever it appears, here and in every query.

create table historical_season (
    season_start      int         primary key,           -- 1995 means 1995-96
    label             text        not null,
    source            text        not null check (source in ('KAGGLE', 'SLEEPER')),
    opening_night     date        not null,
    last_counted_date date        not null,
    week_count        int         not null,
    game_count        int         not null,
    counted_stats     text[]      not null,              -- scoring keys passing the era rule (research R2)
    excluded_stats    jsonb       not null,              -- {key: reason}
    stat_coverage     jsonb       not null,              -- {stat: share of played rows carrying it}
    ranking_source    text        not null,              -- PRIOR_SEASON_POINTS in v1; ADP reserved
    imported_at       timestamptz not null
);

-- Upserted on (source, source_key) and never deleted (plan F9): a game's picks point here.
-- No positions column: positions are per season (F5), on historical_player_season.
create table historical_player (
    id         bigserial primary key,
    source     text      not null check (source in ('KAGGLE', 'SLEEPER')),
    source_key text      not null,
    name       text      not null,
    unique (source, source_key)
);

-- The prior-season card line, and the drafted season's own line in results. season_start need not be an
-- offered season: 1969 exists so 1970 has cards. NULL in a stat column means unrecorded, not zero.
create table historical_player_season (
    player_id    bigint   not null references historical_player (id),
    season_start int      not null,
    team         text,
    age          int,
    games        int      not null,
    positions    text[]   not null,
    pts          numeric,
    reb          numeric,
    ast          numeric,
    stl          numeric,
    blk          numeric,
    "to"         numeric,
    tpm          numeric,
    fgm          numeric,
    fga          numeric,
    ftm          numeric,
    fta          numeric,
    primary key (player_id, season_start)
);

-- Played games only (DNPs are dropped at import, F11). Typed nullable columns, NULL = unrecorded (F15).
-- dd/td/bonus_* are derived at import and null when not computable that season.
create table historical_player_game (
    player_id      bigint   not null references historical_player (id),
    game_id        bigint   not null,
    season_start   int      not null,
    game_date      date     not null,
    week           int      not null,
    team           text,
    pts            smallint,
    reb            smallint,
    ast            smallint,
    stl            smallint,
    blk            smallint,
    "to"           smallint,
    tpm            smallint,
    fgm            smallint,
    fga            smallint,
    ftm            smallint,
    fta            smallint,
    tf             smallint,
    ff             smallint,
    dd             smallint check (dd in (0, 1)),
    td             smallint check (td in (0, 1)),
    bonus_pt_40p   smallint check (bonus_pt_40p in (0, 1)),
    bonus_pt_50p   smallint check (bonus_pt_50p in (0, 1)),
    bonus_reb_20p  smallint check (bonus_reb_20p in (0, 1)),
    bonus_ast_15p  smallint check (bonus_ast_15p in (0, 1)),
    primary key (player_id, game_id)
);

create index historical_player_game_season_week on historical_player_game (season_start, week);

-- One user's play of one season (FR-013).
create table historical_game (
    id               bigserial   primary key,
    sleeper_user_id  text        not null,
    season_start     int         not null references historical_season (season_start),
    teams            int         not null,
    rounds           int         not null,
    reversal_round   int         not null,               -- 0 = plain snake (F8)
    roster_positions text[]      not null,
    scoring          jsonb       not null,               -- era-filtered snapshot, so config changes never alter a finished game
    user_slot        int         not null,
    rng_seed         bigint      not null,
    current_pick_no  int         not null,
    board            jsonb       not null,               -- rank-ordered snapshot of {playerId, adp, positionalRank} (F9)
    status           text        not null check (status in ('DRAFTING', 'COMPLETE', 'ABANDONED')),
    result           jsonb,                              -- frozen at completion, never recomputed on read
    created_at       timestamptz not null default now(),
    completed_at     timestamptz
);

create index historical_game_user on historical_game (sleeper_user_id, created_at desc);

create table historical_game_pick (
    game_id    bigint not null references historical_game (id) on delete cascade,
    pick_no    int    not null,
    round      int    not null,
    draft_slot int    not null,
    seat_type  text   not null check (seat_type in ('USER', 'BOT')),
    player_id  bigint not null references historical_player (id),
    primary key (game_id, pick_no),
    unique (game_id, player_id)
);

create table historical_import_job (
    id          bigserial   primary key,
    part        text        not null,
    status      text        not null check (status in ('RUNNING', 'DONE', 'FAILED')),
    started_at  timestamptz not null,
    finished_at timestamptz,
    report      jsonb
);

-- Phase 1 of the Kaggle import streams the box-score CSV here via COPY (plan F3): the file is
-- player-sorted and cannot be grouped by season in one streaming pass. Declared here so all DDL stays in
-- migrations; the import truncates it at both start and end. Columns mirror the box CSV as text; the
-- column list is a first guess at the header (T010 owner: adjust before this migration ships anywhere
-- shared, since V43 is not yet applied on main).
create unlogged table historical_kaggle_staging (
    season_start int,
    game_id      text,
    game_date    text,
    team         text,
    player_name  text,
    min          text,
    pts          text,
    reb          text,
    ast          text,
    stl          text,
    blk          text,
    tov          text,
    fg3m         text,
    fgm          text,
    fga          text,
    ftm          text,
    fta          text
);

create index historical_kaggle_staging_season on historical_kaggle_staging (season_start);
