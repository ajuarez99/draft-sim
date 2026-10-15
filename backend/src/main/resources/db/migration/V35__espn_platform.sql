-- Spec 035: ESPN league ingest. Additive only; nothing here changes the type, nullability
-- or meaning of an existing Sleeper row (FR-013).
--
-- Numbering (plan F6, re-checked at build time, T001): main's highest is V31; 033's worktree
-- holds V34__trade_assets.sql and 034's holds V33__league_bracket_match.sql; no worktree holds
-- V35. Re-check every worktree's migration directory before this is committed or merged.

-- Platform columns. The default makes every existing row SLEEPER without a rewrite.
alter table league
    add column platform text not null default 'SLEEPER' check (platform in ('SLEEPER', 'ESPN'));
alter table manager
    add column platform text not null default 'SLEEPER' check (platform in ('SLEEPER', 'ESPN'));
alter table draft
    add column platform text not null default 'SLEEPER' check (platform in ('SLEEPER', 'ESPN'));
alter table league_transaction
    add column platform text not null default 'SLEEPER' check (platform in ('SLEEPER', 'ESPN'));

-- Column reuse convention (research R14): the sleeper_* columns hold the platform's own key
-- for a non-Sleeper row rather than being renamed (a rename touches ~16 migration sites and
-- every query for no behavioural gain).
--   league.sleeper_id            = espn-{ffl|fba}-{leagueId}-{season}
--   manager.sleeper_user_id      = espn:{member GUID, braces kept as ESPN sends them}
--   draft.sleeper_draft_id       = {league key}-draft
--   league_transaction.sleeper_transaction_id = espn:{ESPN transaction id}
-- The platform is always read from `platform`, never parsed out of these keys.

alter table league add column platform_league_id text null;
alter table league add column scoring_untranslated text[] null;
alter table league add column unmodelled_slots text[] null;
comment on column league.platform_league_id is
    'ESPN''s own league id, stable across seasons. Null for Sleeper.';
comment on column league.scoring_untranslated is
    'ESPN scoring items with no confirmed Sleeper key. Non-empty => scoring_json is {} and scoring-dependent pages are unavailable (research R7).';
comment on column league.unmodelled_slots is
    'ESPN lineup slots this app has no equivalent for (e.g. IDP, HC, combo slots).';

-- IR players, so the Sleeper-shaped roster the engine reads has a `reserve` list (plan F7).
alter table roster_season add column reserve text[] null;

-- Plan F10: `espn:{playerId}` keys may appear ONLY in roster_season.players / reserve,
-- roster_week_points.players_points / starters, and league_transaction.adds / drops.
-- They are an ESPN player with no confident match to a Sleeper player. Anything that joins
-- those columns to player.sleeper_id must filter the prefix (PlayerGameIngestService does).

-- A signed-in (Sleeper) manager's claim on an ESPN member. One ESPN manager belongs to at most
-- one claimant. The link is per ESPN member, not per league (plan F3): it reaches every stored
-- ESPN league that member plays in.
create table manager_link (
    manager_id         bigint      not null references manager (id) on delete cascade,
    linked_manager_id  bigint      not null references manager (id) on delete cascade,
    league_id          bigint      not null references league (id) on delete cascade,
    created_at         timestamptz not null default now(),
    primary key (manager_id, linked_manager_id),
    unique (linked_manager_id)
);

-- Remembers which app player an external player id resolved to (or why it did not).
-- A hand fix is outcome = 'MANUAL' here, not a player_alias row (plan F11).
create table platform_player_link (
    platform           text        not null,
    sport              text        not null,
    external_id        text        not null,
    player_id          bigint      null references player (id),
    outcome            text        not null
        check (outcome in ('MATCHED', 'AMBIGUOUS', 'UNMATCHED', 'ESPN_ONLY_POSITION', 'MANUAL')),
    external_name      text        not null,
    external_position  text        null,
    external_team      text        null,
    resolved_at        timestamptz not null,
    primary key (platform, sport, external_id)
);

-- ESPN cookies, AES-256-GCM sealed, one set per ESPN league (research R13, amends FR-009).
-- Only LeagueCredentialRepository.forIngest ever selects ciphertext.
create table league_credential (
    platform           text        not null,
    sport              text        not null,
    platform_league_id text        not null,
    owner_manager_id   bigint      not null references manager (id) on delete cascade,
    ciphertext         bytea       not null,
    nonce              bytea       not null,
    key_version        int         not null default 1,
    updated_at         timestamptz not null,
    last_worked_at     timestamptz null,
    primary key (platform, sport, platform_league_id)
);
