-- Which league-seasons lost their head-to-head pairings to the score-cache gate?
--
-- Background: ingestWeeklyPoints used to skip a week whenever its scores were
-- already cached, which took the league_matchup upsert down with it. Any season
-- ingested before league_matchup existed (2026-09-14) therefore has complete
-- scores and no pairings, and re-running the ingest could not repair it because
-- the skip was keyed on the table that was already full. Fixed in 945371e.
--
-- This query only REPORTS. The pairings themselves cannot be recovered with
-- SQL -- they live in Sleeper's matchups endpoint and nowhere in this schema,
-- so repairing them means re-running the ingest. See repair-missing-pairings.sh.
--
--   psql -d "$DATABASE_URL" -f scripts/find-missing-pairings.sql

\echo '== League-seasons with scored weeks that carry no pairing =='

select l.id,
       l.sport,
       l.season,
       l.name,
       l.sleeper_id,
       count(distinct w.week)                          as scored_weeks,
       count(distinct m.week)                          as paired_weeks,
       count(distinct w.week) - count(distinct m.week) as missing_weeks
from league l
join roster_week_points w on w.league_id = l.id
left join league_matchup m on m.league_id = l.id and m.matchup_id is not null
group by l.id, l.sport, l.season, l.name, l.sleeper_id
having count(distinct w.week) > count(distinct m.week)
order by missing_weeks desc, l.name, l.season;

\echo ''
\echo '== Chain HEADS to re-ingest (one call each repairs every season behind it) =='

-- The ingest walks previous_league_id BACKWARDS from the id it is given, so the
-- newest league in a chain covers all of its predecessors in one pass. Calling
-- an affected season's own id would repair that season and leave anything newer
-- in the same chain untouched.
with affected as (
    select l.sleeper_id
    from league l
    join roster_week_points w on w.league_id = l.id
    left join league_matchup m on m.league_id = l.id and m.matchup_id is not null
    group by l.id, l.sleeper_id
    having count(distinct w.week) > count(distinct m.week)
),
heads as (
    select l.* from league l
    where not exists (select 1 from league c where c.previous_league_id = l.sleeper_id)
),
chain as (
    select h.sleeper_id as head_sleeper, h.name, h.sport, member.sleeper_id as member
    from heads h
    join lateral (
        with recursive walk as (
            select h.sleeper_id, h.previous_league_id
            union all
            select l2.sleeper_id, l2.previous_league_id
            from league l2 join walk w on l2.sleeper_id = w.previous_league_id
        ) select sleeper_id from walk
    ) member on true
)
select distinct c.head_sleeper, c.name, c.sport
from chain c
join affected a on a.sleeper_id = c.member
order by c.name;
