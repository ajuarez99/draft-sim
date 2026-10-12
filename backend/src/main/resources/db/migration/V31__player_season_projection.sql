-- specs/030-post-draft-season-view data-model E1. One PER-GAME stat line per player for a whole
-- season, as the undocumented GET /projections/{sport}/{season} endpoint serves it.
--
-- Separate from V15's player_projection on purpose: V15 stores only Sleeper's precomputed
-- pts_ppr / pts_half_ppr / pts_std totals (football scoring) and has no stats column, and for
-- basketball pts_std matches neither league's scoring (GameScoringService's javadoc: Jokic 56.5
-- against the league's 58.5). Basketball has to be scored per league at read time, so the raw
-- line is what has to be stored (research R1).
--
-- `basis` is stored so a later source that sends season totals cannot be read as per-game by
-- accident. `stats` is the line exactly as returned, minus the adp_* keys.
create table player_season_projection (
    id                 bigserial primary key,
    sport              text not null,
    season             int  not null,
    sleeper_player_id  text not null,
    stats              jsonb not null,
    basis              text not null,
    source             text,
    source_modified_at timestamptz,
    fetched_at         timestamptz not null default now(),
    unique (sport, season, sleeper_player_id)
);

-- The read is always "this sport and season, every player" at once.
create index player_season_projection_season_idx on player_season_projection (sport, season);
