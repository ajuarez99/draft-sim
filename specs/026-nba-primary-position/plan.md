# Spec 026: Sleeper's real NBA primary position in the simulation

**Written 2026-10-10, ~15:45 UTC, on the day of the "Ball Knowers" NBA draft (21:15 UTC).** This is a
compressed run of the repo's pipeline: plan → adversarial review → build → review → live verification,
all before the draft, because Allan asked for every owed item to be merged before it.

## What's there now

- `PlayerIngestService.fantasyPositions` (`ingest/PlayerIngestService.java:176-186`) stores Sleeper's
  `fantasy_positions` in Sleeper's order. Sleeper lists NBA `fantasy_positions` alphabetically
  (lessons #38). That ordering is stored into `player.positions` (text[], order preserved,
  `store/PlayerRepository.java:27-42,117`).
- `Player.primary()` (`domain/Player.java:18`) = `positions.getFirst()`. That means alphabetical-first
  for NBA. These consumers read it:
  - **Simulation-relevant:**
    - `ProfileService.fit` `:135`: manager positional tilt and positional priors.
    - `BoardService` `:128`: the board's per-position rank.
    - `BoardEntry.position()` → `PlayerRef.position` on the wire, then `onBrand.ts`'s lean (spec 025 research R9).
  - **Display-only (labels):** `LeagueController:334`, `PlayerSpotlightService`, `WeeklyReportService`,
    `SeasonSuperlativesService`, `TransactionAnalysisService`, `LeagueAnalysisService:754`.
  - `WeeklyReportService:547` compares two players' primaries.
- Spec 025's frontend labels sort NBA positions into the fixed PG,SG,SF,PF,C order
  (`web/src/positions.ts` `nbaSorted`). Labels do not depend on the stored order.

## Measured (Sleeper `/v1/players/nba`, 2026-10-10 15:36 UTC)

- 1,478 active multi-position players.
- Sleeper's own `position` field is in `fantasy_positions` for 1,472 of them. It is already first for 769;
  **703 (48%) would move.**
- The 6 whose `position` is not eligible (e.g. Herbert Jones `PF`, fantasy `[SF,SG]`) keep the current order.
- Examples:

  | Player | `position` | `fantasy_positions` |
  |---|---|---|
  | Anthony Edwards | SG | [PG,SG] |
  | Devin Booker | SG | [PG,SG] |
  | Kevin Durant | SF | [PF,SF] |
  | Jayson Tatum | SF | [PF,SF] |
  | LeBron James | PF | [PF,PG,SF] |

## Design

**Reorder at ingest; no migration, no new field.** In `fantasyPositions`, for NBA only: if Sleeper's
`position` maps (via `Position.fromSleeper(pos, NBA)`) to a position that is in the eligibility list,
move it to the front. The rest keep Sleeper's order. Eligibility, the *set*, is unchanged.

- `primary()` then becomes Sleeper's real position for every eligible case, with no reader changes.
- NFL is untouched: the method returns early for football. Spec 025 R2 records that no active NFL player
  has two positions anyway.
- **Why not a new column (the handoff suggested V31):** the stored array's *order* is the only thing
  that's wrong. A second field would be a second source of truth for "primary", which is this repo's
  recurring two-implementations class. Reordering keeps one.
- **Order-sensitive readers to audit:**
  - anything that reads `positions` order rather than the set;
  - `BasketballRules` seating, which uses a mask and should be order-free (verify);
  - `PlayerMatcher:57`;
  - the frontend `eligiblePositions()[0]` uses (football only, per spec 025's review);
  - `pickRun.ts` / `familyCode` (verify that they derive from the set).
  - The 37-case `lineup.ts` parity fixture must stay identical.

## Expected to change, on purpose (re-baseline deliberately; say so)

- `Spec025SimBaselineTest` and `EngineOutputFrozenTest`, if their fixtures run through ingest-ordered
  NBA players. If they build `Player`s directly, they won't change, and that must be stated rather than
  presented as proof.
- Fitted NBA positional tilts: SG should appear where it was almost absent. Measure before and after on
  the 5433 DB (the "Ball Knowers" NBA league), and record both in verification.md.
- Board `pos_rank` for NBA: Edwards becomes SGn, not PGn. No NBA badge prints a rank (spec 025), so
  this is invisible in the UI.
- **Never edit `config/weights.yml` to match anything.**

## Not built

- No change to labels, filters or eligibility (spec 025 owns those).
- No new column.
- No NFL change.

## Deploy note

Prod `player.positions` only reorders when NBA players are re-ingested: either the daily refresh
(`DailyRefreshService:93`) or `POST /api/ingest/all/{leagueId}` with `X-Admin-Token`, which is Allan's.
Board and profiles follow from the same ingest. Until that runs, prod behaves exactly as today.

## Acceptance

1. A unit test on `fantasyPositions`: Edwards-shaped input gives `[SG,PG]`; Herbert-Jones-shaped input
   keeps `[SF,SG]`; NFL input is unchanged; a single position is unchanged.
2. A preference-ordering test (lessons class 1): after ingest, Edwards' `primary()` is SG, not PG.
3. Backend full suite green, with 0 skipped (Postgres up), and web suite plus `tsc` green.
4. Local re-ingest on 5433: before/after counts of NBA `positions[1]`-vs-`[0]` order, and before/after
   fitted tilts for the "Ball Knowers" NBA managers.
5. Live: the NBA draft board and a mock still render. Labels are unchanged ("PG/SG"), and "Fills X" and
   the parity fixture are unchanged.

---

## Amended after review (2026-10-10, ~16:10 UTC; see plan-review.md)

- **B1. The deploy note above was wrong for tonight.**
  - The daily refresh runs at 11:00 UTC (`.github/workflows/daily-refresh.yml:10`) and returns SKIPPED_ALREADY_TODAY for the rest of the day. So a deploy alone reorders nothing before the 21:15 UTC draft.
  - **Rollout:** after the deploy, Allan runs `POST /api/ingest/players?sport=nba` with `X-Admin-Token`. Players only. **Not** `/ingest/all` or `/ingest/board`: the board refresh's global `UPDATE draft_pick` backfill stalls the live poller (`LeagueIngestService:75-82`).
  - `ProfileService.fit` re-reads `player` per request, so tilts and priors move immediately. NBA `pos_rank` stays on the old order until the next daily board refresh; no NBA badge prints it.
  - Post-deploy check: Edwards reads `{SG,PG}` on prod.
- **S1. The plan's reader list left out the engine.** `PickDecider:129` reads the candidate's primary for the positional-prior + tilt term (`PickScorer:83-85`). `PickDecider:73`, `DraftSimulator:98` and `MockDraftEngine:96` feed primaries into run-pressure history.
  - **Measured locally:** NBA has two completed 168-pick drafts (2024, 2025) with managers. So NBA tilts *are* fitted, and this change moves tonight's sim through positionalPrior 0.35 and runPressure 0.25 (both GUESS in weights.yml, untouched).
  - This contradicts the "NBA managers can't be fitted until 2026" note, which was wrong.
  - Seating and rosterNeed are set-based and unaffected.
- **S2.** `OnBrandPanel.tsx:45`'s tooltip ("not necessarily their main position") becomes false. Update it, along with the comments at `onBrand.ts:46-51`, `SimulationResult.java:26-27`, `api.ts:18` and `posRank.ts:18`.
- **S3. Order-dependent readers that change on purpose:**
  - NBA percentile peer groups (`PlayerStatsService`, `PlayerPercentiles`, `PlayerPage.tsx`): about 703 players move group, Edwards PG→SG. Intended.
  - Single-position display labels: Edwards now shows "SG". Intended.
  - `TransactionAnalysisService:350` and `WeeklyReportService:547`: intended.
  - `DraftGradesService:201-203` joins in stored order: sort it into PG,SG,SF,PF,C order so the string doesn't flip.
- **S4. The "re-baseline" claim is moot.** `Spec025SimBaselineTest`, `NbaLineupParityFixtureTest` and `EngineOutputFrozenTest` all build Players directly, so none will move, and none is evidence for or against this change. `PlayerIngestServiceTest`'s builder has no `position` key; new tests must set it.
- **S5. A preference-ordering test is required.** A scorer test: a seat favouring SG, with equal-ADP `[SG,PG]` and `[PG]` candidates. The `[SG,PG]` candidate gets SG's positional term and outranks the pure PG. Optionally also a fit test: `[SG,PG]`-shaped completed picks give tilt(SG) > 1.
- **N1.** Reorder after `.distinct()`, and only move a position already present. Test: null `position`; "G"/"F"/unmapped; no `fantasy_positions`; ineligible `position` (Herbert Jones); NFL unchanged.
