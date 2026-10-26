-- Spec 048: the weekly high-score pot. One row per league-SEASON (research R7), exactly like the conduct
-- list: a new season starts with no row, which reads as "off". Absent row = off (FR-001); everything else
-- about the pot is derived on read from stored weekly scores, never stored.
--
-- stake_cents is integer cents so the ledger is exactly zero-sum. The 100000 ($1,000) ceiling is a
-- hand-set sanity limit and arbitrary; it is not derived from anything.
create table league_weekly_pot (
    league_id   bigint      primary key references league(id) on delete cascade,
    enabled     boolean     not null,
    stake_cents int         not null default 100 check (stake_cents between 1 and 100000),
    -- null when saved by the configured app owner, who has no manager row
    updated_by  bigint      null references manager(id),
    updated_at  timestamptz not null default now()
);
