-- specs/008-season-superlatives, coordinator follow-up 2026-09-23.
--
-- The JOEL_EMBIID coverage note must be backed by a real number, not a
-- standing caveat -- research R9's live ingest measured 0 unclassified weeks
-- for both reference leagues, and a caveat with no number behind it is the
-- thing this repo refuses to ship. Persisting an UNCLASSIFIED row per
-- unclassifiable football `None` week (instead of only counting it in the
-- ingest Result, which is never stored) lets the superlatives payload report
-- an actual, per-holder count.
--
-- V23, after V22: V22 is the latest applied and outOfOrder is unset (so
-- false), so append-only. V22's basis check constraint cannot be edited in
-- place -- drop and re-add it here with the third value.
alter table player_absence drop constraint player_absence_basis_check;

alter table player_absence add constraint player_absence_basis_check
    check (basis in ('ENTRY_WITHOUT_PLAY', 'TEAM_PLAYED_NO_ENTRY', 'UNCLASSIFIED'));
