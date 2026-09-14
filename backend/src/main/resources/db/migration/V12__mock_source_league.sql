-- multisport-home-redesign (design_handoff_multisport_mock_drafts/README.md):
-- the redesigned home screen's mock-draft rows must identify their source
-- league at a glance ("12-team NFL mock · Ball Knowers"), but a mock session
-- has never recorded which league's team count it borrowed -- MockSetup only
-- ever took a bare team count. Snapshotted at creation time, not a live FK:
-- the league's own name can change later without rewriting an in-progress
-- session's own display string, same reasoning as roster_positions above.
alter table mock_draft_session add column source_league_name text;
