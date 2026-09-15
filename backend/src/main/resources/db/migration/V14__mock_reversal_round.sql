-- The mock draft room learns basketball (claude/nba-mock-drafts.md).
--
-- mock_draft_session.sport already exists -- V6 added it with a 'nfl' default
-- when Sport became a runtime value -- but nothing has ever written or read it,
-- because MockDraftService refused any non-NFL session up front. Only the
-- reversal round is genuinely new.
--
-- Why it matters: the one NBA draft still in pre_draft (the 2026 Ball Knowers
-- season, the draft a basketball mock is practice FOR) has reversal_round = 3.
-- LeagueShape.toSettings() hardcoded plain snake, so without this column a mock
-- of that draft would hand every pick from 3.01 onward to the wrong manager
-- while looking entirely correct on screen.
--
-- Default 0 is plain snake, which is what every session written before now
-- actually ran and what all six football drafts in this database use.
alter table mock_draft_session
    add column reversal_round int not null default 0;

-- Same bound LeagueShape's compact constructor and LeagueController's override
-- endpoint enforce: a reversal at round 1 is not a reversal, and one past the
-- last round never fires. Both columns are on the same row, so the check costs
-- nothing and keeps the invariant true even for a row written by hand.
alter table mock_draft_session
    add constraint mock_draft_session_reversal_round_check
    check (reversal_round = 0 or reversal_round between 2 and rounds);
