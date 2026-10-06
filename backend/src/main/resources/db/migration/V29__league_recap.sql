-- specs/020-ai-weekly-recap: premium AI weekly recap, behind a flag.
-- league_feature: per-league entitlement (only RECAP exists). league_recap: one row per
-- (league season, week) holding the last READY body and, separately, the last attempt, so a
-- failed regeneration never destroys a good body (review F11). league_recap_attempt: one row
-- per Anthropic API call, for the per-league and global daily caps (review N7).

create table league_feature (
    league_id  bigint      not null references league(id) on delete cascade,
    feature    text        not null check (feature in ('RECAP')),
    granted_at timestamptz not null default now(),
    note       text,
    primary key (league_id, feature)
);

create table league_recap (
    id                  bigserial primary key,
    league_id           bigint not null references league(id) on delete cascade,
    week                int    not null,

    -- last READY body
    ready_key           text,
    ready_numbers_hash  text,
    ready_input_json    text,
    headline            text,
    sections            jsonb,
    model               text,
    prompt_version      text,
    input_tokens        int,
    output_tokens       int,
    revision            int    not null default 0,
    revision_reason     text,
    generated_at        timestamptz,

    -- last attempt (READY or FAILED)
    attempt_key         text,
    attempt_status      text check (attempt_status in ('READY', 'FAILED')),
    failure_reason      text,
    failure_detail      jsonb,
    stop_reason         text,
    retry_after         timestamptz,
    attempted_at        timestamptz,

    unique (league_id, week)
);

create table league_recap_attempt (
    league_id bigint      not null references league(id) on delete cascade,
    at        timestamptz not null
);
create index league_recap_attempt_league_at on league_recap_attempt (league_id, at);
create index league_recap_attempt_at on league_recap_attempt (at);
