# Code review: spec 031 (schedule swap matrix)

**Date:** 2026-10-12
**Stage:** bug-hunting code review. I ran it cold against the uncommitted working tree in the
worktree `031-schedule-swap` (base `origin/main` d821e0e, plus spec 030's 98152a3). This is not a
style pass.

**What I reviewed:**
- The `git diff`: `ExpectedWinsService` (the `ScheduleSwap` record, `scheduleSwap()`, the
  `compute()` wiring, `Result`), `ExpectedWinsController.body()`/`swap()`,
  `LeagueAnalyticsContractTest`, `PlayerTrendsReadIT` (the spec-019 date fix), `claude/schedule-swap.md`,
  `api.ts`, `ExpectedWins.tsx` (+ test) and `styles.css`.
- The untracked files: `ScheduleSwapTest`, `ScheduleSwapIT`, `specs/031-schedule-swap/`.

I read these against spec.md, plan.md (its "Amended after review" table and amended contract are
the authority), plan-review.md F1–F11 and N1–N6, AGENTS.md hard rules and claude/lessons.md.

**What I ran:**
- GET `/api/leagues/{id}/expected-wins` on the worktree backend at :8087 as `1122386008709910528`
  for all six leagues in the brief, plus four more: 1400986983953608704 and 1414306784801239040
  (no games, so unavailable), and 1391509063170293760 and 1389361939561332736 (NFL 2026, 2 final
  weeks, early). Every response was a 200.
- A scratch script over the live JSON. For every available league it checks four things. Rows
  follow the `teams` order. The diagonal W + ½T equals `actualWins`. Every cell's W+L+T equals
  `weeksScored`. Off-diagonal row mean = actualWins − winsAboveExpected (F1). It also checks that
  the mean rate is 0.5 and that no tie in `difficultyWinRate` is created by the 3 dp rounding.
- Read-only SQL on :5433:
  - whether any roster appears in two scored pairings in one week, over **all** weeks, playoffs
    included (it never does);
  - NBA 2025 `roster_season` W-L against the diagonal;
  - the NBA 2026 league's status and its `player_game` TEAM_ rows.
- `./gradlew test` on ScheduleSwapTest (9), ScheduleSwapIT (4), LeagueAnalyticsContractTest (12)
  and PlayerTrendsReadIT (2): **27 tests, 0 skipped, 0 failed** (counts read from the XML results,
  not from BUILD SUCCESSFUL).
- `vitest run src/pages/ExpectedWins.test.tsx`: 21 passed. `npx tsc -b`: clean.

I did not drive the UI in a browser. No frontend dev server was running from this worktree, so the
sticky column, the 375px layout and the tint colours are **read, not seen**.

## Findings

### B1. Medium, measured: the diagonal is labelled "actual" and "your real record", but in NBA 2025 it isn't Sleeper's record for 4 of 12 rosters

- `ExpectedWins.tsx` (`ew-swap-actual`, "actual" on every diagonal cell).
- The HowThisWorks text: "That is what keeps the diagonal your real record."

plan-review F4 found this, and plan.md filed it as "out of scope, recorded". The code adds the
new label anyway, and `ScheduleSwapIT`'s javadoc says the mismatch is "Not asserted here".

**Measured:** live `scheduleSwap` diagonal for NBA 2025 (`1229352720222134272`) vs `roster_season` on :5433:

| roster | diagonal ("actual") | Sleeper's record |
|---|---|---|
| 1 | 9-9 | 8-10 |
| 4 | 8-10 | 7-11 |
| 6 | 4-14 | 5-13 |
| 8 | 5-13 | 6-12 |

The other 8 rosters match. The NBA 2026 league (`1339351318115946496`) falls back to season 2025
(`requestedSeason: 2026`), so the league Allan actually plays in (basketball is the real target)
shows the same four wrong "actual" cells today.

**Failure scenario:** a manager in that league opens Luck. The new grid marks "8-10 · ACTUAL" on
roster 4's diagonal and says that's their real record, but Sleeper says 7-11. The existing Wins
column already showed this (pre-existing). The difference is that the page now claims, in words,
that the number is the real record.

**Fix (pick one):**
- Diagnose the two games (week 8, 4 v 8; week 9, 1 v 6) before shipping.
- Label the diagonal for what it is ("record from stored scores", or just mark it with the outline
  and drop the "real record" sentence).
- At minimum, do what F4 asked of the IT: assert the four known NBA 2025 mismatches by roster
  against `roster_season`, so that a fix or a new drift turns the test red rather than staying
  invisible.

### B2. Low, read + measured: a roster twice in one week now throws out of `compute()`, which takes down two other pages, not just this section

`ExpectedWinsService.scheduleSwap()` throws `IllegalStateException`. It runs unconditionally
inside `compute()`, which three callers share:
- `/expected-wins`;
- `SeasonSuperlativesService.addLuckSuperlatives` (`:518`), with a bound of `through(throughWeek)`
  that **includes playoff weeks**;
- `ManagerCareerService` (`:229`), which loops over every season of a career, so one bad season
  fails the whole career page.

Before this change those callers degraded (they double-counted). Now they 500.

**Measured:** across every week of every league on :5433, playoffs included, **0** rosters appear
in two scored pairings. The schema allows it: the unique key is `(league, season, week, roster)`,
but three rosters can share one `matchup_id`. So the risk is latent. F6 asked for "fail loudly",
but only the matrix needs the invariant, and two of the three callers discard the matrix anyway.

**Fix:** catch it at the wiring in `compute()`, and set `scheduleSwap` to null there (log the
league/week), so that Superlatives and Career aren't collateral. Better still, compute the matrix
only on the `forLeagueRegularSeason` path the page uses. That also stops Superlatives building an
N×N matrix over playoff weeks just to discard it. The cost is negligible (N4), but the coupling is
not.

### B3. Low, read + measured: the PlayerTrendsReadIT fix is sound, but it is looser than the data allows and goes silent after the cutover

The change replaces assertions that broke once the 2026-10-10 draft happened. The list cap is
right: `capByOwnership` returns up to n unowned + n owned rows, so 2n is the true bound once
ownership is known. The fallback flags are now derived, not hard-coded.

Three weaknesses:
1. **It accepts both reasons when it could pin one.** The `else` branch accepts `streamingReason`
   null *or* `ROSTERS_NOT_LOADED`. Measured: the league is `in_season`, with 12 `roster_season`
   rows carrying `players`. So the service must return null, and the test would still pass if a
   regression made it return `ROSTERS_NOT_LOADED` (this run returned null). Fix: assert null when
   `roster_season.players` is non-empty, and `ROSTERS_NOT_LOADED` only when it is empty.
2. **It is a second copy of the cutover rule.** `cutoverMet` counts raw `TEAM_*` rows. The service
   counts distinct game ids after `NbaGameLines` drops configured Cup finals. The two agree today
   (0 rows of 2026), and a disagreement would fail loudly rather than pass, so this is low. But it
   is the "two implementations of one rule" shape. A comment saying so, or reading the service's
   own `Prepared` counts, would be better.
3. **It goes silent after the cutover.** Once 2026 crosses the cutover (after about 10-21),
   `rolesSeason` becomes 2026, and `commonInvariants(t, 2025)` skips its per-row games spot-check
   for every row (`continue` when `rolesSeason != season`). The test stays green while checking
   less. Fix: pass `t.rolesSeason()` rather than the literal 2025.

### B4. Low, read: spec.md's body still specifies what was dropped

The status line says "amended", but the body was not amended in place:
- User Story 1 and FR-003 still define **schedule luck** and a mean-wins **difficulty**.
- Scenario 5 still says "fewer than 2 scored weeks" (F5 asked for it to be rewritten as "reuse
  `early`").
- The Independent Test still says W+L+T is 14 per cell.

The build follows plan.md, which is right. But a reader of spec.md alone gets the pre-review
feature, which is lesson 25's shape for the next builder. Fix: add visible "amended after review"
notes in place on US1, FR-003 and scenario 5, per the repo convention.

### B5. Low, read: the losing tint is crimson's hue, and the app reserves crimson for "you"

`styles.css` `.ew-swap-down` mixes `#ef4444` (red, about hue 25) into `--bg`. The token block says
crimson (hue 25) means **you**, and that `--down` is "hue 350 [to keep] it off crimson".
`.ew-swap-up` likewise uses a raw `#22c55e` instead of `--up`. At 12% the tint is faint, and
colour only repeats the printed W-L, so this is a semantic slip rather than an unreadable cell.
Fix: `color-mix(in oklab, var(--bg) 88%, var(--down) 12%)`, and `var(--up)` for the winning tint.
(I didn't see this in a browser.)

## Notes

- **N1. Rounding before comparison is safe today, and its limit is easy to state.** The page names
  the easiest and toughest schedules by `===` on the 3 dp `difficultyWinRate`. With no byes, every
  rate is k / (2·G·N), so two distinct rates differ by at least 1/(2GN). 3 dp keeps them apart
  while G·N ≤ 500, for example 14 teams × 17 weeks = 238. Measured on all 9 available payloads:
  no false ties and no split ties. With byes, the denominators mix and a false tie becomes
  possible, though there are 0 byes in the data today.
- **N2. "A row's spread is the existing Luck number"** (HowThisWorks) is loose wording. The row's
  *mean* over the other schedules is expected wins, and the diagonal minus that mean is Luck. The
  spread (range) isn't Luck. The second half of the sentence is exact (measured to 0.011, the 2 dp
  rounding, on every league), but it holds only with no byes. Suggest: "Average a team's record
  over every other schedule and you get its expected wins; its real record minus that is the Luck
  number above."
- **N3. N2 from plan-review (an in-season "so far" label) only half landed.** "so far" shows only
  while `early` (under 4 weeks). (Foot) Ball Knowers 2026 (`1346366555759341568`, `in_season`,
  4 weeks, not early) shows no "so far", and it names "Toughest schedule: Khatt Stafford (27.1%)"
  on four games. That is consistent with the existing `luckSubtitle` rule, and the section header
  above it already prints "4 weeks", so this is a disposition choice rather than a bug. It should
  be stated, because plan.md's F5 row says "keep a 'so far' label (N2)".
- **N4. The bye unit test is weaker than F8 asked for.** `byeColumnWeekIsNotPlayedAndMissingRowScoreIsSkippedNotLost`
  checks the per-cell game counts and the diagonal, but it asserts only `0 ≤ rate ≤ 1` for
  difficulty on the bye variant. F8 asked for the values. The code is right by reading. The test
  just wouldn't catch a regression back to mean wins. Its inline comment also says "j = 1 and 3
  (rosters 1, 3)" where the code (correctly) uses indices 0 and 2.
- **N5. Superlatives now builds the matrix over playoff weeks.** This is harmless: with partial
  pairings those weeks count as byes, and the result is discarded. See B2's fix.
- **N6. The thead `max-width: 11ch; overflow: hidden` probably does nothing.** Browsers largely
  ignore `max-width` on auto-layout table cells. It also applies to the corner cell ("Team ↓ ·
  schedule →"), so if it *did* apply, it would truncate the corner label. Long team names will just
  widen their columns, and the scroll container absorbs that. Check this in the live pass at 375px.
- **N7. `specs/004…/contracts/league-analytics-api.md`** (the controller's cited contract) isn't
  updated. F2 marked that as optional.
- **N8. The design-doc amendment says "with a bye, a cell totals the column team's games".** The
  exact rule also subtracts the row team's missing weeks (spec scenario 4). This is a minor
  wording point.

## Areas checked clean

- **The self-pairing rule.** `if (x == t) x = s` unboxes correctly (Integer vs int). It never fires
  on the diagonal, and the hand-worked 4×3 fixture from plan-review is asserted cell for cell.
- **The diagonal = stored-game W-L-T.** It is asserted against an independent SQL count in the IT
  for 4 completed leagues (0 skips), and against `actualWins`. Live: diagonal W+½T equals
  `actualWins` for every roster in 9 payloads.
- **The F1 identity** (the off-diagonal mean equals expected wins) holds on every live league to
  2 dp rounding. **F7:** mean rate 0.5000 ± 0.0001.
- **Byes and missing scores.** A column bye isn't played. A row without a score is skipped, not
  lost. The opponent's score can't be null: when S's opponent is X, X's score exists in that game,
  and the self-pairing substitution uses S's score, which exists. A roster that never played gets
  no row and no column.
- **Row and column order vs teams.** `rosterIds` equals the `teams` order in all 9 live payloads,
  and the IT asserts it. The page joins by `rosterId` via a Map, never by index, and a Vitest case
  pins it (rosterIds `[2,1,3]` against teams `[1,2,3]`).
- **The controller's null handling.** The unavailable branch emits `"scheduleSwap": null`. Verified
  on the wire for 1400986983953608704 and 1414306784801239040, and no global NON_NULL inclusion
  strips it. The matrix map is a `LinkedHashMap` with no `Map.of`. The contract test asserts the
  shape and the null key.
- **The TS mirror.** `ScheduleSwap {rosterIds, cells, difficultyWinRate}` matches the record field
  for field. It's `scheduleSwap?: ScheduleSwap | null` and renders on `!= null`, so an older
  backend shows no section (Vitest covers both absent and null).
- **Easiest and toughest.** Every tied name is listed (`namesAt`, exact `===` against
  `Math.max`/`Math.min` of the same array). Nobody is named while `early`, or when all rates are
  equal. Early leagues (2 weeks) return a matrix, and the page labels it "so far".
- **The other callers of `compute()`.** `ManagerCareerService` and `SeasonSuperlativesService`
  only read `teams()`, and `TeamRow` is unchanged. No other main-code constructor of `Result`
  exists. The cost is about N²·W comparisons per call (negligible), apart from the B2 coupling.
- **The sticky column is opaque.** It uses `var(--bg)`, the page surface the section sits on. The
  app defines only a dark `:root` palette, so there's no second theme to diverge.
- **The PlayerTrendsReadIT list cap** (`2 * listSize` when ownership is known) matches
  `capByOwnership`.
