-- Spec 024 (draft-room UX): per-user draft targets, the draft's pick timer, and the AUTO pick source.
-- See specs/024-draft-room-ux/research.md, amendment A2 (pick timer stored, not streamed).

-- (a) A user's stated targets for one draft: either a real Sleeper draft or a mock session.
-- sleeper_draft_id deliberately has NO foreign key to draft: a re-ingest must never cascade-delete
-- someone's stated choices (same reasoning as V9's ranking_ballot). mock_session_id does cascade,
-- because a deleted mock has no targets worth keeping.
create table draft_target (
    id                    bigserial primary key,
    owner_sleeper_user_id text   not null,
    sleeper_draft_id      text,
    mock_session_id       bigint references mock_draft_session (id) on delete cascade,
    player_id             bigint not null references player (id),
    rank                  int    not null,
    created_at            timestamptz not null default now(),
    check ((sleeper_draft_id is null) <> (mock_session_id is null))
);

create unique index draft_target_live_uq
    on draft_target (owner_sleeper_user_id, sleeper_draft_id, player_id)
    where sleeper_draft_id is not null;
create unique index draft_target_mock_uq
    on draft_target (owner_sleeper_user_id, mock_session_id, player_id)
    where mock_session_id is not null;

create index draft_target_live_idx
    on draft_target (owner_sleeper_user_id, sleeper_draft_id)
    where sleeper_draft_id is not null;
create index draft_target_mock_idx
    on draft_target (owner_sleeper_user_id, mock_session_id)
    where mock_session_id is not null;

-- (b) Sleeper settings.pick_timer, in seconds. Nullable: null = Sleeper did not send one.
alter table draft add column pick_timer_seconds int;

-- (c) AUTO = the user's own seat, decided by auto-pick.
alter table mock_draft_pick drop constraint mock_draft_pick_source_check;
alter table mock_draft_pick add constraint mock_draft_pick_source_check
    check (source in ('USER','BOT','LIVE','AUTO'));
