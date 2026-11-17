-- Spec 074: Ball Knowers Redraft (the Time Machine's new name) for signed-out players, and chosen seasons.
--
-- true when the player picked the season from a season page instead of being dealt one. Such a game is real play but
-- never counts: it earns no XP, no badge, and no best or average draft score (the service skips it when settling).
-- A game of the day is dealt, never chosen, so the two cannot both be set.
alter table historical_game add column chosen_season boolean not null default false;

alter table historical_game add constraint historical_game_chosen_not_daily
    check (not (chosen_season and daily_date is not null));

-- The owner column keeps its name. It now holds either a Sleeper user id or 'guest:<uuid>' for a player who has not
-- signed in (the uuid is made and kept by their browser). A Sleeper id never starts with 'guest:': the API refuses one
-- that does, so the two namespaces cannot collide. Signing in moves a guest's games to the Sleeper id (claim).
comment on column historical_game.sleeper_user_id is
    'owner key: a Sleeper user id, or guest:<uuid> for a signed-out player (spec 074)';
