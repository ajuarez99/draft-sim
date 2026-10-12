# Adversarial plan review

This review read the spec 031 documents (`spec.md`, `plan.md`, `claude/schedule-swap.md`) cold on
2026-10-12, before any code was written. It checked them against worktree `031-schedule-swap`
(`origin/main` `d821e0e` plus spec 030's `98152a3`; spec files untracked) and the local Postgres 17
DB on `127.0.0.1:5433` (read-only).

Each item is labelled **measured** (run today) or **read** (from code, not executed). The measured
items come from a scratch Python script, not from Java. It exports every regular-season game
(`week < playoff_week_start`, both sides scored, the same joins as
`LeagueMatchupRepository.pairedWithScores`) for leagues 3, 4, 5, 9465, 9466, 211 and 212, which is
468 games. It then builds the matrix with the plan's self-pairing rule and compares the result with
`actualWins`, the all-play expected wins, the existing strength of schedule, and Sleeper's own
`roster_season` record. For the in-season leagues the script treats every stored week as final. That
matches `last_scored_leg` today (3, 4 and 3), but it is not `ScoredWeeks` itself.

## Claims confirmed

| Claim | Where checked | Result |
| --- | --- | --- |
| R1 shape: (Foot) Ball Knowers 2025 14/12/0, West Coast FF 2025 14/12/0, NBA 2025 18/12/0, NBA 2024 21/12/0 | `league_matchup` below `playoff_week_start` (measured) | ✅ exact. Also **0** null `matchup_id`, **0** matchup groups whose size isn't 2, and **0** missing or null `starters_points` in the four completed leagues. Game counts are 84/84/108/126 |
| R2 line refs: `Game` :63, `WinLossTie` :75, `actualWins` :172, `scoresByWeek` :255 | `ExpectedWinsService.java` (read) | ✅ all correct |
| R2: the regular-season bound and the final-weeks filter are applied before the pure functions | `compute()` :310-318, `regularSeasonBound` :299 (read) | ✅ so `scheduleSwap(games)` inherits both, with no second rule |
| Diagonal = `actualWins` (invariant 1) | script, all 7 leagues (measured) | ✅ `wins + ties/2 == actualWins` for **every roster in all 7 leagues**, including NBA 2024, which has a real tied game, and the 8 and 16 tied cells in NBA 2025 and 2024. See F4 for the different question of whether `actualWins` is the record managers see |
| Self-pairing rule: T never meets itself and the diagonal is exact | by construction plus the line above (measured) | ✅ On its own schedule T's opponent is never T, so the rule never fires on the diagonal. Off the diagonal, cell (T,S) uses it in exactly the weeks S played T. Those weeks then reproduce the real T-vs-S result |
| No roster appears twice in a week | `league_matchup` unique `(league_id, season, week, roster_id)` + group-size count (read + measured) | ✅ today. The unique key allows only one `matchup_id` per roster per week, but nothing stops three rosters sharing a `matchup_id`. See F6 |
| Ties: equal scores tie, as `actualWins` does (`Double.compare`) | `actualWins` :175 (read) | ✅ The cell has to use the same compare. `WinLossTie` holds the split |
| Including the column's owner as a row does **not** make that owner's schedule look easy | proof + script (measured) | ✅ The concern was that column S counts S's own record. It is cancelled exactly. In each week, row S plays X = opp(S) and row X plays S (the self-pairing rule), and together those two cells score exactly 1 win. So **difficulty(S) = (G_S + (N−2)·pure_S)/N**, where pure_S is the mean share of the *other* N−2 teams that beat S's opponent that week. Measured max error ≤ 3.6e-15 in all 7 leagues. Including the owner gives an exact affine transform of an opponents-only difficulty. **Excluding** the owner breaks it: Spearman vs pure drops to 0.874–0.993, and the hardest schedule named changes in NBA 2025 (6→5) and West Coast FF 2026 (2→12). Keep "over all rows", as FR-003 says. This is a reason to keep it, not an accident |
| Payload size for 14 teams | script, leagues 3 and 9465 (N = 14) (measured) | ✅ 196 cells, about **7.3 KB** as `{wins,losses,ties}` objects (12 teams: about 5.4 KB). Not a concern. Compact arrays aren't needed |
| `Result` constructor ripple | `grep "new ExpectedWinsService.Result\|new Result("` (read) | ✅ (with F2) Main code constructs it only in `Result.unavailable` :93 and `compute()` :370. Tests construct it at `LeagueAnalyticsContractTest:198` and `:233`. `ManagerCareerService:229` and `SeasonSuperlativesService:518` only read `teams()`. `TeamRow` isn't changed by the plan, which is good: it has four positional test constructors (`LeagueAnalyticsContractTest`, `SeasonSuperlativesLuckTest:25`, `SeasonSuperlativesStandingsTotalsTest:88`). The `Result` in `recap/*` is `WeeklyReportService.Result`, a different type |
| No median-game wins in Sleeper's records | `settings_json.league_average_match` = 0 for all 7 (measured) | ✅ So Sleeper's W-L is comparable to a one-game-a-week diagonal |
| Basketball works unchanged | NBA 2024/2025 shape above (measured) | ✅ one pairing a week, no byes, every roster every week |

## Findings: the plan was wrong or incomplete

Ranked by severity.

**F1 (high). "Schedule luck" as specified is not a second luck measure. It is exactly the existing
Luck number (`winsAboveExpected`) times (N−1)/N.** (proof + measured)

Fix row T and let S range over all N columns. Across the columns, T's opponent in week w is opp(S)
for every S except the one S where opp(S) = T. That S is opp(T), and there the rule substitutes S =
opp(T). The column S = T also gives opp(T). So over the N columns, T faces every other roster once,
plus its real opponent a second time:

    Σ_S wins(T,S) = (N−1)·expectedWins(T) + actualWins(T)
    ⇒ luck_FR-003 = actual − mean_over_N = (N−1)/N · (actual − expected) = (N−1)/N · winsAboveExpected
    ⇒ luck over the other N−1 schedules = winsAboveExpected exactly

Both hold exactly whenever every roster plays every scored week. Measured on all 7 leagues: max
|luck(own excluded) − winsAboveExpected| ≤ **3.6e-15**, and max |luck(FR-003) − 11/12 ·
winsAboveExpected| ≤ **3.1e-15**, including the leagues with ties. So the page would show
"Luck +4.18" in the existing table and "Schedule luck +3.83" in the new section for the same team in
(Foot) Ball Knowers 2025. That is two labels and two values for one quantity, and the only
difference is a 1/N shrink that the include-own definition brings in. The design doc's line "This
is a second luck measure beside all-play, and the page says how they differ" is false. They don't
differ.

Fix: **drop the luck column** from the contract and the grid. Optionally, put one sentence under the
grid that turns the identity into the payoff: "Averaged over the other N−1 schedules, every team's
record is exactly its expected wins, so the Luck column above is this grid's row average, seen from
the other side." Make the identity a unit test. On the hand-worked fixture below and in the IT, the
mean of row T's off-diagonal wins equals `expectedWins(T)` (to 1e-9) when there are no byes. Amend
the design doc's "second luck measure" line with a visible "amended after review" note. If a luck
number must stay, define it over the other N−1 schedules, so it *equals* the existing one, and label
it as the same number.

**F2 (high). The field never reaches the wire. `ExpectedWinsController` hand-builds the JSON, and
the plan's Files list leaves the controller out.** (read)

`ExpectedWinsController.body()` (:48-97) copies `Result` into a `LinkedHashMap` field by field.
Adding `scheduleSwap` to the record serializes nothing. The page would get `undefined`, and with
`scheduleSwap: ScheduleSwap | null` the TS type wouldn't object (AGENTS.md: a stale mirror fails
silently). Also, the unavailable branch (:58-62) returns early, so it must put
`scheduleSwap: null` explicitly if the type promises the key. Separately, `LeagueAnalyticsContractTest`
builds `Result` positionally at `:198` and `:233`, so the change doesn't compile until both get a
ninth argument.

Fix: add `api/ExpectedWinsController.java` and `api/LeagueAnalyticsContractTest.java` to Files. Build
the matrix map mutably: no `Map.of`, and the hard rule already covers the nullable field. Add a
contract-test assertion on the serialized shape (`rosterIds`, `cells[i][j]` as
`{wins,losses,ties}`, `difficulty`). Optionally record the field in
`specs/004-ffwrapped-feature-parity/contracts/league-analytics-api.md`, which the controller's
javadoc cites as its contract.

**F3 (medium). The new "difficulty" row and the page's existing "Schedule" column name different
toughest and easiest schedules, with opposite polarity, on the same page.** (measured)

The existing column is `strengthOfSchedule`: opponents' season PPG minus the league's. **Positive
means harder** (`ExpectedWins.tsx` HowThisWorks). Difficulty is mean wins on that schedule, so
**lower means harder**. Measured hardest / easiest (difficulty vs SOS):

| League | Hardest | Easiest | Spearman(difficulty, −SOS) |
| --- | --- | --- | --- |
| (Foot) Ball Knowers 2025 (5) | 7 vs **12** | 9 vs **4** | 0.34 |
| West Coast FF 2025 (9466) | 7 vs **11** | 2 vs **12** | **−0.21** |
| NBA 2025 (211) | 6 vs **4** | 12 vs **3** | 0.18 |
| NBA 2024 (212) | 9 = 9 | 2 = 2 | 0.69 |
| (Foot) Ball Knowers 2026 (4) | 4 vs **11** | 6 vs **2** | 0.83 |

The two aren't measuring the same thing. SOS uses opponents' *season average*. Difficulty uses
opponents' scores *in the week you met them*, judged against the rest of the league that week, so
it's "did your schedule hit people on their hot weeks". Both are legitimate, and the AGENTS.md bug
class 5 applies: a reader assumes two "schedule" numbers on one page agree. In West Coast FF 2025 they
rank schedules slightly *anti*-correlated. Fix: (a) the HowThisWorks text under the grid names the
difference in one sentence and states the polarity ("lower = harder; this counts opponents' scores
in the week you played them, not their season average like the Schedule column"). (b) The spec gets
a scenario for it. (c) Don't call one schedule "toughest" in a headline without that sentence next
to it. Also, ties at the extremes are common: difficulty has 1–5 tied values per league, including
5 in West Coast FF 2026. Whatever names "toughest" needs a tie rule or should name nobody.

**F4 (medium). In NBA 2025 the "actual" diagonal disagrees with Sleeper's own record for 4 of 12
rosters. SC-001's oracle (`actualWins`) is the same source, so it passes regardless.** (measured;
root cause not diagnosed)

`roster_season.wins/losses/ties` (Sleeper's standings) against the diagonal: **0 mismatches** in
leagues 3, 4, 5, 212, 9465 and 9466. League 211 (NBA 2025) has **4**: roster 1 is 9-9 here and 8-10 in
Sleeper, 4 is 8-10 vs 7-11, 6 is 4-14 vs 5-13, and 8 is 5-13 vs 6-12. That traces to two stored
games that Sleeper scored the other way: **week 8, 4 v 8, 229.50–219.50** and **week 9, 1 v 6,
244.50–231.50**. Margins of 10 and 13 points rule out rounding. Possible causes, unverified: a stat
correction after ingest, or NBA Cup knockout games, since weeks 8–9 bracket the 2025-12-16 Cup final
that `weights.yml` already excludes elsewhere (lesson 34). This is pre-existing: the live Luck page's
Wins column already shows 9 for roster 1. But spec 031 adds a cell labelled **"actual"**, and its
SC-001 says "equals the real record". Fix: the SC-001 IT also compares the diagonal's W-L-T with
`roster_season` (an outside count, lesson 34) and, for now, asserts the 4 known NBA 2025
mismatches by name, so a fix or a new drift shows up. Either diagnose the two games before shipping,
or label the diagonal "record from stored scores" rather than "actual". Don't silently relabel
SC-001 as passing.

**F5 (medium). Scenario 5 makes up a "fewer than 2 scored weeks" rule while it says "no separate
rule". The early-season handling of the new section isn't specified.** (read + measured)

The page's existing rules are: unavailable when there are 0 final games (`compute()` :319), and
`early` when `weeksScored < SeasonWindow.EARLY_THRESHOLD_WEEKS` = **4**, sent by the controller
(:56) and read by the page through `isEarly` (`ExpectedWins.tsx:32`). "2" appears nowhere. That's
exactly lesson 25's class: a builder handed "2" writes a second threshold. Locally, NFL 2026
leagues 3 and 9465 have **3** final weeks (early), and league 4 has **4** (not early: 4-game cells).
Fix: rewrite scenario 5 as "unavailable → no section; early → the grid shows with the same
`sl-early` tag and names no toughest/easiest schedule (mirrors `luckSubtitle`)". The section reads
`data.early` and declares no constant. Add a Vitest case for the early state, and use a pre-season or
early league in the live check (lesson 28).

**F6 (low–medium). The diagonal test as written can't catch a W/T mix-up, and the opponent map can
lose a game without a sound.** (read)

(a) `actualWins` is a `Double`, so `wins + ties/2 == actualWins` accepts 1-0-2 where the truth is
2-1-0. Compare the full `WinLossTie` against a W-L-T counted straight from `games` (one pass,
`Double.compare` per game), and in the IT against `roster_season` (F4). (b) The natural
implementation builds `opp[week][roster]` with `put`. If a roster ever appears in two games in one
week (three rosters on one `matchup_id`; the schema allows it, the data has 0 today), the second
game overwrites the first, and the diagonal silently drops a game that `actualWins` counts. Fail
loudly (`IllegalStateException`, or exclude that week with a reason) and add a unit test for it.

**F7 (low). Plan invariant 3 is wrong. Luck sums to exactly 0, and mean difficulty is exactly
weeks/2, whenever every roster plays every week.** (proof + measured)

Per week, Σ_S Σ_T wins = N²/2 and Σ actual = N/2, so Σ_T luck = 0, and Σ_S difficulty / N = W/2.
Measured: Σ luck = 0.000 and mean difficulty = 1.5 / 2.0 / 7.0 / 9.0 / 10.5 / 1.5 / 7.0 = weeks/2 in
all 7 leagues. These are conservation checks of the same kind as the class javadoc's
`sum(expected) == sum(actual)`. Assert them in the unit test and the IT, conditioned on "no byes".
Don't tell the builder not to. (If F1 drops luck, keep the difficulty one plus F1's row-mean
identity.)

**F8 (low). With byes, column means aren't comparable, and the identities above break.** (read)

Scenario 3 lets a column with a bye have fewer games, so its mean wins is lower purely because it
has fewer games, and it reads as "harder". R1 measured 0 byes, so this is latent today. Fix: define
difficulty as a **win share** (mean over rows of `(W + T/2)/(W+L+T)`), or keep wins but suppress
the difficulty row with a stated reason when column game counts differ. Add the bye unit test with
an assertion on this, not only on W+L+T.

**F9 (low). `ScheduleSwap.weeks` duplicates `Result.weeksScored`.** (read)

`compute()` :369 already computes the distinct weeks of the same `games`. A second copy is the
"two implementations of one number" shape. Drop the field. The page already has `weeksScored`.

**F10 (low). The TS mirror should be optional, and rows should be joined by id.** (read)

The frontend and backend are separate Railway services that have drifted before (memory, and lesson
17: a field the frontend shipped and the backend didn't took the site down). Type it
`scheduleSwap?: ScheduleSwap | null`, the same pattern as `early?`, and render on `!= null`, so an
older backend shows no section rather than crashing. The page should label rows by looking up
`rosterId` in `teams`, not by index. Today the order is the same (the plan says so), but nothing
enforces that, and a test should pin it.

**F11 (low). The design doc contradicts the spec on W+L+T, and keeps the "second luck measure"
claim.** (read)

`claude/schedule-swap.md` acceptance 2 says W+L+T per cell = regular-season weeks. Spec invariant 2
(column's games minus the row's missing weeks) is the correct one once byes exist. Amend acceptance
2 and the luck line (F1) visibly, with "amended after review" notes, per the repo convention.

## Hand-worked fixture (SC-002): 4 teams, 3 weeks

This is a round robin, so every off-diagonal cell uses the self-pairing rule in exactly one week. It
includes one real tied game (week 3, 2 v 3). Checked by hand, then reproduced exactly by the script.

Games (`Game(week, a, aPts, b, bPts)`):

| Week | Game 1 | Game 2 |
| --- | --- | --- |
| 1 | 1: 100 v 2: 90 | 3: 80 v 4: 110 |
| 2 | 1: 70 v 3: 95 | 2: 120 v 4: 60 |
| 3 | 1: 105 v 4: 100 | 2: 85 v 3: 85 (tie) |

Schedules (opponent by week): 1 → 2, 3, 4 · 2 → 1, 4, 3 · 3 → 4, 1, 2 · 4 → 3, 2, 1.

Expected matrix (row = team, column = whose schedule; W-L-T; ★ = diagonal):

| | sched 1 | sched 2 | sched 3 | sched 4 |
| --- | --- | --- | --- | --- |
| **1** | ★ 2-1-0 | 3-0-0 | 1-2-0 | 2-1-0 |
| **2** | 1-2-0 | ★ 1-1-1 | 1-1-1 | 2-1-0 |
| **3** | 1-2-0 | 1-1-1 | ★ 1-1-1 | 0-3-0 |
| **4** | 1-2-0 | 2-1-0 | 2-1-0 | ★ 1-2-0 |

Two cells worked through:
- (1, sched 2): w1 opp(2) = 1, which is T, so T plays 2: 100 v 90 W. w2 opp = 4: 70 v 60 W. w3 opp =
  3: 105 v 85 W. Result 3-0-0.
- (2, sched 3): w1 opp(3) = 4: 90 v 110 L. w2 opp = 1: 120 v 70 W. w3 opp = 2, which is T, so T plays
  3: 85 v 85 T. Result 1-1-1.

Derived values (wins count ties as ½):

| Team | actual (diag) | expectedWins (all-play) | winsAboveExpected | mean over all 4 (FR-003) | FR-003 luck | mean over other 3 | luck over others |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 2.0 | 2.0 | 0.00 | 2.0 | 0.00 | 2.0 | 0.00 |
| 2 | 1.5 | 1.5 | 0.00 | 1.5 | 0.00 | 1.5 | 0.00 |
| 3 | 1.5 | 0.8333 | +0.6667 | 1.0 | **+0.50** (= ¾ · 0.6667) | 0.8333 | **+0.6667** |
| 4 | 1.0 | 1.6667 | −0.6667 | 1.5 | **−0.50** | 1.6667 | **−0.6667** |

Difficulty (column mean over all 4 rows): sched 1 **1.25**, sched 2 **2.00**, sched 3 **1.50**, sched 4
**1.25**. Mean 1.5 = weeks/2. Sched 1 and sched 4 tie for hardest, which exercises the tie rule from
F3. Identity check for sched 1: pure = ½ (w1, of {3, 4} only 4 beats 90) + ½ (w2, of {2, 4} only 2
beats 95) + 0 (w3, nobody beats 100) = 1.0, and (3 + 2·1.0)/4 = 1.25 ✓.

Assertions the test should make: the full 4×4 `WinLossTie` grid. The diagonal equals the per-game
W-L-T (2-1-0, 1-1-1, 1-1-1, 1-2-0). Each cell has W+L+T = 3. The mean of the off-diagonal row equals
`expectedWins` (F1). Σ luck = 0 and mean difficulty = 1.5 (F7). The difficulty values above.

Bye variants (separate tests, scenarios 3–4): add roster 5 with no game in any week, and check it
gets no row and no column. Then drop week 2's 2 v 4 game: column 2 and column 4 each lose week 2
(W+L+T = 2 for every row), rows 2 and 4 skip week 2 in every column, and the diagonal still equals
the per-game record. If F8's win-share difficulty is adopted, assert it on this variant.

## Lower-severity notes

- **N1. R3's citation was not checked.** "StatsGuy and ESPN-style write-ups" was not looked up. The
  rule is better justified by what was measured: it makes the diagonal exact, and it is exactly what
  makes F1's identity hold. A "skip that week" alternative would break both.
- **N2. In-season reading.** With 4 final weeks (league 4 locally), cells range 0–4 and difficulty
  spans 1.08–2.67 wins. Label the section "so far" for an `in_season` league. The matrix covers
  played weeks only, not the remaining schedule, which the design doc correctly doesn't build.
- **N3. The SC-001 IT will skip silently if a league is missing** (the `DraftGradesReadIT` pattern
  uses `Assumptions`, and memory/lesson 21 covers the skip count). Use one assumption per league,
  naming the league, and read the skip count in the run, not just BUILD SUCCESSFUL.
- **N4. Cost to other callers.** Wiring into `compute()` also runs it for `ManagerCareerService`
  (once per season) and `SeasonSuperlativesService` (once per request). At N²·W ≈ 3k comparisons
  that's negligible, so it isn't worth a separate entry point.
- **N5. Phone width.** 14 columns of "10-3-1" won't fit at 375px, and FR-006's in-card scroll plus a
  sticky first column is the right call. Check that the sticky column has an opaque background in
  both themes (the existing `ew-table` has none to inherit).
- **N6. Row order** = `teams` order = descending `winsAboveExpected`. By F1 that is also descending
  schedule luck, which is a sensible order. Rows and columns share it, so the diagonal stays
  diagonal.

## Measured vs read

- **Measured** (scratch script over a DB export, not Java): R1 shape and the null/group/points
  checks; diagonal = `actualWins` (7 leagues); diagonal vs `roster_season` (F4, including the two NBA
  2025 games); F1's identities; the F3 table; the F7 sums; the difficulty identity and the
  include-vs-exclude-owner comparison; difficulty ties; payload sizes; `league_average_match`; the
  fixture matrix.
- **Read**: every line reference; the controller's hand-built body and the constructor ripple (F2);
  the early rule and threshold (F5); F6, F8–F11; N3–N6.
- **Not done**: no Java was compiled or run, no endpoint was hit, and the cause of F4's two NBA 2025
  games was not diagnosed. In-season finality comes from `last_scored_leg`, not from `ScoredWeeks`
  itself.
