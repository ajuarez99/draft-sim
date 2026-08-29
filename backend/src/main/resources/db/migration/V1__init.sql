-- sport is present from day one so basketball can be added without a migration.
-- Only 'nfl' is written by v1.

create table player (
    id             bigserial primary key,
    sport          text        not null,
    sleeper_id     text        not null,
    name           text        not null,
    positions      text[]      not null default '{}',
    team           text,
    status         text,          -- Active / Inactive / Injured Reserve ...
    injury_status  text,          -- Questionable / Out / null
    age            int,
    years_exp      int,
    unique (sport, sleeper_id)
);
create index player_sport_name_idx on player (sport, name);

-- One row per player per source per capture date. Keeping the history is what
-- lets a historical pick be scored against the board that existed at the time.
--
-- Sources in use:
--   sleeper_search_rank  Sleeper's own popularity rank, rank-as-pick-number
--   league_draft:<id>    actual pick order from one completed draft
--   blend                the derived board the engine actually values against
create table adp_snapshot (
    id               bigserial primary key,
    player_id        bigint      not null references player (id) on delete cascade,
    sport            text        not null,
    source           text        not null,
    captured_on      date        not null,
    adp              numeric(6,2) not null,
    positional_rank  int,
    unique (player_id, source, captured_on)
);
create index adp_snapshot_lookup_idx on adp_snapshot (sport, source, captured_on);

create table manager (
    id                bigserial primary key,
    sleeper_user_id   text not null unique,   -- never key on display name
    display_name      text
);

create table league (
    id                  bigserial primary key,
    sport               text not null,
    season              int  not null,
    sleeper_id          text not null unique,
    previous_league_id  text,
    name                text,
    total_rosters       int,
    settings_json       jsonb not null default '{}'::jsonb,
    scoring_json        jsonb not null default '{}'::jsonb,
    roster_positions    text[] not null default '{}'
);

create table draft (
    id                bigserial primary key,
    league_id         bigint not null references league (id) on delete cascade,
    sleeper_draft_id  text   not null unique,
    season            int    not null,
    rounds            int    not null,
    teams             int    not null,
    draft_type        text   not null,
    status            text,
    start_time        timestamptz,
    -- {"<slot>": <manager.id>} -- resolved at ingest from Sleeper draft_order
    slot_to_manager   jsonb  not null default '{}'::jsonb
);

create table draft_pick (
    id           bigserial primary key,
    draft_id     bigint not null references draft (id) on delete cascade,
    pick_no      int    not null,
    round        int    not null,
    draft_slot   int    not null,
    manager_id   bigint references manager (id),
    player_id    bigint references player (id),
    -- denormalized at ingest: the board position that existed when this pick
    -- was made, so reach is measured against the board of the day
    adp_at_time  numeric(6,2),
    unique (draft_id, pick_no)
);
create index draft_pick_manager_idx on draft_pick (manager_id);
create index draft_pick_draft_idx on draft_pick (draft_id, pick_no);

-- Two kinds of knowledge about a manager, deliberately kept in separate columns
-- so neither can clobber the other:
--
--   feature_json  FITTED from draft history. Written only by profile fitting.
--   manual_json   STATED by the user. Written only through the API.
--
-- With one or two drafts of history a fitted estimate is mostly noise, while the
-- user genuinely knows these people. manual_json is treated as the prior that
-- shrinkage pulls toward, so a seat with no history uses the stated value exactly
-- and a seat with history blends toward what actually happened.
create table manager_profile (
    id               bigserial primary key,
    manager_id       bigint not null references manager (id) on delete cascade,
    sport            text   not null,
    feature_json     jsonb  not null default '{}'::jsonb,
    manual_json      jsonb  not null default '{}'::jsonb,
    -- surfaced in the API so the UI can be honest about how thin this is
    drafts_observed  int    not null default 0,
    updated_at       timestamptz not null default now(),
    unique (manager_id, sport)
);
