-- Sleeper's own avatar id for a manager, e.g. "e1d4ebf9ea0760f248119d4ec2ac5a63".
-- Resolves to an image via https://sleepercdn.com/avatars/thumbs/<id>. Was
-- already being fetched off Sleeper (SleeperUserController, leagueUsers()) and
-- discarded -- this just gives it somewhere to land.
alter table manager add column avatar_id text;
