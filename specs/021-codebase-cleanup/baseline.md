# Baseline (spec 021, Phase 1)

Measured 2026-10-07 on branch `021-codebase-cleanup` @ `47751c0`; the code is identical
to `origin/main` @ `7b7238e`. Postgres is the `draftsim-pg` Docker container on :5433.
The JDK is 24.0.2 on PATH, with a toolchain-provisioned 21 for the build.

## T001: backend

`./gradlew test` gave **BUILD SUCCESSFUL in 2m 29s**. From `build/test-results/test/*.xml`:

| tests | skipped | failures | errors |
|---|---|---|---|
| 1340 | **0** | 0 | 0 |

## T002: frontend

| check | result |
|---|---|
| `npx vitest run` | 85 files, **1074 tests passed** |
| `npx tsc -b` | exit 0 |
| `npm run build` | ✓ built (the >500 kB chunk warning is pre-existing) |
| `dist/assets/index-BEtZrnLk.js` | 513,388 B |
| `dist/assets/index-CubZmfzm.css` | 139,132 B |
| sha256 of the CSS | `55e778a276d857776032d8faaa7724cb197b3b95e2d4e202a074edd735c6bd0a` |
| `dist/pr-reference/` | **present** (removed by US1) |

## T003: duplicate counts and the SC-004 denominator

- Backend files with a `static (double|Double) roundN(` helper: **17**
- `ordinal` definitions: **5**, at `draftGrades.ts:87`, `rankOrder.ts:133`,
  `ExpectedWins.tsx:274`, `Superlatives.tsx:656` and `PowerRankings.tsx:139`
- `new LinkedHashMap|Map.of(` in `api/*.java`: **141**
- Error bodies (`Map.of("error"|"message"`): **30**. This is one more than the 29 in
  tasks.md. The extra one is `SimulationController:130`, an SSE `error` event, so it
  is also counted as SSE.
- SSE builds: SimulationController :98, :102 and :130, plus the LeagueController
  heartbeat at :565. That is 4 builds; 3 of them are not already in the error count.
- **In-scope success-body builds = 141 − 30 − 3 = 108.** This matches the tasks.md
  denominator. The three worst controllers' success bodies are LHC 32 (43 − 11),
  SC 13 (17 − 4) and LC 10 (17 − 6 − 1 heartbeat), so 55/108 = 50.9%.

## T013: after US1 (the new P3 CSS baseline)

| check | result |
|---|---|
| backend | 1346 tests (1340 + 6 GoldenJsonTest), **0 skipped**, 0 failed |
| vitest | 86 files, 1083 tests (1074 + 9 builders) |
| `tsc -b` | exit 0 |
| `dist/pr-reference/` | **absent** |
| JS | `index-CDEVlM9V.js` 513,385 B (-3) |
| CSS | `index-ROkkXWFm.css` 138,080 B (-1,052, the `.verify-*` rules) |
| **CSS sha256 (P3 oracle)** | `3b86420695aba6a42c4a9362570180d8da5eb196c22af70ded9771292c1fc667` |
| `db/migration` diff vs origin/main | empty |

The power rankings screenshot smoke check is deferred to T057's live pass. The only
CSS removed was `.verify-*`, and the grep in removal-evidence.md shows no remaining
user of those selectors anywhere in `web/src`. Not screenshotted yet.

## T025: after US2

| check | result |
|---|---|
| backend | 1351 tests (1346 + 5 RoundingTest), **0 skipped**, 0 failed |
| vitest | 87 files, 1086 tests (1083 + 3 format) |
| `tsc -b` | exit 0 |
| CSS sha256 | `3b8642…c667`, **unchanged** from T013 |
| JS | 513,044 B (-341, the deleted duplicates) |
| private `roundN` helpers | 17 files → **0**; `util/Rounding` is the one definition |
| `ordinal` definitions | 5 → 2 (`format.ts`, plus PowerRankings until T026) |
| `db/migration` diff | empty |

Measured while building: the RoundingTest edge case `round2(2.675)` was first
written expecting 2.67 as a guess, and it failed. It is 2.68, because `2.675 * 100.0`
is exactly 267.5. The test and the `Rounding` Javadoc were corrected to the measured
value. For `PlayoffOddsService:422`, the review's "24 differences" was not
reproducible without its exact totals. The measured figure is 12 of 10,001 at
total=10000, plus the confirmed 5005/10000 example, and that is what the code
comment now cites.

## T029: LeagueHistoryController converted

| check | result |
|---|---|
| characterization test (unedited in the conversion commit) | 15/15 |
| mutation check (each mutation applied, run, reverted) | caught all 4: `NON_NULL` on makesPlayoffsPct (2 fail), present-null realizedSkipped collapsed (1), `@JsonUnwrapped` dropped (2), positionalTilt reordered (2) |
| backend | 1366 tests (1351 + 15), **0 skipped**, 0 failed |
| `tsc -b` | exit 0 |
| maps left in LHC | **13, all error bodies**; success bodies **0** |
| `db/migration` | untouched |

**Correction (measured):** T003 counted 11 LHC error bodies, but there are 13. Its
grep matched `Map.of("message"` only on one line. It missed the two-line
`Map.of(\n "error", …)` in backfill and the 403 `LinkedHashMap` in commissioner that
carries `message` + `commissionerKnown`. So T029's "≤ 11" bound was mis-derived, and
the right check (0 success-body maps) holds. The SC-004 denominator is re-measured
at the US3 checkpoint with a multi-line-aware count.

**api.ts drift found and fixed** (types only; all four callers discard the result):
`submitBallot` was typed `{ saved: number }` but has always returned
`{ saved: true, season, week }`. `computePowerRankings` lacked `playoffOdds` and
`week0Skipped`, and didn't allow `realizedSkipped: null`.

## T032: SuperlativesController converted

| check | result |
|---|---|
| characterization test (unedited by the conversion) | 6/6; ControllerIT 9/9, StandingsIT 7/7, batch test 1/1 |
| mutation check (applied, run, reverted) | caught all 3: `NON_NULL` on faabBid (1 fail), a mistyped `type` (1), coverage `{}` for null (1) |
| backend | 1372 tests, **0 skipped**, 0 failed |
| controller | 376 → 173 lines |
| maps left | 6 lines, **0 success bodies**: 4 `Map.of` error bodies, the 403 `LinkedHashMap` (`message` + `commissionerKnown`), and one internal player-lookup `Map.of` that is never serialized |

The "≤ 4" bound has the same mis-derivation as T029: the single-line grep missed the
403 map and counted a non-response lookup map.

**api.ts:** no change needed. The two candidate drifts, `closeGameMargin: number`
and `SuperlativeHolder.teamName: string`, are accurate in production: `closeMargin`
comes from a primitive `double`, and holder names default to "Roster N". Only the
fixtures used nulls the service can't produce.

## T035: LeagueController converted

| check | result |
|---|---|
| characterization test (unedited by the conversion) | 11/11; all 75 LC-related tests and ManualPickGuardsIT green |
| mutation check (applied, run, reverted) | caught all 3: `NON_NULL` on adpAtDraft (2 fail, **the REST board and the live SSE frame**), seat sort dropped (3), free-agent team as the string "null" (1) |
| backend | 1384 tests, **0 skipped**, 0 failed |
| vitest | 88 files, 1087 tests |
| maps left | 8, **0 success bodies**: 6 error bodies + 2 SSE payloads (heartbeat, `state` frame) |

**api.ts drift found and fixed** (types only; `tsc` is strict-clean, so no code
relied on the old types): `SeatsResponse.status` was `string` but is null for a
null status column (lessons #12), and `TrackResponse` was missing `draftId`, which
the server has always sent.

A pre-conversion miss, recorded: `LeagueControllerBoardTest` bound `board()`'s
`Map` return type and wasn't caught by the T033 cast grep. It was fixed in its own
commit before the conversion (`83e41fc`).

## US3 checkpoint: SC-004, measured with a multi-line-aware classifier

Every remaining `new LinkedHashMap|Map.of(` line in `api/*.java` was classified using
a 4-line context window, not T003's single-line grep:

| | lines |
|---|---|
| original total (T003) | 141 |
| remaining after LHC + SC + LC (before T036) | 91: **33 error** · **5 SSE** · **3 non-response** (default `Map.of()` / lookup maps) · **50 success bodies** |
| converted by LHC + SC + LC | 141 − 91 = **50** |
| in-scope denominator | 50 converted + 50 remaining success = **100** (not T003's 108, whose error and SSE counts were single-line) |
| after LHC + SC + LC | 50 / 100 = **50.0%**, which technically meets "≥ 50%" with zero margin |
| **after T036 (WeeklyReport, 9)** | 141 − 82 = **59 / 100 = 59%** |

The three worst controllers' success bodies are at 0 (SC-004's second clause). T036
was taken because the plan says to fall back to it when the margin is thin, and 0
lines of margin, resting on a classifier's judgement calls, is thin.

T036 gate: WeeklyReportShapeTest 12/12 (unedited by the conversion; 4 new
goldens); mutation check caught both (`NON_NULL` dropped from bestNights: 2 fail;
unavailable's empty topPerformers as null: 1); backend 1385 tests, 0 skipped;
controller ~200 → 51 lines; `api.ts` already models both shapes with optionals.

## Live verification of US1–US3 (2026-10-07, a T057 slice; the full T057 runs after US4)

Run against the shared local Postgres (`draftsim-pg`, :5433) with two backends built
from different code: **origin/main @ 7b7238e on :8088**, and **this branch on :8087**
(frontend :5187 proxied to it). Read-only GETs, as `popsharky`.

**1. Live JSON parity, 37/37 identical.** Compared type-strictly (`1` ≠ `1.0`,
null ≠ absent), with key order ignored except inside `positionalTilt`. Covered:
- history, power, ballot (default and `week=0`), superlatives and conduct-list for 3
  leagues (NFL 2026, NBA 2026, NFL 2025);
- the weekly report for NFL weeks 0–5 and 18 (weeks 5 and 18 are the *unavailable*
  shape) and NBA weeks 0–1 (the basketball shape);
- `managers/7/history`;
- seats and board for 3 drafts (complete NFL ×2, pre_draft NBA);
- `/api/board` for both sports;
- 3 stranger 404s.

About 500 KB of JSON per side. The comparator was checked against inputs that must
differ (two leagues, `1` vs `1.0`, null vs absent, a tilt reorder) and flagged every
one.

**2. Click-through in the browser pane.** Every page that reads a converted endpoint
rendered with real data:
- league home;
- history, with 🏆 only on completed 2025, "—" ranks for the in-progress season, and
  the record book;
- power rankings, with playoff %, your ballot, ballot ranges and the commissioner
  note;
- awards (NFL 2026; NBA falls back to 2025 with its note);
- the weekly report (NFL week 3 with "WON by 32.64"; the NBA basketball shape);
- manager history (both sports, tilt multipliers, the 🏆 row);
- the real draft board (snake order);
- the NBA draft room's seats, with "you're slot 5" and the 3rd-round reversal laid out.

Not opened: `/drafts/:id/live`, because it starts a live Sleeper poller.

**Console:** the only errors were 403s from `POST /api/leagues/:id/refresh`
(refresh-on-visit). Measured as the **CORS allow-list rejecting the spare port
5187**: both builds return 403 for `Origin: localhost:5187` and 200 for the default
`5173`. That's environment, identical on main, and the endpoint isn't touched by
this branch.

**Side effect, disclosed:** diagnosing that 403 with header-less curl, and with
`Origin: 5173`, triggered real refresh-on-visit runs for (Foot) Ball Knowers 2026 on
the shared local DB: a normal app action, reading Sleeper into Postgres. That
happened after the parity diff, so it doesn't affect the 37/37.

## T046: styles.css split

- `styles.css` (5,545 lines) → a 16-line `@import` index plus 7 pieces in
  `web/src/styles/` (377–948 lines each). Every cut is at brace depth 0, computed by
  a brace-aware scan that skips braces inside comments and strings. Original order is
  kept; the Google Fonts import stays as line 1.
- **Reassembly is byte-identical:** `cat styles/0*.css` `cmp`-equal to original lines
  3–end. The first draft of the split script dropped a trailing blank line from 5
  pieces; its own assertion caught it before anything was committed.
- **Built CSS is byte-identical:** a fresh `npm run build` (`dist/` confirmed absent
  first) produces `index-ROkkXWFm.css`, sha256 `3b8642…c667`, the same as T013.
  (A first check had read a stale `dist/` after a failed chain; that result was
  discarded.)
- Dev server smoke check: `:root` tokens, `.pr-ladder-head` and `.signin-kicker` (the
  first, middle and last pieces) are all in the CSSOM (1,704 rules), and the body
  uses `--bg`.
- `useNarrow.test.ts` now follows the index's imports in order; vitest 1087/1087,
  `tsc` clean.

## T049: api.ts split

- `api.ts` (2,568 lines) → a 33-line barrel plus **25 domain files** in
  `web/src/api/` (20–263 lines). The cuts are the 16 existing `// ---` markers **plus
  13 anchors**. The markers alone would have named files falsely: unrelated types had
  been appended under old markers (power rankings under "manager comparison",
  schedule/trends under "transactions", setup/managers/mock under "identity"). Each
  anchor cut lands on the comment attached to its declaration.
- **Public surface unchanged:** 194 exports before and after, none missing, none
  added. The transport internals (`apiFetch`, `json`, `apiError`) live in `http.ts`,
  which the barrel does not `export *`. They stay private to `api/`, as they were
  private to `api.ts`; only `apiUrl` is re-exported.
- **Content unchanged:** all 2,359 non-blank lines are preserved (multiset check),
  except for generated imports and the `export` on those three internals.
- No value-import cycle (computed). Nothing in `api/` imports the barrel.
- **0 of the 108 importers changed.** vitest 1087/1087, including all 26
  `vi.mock('../api')` files. `tsc` clean. JS 512,991 B (−0.01%); CSS hash unchanged.
- Caught by `tsc` during the build, before any commit: the first anchor pass cut
  inside a `/* … */` block, because the walk-back recognized `/**` but not `/*`.
- **T048:** every "mirror `api.ts`" pointer was repointed to the file that holds the
  type: AGENTS.md hard rule 2 (and the constitution, re-diffed verbatim), 7 dto
  files, SeasonSuperlativesService ×2, RecapView, and 7 frontend comments. One
  pointer, `searchIndex.ts`'s `api.ts:426`, had **already been stale** before this
  split. It now cites the file and the comment's text instead of a line number.

## T051: SeasonSuperlativesService split

- 1,586 → **852 lines**, plus 5 package-private classes in `engine`:
  `SuperlativeStandingBuilders` (231), `SuperlativeGameMath` (109),
  `SuperlativeAbsenceMath` (279), `SuperlativeWaiverMath` (161) and
  `SuperlativeConductMath` (91).
- **It's a pure move** (line-multiset diff against `HEAD`): of 1,435 non-blank lines,
  the only 8 not carried verbatim are the deliberate edits (the absence builder's
  signature and its call site, plus 6 `private` → package-private). Everything added
  is class wrappers, imports and **23 one-line forwarders**, kept because tests call
  those methods as `SeasonSuperlativesService.x(...)`. The nested public types stayed
  in the service, so no FQN changed.
- `absenceSuperlative`, the one moved instance method, now takes its 4 repositories
  explicitly. Compiling caught 2 mistakes before any commit:
  - `forLeague` has a **local** `games` that shadows the field, so the call site now
    passes `this.games`, `this.absences`, `this.gameScoring` and `this.leagues`;
  - my first dependency scan looked only for `field.` and missed `gameScoring` being
    passed as an argument.
- **Live parity (the T050 oracle, amended):** the pre-split build `eb35636` on :8088
  vs this one on :8087, same DB, `/superlatives` for all **9 leagues**: **9/9
  identical**, 354 KB, all 13 kinds present. JOEL_EMBIID, WAIVER_WIRE_WARRIOR,
  JABARI_SMITH_JR and MOST_BENCH_POINTS all produced real winners and full standings.
  UNETHICAL ran its empty path live; its populated path is covered by 7 unedited
  unit-test calls.
- Backend 1386 tests, **0 skipped**, 0 failed (115 superlatives tests); the test tree
  is untouched.
