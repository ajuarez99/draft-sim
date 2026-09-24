-- specs/008-season-superlatives.
--
-- Four tables for the "Season so far" page. Three are new *facts* fed by
-- existing ingest paths (player_absence, status_capture, player_suspension);
-- the fourth (league_conduct_entry) is the one piece of this feature that is
-- genuinely user-entered data, a commissioner's own claim about a player.
--
-- V22, after V21: V21 is the latest applied and Flyway's outOfOrder is unset
-- (and therefore false), so a lower version added after a higher one has been
-- applied fails at boot.

-- A scoring period a player's real team played and he didn't. Deliberately a
-- separate table from player_game rather than a "played" flag on it: V20's
-- table exists to answer "what did he score", and spec 005's readers already
-- assume every row in it is a game played. Adding a flag would need every one
-- of those readers to start filtering on it, a change nobody asked for; a
-- separate table means they don't have to.
create table player_absence (
    id                bigserial primary key,
    sport             text not null,
    season            int  not null,
    -- fantasy week, from the entry's own week -- same rule as player_game.week.
    week              int  not null,
    sleeper_player_id text not null,
    -- basketball: the missed game's id. A football None week has none.
    game_id           text null,
    -- null for a football None week; basketball always has the game's date.
    game_date         date null,
    -- the player's real team that week, read from the entry itself or, for a
    -- football None week, from a neighbouring entry.
    team              text null,
    -- ENTRY_WITHOUT_PLAY: an entry exists but SportRules.playedIn is false.
    -- TEAM_PLAYED_NO_ENTRY: a football None week where the player's own team
    -- was seen playing elsewhere in the same walk (research R9).
    basis             text not null,
    constraint player_absence_basis_check
        check (basis in ('ENTRY_WITHOUT_PLAY', 'TEAM_PLAYED_NO_ENTRY'))
);

-- The natural key. Re-running the player-games backfill upserts rather than
-- duplicates, the same shape as player_game_natural_key. coalesce(game_id, '')
-- because a football None-week absence has no game_id to key on.
create unique index player_absence_natural_key
    on player_absence (sport, season, sleeper_player_id, week, coalesce(game_id, ''));

-- "We looked at suspension tags during this week" -- recorded on every
-- successful player ingest even when nobody was suspended, so a gap in this
-- table is a real gap in observation rather than an assumed "no suspensions".
create table status_capture (
    sport         text        not null,
    -- Sleeper's /state/{sport} season and week at capture time.
    season        int         not null,
    week          int         not null,
    captured_at   timestamptz not null,
    primary key (sport, season, week)
);

-- A player tagged suspended in a captured week. Never backfilled -- Sleeper
-- only exposes today's tag, so this table can only grow forward from whenever
-- ingest started watching for it (FR-019).
create table player_suspension (
    sport             text not null,
    season            int  not null,
    week              int  not null,
    sleeper_player_id text not null,
    primary key (sport, season, week, sleeper_player_id),
    foreign key (sport, season, week) references status_capture (sport, season, week)
);

-- The commissioner's own list of conduct the automatic suspension read can't
-- see. Scoped to one league row (one league-season), not the league chain, so
-- FR-017's "never visible to another league" is structural and a new season
-- starts with an empty list.
create table league_conduct_entry (
    id                   bigserial primary key,
    league_id            bigint      not null references league (id) on delete cascade,
    sleeper_player_id    text        not null,
    reason               text        not null check (char_length(reason) between 1 and 140),
    -- counts from this week on (FR-018); the spec's "date" would need a
    -- conversion nobody asked for, since this is what gets compared against a
    -- fantasy week everywhere else in this feature.
    applies_from_week    int         not null check (applies_from_week >= 1),
    -- null when added by the configured app owner, who has no manager row in
    -- the league.
    added_by_manager_id  bigint      null references manager (id),
    created_at           timestamptz not null default now(),
    -- one entry per player per league; editing replaces reason and week.
    unique (league_id, sleeper_player_id)
);
