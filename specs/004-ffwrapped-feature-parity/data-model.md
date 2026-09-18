# Phase 1 Data Model

**Branch**: `004-ffwrapped-feature-parity` | **Date**: 2026-09-18

Two principles govern everything below, both from [research.md](research.md):

1. **Most of this feature stores nothing new.** US1–US4 read tables that already exist and are already
   populated. Storage appears only in US4 (two columns on an existing snapshot), US5 (one column) and
   US6 (one table).
2. **A new per-week column is not done when the migration lands.** The ingest skip gate tests whether
   rows exist, not whether a column is populated (R6), so every new per-week column ships with a gate
   change and a counted backfill in the same task.

**Version numbers below follow execution order, not story order.** Flyway's `outOfOrder` is unset and
therefore false, so a lower version added after a higher one has been applied fails at boot. US4 ships
in phase 6 and takes `V17`; US5 ships in phase 7 and takes `V18`. Numbering these by story number
instead would put `V17` after `V18` on disk and break the next startup.

---

## Existing tables this feature reads

### `roster_week_points` (V5) — the workhorse

| Column | Type | Role in this feature |
|---|---|---|
| `league_id`, `season`, `week`, `roster_id` | identity | keys every per-week computation |
| `starters_points` | `numeric(8,2)` | **actual** points for the week; summed for Total Points |
| `players_points` | `jsonb` | `sleeper_player_id -> points` for every rostered player; the input to potential points, top performers and every award |

Already populated for every scored week of every ingested season, in both sports. No change in US2/US3.

### `roster_season` (V5)

`wins`, `losses`, `ties`, `points_for`, `points_against`, `points_possible`, `final_placement`.

Read for actual wins (US3's conservation check) and as a **cross-check only** for potential points —
`points_possible` is Sleeper's season aggregate under Sleeper's rules, not this app's, and is
deliberately not rendered as Potential Points (R3).

### `league_matchup` (V13 era)

Per-week pairings. Drives Weekly Report matchups (US5), strength of schedule (US3) and the remaining
schedule the forecast simulates (US4).

### `playoff_odds`

The stored simulation snapshot. Extended in US4; never recomputed on read.

---

## New storage

### US4 — `V17__playoff_odds_distributions.sql`

The simulator already computes these distributions over its 10,000 seasons and discards them at
serialization (R8). This adds somewhere to keep them.

| Column | Type | Notes |
|---|---|---|
| `seed_counts` | `jsonb` | `seed -> count` over simulated seasons. Marginal over seeds must reproduce the existing playoff-odds figure — asserted, not assumed (FR-008) |
| `win_counts` | `jsonb` | `wins -> count`; yields average wins and the 10th–90th percentile range |

Written on the same commissioner recompute that already writes the snapshot. No new trigger, no
page-load computation, and the existing refusal for divisions / non-default `playoff_seed_type` is
untouched (FR-009).

### US5 — `V18__roster_week_starters.sql`

| Column | Type | Notes |
|---|---|---|
| `starters` | `jsonb` | ordered array of `sleeper_player_id` — **who** was started, which the app has never stored |

Today only `starters_points` (the total) is kept. That is enough for efficiency and potential points,
which is why US2 needs no migration. It is not enough for an award that must name the bench player who
should have started.

**Ships with, in the same task:**

- the skip gate extended to `stored.contains(week) && paired.contains(week) && hasStarters(week)`,
  following the pattern already used when pairings hit this exact bug;
- a backfill verified by **counting populated rows**, not by a successful build (R6);
- nullable by design — weeks ingested before this column existed and never refetched stay null, and
  US5.4 requires the affected award to be omitted with a stated reason rather than guessed.

### US6 — `V19__league_transaction.sql`

| Column | Type | Notes |
|---|---|---|
| `id` | `bigserial` PK | |
| `league_id` | `bigint` FK -> `league` | `on delete cascade`, matching siblings |
| `season`, `week` | `int` | `week` from Sleeper's `leg` |
| `sleeper_transaction_id` | `text` | natural key for idempotent re-ingest |
| `type` | `text` | checked: `WAIVER`, `FREE_AGENT`, `TRADE`, `COMMISSIONER` |
| `status` | `text` | complete vs failed — failed waiver bids are what the "Failed bids" section reads |
| `roster_id` | `int` | the acting roster; null for a multi-roster trade |
| `manager_id` | `bigint` FK -> `manager` | nullable, exactly as `roster_season.manager_id` is, for orphan rosters |
| `adds`, `drops` | `jsonb` | `sleeper_player_id -> roster_id`, as Sleeper sends them |
| `faab_bid` | `int` | nullable; `settings.waiver_bid` |
| `created_at` | `timestamptz` | from `status_updated`, for "weeks since the move" |

`unique (league_id, sleeper_transaction_id)` so re-ingest is idempotent. Index on
`(league_id, season, week)` to match the read pattern, mirroring `roster_week_points_lookup_idx`.

Sport-neutral by construction: Sleeper's transaction payload has the same shape for NBA, and nothing
here encodes a football position.

---

## Computed entities (not stored)

Read-only, computed on request beside `LeagueAnalysisService`, which established this shape.

### Realized lineup (US2, shared)

For one roster-week: the optimal lineup under `SportRules.startingLineup`, valued by that week's actual
points from `players_points`.

- **Inputs**: `players_points`, league roster slots, per-player position eligibility.
- **Rule**: the value function is realized points, which is **not monotone in ADP**, so candidates must
  be sorted by it before slots are filled (R5). This is where the football javadoc's warning becomes
  load-bearing for basketball too.
- **Validity**: a week whose `players_points` is empty or missing is **excluded**, and the exclusion is
  carried out of the service so the UI can show it (FR-007). It is never summed as zero.

### Season roster summary (US2)

Per roster: `totalPoints` (sum of `starters_points`), `potentialPoints` (sum of realized lineups),
`efficiency` (total ÷ potential), `weeksCounted`, `weeksExcluded`.

`efficiency` is undefined, not 1.0, when `potentialPoints` is 0 — the zero-scored-weeks case (US2.4).

### Expected wins record (US3)

Per roster: `expectedWins` (sum over weeks of the fraction of other teams outscored that week),
`actualWins`, `winsAboveExpected`, `strengthOfSchedule` (mean of opponents' PPG minus league PPG), and
either the swing weeks or the flag that the cause was consistent opponent scoring (US3.4 — one or the
other, never both).

**Invariant**: `sum(expectedWins) == sum(actualWins)` across the league, within floating-point tolerance
(SC-004). This is the check that proves the all-play model rather than merely exercising it.

### Season forecast (US4)

Per roster, read off one snapshot: `playoffOdds`, `averageWins`, `winRange` (p10–p90), `averageSeed`,
`seedOdds`, `championshipOdds`. Every field derives from the stored distributions — no second
computation, so the two views cannot disagree (FR-008).

### Weekly report (US5)

Per week: matchups from `league_matchup` plus scores; top performers ranked from `players_points`;
awards. Awards split into those computable from totals alone and those requiring `starters` — the
second group degrades explicitly when the column is null.

### Transaction analysis (US6)

Per manager: counts by type. Per trade and per add: the player's average positional rank over weeks
played since the move, computed from `players_points` across the league. Positional rank is resolved
through the sport's own positions, never a football-shaped list (US6.5).
