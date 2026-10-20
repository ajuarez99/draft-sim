-- Spec 040: a commissioner's-list entry may now cover a bounded span of weeks.
-- null applies_through_week = rest of the season (every existing row keeps today's meaning).
alter table league_conduct_entry add column applies_through_week int null;
alter table league_conduct_entry add constraint league_conduct_entry_through_ge_from
    check (applies_through_week is null or applies_through_week >= applies_from_week);
