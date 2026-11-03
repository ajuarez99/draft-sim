-- claude/official-week-scores.md: Sleeper's official season totals (/rosters settings.fpts), kept once per
-- scored leg instead of only the latest value (roster_season.points_for). The difference between two
-- consecutive legs is that week's official score, which the public /matchups feed does not reliably give
-- (claude/nba-2026-score-drift.md). Upserted while the leg is current, so the last read of a leg wins.
create table roster_fpts_snapshot (
    league_id     bigint        not null references league(id) on delete cascade,
    roster_id     int           not null,
    through_week  int           not null check (through_week >= 1),
    fpts          numeric(10,2) not null,
    fpts_against  numeric(10,2) null,
    captured_at   timestamptz   not null default now(),
    primary key (league_id, roster_id, through_week)
);
