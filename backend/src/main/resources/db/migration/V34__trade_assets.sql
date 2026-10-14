-- Trade assets beyond players (spec 033): draft picks and FAAB that moved in a trade.
-- Numbered V34, not V32: spec 034 merged first with V33, and Flyway refuses an older version
-- arriving after a newer one (renumbered at merge, before this ever reached prod).
-- null = stored before V34 (unknown); [] = none. Sleeper payloads are stored verbatim.
alter table league_transaction add column draft_picks jsonb, add column waiver_budget jsonb;
