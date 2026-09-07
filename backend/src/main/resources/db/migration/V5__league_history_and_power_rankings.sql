-- claude/league-suite.md Phase A: read-only league history + power rankings,
-- no auth (Phase B's ballots/manager_session are not part of this migration).
-- Reviewed claude/plan-review-league-suite.md, amendments folded in below.

-- One row per (league, roster) per season. manager_id is nullable: a roster
-- can be an orphan (no owner_id, or an owner_id this app has never ingested a
-- user row for).
create table roster_season (
    id                bigserial primary key,
    league_id         bigint  not null references league (id) on delete cascade,
    manager_id        bigint  references manager (id),
    roster_id         int     not null,
    wins              int,
    losses            int,
    ties              int,
    points_for        numeric(8,2),
    points_against    numeric(8,2),
    points_possible   numeric(8,2),
    final_placement   int,
    unique (league_id, roster_id)
);

-- Raw per-week matchup results, cached so re-running ingest never refetches a
-- week already stored (finding 5) -- only the current week (still moving) and
-- weeks genuinely new to this table are fetched. starters_points is Sleeper's
-- own total for that week's actual starting lineup; players_points is kept
-- alongside it (jsonb, sleeper_player_id -> points) in case a future view
-- wants a per-player breakdown, not just the roster total.
create table roster_week_points (
    id               bigserial primary key,
    league_id        bigint  not null references league (id) on delete cascade,
    season           int     not null,
    week             int     not null,
    roster_id        int     not null,
    starters_points  numeric(8,2) not null default 0,
    players_points   jsonb   not null default '{}'::jsonb,
    unique (league_id, week, roster_id)
);
create index roster_week_points_lookup_idx on roster_week_points (league_id, season, week);

-- One row per computed/submitted ranking for one league/season/week. Computed
-- rankings are snapshots, not recomputed on read (storage-sketch note in the
-- plan): "up 3 spots since last week" needs history, and the inputs (rosters,
-- the board) move underneath you.
create table power_ranking (
    id          bigserial primary key,
    league_id   bigint not null references league (id) on delete cascade,
    season      int    not null,
    week        int    not null,
    kind        text   not null,
    created_at  timestamptz not null default now(),
    constraint power_ranking_kind_check
        check (kind in ('COMMISSIONER', 'COMPUTED_MARKET_VALUE', 'COMPUTED_REALIZED')),
    unique (league_id, season, week, kind)
);

create table power_ranking_entry (
    id          bigserial primary key,
    ranking_id  bigint not null references power_ranking (id) on delete cascade,
    roster_id   int    not null,
    manager_id  bigint references manager (id),
    rank        int    not null,
    score       numeric(12,4),
    note        text,
    unique (ranking_id, roster_id)
);
create index power_ranking_entry_ranking_idx on power_ranking_entry (ranking_id);
