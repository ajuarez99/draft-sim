-- specs/064-time-machine-achievements, plan.md and its "Amended after review (2026-11-13)" section.
--
-- V48, after V47: V47 was the highest on origin/main and every remote branch when this was written. Migrations
-- here are append-only. Never edit this file once applied; any change is V49.
--
-- Awards are stored BESIDE a game's frozen result, never inside it (FR-002). Level is derived from XP on read and
-- is not stored; historical_profile only remembers the highest level the fan has already been told about.

-- Every game that is COMPLETE right now was finished before achievements existed: it settles from its saved
-- result only and its badges are granted quietly (FR-005). A game that is still DRAFTING here is a normal new game.
alter table historical_game add column pre_achievements boolean not null default false;
update historical_game set pre_achievements = true where status = 'COMPLETE';

-- Per-pick facts captured when the draft finishes (amendment A1), so settle never recomputes from reference
-- tables a re-import may have changed. Null for an old game or when the capture failed.
alter table historical_game add column pick_facts jsonb;

create table historical_game_award (
    game_id           bigint      primary key references historical_game (id) on delete cascade,
    sleeper_user_id   text        not null,
    sport             text        not null,
    xp                int         not null,
    weekly_wins       int         not null,
    playoff_wins      int         not null,
    title             boolean     not null,
    from_saved_result boolean     not null,
    xp_lines          jsonb       not null,                  -- [{label, xp}], game lines only; badge XP is on the badge rows
    moments           jsonb       not null default '[]',     -- per-game reach / bust / gem / gone-early instances, no XP
    moments_seen_at   timestamptz,
    settled_at        timestamptz not null default now()
);
create index historical_game_award_user on historical_game_award (sleeper_user_id);

create table historical_achievement (
    sleeper_user_id text        not null,
    code            text        not null,
    game_id         bigint      references historical_game (id) on delete set null,
    xp              int         not null,                    -- frozen at unlock, so a later retune does not rewrite history
    detail          jsonb,
    unlocked_at     timestamptz not null default now(),
    seen_at         timestamptz,
    primary key (sleeper_user_id, code)
);

create table historical_profile (
    sleeper_user_id text primary key,
    seen_level      int  not null default 1
);
