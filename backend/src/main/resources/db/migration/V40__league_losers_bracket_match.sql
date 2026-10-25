-- specs/047-hall-of-fame T005 (research R2 amended, plan A1): Sleeper's LOSERS bracket, one row per
-- bracket match, per league-season. Exactly V33's columns and primary key.
--
-- Why it exists: last place for the trophy case. Sleeper reports the bracket and its placements but not
-- which end of it is "last" (loser_bracket_type is null in the API for every real league). The reader
-- (engine/LastPlaceRule) decides the direction from the stored week scores, and refuses when they don't
-- agree. A separate table, not a flag on league_bracket_match, so no existing winners-bracket reader
-- (RivalryGameKind, BracketIngestService.isComplete) can pick up losers rows by accident.
--
-- Written only by BracketIngestService, with the same rules as the winners bracket: an empty Sleeper
-- answer never deletes rows, and a fully-played stored bracket is skipped unless forced.
create table league_losers_bracket_match (
    league_id        bigint not null references league (id) on delete cascade,
    match_id         int    not null,      -- Sleeper "m"
    round            int    not null,      -- Sleeper "r"
    t1_roster_id     int,
    t2_roster_id     int,
    winner_roster_id int,                  -- Sleeper "w"; null until played
    loser_roster_id  int,                  -- Sleeper "l"
    -- Sleeper "p": the placement game this match decides (1, 3, 5, ...); null = an elimination game.
    placement        int,
    primary key (league_id, match_id)
);
