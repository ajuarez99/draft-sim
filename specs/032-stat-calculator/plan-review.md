# Adversarial plan review

This review read the spec 032 plan documents cold on 2026-10-12, before any code was written:
spec.md, plan.md, research.md, data-model.md, quickstart.md and contracts/formula-grammar.md,
url-state.md and saved-formulas-storage.md. It checked them against branch `032-stat-calculator`
(worktree `.claude/worktrees/032-stat-calculator`, at `e9f71aa`, the same commit the plan read).
The code read was `web/src/statLeaderboard.ts`, `pages/StatLeaderboard.tsx` and its test,
`statCells.tsx`, `statCopy.ts`, `statChoice.ts`, `useBlock.ts`, `api.ts` (the C1/C2 types),
`destinations.ts` and its test, `App.tsx`, `components/LeagueRailSection.tsx`, `draftRoomStats.ts`,
and the backend's `PlayerStatsService.java` and `ReplacementLevel.java`.

No code was run. The worktree has no `node_modules`, so the vitest runs this review could have used
were not made, and nothing was installed. No server, database or network call was made.

Each item is labelled:

- **read**: taken from code or docs, not executed;
- **inferred**: reasoned from what was read, not checked directly;
- **measured**: nothing in this review is measured. Numbers quoted from spec 022's verification
  (582 rows, 303 qualified) are that pass's measurements, cited as such.

## Verdict

The direction is right. Reading the existing payload and evaluating client-side, deriving the
inputs from `PICKABLE`, a tagged result type, and URL state are all sound. Most file:line
references check out. **It is not ready for `/speckit-tasks` as written.** Two findings would ship
wrong output on first use:

- **F1**: three column labels collide with the grammar. `FP/G` contains the division operator,
  `Pos rank` and `Pts rank` contain spaces, and FR-013's `VOR (fantasy)` contains brackets. So the
  readable form the plan promises to parse back cannot parse back, and `fp/gp` typed naturally is
  an error.
- **F2**: standardized mode with ÷ by a stat (the user's own example with the switch on) ranks
  players by how close their denominator sits to the average. The plan only warns. It should
  block (this is the answer to the plan's second open question).

F3 to F9 are medium. They would mislabel output or cause rework: wrong exclusion reasons, a data
reload that throws away the editor, no sort direction for lower-is-better formulas, the rail's
season switch dropping the formula, fallback-scoring copy, unvalidated URL filters, and unit
inference rules that give wrong warnings. F10 answers the first open question. F11 to F13 are
verification and spec gaps. Fix F1 to F3 and settle F4, F5 and F10 before writing tasks.

## Claims that held up

| Claim | Where checked | Result |
|---|---|---|
| R1: `getStatLeaderboard` at `api.ts:2925`, `GET /api/leagues/{id}/stats?window=` | `api.ts:2925-2926` (read) | ✅ |
| R2: `PICKABLE` at `statLeaderboard.ts:171`, derived from `GROUPS` minus Draft | `statLeaderboard.ts:171-176` (read) | ✅ But see F5: it already drops `fgPct` and `ftPct` |
| R4: percentages are 0–100, so `PTS × TS% ÷ 100` is right | `api.ts:2648` ("Shooting percentages are percent points (47.3, not 0.473)") and `:2659` (advanced rates, ftr and tpar included, in percent points) (read) | ✅ |
| R4: the payload carries more precision than the display | `api.ts:2649-2650`, `formatValue` `statLeaderboard.ts:359-373` (read) | ✅ Except that `fpPerGame` and VOR are rounded to 2 dp by the server (`ReplacementLevel.java:45,129`), and VOR displays at **1** dp (`'signed'` format), not 2 as R4 says "fantasy stats" do |
| R6: `qualificationApplies` at `statLeaderboard.ts:290` bites only on pct, rank, fp, vor, rankMove and gameScore | `statLeaderboard.ts:290-292` (read) | ✅ |
| R10: the season switch is a navigation, at `StatLeaderboard.tsx:285` | `StatLeaderboard.tsx:276-296` (read) | ✅ For the in-page picker. Not for the rail's picker (F7) |
| R12: the `stats` row is at `destinations.ts:339`, and App.tsx coverage is enforced | `destinations.ts:339`, `destinations.test.ts:208-221` (read) | ✅ The widened regex `/^\/leagues\/([^/]+)\/stats(?:\/calculator)?\/?$/` satisfies every existing test by reading: the App.tsx scan substitutes `X1` and matches; the global-route test is unaffected; `leagueIdFromPath` still yields group 1; the href test is unchanged; no earlier row (`players`) matches the new path. `power`'s `(?:\/verify)?` (`:233`) is the precedent. **Not executed** (no `node_modules`) |
| R12: `KeyedByLeague` at `App.tsx:133` | `App.tsx:73-76,133` (read) | ✅ It remounts on a league change. Because the calculator's state is in the URL, a remount restores it |
| R13: extraction won't break `StatLeaderboard.test.tsx` | the test (read) | ✅ It renders the default export under `/leagues/:sleeperLeagueId/stats`, mocks `../api`, and scopes its queries by group name (`'Window'`, `'View'`, `'Column group'`). Moving the components doesn't touch any of that. See F13 for the new switch's name |
| Football is gated by the payload | `PlayerStatsService.java:381-383` returns `available: false, reason: NOT_BASKETBALL` (read) | ✅ The calculator can reuse the leaderboard's `d.reason` branch (`StatLeaderboard.tsx:358-362`) |
| No charting dependency exists | plan R9 (read, not re-checked beyond the plan's citation) | ✅ |

## Findings: the plan was wrong or incomplete

**F1 (high). Three column labels collide with the grammar, so the readable form can't round-trip
and natural typing fails.** (read + inferred)

C1's lexer tries "the longest STAT match against all ids and labels" first, and the readable form
"swaps ids for labels". Applied to `PICKABLE`'s real labels (`statLeaderboard.ts:89-138`):

1. **`FP/G` contains `/`.** Typing `fp/gp` (FP ÷ games, written the way every other id-based
   formula is typed) lexes as the label `FP/G`, then `p`, so the member gets "‘p’ isn't a stat".
   `fp/gameScore` and `fp/GmSc` fail the same way. The error message points at the wrong place
   and names the wrong problem.
2. **The readable printer mangles `FP/G` if it is done as text.** R3 and C1 describe the readable
   form as "swaps ids for labels and `/` for `÷`". Done in that order on a string, `fp / gp`
   becomes `FP÷G ÷ GP`.
3. **`Pos rank` and `Pts rank` contain spaces.** C1 rule 1 says whitespace is ignored, so it's
   unspecified whether a label match spans a space. If it doesn't, the readable form
   `Pos rank × 2` doesn't parse back. If it does, `pts rank` (two stats in a row, which should be
   an error) silently becomes `pointsRank`.
4. **FR-013 puts brackets in a label.** US1 scenario 2 and FR-013 require the readable form to
   show `(USG + VOR (fantasy)) ÷ TO`. Parsed back, that's `VOR` followed by a bracketed `fantasy`:
   an error. Quickstart V1's round-trip test uses `(USG + VOR) ÷ TO`, which contradicts FR-013.
   The column's actual label is `VOR` (`statLeaderboard.ts:137`), and FR-002 says to use the
   leaderboard's label, so FR-002 and FR-013 also contradict each other.
5. **Rank labels gain a window suffix on the leaderboard.** `columnLabel` (`:319-322`) renders
   `Rank (last 10)`. If the calculator's table headers or readable form reuse it, that's more
   brackets inside a token.

**Consequence**: quickstart V1's round trip fails on any formula that uses VOR, a position or
points rank, or FP/G. Any formula that uses FP/G next to a stat starting with `g` can't be typed
without spaces.

**Fix**:

- A STAT match must end at a token boundary. The next character must not be a letter, a digit or
  `%`. Otherwise drop to the next-longest match. This turns `fp/gp` back into `fp`, `/`, `gp`, and
  also covers `usgx`.
- Labels that contain an operator, a bracket or a space get an operator-free alias that the
  readable form uses: `FPG` or `FP/G` printed as `FP per G`, `PosRank`, `PtsRank`. Alternatively,
  the readable form uses labels only where they lex cleanly and ids elsewhere. Pick one and put it
  in C1.
- Print the readable form from the AST, never by string substitution.
- Decide FR-013 explicitly. Either `(fantasy)` is a visual tag outside the token (rendered as a
  styled suffix that isn't part of the text the parser sees, and the copy-to-editor path inserts
  `VOR`), or the lexer accepts `VOR (fantasy)` as one alias. Amend US1 scenario 2 and quickstart
  V1 to agree.
- Add every label in `PICKABLE` to the C1 test table as a round trip: id → readable → parse → id.
  Generate it from `PICKABLE`, so a future label with a `/` fails the suite rather than the user.

**F2 (high). Standardized mode with a stat under ÷ produces a ranking driven by near-zero
denominators. Block it rather than warn.** (read + inferred; this answers the plan's second open
question)

R7: every z-scored input has mean 0 and sd 1 over the reference group. Under `x ÷ z(tov)`:

- about half the group has z ≤ 0. FR-006 excludes them. So the "ranking" covers only players with
  above-average turnovers, which isn't what the member asked;
- among the rest, the top of the chart is whoever sits just above the average. A z of 0.02 turns a
  z(usg) of 1.0 into 50. The leader is decided by distance from the mean, not by anything the
  formula expresses.

The plan's warning text is accurate, but a chart still renders, with exact values beside each bar,
looking as confident as any other. That is the case AGENTS.md calls out: "If a change would make
the tool look more certain without making it more correct, that's a reason not to make it."
The warning also fires on every formula the user is most likely to toggle the switch on: their own
example, `(usg + vor) / tov`, is a stat ratio.

Blocking `÷` outright is too broad, though. `÷ 100` and `÷ 2` are harmless under
standardization, and `pts * ts / 100` is a preset.

**Fix**:

- With standardize on, a `÷` whose right-hand side contains any stat is a validation error, not a
  warning. It goes through the same FR-004 path, with a position and words: "With standardize on,
  dividing by a stat isn't meaningful: about half the players sit below average, so the
  denominator crosses zero. Turn standardize off, or rewrite with + and −." Dividing by a constant
  stays allowed.
- When standardize is turned on with such a formula already present, keep the last result
  labelled as the previous formula (US1 scenario 5's mechanism), rather than switching back.
- FR-006's sentence "÷ by an input that can be zero or negative MUST exclude those players"
  becomes moot in standardized mode. Amend FR-006 visibly ("amended at plan review").
- Update quickstart V3's standardize step 3 (`usg / tov` should now show the error, not a warning),
  and add a C1 error row for it.
- Related raw-mode gap (inferred): raw denominators can also cross zero. `vor`, `rankMove` and
  `gameScore` can be negative or near zero, so `fp / vor` in raw mode has the same blow-up near
  replacement level. The plan only excludes exactly 0. At minimum, warn in raw mode when the
  denominator's values over the reference group include anything ≤ 0. One rule ("the denominator
  crosses zero in this group") then covers both modes.

**F3 (medium). The exclusion-reason taxonomy is wrong about which values are null, so it would
print the wrong reason.** (read)

R5 and data-model "Reason" say "VOR, FP/G and the rank columns are null unless `qualified`
(`api.ts:2880`) … With the qualification switch on, this case can't happen for those inputs."
Three parts of that are wrong:

1. **FP/G is not null when unqualified.** `api.ts:2880` says "Ranks, and the two replacement
   fields, are null unless `qualified`". The backend says so explicitly:
   `PlayerStatsService.java:156-157`, "the four ranks and the two replacement fields are null for
   a player who is not qualified; `fpPerGame` is not". `fpPerGame` is null only for a window with
   no games.
2. **VOR can be null for a qualified player.** `ReplacementLevel.vor` returns `Vor.NONE`
   (value null) when none of his eligible positions has a level (`ReplacementLevel.java:113-129`),
   and `Replacement.byPosition` documents a null level as possible (`api.ts:2849`). The leaderboard
   already prints "none" for such a level (`StatLeaderboard.tsx:145`). So with qualification on,
   the calculator would say "not qualified, so no VOR" about a qualified player.
   `positionRank` is likewise null for a qualified player with no positions
   (`PlayerStatsService.java:438-440`). That was 0 of 582 in 2025 (spec 022's measurement), but
   2026 call-ups are the `known: false` case.
3. **Counting nulls aren't covered at all.** `per36` is null when he has no minutes and `perGame`
   is null with no games (`api.ts:2702-2705`). `min` returns null when games is 0 (`:94`).
   `gameScorePerGame` is nullable (`api.ts:2707`). A per-36 formula over a player with games but 0
   minutes hits a null that R5 has no reason for. `statCells.Cell` already handles exactly this
   case (`statCells.tsx:91-94`, `NO_MINUTES`). Its last fallback (`:95-96`) also says "not
   qualified" for any other null, which is the same mislabel if copied.

**Fix**: derive `INPUT_MISSING`'s reason per column kind, in one function next to `Cell` (or
extracted from it, so the table cell and the exclusion list can't disagree):

- a rate column uses its `RateReason`;
- a counting column in per 36 with null `per36` is `NO_MINUTES`;
- a rank or VOR column on an unqualified row is `row.reason` (`NOT_QUALIFIED` or
  `NOT_QUALIFIED_STALE`, with the date);
- VOR null on a qualified row is "no replacement level at his position (`vorPosition` is null)";
- `positionRank` null on a qualified row is "no listed position";
- anything else is an explicit `UNKNOWN_NULL` that a test asserts never occurs on the fixtures.

Strike "can't happen" from R5.

**F4 (medium). A window change or a background data refresh unmounts the page, losing the
editor's unsaved text and the "previous formula" result. SC-006 is never measured.** (read +
inferred)

`useBlock` sets `{ status: 'loading' }` on every dependency change (`useBlock.ts:17`). The
leaderboard renders everything below the header only inside `d && …` (`StatLeaderboard.tsx:312,
364`), so a window change blanks the page until the request returns. The dependencies include
`useLeagueDataVersion` (`:301,311`), which the rail's refresh bumps (`LeagueRailSection.tsx:56-77`).

If the calculator copies that shape:

- a formula being edited but not yet run (so not yet in the URL) disappears on a window change or
  a data refresh;
- US1 scenario 5's "keep the last valid result labelled as the previous formula" is local state,
  and is lost too;
- the chart flashes empty on every window change.

SC-006 says "under 1 second" for a season, window or mode change. A window change is a network
request, and a season change is a navigation plus a remount plus a request. The plan asserts that
this is "the same cost the leaderboard pays today", but nothing measured the endpoint. Spec 022
measured 632–668 ms just to read a season's rows from Postgres on a cold cache
(`specs/022-player-stat-analysis/research.md:132`), before Java computes anything. Quickstart has
no SC-006 step at all.

**Fix**:

- Keep the editor and the formula state outside the data gate. Gate only the results on `d`, and
  gate the whole page only on `d.reason`, for the football and no-data cases.
- Keep the previous `d` on screen, marked as updating, while the next one loads. A small
  `useBlock` variant, or holding the last `ok` value in the page, would do.
- Define SC-006 as: a mode or formula change re-renders with no request (testable in Vitest with a
  timer); a window or season change is measured in the live pass, from click to chart, warm and
  cold, and both numbers are reported. Add that step to quickstart V3. If the cold number is over
  1 s, report it rather than tuning anything.

**F5 (low-medium). The duplicate-label rule resolves to a column that isn't in the input set.**
(read)

R2 says `PICKABLE` contains both `fgPct`/`fgPctMA` (both `FG%`) and `ftPct`/`ftPctMA`. R3 and
data-model then resolve the shared label "to the Basic-group column". But `PICKABLE` already drops
Basic's `fgPct` and `ftPct` (`statLeaderboard.ts:166-174`: "with Basic's plain FG%/FT% dropped for
Shooting's FG%/FT% … so no label is offered twice"). So:

- the rule would resolve `FG%` to `fgPct`, which FR-002 and R2's own input set exclude;
- a canonical `fgPct` would then be stored in links, but the picker never offers that id;
- R2's "two duplicate labels found" and the constitution-check line that credits that discovery
  are wrong as stated.

**Fix**: drop the duplicate rule. Labels in `PICKABLE` are already unique, so add a test asserting
that (case-insensitively, including the new aliases from F1). `FG%` resolves to `fgPctMA`. Correct
R2 and the plan's constitution-check row visibly, as "amended at plan review".

**F6 (medium). There's no sort direction, and the bar chart misreads negative values. "Top" is
undefined for a lower-is-better formula.** (read + inferred)

The data-model fixes ordering as value descending. The spec says the calculator doesn't flip signs,
which is right, but it also gives the member no way to ask for "lowest first". So:

- `tov`, `tovPct`, any rank, or `tov / min` charts the **worst** players as the top 15, under a
  title that says top;
- the only workaround is writing `0 - tov`. With R9's zero-baseline bars, every bar then extends
  left, and the leader (closest to zero) gets the **shortest** bar. Bar length then reads opposite
  to rank. That breaks the project's own rule that bar length encodes the value and nothing else,
  which the chart claims to follow (spec assumptions; memory "Label the axis, spell out the
  number");
- a mixed-sign top N (common for anything involving VOR or rank move) needs a baseline in the
  middle of the row, which R9 mentions but doesn't specify at 375 px.

FR-009 also lets the table be sorted by any column, but doesn't say whether the chart follows the
table's sort or keeps its own order.

**Fix**:

- Add a "Rank by: highest first / lowest first" control. Default to highest. Put it in the URL
  (`dir`, part of SC-004's identical chart). The page says which way the chart reads. This is a
  sort direction, not a sign flip, so it doesn't contradict the spec's edge case.
- Optional, but cheap: when every input in the formula is `lowerIsBetter` and the formula is a
  sum or product of them, show a one-line hint that lowest-first may be what's wanted. Don't
  switch automatically.
- The chart always follows the result's direction, never the table's column sort. State that in
  FR-009.
- For an all-negative top N, draw bars from the zero baseline leftward and show the sign in each
  value. Add a RankedBars test for all-negative and mixed-sign data, including at 375 px.

**F7 (medium). The rail's season switch drops the calculator and the formula. The fallback
rules live inline in the leaderboard page and aren't in the extraction list.** (read)

1. C2 promises that "a season switch … keeps the query string". That holds only for the in-page
   `SeasonPicker`. The rail's season links use
   `switchTarget(currentKey, …)` (`LeagueRailSection.tsx:178-193, 463, 504`), which returns the
   destination row's `href`. For `stats` that's `/leagues/{id}/stats`. So a member on the
   calculator who switches season in the rail lands on the table with the formula gone. The player
   row already has the pattern for reading extra state off the pathname (`:182-186`).
2. The leaderboard's fallback handling is page-local code, not one of R13's components:
   `shownLeagueId` (`StatLeaderboard.tsx:324`, so a player link opens the season actually shown),
   `fellBack`, `nowLabel`, `currentAsOf` and `asOf` (`:327-335`), the fallback note (`:367-371`),
   and the owners sentence (`:428-433`). FR-010 says the fallback must "behave exactly as on the
   leaderboard". Copying those lines makes two implementations of one rule, the class AGENTS.md
   and memory ("Optional params that encode rules") keep recording. Getting `shownLeagueId` wrong
   sends FR-009's player links to the 2026 page while showing 2025 numbers.

**Fix**:

- Either teach `switchTarget` to keep `/calculator` and the search string when the current
  destination is `stats` (with a `destinations`/rail test), or state in C2 that the rail goes to
  the table and only the in-page picker keeps the formula. The first is better.
- Extract a pure `boardContext(d, leagueId)` into `statLeaderboard.ts` or `statCopy.ts` that
  returns `{ shownLeagueId, fellBack, nowLabel, ownershipLine }`. Both pages use it. Add it to R13
  and to the plan's file list.
- The in-page picker strips `own=r:<id>` when it changes season (see F9).

**F8 (medium). FR-012's "this league's scoring" is wrong on a fallback, and the plan ignores the
two fields that say so.** (read)

`StatLeaderboard` carries `scoringSeason` and `scoringMatchesRequested` (`api.ts:2898-2908`). The
backend has two kinds of fallback (`PlayerStatsService.java:384-400, 483-484`):

- the resolver walks back to an older league in the chain. Fantasy figures then use **that older
  league's** scoring, and `scoringMatchesRequested` says whether it equals the requested league's;
- the data fallback borrows an earlier season's box scores but keeps the requested league's
  scoring. `scoringMatchesRequested` is then null.

The quickstart's season-fallback check (2026 league opening on 2025) is the first kind. So FR-012's
sentence, "those inputs use this league's scoring", and US3 scenario 2's "the new league's scoring"
can both be false on exactly the page the quickstart checks. The draft room already has this copy:
`draftRoomStats.ts:94-95` names `scoringSeason` and flags `scoringMatchesRequested === false`.

**Fix**: FR-012's label names the scoring season, reusing `draftRoomStats`' sentence (extract it
to `statCopy.ts` rather than copying it). When `scoringMatchesRequested === false`, say that the
scoring changed between seasons. Amend the spec's edge case "A past season's fantasy inputs use
that season's league scoring", which is not true for the data fallback. Add a page test with a
fallback board where `scoringMatchesRequested` is false.

**F9 (medium-low). URL filter values aren't validated, so a stale link can show an empty or
silently wrong chart under controls that read "All".** (read + inferred)

C2 validates `own=r:` ("dropped, with a sentence"), but not `pos` or `team`:

- `FilterBar`'s selects take their options from the current rows (`StatLeaderboard.tsx:195-196`).
  A `team` from a link that isn't in this season's rows (a different season, or a hand edit) gives
  a `<select>` whose value matches no option. React then shows the first option, "All", while
  `applyFilters` still filters on the stale code. The result is "every player excluded" under a
  control that says All. That's the "declared value vs. actual state" bug class (AGENTS.md #4).
- `own=r:<rosterId>` never triggers C2's "doesn't exist" rule within one league chain, because
  Sleeper roster ids are 1..N in every season. After a season switch (which keeps the query
  string), it silently filters to whoever holds roster N in the other season. On a fallback,
  ownership is `currentOwnership` (`statLeaderboard.ts:263-265`), so the id space is the requested
  season's, which is another way to get it wrong.
- A literal `+` in a hand-edited or chat-mangled `f` decodes as a space. `useSearchParams` encodes
  `+` as `%2B` on write, so links the app writes are fine (inferred), but test it.

**Fix**: on read, drop `pos`/`team`/`own` values that aren't among the current rows' options, with
one sentence naming what was dropped. The in-page season picker strips `own=r:` when it changes
season. Add C2 test rows for an unknown team and a roster id from another season, plus a
`+`-in-formula round trip.

**F10 (medium; recommendation on open question 1). Keep qualification on by default, but the
plan's reason is wrong, and the page needs the count and the recency rule.** (read + inferred)

R6 justifies the blanket rule because "the leaderboard's per-column rule can't see what a formula
does with its inputs". The calculator has the parsed AST, so it can. A formula-aware rule (apply
qualification when any input has `qualificationApplies`, or any stat sits under a `÷`) is easy to
write. The blanket default is still the better choice, for different reasons:

- the page is "top players by this stat". In a ranked top N, a 9-game sample is noise whatever the
  operators are. The formula-aware rule would let `pts + reb` per game rank a 3-game player;
- one rule is easier to state than a rule that changes as you type;
- in totals mode it costs nothing, because a low-games player can't top a totals formula anyway.

What it costs has to be visible:

- **How many players it hides.** In 2025 SEASON, 303 of 582 rows qualify (spec 022's verification,
  `verification.md:226`). A blanket rule silently drops 279 players from `pts + ast`, which the
  leaderboard lists in full under the same checkbox label. The page must say "Showing 303 of 582
  players: qualified only" (the leaderboard already prints "`shown` of `rows` players", `:445`).
- **The recency part.** In last-10 and last-5, `qualified` also requires a game within 14 days of
  the season's latest game. Failing only that is `NOT_QUALIFIED_STALE`
  (`PlayerStatsService.java:669-688`; `weights.yml` `recency-days: 14`). SEASON has no recency
  check. So "last 10" hides an injured star even on a counting formula. That is right for
  "currently", but the copy must say it. `statCopy.qualificationRule` already includes the recency
  clause (`statCopy.ts:269-275`), so reuse it verbatim, and don't reuse the leaderboard's
  "Applies to rate and rank columns" sentence (`StatLeaderboard.tsx:413`).
- **What "small-sample label" means.** The spec edge case says players below the threshold "carry
  the same small-sample label they have elsewhere". There are two different things it could mean:
  the leaderboard's unranked line (`reasonSentence(row.reason)`, `StatLeaderboard.tsx:99-103`), or
  the player page's `stats.smallSample` flag (`api.ts:2710`, `PlayerPage.tsx:259`), which is a
  different, minutes-based rule. Name the first one in the spec. It's the one tied to `qualified`.
- With qualification off, unqualified rows rank normally on FP/G (F3: not null) but drop to
  "Couldn't compute" on VOR and the ranks. The copy on quickstart V3 step 5 is right for VOR only.

**Recommendation**: keep R6's default, correct its rationale in research.md, and add the
count-and-recency sentence as a requirement with a page test.

**F11 (medium-low). Unit inference has three wrong rules.** (read + inferred)

1. **Constants and ×.** R8: "constants take on the unit of whatever they meet. × and ÷ produce a
   composite unit." Read literally, `2 * usg` is `pct·pct`, so `2 * usg + vor` warns about the
   wrong thing, and `2 * usg + usg` warns when nothing is mixed. A constant is dimensionless:
   `unit(k × X) = unit(X)`, `unit(k ± X) = unit(X)`, and `unit(X ÷ X) = dimensionless`, so that
   `ast/tov + stl/tov` doesn't warn.
2. **`min` in per 36.** `min` is `counting: true` (`statLeaderboard.ts:92`), but in per-36 mode its
   value is minutes **per game** (`:94`), not 36. R8 gives it a mode-independent `minutes` class,
   and US1 scenario 8 says the readable form "shows which mode applies". So `pts / min` in per 36
   is (points per 36) ÷ (minutes per game), labelled as per 36. The readable form and the table
   header should mark `MIN` as per game in per-36 mode. The same goes for FP/G, VOR and GmSc,
   which are per game in every mode.
3. **Same unit, different spread.** FR-006's warning is about units, but its stated harm is
   spread ("the input with the widest spread will drive the ranking"). `pts + stl` per game (both
   `count:perGame`) is in effect a points ranking, and gets no warning. `usg + ts` (both `pct`,
   very different spreads) is SC-007's own example, and gets none either. Consider a second,
   data-driven note when the inputs' standard deviations over the reference group differ by more
   than some hand-set ratio. Label the ratio as hand-set. That's a scope call; at minimum, the
   copy shouldn't imply that "same unit" means "balanced".

**Fix**: put rules 1 and 2 in research R8 and test them. Decide 3 explicitly and record it either
way.

**F12 (low-medium). Verification and spec text gaps.** (read)

- **SC-001 and SC-006 have no quickstart step.** SC-006 is covered by F4. SC-001 ("under 60 s
  without reading instructions") needs at least a named check, or an honest note that it's judged,
  not measured.
- **FR-005 wasn't amended alongside SC-002.** It still says the result equals the formula "applied
  to the exact values the leaderboard shows … to the displayed precision". R4 says that fails on
  purpose for ratios. Amend FR-005 to say "payload values", with the same note.
- **"The user's example as written."** The request was "usage rate + vorp / turnovers". By
  ordinary precedence, that's `usg + (vor / tov)`. The preset and US1 use `(usg + vor) / tov`.
  That's probably what was meant ("type beat"), but FR-016 says "as written", so the preset's
  description should say the brackets were added and why. Otherwise the tool's own example
  contradicts its precedence rule.
- **US1 scenario 3's "bar length encodes the value and nothing else"** needs F6's
  negative-value rule to be true.
- **FR-001** says the page is reachable "from the league navigation's stats area". The plan adds no
  rail item and reaches it only through the in-page switch. Amend FR-001 to say that, or add a
  sub-link.
- **SC-007 is listed before SC-006.** Cosmetic, but tasks will reference them by number.

**F13 (low). The new "Table · Calculator" switch would sit next to the existing "View: Table |
Stat leaders" switch.** (read)

The leaderboard already has a segmented control named `View` with a `Table` button
(`StatLeaderboard.tsx:375-380`). A second control under the header that also has a `Table` option
is ambiguous for a reader and for `getByRole('button', { name: 'Table' })` in future tests. The
existing tests scope by group name, so they won't break (read).

**Fix**: make the page switch a pair of links in a `nav` named "Stats pages", labelled
"Leaderboard" and "Calculator". It's a navigation, not a toggle, so it uses `Link` with
`aria-current`, not `aria-pressed`.

## Notes (smaller)

- **N1.** R12 cites `destinations.test.ts:220`. The coverage test is at `:208-221`. That's close
  enough, but tasks should cite the test by name.
- **N2.** `name` can be null (`api.ts:2868`). RankedBars needs the same "Unknown player" fallback
  as `PlayerCell` (`StatLeaderboard.tsx:90`). The tiebreak already sorts null names last
  (`statLeaderboard.ts:228-229`).
- **N3.** Football, and the `NO_GAMES`/`NOT_CONFIGURED` reasons: gate the whole page on `d.reason`
  before rendering the editor or the presets. Otherwise US1 scenario 9's "no partial layout"
  breaks for the length of the request. This doesn't conflict with F4's fix: the editor sits
  outside the loading gate, not outside the reason gate.
- **N4.** C1's error "Two operators in a row" must not fire on `usg + -tov` or `usg * -2`, which
  the grammar's unary minus makes valid. Add those as positive cases. Also add `.5` and `1e3` as
  errors with messages, since NUMBER requires a leading digit and has no exponent.
- **N5.** `USG` has no `%` while every other rate label does. `USG%` will be typed. Consider it as
  an alias, which is harmless once F1's boundary rule is in.
- **N6.** USG, FTr, 3PAr and MIN% are `STYLE_KEYS` (`statCopy.ts:278`): the app deliberately
  avoids presenting them as good or bad. Two presets rank by USG. Their descriptions should say
  that usage measures role, not quality.
- **N7.** C3: two tabs saving at once overwrite each other's lists (last write wins). Accept it
  and say so in C3, or re-read before each write.
- **N8.** C3 says filters aren't saved, which is right. US3 scenario 1's "restores … exactly" is
  about links only, which carry filters (R10). Say so, so nobody tests saved formulas against
  SC-004.
- **N9.** R4 says results show "2 decimals, or 3 significant figures when |value| < 1". Totals-mode
  sums run into the thousands, and the spec's × 1000 edge case wants "readable format". Add
  thousands separators, or switch to integers above some size, and a test.
- **N10.** The extraction's `PlayerCell` takes `d: Board` only for `d.sport` (`:93`). Passing
  `sport` keeps the shared component from depending on the whole payload type.

## Answers to the plan's two open questions

1. **R6, blanket qualification**: keep it on by default for every formula. It's clearer for a
   "top players" chart, and it costs nothing in totals mode. But correct the rationale (the AST
   can see the formula, so that isn't the reason), and require two sentences on the page: the
   shown/total count, and the rule from `qualificationRule` including its last-5/last-10 recency
   clause. See F10.
2. **R7, ÷ in standardized mode**: block it, but only when the right-hand side of a `÷` contains a
   stat. Dividing by a constant stays allowed. A warning-only design renders a confident-looking
   chart whose order is set by distance from the mean. See F2, which also proposes one
   "denominator crosses zero" warning for raw mode.

## Disposition (applied 2026-10-12)

Every finding was applied as recommended, marked inline in each document as "amended after plan
review, F<n>". There were two judgment calls:

- **F1**: picked the alias route over "labels only where clean". Formula tokens are `FPG`,
  `POSRANK` and `PTSRANK`, the only three `PICKABLE` labels outside `[A-Za-z0-9%]+`, confirmed by
  grep. `USG%` is added as an alias (N5).
- **F11 rule 3**: in scope, as a note shown when two added inputs' spreads differ by more than a
  hand-set 3×. The ratio is labelled as hand-set.

R15 (one shared `missingReason`) changes the leaderboard's last-fallback copy too, and plan.md
records that as a visible change outside the feature.
