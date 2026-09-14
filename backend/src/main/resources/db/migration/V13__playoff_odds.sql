-- claude/playoff-odds.md. Makes PowerRankingEntry.makesPlayoffsPct real: it
-- has been a client-side type with no backend behind it since the power-
-- rankings reskin, rendering "--" everywhere.

-- The schedule, including weeks not yet played. Deliberately NOT a column on
-- roster_week_points: that table is a cache of RESULTS bounded by
-- last_scored_leg, this is a cache of FIXTURES that has to extend past it --
-- the whole point is the weeks nobody has played yet. Verified 2026-09-14
-- that Sleeper returns a future week's pairings with matchup_id set and
-- points 0.0, so the remaining schedule is one call per week.
create table league_matchup (
    id          bigserial primary key,
    league_id   bigint not null references league (id) on delete cascade,
    season      int    not null,
    week        int    not null,
    roster_id   int    not null,
    -- null = this roster has no game that week: a bye, an odd roster count,
    -- or a week Sleeper has not published pairings for yet.
    matchup_id  int,
    unique (league_id, season, week, roster_id)
);
create index league_matchup_lookup_idx on league_matchup (league_id, season, week);

-- One odds snapshot per (league, season, week), the same snapshot discipline
-- power_ranking uses and for the same reason: the inputs (records, the
-- schedule, a team's own scoring) all move underneath you, so "62% last week"
-- cannot be recomputed after the fact.
create table playoff_odds (
    id          bigserial primary key,
    league_id   bigint not null references league (id) on delete cascade,
    season      int    not null,
    week        int    not null,
    iterations  int    not null,
    -- Version tag for the model that produced these numbers. A retune (the
    -- shrinkage constant, the distribution) writes a NEW snapshot under a new
    -- tag rather than silently rewriting what an older week already showed.
    model       text   not null,
    created_at  timestamptz not null default now(),
    unique (league_id, season, week)
);

create table playoff_odds_entry (
    id             bigserial primary key,
    odds_id        bigint not null references playoff_odds (id) on delete cascade,
    roster_id      int    not null,
    made_pct       numeric(5,2) not null,
    -- Null unless the league's bracket actually has a bye / this is worth
    -- asking. Stored now so championship odds are a later read, not a later
    -- migration.
    bye_pct        numeric(5,2),
    seed_one_pct   numeric(5,2),
    proj_wins      numeric(5,2) not null,
    proj_points    numeric(9,2) not null,
    unique (odds_id, roster_id)
);
create index playoff_odds_entry_odds_idx on playoff_odds_entry (odds_id);
