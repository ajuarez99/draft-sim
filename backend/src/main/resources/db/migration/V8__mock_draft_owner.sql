-- Mock sessions had no owner at all: mock_draft_session carried no identity
-- column, GET /api/mocks returned every session to every caller, and
-- POST /api/mocks/{id}/pick accepted a pick into any of them. With one user
-- that is invisible; with two it is the only place in the app where a visitor
-- does not merely read someone else's data but overwrites their in-progress
-- work.
--
-- The Sleeper user id from the X-Sleeper-User header, not a FK to `manager`:
-- a mock is started by whoever is at the keyboard, and that person need not
-- have a manager row (they may have no ingested league yet -- the from-scratch
-- room at /mock/new exists precisely for that case). Same identity token the
-- rest of the app scopes on.
--
-- Nullable, and null means "created before this column existed". Those rows
-- stay visible to everyone, which is a deliberately closed set: nothing is
-- deployed yet, so the population is the handful of local development
-- sessions and can never grow. Every session created from here on has an
-- owner. Preserving them beats orphaning work to make the rule prettier.
alter table mock_draft_session add column owner_sleeper_user_id text;

create index mock_draft_session_owner_idx
    on mock_draft_session (owner_sleeper_user_id, created_at desc);
