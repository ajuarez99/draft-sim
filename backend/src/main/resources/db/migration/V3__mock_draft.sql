-- Feature C (claude/next-features-roadmap.md, Phase 3): the interactive mock
-- draft room. Structurally walled off from draft/draft_pick and everything
-- ProfileService.fit()/DraftRepository.allCompletedPicks() touches -- no FK,
-- no shared id space, no view that unions them. §3.4 of that doc: keep these
-- as two separate producers indefinitely, on purpose, even if it looks
-- tempting to unify them later.

create table mock_draft_session (
    id                    bigserial primary key,
    status                text        not null default 'IN_PROGRESS',
    teams                 int         not null,
    rounds                int         not null,
    -- Snapshotted, not re-derived from LeagueShape.STANDARD_ROSTER at read
    -- time -- a later change to the standard template must not retroactively
    -- rewrite an in-progress session's roster shape.
    roster_positions      text[]      not null,
    points_per_reception  numeric(3,2) not null default 1.0,
    -- One SeatSpec per occupied slot, snapshotted at creation:
    -- [{"slot":1,"type":"USER","managerId":null}, ...]. A slot missing from
    -- this array is a BOT, same convention DraftContextFactory.build() uses.
    seats_json            jsonb       not null,
    user_slot             int         not null,
    rng_seed              bigint      not null,
    -- The next pick still to be decided. Invariant the whole feature leans
    -- on: whenever a mutating call returns, the seat at current_pick_no is
    -- either USER (waiting on a human) or current_pick_no > teams*rounds
    -- (status = COMPLETE). A bot is never left "owing" a pick between calls.
    current_pick_no       int         not null default 1,
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),
    check (teams in (8, 10, 12, 14)),               -- same domain as feature A, §3.1
    check (status in ('IN_PROGRESS', 'COMPLETE'))
);

create table mock_draft_pick (
    id           bigserial primary key,
    session_id   bigint not null references mock_draft_session (id) on delete cascade,
    pick_no      int    not null,
    round        int    not null,
    draft_slot   int    not null,
    -- Denormalized off the session's own seats_json so board rendering never
    -- needs to rejoin it.
    seat_type    text   not null,
    manager_id   bigint references manager (id),   -- null for BOT, and for a USER with no Sleeper identity
    player_id    bigint not null references player (id),
    -- Who actually decided this pick -- distinct from seat_type. A MANAGER or
    -- BOT seat's pick is always source=BOT (the engine decided it); a USER
    -- seat's pick is always source=USER.
    source       text   not null,
    created_at   timestamptz not null default now(),
    unique (session_id, pick_no),
    check (seat_type in ('USER', 'MANAGER', 'BOT')),
    check (source in ('USER', 'BOT'))
);
create index mock_draft_pick_session_idx on mock_draft_pick (session_id, pick_no);
