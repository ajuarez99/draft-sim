# Spec 026: adversarial plan review

Written 2026-10-10, before any code, against `plan.md` as of commit 3657470 (branch 026-nba-primary-position).
Read-only review. Everything below was **read in code** unless it says **measured**. One item was measured,
on the local 5433 DB. Nothing was run against prod.

## BLOCKER

**B1. The deploy note is wrong for tonight. A deploy alone will not reorder prod before the 21:15 UTC draft.**
- The daily job is a GitHub Actions cron at **11:00 UTC** (`.github/workflows/daily-refresh.yml:10`). It has
  almost certainly already run today.
- `DailyRefreshService.players` (`refresh/DailyRefreshService.java:90-91`) returns `SKIPPED_ALREADY_TODAY` once
  today's `PLAYERS` capture row exists. The UI's setup path, `POST /api/refresh/players`
  (`RefreshController.java:81-90`), goes through the same skip.
- So neither path the plan names re-ingests NBA players today. The first automatic reorder would be
  **2026-10-11 11:00 UTC**, after the draft.
- **Amend:** say plainly that "merged and deployed" is not the same as "in effect". For the draft, Allan must run
  `POST /api/ingest/players?sport=nba` with `X-Admin-Token` (`api/IngestController.java:65-69`, no skip) after
  the deploy and **before ~21:00 UTC**.
- Recommend that **players-only** call, not `/ingest/all` and not `/ingest/board`:
  - `ProfileService.fit` reads `players.findAll` on every request (`profile/ProfileService.java:131-135`). It is
    called per request by `SimulationService:81`, `MockDraftService:298/466/649` and `LeagueController:135`.
    The profile snapshot row is "not a cache the engine reads" (`:244-245`). So priors and tilt move as soon as
    the player rows change.
  - `BoardRefresh` runs a global `UPDATE draft_pick` backfill. `LeagueIngestService.java:75-82` documents that
    this stalls `LiveDraftPoller` if it runs during a live draft. Do not run it near 21:15.
  - The cost of skipping the board rebuild: `pos_rank` stays on the old order until tomorrow's daily run.
    NBA never prints that rank (`web/src/posRank.ts:22`), so it is invisible.
- Add the post-deploy check to acceptance: a `select positions from player where sport='nba' and
  name='Anthony Edwards'` (or equivalent) reads `{SG,PG}` in prod.

## SHOULD

**S1. The plan's list of simulation-relevant readers is missing the engine itself.**
The engine reads `BoardEntry.position()` (which is `primary()`, `domain/BoardEntry.java:16`) per candidate:
- `PickDecider.java:129`: indexes the positional-prior + tilt term (`PickScorer.java:83-85`) and the
  run-pressure term by the candidate's primary.
- `PickDecider.java:73`, `DraftSimulator.java:98`, `MockDraftEngine.java:96`: push the chosen player's primary
  onto `recent`, the run-pressure history (`PickScorer.java:114-125`).

Priors (`ProfileService.java:135,307-353`) and candidates both read the same `primary()` from the same table.
They therefore stay consistent at any instant, before and after the change. That is fine, but state it.

**Measured (local 5433):** NBA has 2 completed drafts:

| Season | Picks | With `manager_id` |
|---|---|---|
| 2024 | 168 | 168 |
| 2025 | 168 | 168 |

So NBA priors and tilts are fitted, not neutral, and this change does move tonight's sim. It acts through
`positionalPrior` 0.35 and `runPressure` 0.25 (`config/weights.yml:61-65`, both labelled GUESS). Two
corrections follow from that:
- Spec 025's note that NBA managers "can't be fitted until the 2026 draft" does not match this local data.
  The plan should not inherit it.
- The rollout (B1) matters for that reason.

`rosterNeed` and `isDraftable` stay set-based, so the change cannot make seating worse (see "Order-free").

**S2. A user-visible tooltip becomes false, and comments assert the old meaning.** This repo's honesty rule
means these get fixed in the same change:
- `web/src/components/OnBrandPanel.tsx:45` (user-visible): "Sleeper lists positions alphabetically, so for
  multi-position players this is not necessarily their main position". After the change it *is* the main
  position for 1,472 of 1,478 players. Reword it. The 6 exceptions (Sleeper's `position` not eligible) can keep
  a caveat.
- `web/src/onBrand.ts:46-51`: this comment explicitly names this follow-up. Update it.
- `engine/SimulationResult.java:26-27` ("alphabetical-first meaning", "stored (Sleeper, alphabetical) order").
- `web/src/api.ts:18` ("Sleeper's alphabetical order").
- `web/src/posRank.ts:18` ("alphabetically-first listing").

**S3. Order-dependent readers the plan doesn't list. They change on purpose; list them as intended changes.**
- **NBA player-page percentile peer group:**
  - backend: `engine/PlayerStatsService.java:319-320, 439, 570-576`; `engine/PlayerPercentiles.java:66`, all
    `positions().getFirst()`;
  - frontend: `web/src/pages/PlayerPage.tsx:459` (group label) and `:143` (PlayerFace fallback).
  - About 703 players move to a different peer group, e.g. Edwards from PG to SG. Frontend and backend use the
    same first element, so they move together.
- **NBA draft grades display string** (`engine/DraftGradesService.java:201-203`): `String.join("/", listed)` in
  stored order, so "PG/SG" becomes "SG/PG" on the wire.
  - NBA UI doesn't print it: `PlayerCard.tsx:87-101` and `DraftBoard.tsx:270` use "player" for
    `WEEKLY_AVERAGE_GAME`.
  - Either sort it with a fixed PG,SG,SF,PF,C order to match `positions.ts nbaSorted`, or list it as accepted.
- **`TransactionAnalysisService.java:350`** (per-position ranking by primary) and
  **`WeeklyReportService.java:547`** ("same primary" bench swap): NBA results shift. These are season features,
  and the season starts 10-20.
- **Display labels** (`LeagueController:334`, `PlayerSpotlightService:368,424`, `WeeklyReportService:280,356,382`,
  `SeasonSuperlativesService`, `LeagueAnalysisService:754`): Edwards becomes "SG". This is the point of the
  change; say so.

**S4. The "re-baseline" claim is moot. Say so instead of presenting it as evidence.**
- `Spec025SimBaselineTest.java:48-63` builds `Player`s directly with fixed lists, so it will not move.
- `EngineOutputFrozenTest.java:41-45` is **NFL**, also built directly, so it will not move.
- `NbaLineupParityFixtureTest.java:66,211` builds directly, so the 37-case fixture cannot change from this.
- No IT ingests a recorded Sleeper payload and asserts order.
- The only order assertion on ingest is `PlayerIngestServiceTest.java:81` (`[PG,SG]`). Its record builder
  (`:84-86`) has **no `position` key**, so it stays green. New tests must add `position` explicitly, or they
  test nothing.

**S5. Acceptance #2 is not a sign-of-effect test (lessons class 1).** "Edwards' primary() is SG" restates #1.
Add at least one test that proves the change points the right way *through the engine*:
- (a) **PickScorer/PickDecider:** a seat whose tilt favours SG (SG=2.0, PG=0.5) and two equal-ADP candidates,
  an ingest-shaped `[SG,PG]` player and a pure `[PG]` player. The `[SG,PG]` candidate must get the SG
  positional term and outrank the pure PG. Under the old order it would get PG's term and lose.
- (b) **ProfileService:** a manager whose completed picks are `[SG,PG]`-shaped players (as ingest now stores
  them) gets tilt(SG) > 1 and tilt(PG) not inflated.

Either one fails if the reorder is reversed (for example, moving `position` to the back), which #1 alone
might not catch if the test fixture is symmetric.

## NIT

**N1. Edge cases for the implementation.**
- Reorder **after** `.distinct()`, and only *move* an element that is already in the list. Never insert.
- `Position.fromSleeper(_, NBA)` maps only PG/SG/SF/PF/C (`domain/Position.java:66-77`). "G", "F", "GF", "FC"
  and "DEF" map to empty, so: no reorder, and no new position.
- `fantasy_positions` absent: `raw = [position]` (`PlayerIngestService.java:177-180`), a single element, so a
  no-op.
- `position` null: no-op. Use `str(p.get("position"))`, which handles non-String values.
- Test each of these, not just Edwards and Herbert Jones.

**N2. Caches.**
- `PlayerRepository` has no cache; every read goes to the DB (`store/PlayerRepository.java:105-125`), so no
  restart is needed.
- `DraftAndAdpJoin.memo` (`engine/DraftAndAdpJoin.java:93`) keeps the old NBA grades display string until the
  draft status or `player_game` token changes. Harmless (S3).
- Not checked: whether a mock or live session that is already open holds its `BoardEntry` list in memory for
  its lifetime. If it does, only sessions started after the ingest see the new order.

**N3. Acceptance #4's before/after needs the real `position` field, and the DB doesn't store it.**
- Re-ingest locally from the Sleeper payload.
- Record the tilt and prior deltas for the "Ball Knowers" NBA managers from `GET` profiles before and after.
- For reference, the local DB today reads Edwards `{PG,SG}`, Durant `{PF,SF}` and LeBron `{PF,PG,SF}`
  (**measured**).

## Order-free (checked, no change needed)

| Reader | Why it doesn't depend on order |
|---|---|
| `BasketballRules.maskOf` `:146-152` | bitmask |
| `BasketballRules` `isEligible` `:421-427` | `contains` / `anyMatch` |
| `web/src/lineup.ts` `slotTakes` `:44-46` | `some`; seating iterates slots, players sorted by ADP (`:96-118`) |
| `web/src/pickRun.ts nbaFamily` `:60-69` | null on any family mismatch, independent of order |
| `web/src/positions.ts familyCode` `:106-111` | `Set` plus fixed G,F,C output order |
| `positionLabel` / `nbaSorted` `:44-52` | fixed PG,SG,SF,PF,C order |
| `PlayerMatcher.java:57` | `putIfAbsent` on `name\|pos` for every position, so the key set is identical |
| `web/src/teamNeeds.ts:176` `eligiblePositions(...)[0]` | football-only; the NBA branch returns earlier in `fitterFor` |
| `lineup.parity.test.ts:34` and `lineup.fills.test.ts:25` | use `positions[0]` only to fill `position`, which `lineup.ts` never reads (it uses `eligiblePositions`) |
| `FootballRules.draftGradeGroup:92` | NFL, untouched |
| `BasketballRules.draftGradeGroup` | `"ALL"` |
