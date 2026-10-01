-- specs/014-home-player-spotlight, research R5 / R8, data-model.md.
--
-- V26, after V25: V25 was the highest applied when this was written (re-checked);
-- migrations here are append-only. Never edit this file once applied -- any change is V27.
--
-- Why the fetch state is its own table: "fetched, and the list was empty" and "never
-- fetched" are different claims, and a row set alone cannot tell them apart. The
-- state row also carries the true age of the list (fetched_at), which a failed fetch
-- deliberately leaves untouched so the page can say how old the visible list is.
--
-- Counts are PLATFORM-WIDE: adds across every Sleeper league over lookback_hours, not
-- this league's. Sleeper's API documentation asks consumers to give attribution to
-- Sleeper when using trending data; the page credits it, and this table is where the
-- numbers it credits are stored.
--
-- league_season and season_start_date are Sleeper's /state values at fetch time (the
-- sport's current season, and when it starts); both are nullable because Sleeper may
-- omit them and an unknown is stored as null, never guessed.

create table sport_trending_fetch (
    sport             text primary key,
    fetched_at        timestamptz,            -- last SUCCESSFUL fetch; null = never
    lookback_hours    int not null,           -- window the counts cover; stored, not assumed by the reader
    league_season     int,
    season_start_date date,
    last_failure_at   timestamptz,
    last_failure      text
);

create table sport_trending (
    sport             text not null references sport_trending_fetch (sport) on delete cascade,
    rank              int  not null,          -- 1-based, Sleeper's order
    sleeper_player_id text not null,          -- the same key player_game uses
    add_count         int  not null,          -- platform-wide adds over lookback_hours
    primary key (sport, rank)
);
