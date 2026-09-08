-- claude/multi-sport-and-rebrand.md Phase 2. Two columns only -- no
-- player_alias.sport: that table (V2__ffc_adp.sql:20) is never read or
-- written by any Java code (PlayerMatcher is built from
-- players.findAll(sport) via the no-alias overload), so a column on it would
-- be noise, not a fix.

-- Read by Phase 5 (DraftSlot's snake-reversal handling); nothing reads or
-- writes it yet. Default 0 means "no reversal", i.e. today's plain-snake
-- behaviour for every existing row.
alter table draft add column reversal_round int not null default 0;

-- The mock draft room is out of scope for this project (Non-goals), but
-- migrations are append-only and this is one line, so it goes in now rather
-- than forcing a V7 later just to add it. Column only -- no Java reads or
-- writes it yet.
alter table mock_draft_session add column sport text not null default 'nfl';
