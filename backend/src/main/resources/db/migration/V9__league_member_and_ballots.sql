-- claude/power-rankings-ballots.md; amended after
-- claude/plan-review-power-rankings-ballots.md findings 1, 3, 4, 5, 14.
--
-- league_member fixes a membership hole LeagueMembership could not close on
-- its own. A league member is currently discoverable only through
-- roster_season (written by league-history ingest) or draft.slot_to_manager
-- (written by draft ingest, and empty until the commissioner sets
-- draft_order -- claude/lessons.md #15). A league created yesterday has run
-- neither, so it has NO member this app can identify at all -- not "a member
-- with less data", genuinely none. That is exactly the case AC11 needs to
-- work, so it gets its own table rather than being asserted away.
--
-- Populated inside the two upsertManagers loops that already walk
-- sleeper.leagueUsers() for every league ingest (LeagueIngestService.java,
-- LeagueHistoryIngestService.java) -- not a third read of that payload.
-- Sleeper is the source of truth for who is in a league, so this table is
-- REWRITTEN on every ingest, exactly like `manager` itself: a
-- commissionership that changes hands, or a new member joining, shows up
-- automatically on the next re-ingest. Contrast with ranking_ballot below --
-- ingest must NEVER touch that table, because a ballot is a person's stated
-- opinion, not a fact Sleeper can correct out from under them.
--
-- This replaces the `commissioner_sleeper_user_ids text[]` column an earlier
-- draft of the design doc proposed adding to `league` -- amended away
-- entirely (review findings 2 and 3), not merely fixed, because a text[]
-- column needs an explicit createArrayOf bind on every write
-- (claude/lessons.md #4 names the exact trap of handing pgjdbc a bare
-- String[] instead) and LeagueRepository.upsert's on-conflict column list
-- would need extending at every call site that already exists. A join table
-- needs neither: no array binding, and no touching a repository that
-- currently has nothing to do with membership at all.
create table league_member (
    id               bigserial primary key,
    league_id        bigint  not null references league (id) on delete cascade,
    manager_id       bigint  not null references manager (id) on delete cascade,
    -- NOT a plain boolean read off Sleeper. Measured live against a real
    -- 12-user league: `is_owner` is present-and-true on the commissioner and
    -- ABSENT -- not false -- on every other member. `(boolean) u.get("is_owner")`
    -- NPEs on all eleven of them; the only safe read is
    -- `Boolean.TRUE.equals(u.get("is_owner"))`.
    --
    -- Plural on purpose: Sleeper models co-commissioners, and several members
    -- can carry this flag. Whether Sleeper actually SETS is_owner for a
    -- co-commissioner is UNVERIFIED -- the one league measured here has
    -- exactly one owner -- but a plural-capable column costs nothing to keep,
    -- so it stays a per-row boolean rather than a single not-null owner FK.
    is_commissioner  boolean not null default false,
    -- metadata.team_name, captured at ingest with the app's own fallback
    -- chain: team_name if present and not the literal string "TBD" (measured
    -- live: one of twelve users on the same league carries exactly that
    -- placeholder), else display_name, else null. The final "roster N"
    -- fallback is NOT applied here -- this table has no roster_id, only a
    -- manager -- it is applied by whichever endpoint already knows which
    -- roster this member currently owns (a live Sleeper read, not a stored
    -- one, so it works before this member has ever been ingested into
    -- roster_season).
    team_name        text,
    unique (league_id, manager_id)
);
create index league_member_manager_idx on league_member (manager_id);

-- One ballot per (league, week, manager) -- deliberately NOT
-- (league, season, week, manager). `league` is already per-season
-- (V1__init.sql: `season int not null, sleeper_id text not null unique` --
-- one league row is one Sleeper league id is one season), so league_id
-- already implies the season and a season column here would carry no
-- information except a way to break the very uniqueness it looks like it
-- strengthens: nothing would constrain a client-supplied season to equal
-- league.season, so two POSTs for the same league/week/manager but
-- different `season` values would both insert and the unique constraint
-- would never fire (plan-review finding 4). The season the API reports back
-- is read from league.season server-side; any value in the request body is
-- ignored, the same rule as manager_id being read from the header and never
-- the body.
create table ranking_ballot (
    id            bigserial primary key,
    league_id     bigint not null references league (id) on delete cascade,
    week          int    not null,
    manager_id    bigint not null references manager (id) on delete cascade,
    submitted_at  timestamptz not null default now(),
    unique (league_id, week, manager_id)
);

-- roster_id carries no FK, deliberately -- the same shape power_ranking_entry
-- (V5) already uses, and for the same reason: it is a per-league Sleeper int,
-- not a global key, and league_id already pins the season it is scoped to.
--
-- This schema does NOT enforce ballot completeness. Measured against local
-- PG 17.11 with these exact two constraints: `unique (ballot_id, rank)` does
-- reject a duplicate rank, but delete-then-insert of ranks 1, 2, 7 (a gap,
-- and a short ballot) is accepted outright -- the constraint stops a
-- duplicate, not a hole. Coverage (every roster present, no roster twice, no
-- roster from another league) is therefore enforced by the service, in the
-- same transaction as the write: RankingBallotRepository.upsert is
-- @Transactional, unlike the pattern it otherwise mirrors
-- (PowerRankingRepository.save, which has no transaction boundary anywhere
-- on it, making a partially-written ballot a real state rather than a
-- hypothetical one). Measured: same-transaction delete-then-reinsert of a
-- full, reordered ballot is accepted cleanly, so there is no reason to leave
-- a partial write on the table while the completeness check runs.
create table ranking_ballot_entry (
    id         bigserial primary key,
    ballot_id  bigint not null references ranking_ballot (id) on delete cascade,
    roster_id  int    not null,
    rank       int    not null,
    unique (ballot_id, roster_id),
    unique (ballot_id, rank)
);

-- Deliberately no MEMBER row anywhere in power_ranking, and
-- power_ranking_kind_check (V5) is untouched by this migration. Member
-- aggregates are computed on read from ranking_ballot, never stored: a
-- computed snapshot (COMPUTED_MARKET_VALUE, COMPUTED_REALIZED) is a snapshot
-- because its inputs -- rosters, the board, matchup results -- move
-- underneath it; "up 3 spots since last week" needs history frozen at the
-- time. A ballot's inputs are frozen the moment the week ends -- the ballots
-- themselves already are the historical record -- so a stored average would
-- just be a second copy of the truth that can disagree with the first.
