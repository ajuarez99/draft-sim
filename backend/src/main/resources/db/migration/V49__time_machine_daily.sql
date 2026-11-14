-- Spec 071: Time Machine game of the day (claude/time-machine-daily.md).
--
-- One row per sport per day: the season and seed everybody who plays that day's game gets. Written the first time
-- anyone asks for the day and never updated, so a later import that changes the offered seasons cannot change a
-- day that was already dealt. The day is a calendar date in America/New_York (the service decides that, not SQL).
create table historical_daily (
    sport        text        not null check (sport in ('NBA', 'NFL')),
    daily_date   date        not null,
    season_start int         not null,
    rng_seed     bigint      not null,
    created_at   timestamptz not null default now(),
    primary key (sport, daily_date),
    foreign key (sport, season_start) references historical_season (sport, season_start)
);

-- Null for free play. One daily game per person per sport per day, whatever its status.
alter table historical_game add column daily_date date;

create unique index historical_game_daily_once
    on historical_game (sleeper_user_id, sport, daily_date)
    where daily_date is not null;
