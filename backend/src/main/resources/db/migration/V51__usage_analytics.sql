-- Spec 076: usage analytics we own.
--
-- usage_daily: one row per (day, thing counted), incremented by an upsert. Nothing in it identifies a visitor:
-- no address, user agent, hash, guest id, user id, game id or time finer than the day. Empty strings, not nulls,
-- so the primary key works and `on conflict` has one target.
create table usage_daily (
    day       date   not null,                       -- the day in America/New_York the send arrived
    site      text   not null check (site in ('timemachine', 'leagues', 'combined')),
    kind      text   not null check (kind in ('view', 'league-view', 'event', 'rejected')),
    route     text   not null default '',            -- route template for view / league-view
    name      text   not null default '',            -- event name for event
    props     text   not null default '',            -- canonical k=v pairs, keys sorted, joined by ';'
    league_id text   not null default '',            -- Sleeper league id for league-view
    sport     text   not null default '',            -- from the league table for league-view
    count     bigint not null,
    primary key (day, site, kind, route, name, props, league_id)
);

-- Whether a Redraft game was started by a signed-out player. NULLABLE with NO default on purpose: null means
-- "not recorded" (a row written by code that predates this column: an overlapping old container, a rollback,
-- another branch's tests on a shared database). Never NOT NULL. Never updated, so claiming a guest's games
-- leaves it alone, which is the point.
alter table historical_game add column started_as_guest boolean;

-- Backfill. Inexact for a game a guest started and claimed before this migration: it reads as signed-in.
update historical_game set started_as_guest = (sleeper_user_id like 'guest:%');
