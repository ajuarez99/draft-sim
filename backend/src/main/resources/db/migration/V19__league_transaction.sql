-- specs/004-ffwrapped-feature-parity US6: the rest of the Roster Management
-- page -- transaction counts by type, trades, waiver and free-agent adds with
-- FAAB, and the Best Adds ranking.
--
-- The only genuinely new ingest in this feature. SleeperClient.transactions()
-- has existed and had ZERO callers; nothing stored what it returns.
--
-- V19, after V18. Versions follow execution order, because Flyway's outOfOrder
-- is unset and therefore false.

create table league_transaction (
    id                     bigserial primary key,
    league_id              bigint not null references league (id) on delete cascade,
    season                 int    not null,
    -- Sleeper's `leg`. A transaction belongs to the scoring period it happened
    -- in, which is how "average positional rank since the move" knows where to
    -- start counting.
    week                   int    not null,
    sleeper_transaction_id text   not null,
    type                   text   not null,
    -- complete / failed. Failed waiver bids are a real row, not an absence:
    -- they are what a "failed bids" view reads, and dropping them would make a
    -- manager who bid and lost look like one who never bid.
    status                 text,
    -- The acting roster. Null for a trade, which has no single actor -- the
    -- participants live in adds/drops instead.
    roster_id              int,
    -- Nullable exactly as roster_season.manager_id is, and for the same reason:
    -- a roster can be an orphan, and its moves still happened.
    manager_id             bigint references manager (id),
    adds                   jsonb  not null default '{}'::jsonb,
    drops                  jsonb  not null default '{}'::jsonb,
    -- settings.waiver_bid. Null means this league does not use FAAB or the move
    -- was not a bid; 0 is a real bid of nothing and must stay distinct from it.
    faab_bid               int,
    created_at             timestamptz,
    constraint league_transaction_type_check
        check (type in ('WAIVER', 'FREE_AGENT', 'TRADE', 'COMMISSIONER')),
    -- The natural key. Re-ingesting a week must not duplicate its moves, and
    -- Sleeper's own transaction id is the only thing stable across runs.
    unique (league_id, sleeper_transaction_id)
);

-- Matches the read pattern, mirroring roster_week_points_lookup_idx: the page
-- asks for one league-season and walks its weeks.
create index league_transaction_lookup_idx on league_transaction (league_id, season, week);
