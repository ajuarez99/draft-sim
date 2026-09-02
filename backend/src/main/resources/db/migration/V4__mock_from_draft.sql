-- Forking a live/real draft into a mock session (claude/next-features-roadmap.md's
-- Phase 3/4 bridge). A fork seeds mock_draft_pick with the real draft's
-- already-landed picks before continuing, so those rows need a third source
-- value distinct from both existing ones: BOT (the engine decided it in this
-- session) and USER (this session's own human clicked it). LIVE means "this
-- pick actually happened in a real Sleeper draft, copied in at fork time."

alter table mock_draft_pick drop constraint mock_draft_pick_source_check;
alter table mock_draft_pick add constraint mock_draft_pick_source_check
    check (source in ('USER', 'BOT', 'LIVE'));

-- Provenance for a forked session -- null for an ordinary from-scratch mock.
-- This points FROM mock_draft_session TO draft, the opposite direction of the
-- contamination risk claude/next-features-roadmap.md §2(a) guards against
-- (nothing reads mock_draft_session when fitting profiles), so it does not
-- weaken that boundary.
--
-- on delete set null, not cascade or the FK default (restrict): a forked
-- session is the user's own exploratory work and must survive the source
-- draft being deleted (this project's own established cleanup path -- see
-- HANDOFF.md's "DB-delete cleanup" -- routinely deletes a league, cascading
-- draft/draft_pick). It just loses its "forked from" provenance and reads
-- like an ordinary from-scratch mock from then on, rather than either being
-- destroyed with no warning or blocking the real draft's own deletion.
alter table mock_draft_session add column source_draft_id bigint references draft (id) on delete set null;
alter table mock_draft_session add column forked_at_pick_no int;
