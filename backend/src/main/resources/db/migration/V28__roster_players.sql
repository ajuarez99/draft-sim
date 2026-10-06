-- Spec 019 (minutes trends + streaming): which players are on each roster, from the /rosters
-- response the refresh already fetches. NULL = never fetched since this migration; an empty array
-- = fetched, and the roster holds no players (Sleeper sends an empty list pre-draft).
alter table roster_season add column players text[];
alter table roster_season add column players_fetched_at timestamptz;
