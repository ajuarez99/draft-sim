-- Spec 057: a commissioner-entered team score for one week of one league-season. Read before the Sleeper
-- feed and before the official season-total snapshots (store/WeekScoreResolver). A re-ingest never touches
-- this table, so an entered score survives it (FR-010).
--
-- numeric(8,2) is the precision of roster_week_points.starters_points. Rows are replaced a whole week at a
-- time (delete the week, insert every roster), so a week never mixes sources.
create table week_score_override (
    league_id   bigint        not null references league(id) on delete cascade,
    week        int           not null check (week >= 1),
    roster_id   int           not null,
    points      numeric(8,2)  not null check (points >= 0),
    -- null when saved by the configured app owner, who has no manager row (as V41)
    entered_by  bigint        null references manager(id),
    entered_at  timestamptz   not null default now(),
    primary key (league_id, week, roster_id)
);
