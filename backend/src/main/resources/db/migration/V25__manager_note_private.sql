-- claude/audit-2026-09-28/02-tendency-writes.md, "Decided 2026-09-29".
--
-- V25, after V24: V24 was the highest applied when this was written (re-checked);
-- migrations here are append-only.
--
-- Two decisions land here.
--
-- (1) A note about a manager is private to the person who wrote it. It used to be
--     one string inside manager_profile.manual_json, shared by every viewer, with
--     no author recorded. It now lives in manager_note, keyed by
--     (author_sleeper_user_id, manager_id, sport).
--
--     EXISTING NOTES ARE DROPPED, NOT MIGRATED. The old notes have no author
--     anywhere -- not in manual_json, not in any log table -- so there is no
--     honest owner to assign them to, and assigning them to the configured app
--     owner would publish a stranger's text under his name. They are deleted
--     from manual_json below; the local database held two, one of them profanity.
--
-- (2) Stated tendencies are not a feature: the API now rejects reachBias and
--     unpredictability, so nothing may keep steering simulations from a value no
--     one can set or clear any more. Any non-null stated reachBias or
--     unpredictability already stored is REMOVED here. Without this an old value
--     would silently keep blending into effectiveReachBias for every user (the
--     engine reads manual_json) with no way left to delete it. The local
--     database held none; production has not been inspected, which is why the
--     strip is unconditional. The stray "empty" key (Jackson serialised
--     ManualTendencies.isEmpty() into the column) goes too.
--
--     Any OTHER key in manual_json is left alone: ManagerProfileRepository.saveManual
--     used to merge with || precisely so an unmodelled key would survive.

create table manager_note (
    author_sleeper_user_id text        not null,
    manager_id             bigint      not null references manager (id) on delete cascade,
    sport                  text        not null,
    note                   text        not null,
    updated_at             timestamptz not null default now(),
    primary key (author_sleeper_user_id, manager_id, sport),
    constraint manager_note_length check (char_length(note) between 1 and 140)
);

create index manager_note_manager_idx on manager_note (manager_id, sport);

update manager_profile
   set manual_json = manual_json - 'reachBias' - 'unpredictability' - 'note' - 'empty'
 where manual_json <> '{}'::jsonb;
