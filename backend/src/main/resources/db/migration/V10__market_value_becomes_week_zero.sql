-- Merge COMPUTED_MARKET_VALUE into COMPUTED_REALIZED as week 0.
--
-- Market value used to be its own mode, recomputed fresh at whatever week the
-- "Compute" button was pressed -- a forward-looking expectation living
-- alongside a backward-looking one, at the same week number, which read as
-- two answers to "how good is this team". It becomes instead the one-time
-- preseason baseline that week 1+ (built purely from games actually played)
-- walks forward from: still visible, still labeled honestly, but no longer a
-- fifth thing to tab between.
--
-- Only the EARLIEST market-value snapshot per (league, season) survives as
-- week 0 -- once demoted to a one-time baseline rather than a "current value"
-- recomputed on every visit, keeping every week it was ever (re)computed at
-- would just be N copies of "whatever the roster looked like that day", and
-- the unique (league_id, season, week, kind) constraint only has room for one
-- row at week 0 regardless.
delete from power_ranking pr
where pr.kind = 'COMPUTED_MARKET_VALUE'
  and pr.id not in (
    select min(id) from power_ranking
    where kind = 'COMPUTED_MARKET_VALUE'
    group by league_id, season
  );

update power_ranking
set kind = 'COMPUTED_REALIZED', week = 0
where kind = 'COMPUTED_MARKET_VALUE';

alter table power_ranking drop constraint power_ranking_kind_check;
alter table power_ranking add constraint power_ranking_kind_check
    check (kind in ('COMMISSIONER', 'COMPUTED_REALIZED'));
