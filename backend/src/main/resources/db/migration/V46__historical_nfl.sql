-- specs/056-nfl-historical-draft-game, data-model.md "Amended after review" (F2, F8, F14).
--
-- V46, after V45: written as V45 on 2026-11-05, renumbered 2026-11-06 because spec 057
-- (V45__week_score_override.sql) keeps V45 (Allan's call), so 057 merges first. V46 was free on every
-- branch and worktree when renumbered (specs/056-nfl-historical-draft-game/research-notes.md). cf84da0's
-- V45 rename of V43 was reverted (ff6711f) and never applied anywhere.
-- Migrations here are append-only. Never edit this file once applied -- any change is V47.
--
-- This ALTERS two V43 tables (V43 is in prod, so it is not edited) and adds three. The statement ORDER is
-- load-bearing (F2): the season primary key cannot be dropped while historical_game's foreign key depends on it,
-- so the FK goes first and comes back last. No CASCADE anywhere: it would silently drop the FK.
--
-- Existing basketball rows get sport = 'NBA' through the temporary default (SC-008), and then the default is
-- dropped (F8) so an insert that forgets the column fails instead of being quietly tagged NBA.

-- 1-2. The sport columns.
alter table historical_season
    add column sport text not null default 'NBA' check (sport in ('NBA', 'NFL'));
alter table historical_game
    add column sport text not null default 'NBA';

-- 3-6. The season key becomes (sport, season_start): NFL 2005 collides with NBA 2005-06 otherwise.
alter table historical_game drop constraint historical_game_season_start_fkey;
alter table historical_season drop constraint historical_season_pkey;
alter table historical_season add primary key (sport, season_start);
alter table historical_game add foreign key (sport, season_start)
    references historical_season (sport, season_start);

-- 7. The new source.
alter table historical_season drop constraint historical_season_source_check;
alter table historical_season add constraint historical_season_source_check
    check (source in ('KAGGLE', 'SLEEPER', 'NFLVERSE'));
alter table historical_player drop constraint historical_player_source_check;
alter table historical_player add constraint historical_player_source_check
    check (source in ('KAGGLE', 'SLEEPER', 'NFLVERSE'));

-- 8. The nflverse stats files carry no dates (R4); provenance columns for FR-014 / SC-002 / R1.
alter table historical_season alter column opening_night drop not null;
alter table historical_season alter column last_counted_date drop not null;
alter table historical_season add column expected_game_count int;             -- schedule fact from config; null for NBA
alter table historical_season add column dropped_rows int not null default 0; -- blank-id rows with a skill position or a scored stat (F7)
alter table historical_season add column source_sha256 text;                  -- the downloaded file's hash; null for NBA

-- 9. F8: from here on every insert must name its sport.
alter table historical_season alter column sport drop default;
alter table historical_game alter column sport drop default;

-- 10. One player's REG game in one week. QB/RB/WR/TE only (FB/HB -> RB). NA in the source becomes 0 (R6).
create table historical_nfl_player_week (
    player_id bigint  not null references historical_player (id),
    season    int     not null,
    week      int     not null,
    game_id   text,
    team      text,
    opponent  text,
    position  text    not null,
    pass_yd   numeric not null default 0,
    pass_td   numeric not null default 0,
    pass_int  numeric not null default 0,
    rush_yd   numeric not null default 0,
    rush_td   numeric not null default 0,
    rec       numeric not null default 0,
    rec_yd    numeric not null default 0,
    rec_td    numeric not null default 0,
    fum_lost  numeric not null default 0,
    pass_2pt  numeric not null default 0,
    rush_2pt  numeric not null default 0,
    rec_2pt   numeric not null default 0,
    st_td     numeric not null default 0,
    src_ppr   numeric,                       -- the source's fantasy_points_ppr, for SC-004 only, never scored
    primary key (player_id, season, week)
);

create index historical_nfl_player_week_season_week on historical_nfl_player_week (season, week);

-- 11. The prior-season card line, aggregated from the week table at import (R5). No age: the card shows null.
create table historical_nfl_player_season (
    player_id bigint  not null references historical_player (id),
    season    int     not null,
    team      text,
    games     int     not null,
    positions text[]  not null,
    pass_yd   numeric not null default 0,
    pass_td   numeric not null default 0,
    pass_int  numeric not null default 0,
    rush_yd   numeric not null default 0,
    rush_td   numeric not null default 0,
    rec       numeric not null default 0,
    rec_yd    numeric not null default 0,
    rec_td    numeric not null default 0,
    fum_lost  numeric not null default 0,
    pass_2pt  numeric not null default 0,
    rush_2pt  numeric not null default 0,
    rec_2pt   numeric not null default 0,
    st_td     numeric not null default 0,
    primary key (player_id, season)
);

-- 12. A player's first NFL draft (F14), for ranking rookies. Skill positions, existing NFLVERSE players only.
create table historical_nfl_draft_pick (
    player_id bigint primary key references historical_player (id),
    season    int    not null,
    round     int    not null,
    pick      int    not null,             -- overall
    position  text   not null
);
