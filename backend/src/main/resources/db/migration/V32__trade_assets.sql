-- Trade assets beyond players (spec 033): draft picks and FAAB that moved in a trade.
-- null = stored before V32 (unknown); [] = none. Sleeper payloads are stored verbatim.
alter table league_transaction add column draft_picks jsonb, add column waiver_budget jsonb;
