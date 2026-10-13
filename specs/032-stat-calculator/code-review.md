# Code review: spec 032 stat calculator (T026, bug hunt)

Scope: the uncommitted tree on `032-stat-calculator` (worktree `.claude/worktrees/032-stat-calculator`):
the 14 modified files in `git diff` plus the untracked `statFormula.ts`, `statCalcUrl.ts`,
`statCalcSaved.ts`, `statCalcPresets.ts`, `components/RankedBars.tsx`, `components/StatScatter.tsx`,
`components/statControls.tsx`, `pages/StatCalculator.tsx` and their tests. Reviewed 2026-10-13, read cold
against `spec.md`, `research.md`, `data-model.md`, `contracts/*.md`, `plan-review.md` and `tasks.md`. No
source file was edited.

**Verdict: one wrong-output defect (B1) must be fixed before live verification: with "standardize inputs"
on, a product of two stats ranks the worst players first, and the bundled "Efficient volume" preset walks
straight into it. B2 (the page keeps showing a formula the URL no longer has) should be fixed in the same
pass because it is cheap and live verification will click the tab that triggers it. B3 is a one-line fix to
a contract breach. B4–B11 are low and can ship with a note.** The lexer boundary rule, `÷ 0`/null handling,
the z-score reference group, the `lastOk` hold, URL filter validation, `stripRoster`/`switchTarget`, the
statControls extraction and the saved-formula store held up (see "Checked and held up").

Labels: **measured** = executed (a throwaway esbuild bundle of the pure functions, or a throwaway vitest
render of the real page, both in the session scratchpad, not the repo); **read** = traced in code;
**inferred** = follows from the code but not executed.

Suites, measured: `npx tsc -b` clean. `npx vitest run`: 120 files, 1,977 tests, all passed.

---

## B1 (high): standardized mode multiplies z-scores, so two below-average inputs rank first

- **Evidence (read)**: `validate`, `statFormula.ts:239-252`, blocks only `÷` with a stat on its right
  (plan review F2). `×` between two stats passes. `ev` then multiplies the two z-scores
  (`statFormula.ts:356`, `:368`). `loadPreset`, `StatCalculator.tsx:209-217`, keeps standardize on whenever
  `validate` passes, which it does for `pts * ts / 100` ("Efficient volume", `statCalcPresets.ts`).
- **Measured** (pure functions, 5 players; Star 32 pts / 62 TS%, Bad 4 pts / 45 TS%):
  - `pts * ts / 100`, raw: Star 19.84 first, Bad 1.80 last. Correct.
  - Same formula standardized: **Bad 0.0266 first**, Star 0.0205 second. Bad's z-scores are both
    strongly negative, so their product is the largest positive number.
  - `pts * ts` standardized: Bad 2.66, Star 2.05. Same inversion.
  - Page render (vitest, real `StatCalculator`, `?z=1`, click "Use this" on Efficient volume): the URL
    becomes `f=pts+*+ts+%2F+100&n=Efficient+volume&z=1` and the chart reads `1 Bad 0.0158 / 2 Star 0.0136 /
    3 Avg −0.000138`.
- **Failure scenario**: a member with standardize on (it persists in the URL and in saved settings) loads
  "Efficient volume", or types any stat × stat. The chart titled "highest first" puts the league's least
  efficient low scorer at #1, with no warning. This is bug class 1 (well-formed, pointed backwards), and it
  is the same failure F2 fixed for `÷`: the sign of a z-score crosses zero for about half the players.
- **Suggested fix**:
  - In `validate`, with standardize on, also reject a `*` whose two sides both contain a stat
    (`inputsOf(n.l).length > 0 && inputsOf(n.r).length > 0`), with a message in the F2 style ("multiplying
    two stats isn't meaningful: two below-average values multiply to a large positive one…"). A stat × a
    constant stays valid (`usg * -2`, `pts * ts / 100` is then blocked, `(usg + ts) * 2` is not).
  - `loadPreset` already turns standardize off when `validate` fails, so "Efficient volume" (and
    "Usage value", "Assist-to-turnover", "Fantasy points per usage point") then load raw. Consider saying so
    in a sentence when it happens; today the switch flips silently.
  - `loadSaved` should run `validate` too, so a saved standardized product (none exist yet; the feature is
    unreleased) shows the error rather than a chart.
  - Add a preference-ordering test (a below-average/below-average player must not outrank the leader) and
    amend contract C1's error table and data-model's "standardized-÷ error" visibly ("amended after code
    review, B1").

## B2 (medium): navigating to the calculator with a different query leaves the old formula on screen

- **Evidence (read)**: the editor text is `useState(state.f)` (`StatCalculator.tsx:172`), seeded once.
  `lastGood` (`:185-189`) is never cleared, so when the URL's `f` becomes empty, `active` is null and
  `display` falls back to `lastGood`. `KeyedByLeague` (`App.tsx:74-77`) remounts only on a league id change,
  so a same-league navigation keeps the component.
- **Measured** (vitest, real page): mount at `?f=pts`, then click "Calculator" in the page's own
  `StatsPagesNav`. The URL becomes `/leagues/L25/stats/calculator` with no query, but the ranked chart is
  still shown, the editor still holds `pts`, "Formula as read" still says `PTS (per game)`, and "Copy link"
  is disabled (`!state.f`). "Save this formula" is also disabled ("Run a valid formula to save it") while a
  chart is on screen (read: `disabled={!active}`, `:758`).
- **Failure scenario**: the member clicks the "Calculator" tab to start over and gets the old chart back
  with no way to share or save it; or any future in-app link to a calculator URL with a different `f` shows
  the previous formula's chart under the new URL. The page then contradicts its own link (SC-004).
- **Suggested fix**: treat an external change of `state.f` as authoritative. For example, keep a ref of the
  last `f` this page wrote; in an effect on `state.f`, when it differs, `setText(state.f)`, clear `runError`,
  and when `state.f` is empty also `setLastGood(null)`. Add a test for the tab click.

## B3 (low-medium): an overflowing constant puts `Infinity` on the page; constant-only and `÷ 0` formulas are accepted

- **Evidence (read)**: the lexer builds a number with `Number(text)` (`statFormula.ts:100-101`) and never
  checks it is finite. `ev`'s `num` case returns `n.v` without the `finite` guard (`:347`), so a formula
  that is only a constant returns it as the result.
- **Measured**: a 400-digit literal parses and canonicalizes, and `evaluate` returns `{ value: Infinity }`.
  `formatResult(Infinity)` is `"∞"`, and `RankedBars` would compute `span = Infinity` and widths of `NaN%`.
  `big * pts` is caught (`NOT_FINITE`). `usg / 0` and `usg / (2 - 2)` parse, run, and exclude every player
  with "0 is 0, so ÷ 0 is undefined".
- **Failure scenario**: a pasted or hand-edited link. It breaches C1's "evaluation never returns `NaN` or
  `±Infinity` to the UI" and SC-003's text scan for `Infinity`. The `÷ 0` cases are just an unhelpful page.
- **Suggested fix**: in the lexer, fail on a non-finite literal ("That number is too large"). In `validate`
  (both modes), reject a formula with no stat ("Use at least one stat") and a `÷` whose right side is a
  constant equal to 0.

## B4 (low): `formatResult` edge cases print exponents and inconsistent thousands

- **Evidence (read)**: `formatResult`, `statFormula.ts:637-641`, picks the branch on the unrounded
  magnitude and uses `toPrecision(3)` below 1.
- **Measured**:
  - `1e-7` → `"1.00e-7"`, and `5e-324` → `"4.94e-324"`. `toPrecision` switches to exponent notation below
    1e-6. The editor rejects exponents ("exponents aren't supported"), so a displayed value can't be typed
    back.
  - `999.995`, `999.996` and `999.9999` → `"1000.00"`, while `1000` → `"1,000"`. In one measured column the
    values read `1,984 · 1,026 · 896.00 · 840.00 · 180.00`.
  - `-0` → `"0.00"` (fine); `0.9996` → `"1.00"` (fine).
- **Failure scenario**: tiny ratios, such as a totals-mode `blk / (min * min)`, show `e-` notation; a column
  mixes two number styles.
- **Suggested fix**: choose the style from the value after rounding to 2 decimals (so 999.995 takes the
  `≥ 1000` style), and below 1e-6 print a fixed form (for example `toFixed(8)` trimmed, or "< 0.000001").

## B5 (low): repeated warnings give duplicate React keys

- **Evidence (read)**: warnings render with `key={w}` (`StatCalculator.tsx:651`).
- **Measured**: `inferUnits('usg + vor - (usg + vor)')` returns the same sentence twice. React then warns
  about duplicate keys and can drop or misplace an item on update.
- **Suggested fix**: de-duplicate the list (`[...new Set(...)]`), or key by index.

## B6 (low): total minutes share a unit with every counting stat, so `pts + min` in totals mode doesn't warn

- **Evidence (read)**: `unitOf`, `statFormula.ts:486-490`, maps `min` in totals mode to `count:totals`, the
  unit of points, rebounds and the rest. data-model's "Stat input" says "`min` … per game in every mode",
  but `COLUMNS.min` returns `r.stats.minutes` (a season total) in totals mode (`statLeaderboard.ts`,
  `BASIC` min).
- **Measured**: `inferUnits('pts + min', 'totals')` returns `[]`; in per game mode it returns one warning.
- **Suggested fix**: use `minutes:totals` (words: "total minutes") for `min` in totals mode, and amend
  data-model's sentence about `min` visibly.

## B7 (low): the standardization sentence names the wrong group size when an input has nulls

- **Evidence (read)**: the sentence uses `shown.length` ("the average of the ${shown.length} players
  shown", `StatCalculator.tsx:633`), but `standardize` averages only rows with a value (`statFormula.ts:416-435`).
  That is spec FR-006's rule, and the per-input `n` line below the sentence is correct.
- **Measured**: with 6 players shown and one TS% null, `standardize` gives TS% `n 5`, while the sentence
  would say 6.
- **Suggested fix**: "…the average of the players shown who have a value for it (n for each input below)".

## B8 (low): the formula language and the page's own labels disagree for three stats; some error messages name the wrong text

- **Evidence**: the results table header and the scatter axis print `COLUMNS[id].label` (`FP/G`, `Pos rank`,
  `Pts rank`; `StatCalculator.tsx:343`, `:692`), while formulas use `FPG`, `POSRANK` and `PTSRANK` (read).
- **Measured**:
  - Typing the header's `FP/G` → "‘G’ isn't a stat", and `pos rank` → "‘pos’ isn't a stat".
  - `3PA` → "‘PA’ isn't a stat", naming only half of what was typed.
  - `1.5.5` → "Numbers need a leading digit: write 0.5", when the problem is a second decimal point.
- **Suggested fix**: in the calculator's own table and axis labels, print the formula token, with the label
  in the title. Special-case the three label spellings in the lexer's error ("write FPG"). For a number
  followed by letters, report the whole run (`3PA`). For `1.5.5`, say "a number has two decimal points".

## B9 (low): the zero-denominator warning's sentence doesn't parse

- **Measured**: `zeroDenominatorWarning('ast / tov', …)` returns "Dividing by TO is zero or negative for 1 of
  2 players shown: …" (`statFormula.ts:620-628`).
- **Suggested fix**: "TO is zero or negative for 1 of 2 players shown, and the formula divides by it: …".

## B10 (low): scatter axis state is lost on a chart toggle, and an empty formula axis can't be changed to the first stat

- **Evidence (read)**:
  - `writeCalcState` writes `x` and `y` only when `chart=scatter` (`statCalcUrl.ts`). Toggling to Ranked and
    back resets both axes to the defaults.
  - When the link's axis is `f` and there is no formula, `AxisSelect` is given `value="f"`
    (`StatCalculator.tsx:606-607`) with no `f` option. The browser shows the first option (GP), and choosing
    GP fires no change event.
- **Suggested fix**: keep `x`/`y` in the URL regardless of `chart` (they are short), or hold them in state.
  Pass `''` plus a placeholder option when the axis is unresolved.

## B11 (low): accessibility of the editor's error

- **Evidence (read)**: the error block is `role="alert"`, and the caret `<pre>` is `aria-hidden`, which is
  correct. However, the formula `<input>` has no `aria-invalid` and no `aria-describedby` pointing at the
  message (`StatCalculator.tsx:440-452`), so a screen-reader user who returns to the field isn't told it is
  in error.
- **Suggested fix**: give the message an id and set `aria-invalid={!!shownError}` and `aria-describedby` on
  the input.

---

## Checked and held up

- **Lexer: boundary rule and longest match (measured, about 90 inputs)**:
  - `fpg` → FPG; `fp/gp` → `fp / gp`; `3PAr`, `3par`, `3P%`, `3pm`, `ts%`, `TS%`, `ts`, `tpar`, `usg%`,
    `USG%`, `tov%`, `TOV%`, `TO`, `tov`, `MIN%`, `min%`, `min`, `gmsc`, `GameScore`, `efg`, `efg%`, `ast%`,
    `stl%` and `stl` all lex to the intended id.
  - `to%`, `minshare`, `usgx`, `usg_x` and `3pm3pm` are errors, never a silent prefix match.
  - No id is a different column's token. The lowercase ids `ts`/`tpar`/`tov`/`tovPct` don't interact,
    because `%` is a word character for the boundary check.
  - `2usg`, `(usg)(tov)`, `usg++tov`, `usg +`, `(usg`, `.5` and `1e3` give the contract's messages.
  - Unary minus, `−`, `×` and `÷` round-trip.
  - Canonical and readable printing keep the tree for `a - (b - c)`, `a / (b / c)`, `-(a * b)` and
    `--a` → `-(-a)`.
- **`÷ 0` and null handling (measured)**:
  - A null input short-circuits to `INPUT_MISSING` with `missingReason`'s code: per 36 with no `per36` →
    `NO_MINUTES`.
  - `÷ 0` → `DIVIDE_BY_ZERO` with "0 turnovers, so ÷ TO is undefined".
  - An overflowing stat product → `NOT_FINITE`.
  - NaN cannot arise: JSON carries no NaN, and every binary result passes `finite`. The only leak is B3's
    bare constant.
- **z-score reference group (read)**:
  - `zmap = standardize(shown, …)` and `rankResults(shown, …)` use the same `shown`: the filters plus the
    qualification switch (`applyFilters` with `sortCol: 'fp'`, for which `qualificationApplies` is true).
  - Each input counts only non-null values (measured: n 5 of 6).
  - The n/mean/SD readout reads that same `zmap`. "Showing X of Y" is `shown.length` of `rows.length`.
  - `spreadNotes` and `zeroDenominatorWarning` use `shown` too. The only mismatch is B7's sentence.
- **`lastOk` hold (measured)**: after a Season → Last 5 click with the request pending, the Window control
  shows Last 5, "Updating…" is shown, and the chart title says "full season", taken from the payload's own
  `window`. The title therefore stays honest about the bars. On a failed refresh, the page says it is
  showing the last ones loaded.
- **URL filter validation timing (read)**:
  - Validation runs against `d.rows`, the shown payload, and is skipped while there is no payload.
  - With `lastOk` from another window of the same season, the rows are the same players: the backend emits
    one row per player with at least one line, and `window.select` never empties a non-empty list
    (`PlayerStatsService.java:413-418`, `AdvancedStats.WindowKind`). So the position and team sets match.
  - A season change remounts the page via `KeyedByLeague`, resetting `lastOk`.
  - Dropped filters stay in the URL until the next control change rewrites it. A copied link then carries
    `team=ZZZ`, and the receiver gets the same sentence. Accepted.
- **`stripRoster` / `switchTarget` (read, existing tests)**:
  - The in-page `SeasonPicker` and the rail's two season lists strip `own=r:` and keep `f`, re-encoding `+`
    as `%2B`. The `%2B` round trip was measured through `writeCalcState`/`readCalcState`.
  - The rail's other-league switch (`LeagueRailSection.tsx:537`) passes no search, so it lands on an empty
    calculator. That is reasonable, because the C2 promise is about links.
- **statControls extraction (read)**: `WINDOWS`, `MODES`, `Segmented`, `FilterBar` and `SeasonPicker` (no
  suffix, no `keepSearch` = the old behaviour) moved verbatim. `PlayerCell` takes `sport` instead of the
  board. `boardContext` is the old inline code. No assertion was removed from the leaderboard, destinations
  or rail tests (`git diff | grep '^-'`), and the suite is green.
- **`missingReason`'s effect on the leaderboard (read)**:
  - The only visible changes are on cells that were already wrong or are unreachable.
  - A qualified row with a null `vor`/`positionRank`/`leagueRank`/`rankMove` used to say "Hasn't played
    enough games or minutes to be ranked". It now says no replacement level, no position, or "No value is
    available".
  - A rate cell with a null reason says "no value" instead of "unavailable".
  - The new first branch, `games === 0` → NO_GAMES ("No games have been played this season yet", which
    would be wrong about one player), is unreachable with today's backend, because rows with no lines are
    never sent.
- **Saved store (read, its tests)**:
  - A blocked or corrupt store reads as empty. Each write re-reads and falls back to the in-memory list.
  - The cap of 50 drops the oldest and says so. Stale entries are kept and flagged. Ids are unique.
  - The list is not league-scoped, which matches C3.
- **Presets (read)**: all five are canonical (a test covers it), and `mixesUnits` matches `inferUnits`. The
  standardize-off rule works for `÷`; B1 is about `×`.
- **Ordering (read)**: `sign * (a - b)`, then `tieBreak` (the leaderboard's own tail, now exported rather
  than copied). Excluded groups are biggest first, then by key. Deterministic, so SC-004 is safe.
- **React (read)**:
  - No effect writes search params, so there is no `setSearchParams` loop.
  - `buildAxis` pushes into a render-local `axisNotes` array, which is harmless, including under StrictMode
    double render.
  - The scatter `useMemo` dependency list covers every value the axis closures read.
  - Keys are unique, except B5.

## Must fix before live verification (T027)

- **B1**: required. SC-007's z-score hand checks and any standardized preset would verify a wrong ranking.
- **B2**: recommended. Live verification clicks the stats tabs, and this one is cheap to fix.
- **B3**: recommended. A one-line lexer guard that SC-003's `Infinity` scan relies on.
- **B4–B11**: can ship with a note in `verification.md`. If they are deferred, say so there in those words.


---

## Disposition (fix pass, 2026-10-13)

All of B1–B11 were fixed in the frontend; details and tests are in `verification.md` under "Code-review fixes (B1–B11)".

- **B1** fixed and corrected in the docs: the "valid because it divides by a constant" claim about `pts * ts / 100` was wrong for standardized mode, and contract C1, spec FR-006, data-model and quickstart now say so in "amended after code review, B1" notes. Ordering test added.
- **B2, B3** fixed (B3 also adds the no-stat and constant `÷ 0` errors; C1 rows added).
- **B4** partly: no exponent notation and the 999.995 boundary are fixed; one column can still mix `1,984` and `896.00` by the N9 rule.
- **B5–B11** fixed as suggested. B10 relaxes C2's rule that axes appear only on the scatter.
- Nothing was declined. Not yet done: live verification in a real browser (T027).

---

## US5 review (T033)

Scope: T029–T032 as they sit uncommitted in the worktree:

- the US5 helpers in `statFormula.ts` (`quantiles`, `additiveTerms`, `contributions`, `chartAvailability`);
- `statCalcUrl.ts`;
- `components/SpreadStrip.tsx`, `ChangeDumbbell.tsx` and `ContributionBars.tsx`;
- the `disabled` option of `Segmented` in `statControls.tsx`;
- the US5 wiring in `pages/StatCalculator.tsx`;
- their tests and the new CSS.

Reviewed 2026-10-13, read cold against spec US5, FR-019/020, tasks Phase 6b and contract C2. No source file was
edited.

**Verdict: two wrong-output defects must be fixed before live verification (T034).**

- **C1**: on the default Change view (season vs last 10) the sign is inverted. A player who is heating up is drawn
  as the biggest *fall*, in the "worse" colour, and the existing page test codifies the inversion.
- **C2**: Change in Totals mode (or with GP in the formula) compares a 10-game sum with a whole-season sum, so every
  player "moves" by a factor of the game count.
- **C3** (colour carries a "better/worse" meaning the page cannot know) should be fixed in the same pass, since
  C1's fix touches the same lines.
- **C4–C5** are medium and cheap. **C6–C10** are low and can ship with a note.

These held up (see "US5: checked and held up"):

- `additiveTerms` and `contributions` are exact. The parts sum to `evaluate` within 2e-15 on 17 adversarial
  formulas, raw and standardized.
- `quantiles` matches numpy's linear method.
- The request-free mode change and the `lastOk` hold are intact.

Labels as above: **measured** = executed (a throwaway vitest file in the session scratchpad, rendering the real
`StatCalculator` or calling the real pure functions; not in the repo); **read** = traced in code; **inferred** =
follows from the code but not executed.

Suites, measured:

- `npx tsc -b` is clean.
- `npx vitest run`: 123 files, 2,073 tests. The first run had 1 failure. Three re-runs did not reproduce it
  (2,073/2,073 passed each time). Its name was not captured, so treat it as an unidentified flake, not as a US5
  result.

### C1 (high): Change's sign is backwards on the default comparison: a hot player is the "biggest fall"

- **Evidence (read)**:
  - The page always computes `now − then` (`StatCalculator.tsx:403`, `ChangeDumbbell.tsx:55`). "Now" is the main
    window and "then" is the comparison.
  - With `w=SEASON` and the default `cmp=LAST_10`, "now" is the season and "then" is the last 10 games, and the
    title reads "Last 10 → Full season".
  - That runs backwards in time: the last 10 games come after the season-to-date average they are part of.
- **Measured**: the real page at `?f=pts&chart=change`, where Hot scores 10 a game over the season and 25 over the
  last 10, and Cold scores 25 and 8. The title reads "PTS · Last 10 → Full season · biggest rise first", and the
  rows are:
  - Cold +17.00 (8.00 → 25.00), drawn as a rise;
  - A, B and D 0.00;
  - Hot −15.00 (25.00 → 10.00), drawn as a fall.
- **Failure scenario**: the member asks spec US5's "who's hot currently" question with the default settings.
  - The player in the best form of his season comes last, drawn in `--down` (the app's "worse" colour) and signed
    −15. The player in a slump comes first, at `+17` in `--up`.
  - This is bug class 1 (well-formed, pointed backwards).
  - The page test "Change: shows both exact values…" asserts it: `fay` "+10.00", "30.00 → 40.00", with the comment
    "change = season minus last 10". So the green suite certifies the inversion.
  - `w=LAST_10, cmp=LAST_5` is inverted the same way. Only `w=LAST_*` with `cmp=SEASON`, and `cmp=PREV`, read the
    right way round.
- **Suggested fix**:
  - Orient by recency, not by which control picked the window. The earlier view is "then" and the more recent one
    is "now", in the order PREV < SEASON < LAST_10 < LAST_5. Change is recent − earlier, titled
    "Full season → Last 10".
  - Correct the page test's expectations: Fay is a fall of 10 and Bob a rise of 6.
  - Add an ordering test of the kind lessons #1 asks for: a player whose last 10 beats his season must sort first
    under "Biggest rise".
  - State which side is "now" in the spec and contract, as an "amended after code review, C1" note.

### C2 (high): Change in Totals mode compares a 10-game sum with a season sum

- **Evidence (read)**:
  - A last-N window's totals are summed over `min(N, games)` games (`AdvancedStats.WindowKind.select`, backend).
  - `chartAvailability('change')` gates only on standardize (`statFormula.ts:865-868`).
  - Mode is shared by both views (`StatCalculator.tsx:394-395`).
- **Measured**: the same page, after clicking Totals. Every row is a large rise:
  - Cold +1,420 (80.00 → 1,500);
  - D +850, B +800, A +750;
  - Hot +350.

  With C1 fixed, every row would be a large fall instead. Either way the ranking is "who has played the most
  games", not a change in production.
- **Failure scenario**:
  - A member on Totals opens Change. Totals persists in the link and in saved formulas.
  - PREV has the same trap early in a season: a 5-game current season against a full previous season.
  - GP is the same problem in every mode, because GP is a count of games (at most 10 in LAST_10).
  - MIN is a total in Totals mode.
- **Suggested fix**:
  - Disable Change when `m === 'totals'`, or when the formula reads `gp`.
  - Give the reason in words, e.g. "Totals cover different numbers of games in the two views, so they don't
    compare; use Per game or Per 36".
  - Add mode and inputs to `chartAvailability`'s context, and test each reason, as T029 asks for the others.

### C3 (medium): the dumbbell paints "higher" in the app's "better" colour

- **Evidence (read)**:
  - `.sc-db-line.rise` is `var(--up)` and `.fall` is `var(--down)`. `styles.css:146-147` defines those tokens as
    "better / gained" and "worse / lost".
  - Every other use (rank moves, won/lost, heat best/worst) keeps that meaning.
  - The component's header comment admits the clash, and words the legend "Rise/Fall" to compensate.
- **Failure scenario (inferred)**: `f=tov`, or any formula ranked "Lowest first". A turnover spike is drawn green,
  and an improvement is drawn in the "worse" pink. The colour's learned meaning beats a legend word.
- **Suggested fix**, either of:
  - Use two non-evaluative hues for rise and fall, such as `--teal` and `--wr` (neither means better or worse
    elsewhere), and keep the sign and the hollow/filled ends.
  - Or tie the colour to the member's own "Rank by". With `dir=asc` a fall is the good direction, and the legend
    says "better (lower, as ranked)".

  The first is simpler and claims nothing the page can't know. Recommended.

### C4 (medium): after a window switch, Change briefly draws "Full season → Full season", every change 0.00

- **Evidence (read)**:
  - The comparison's window comes from the URL (`cmpWin = state.cmp`, which follows the new `w`;
    `StatCalculator.tsx:369`).
  - The main board shown during the reload is `lastOk`, which is still the old window (`:176`).
  - With `w` switched to LAST_10, the default `cmp` becomes SEASON. So the page fetches SEASON and joins it against
    the held SEASON board.
- **Measured**: the real page, with the LAST_10 main request held open. Next to "Updating…", the Change chart
  shows the title "PTS · Full season → Full season" and five rows, all "0.00" and "flat". The chart stays until the
  main board arrives, which on a slow network is as long as that takes.
- **Failure scenario**: during the hold, the member reads a confident-looking "no one changed". The `lastOk` hold
  is meant to keep the last result, not to draw a new, wrong one.
- **Suggested fix**:
  - Derive the comparison from what is shown (`d.window`), not from the URL. Or render no Change rows while
    `d.window !== state.w`, and say only "Updating…".
  - Make the comparison fetch wait for the main board too, so it isn't wasted.
  - Add a page test that holds the main request open.

### C5 (medium): with an ownership filter and "Previous season", the left-out list is mostly players the filter excluded

- **Evidence (read)**:
  - For PREV, the availability filter is dropped from the comparison side (`:381`). The reason, that roster ids
    differ between seasons, is right.
  - The join then iterates over the union of both sides (`:388`). So every player in last season's board who fails
    the current filter is counted as "missing from one view".
- **Measured**: `own=free&cmp=PREV`, with 3 free agents now and 40 qualified players last season. The page says:
  "Left out of Change: 37 players missing from one view. Not in the Full season view (filtered out, or not qualified
  there) (37): Prev4, Prev5, …".
  - The list includes rostered players the member filtered out on purpose, and every retired or departed player.
  - The same union inflates the count for SEASON vs LAST_10 with any filter, but less.
- **Suggested fix**:
  - Drive the join from the current view's players, which are the member's selection.
  - List players found only in the comparison view only when no current-only filter applies, or not at all. A
    player the member's own filter removed should not count as "missing".
  - Name the season in the PREV reasons ("Not in the 2025–26 full season view"), since both sides are
    "Full season" there.

### C6 (low): Breakdown's refusal reason is false for `(a + b) / 2` and `k × (a + b)`

- **Measured**: the pure functions, and the page's picker reason for `(usg + vor) / 2`, both say "Breakdown needs a
  sum of terms; this formula multiplies or divides stats." The formula divides a sum by 2 and multiplies no stats.
  The same sentence refuses:
  - `2 * (usg + vor)`;
  - `-(-(usg + vor))`;
  - `ast - (2 * tov - (stl + blk) / 1)`.
- **Why it matters**: an average of two stats is the most natural additive formula. The reason gives the member a
  wrong account of their own formula. FR-019 wants the reason in words, and it should be true.
- **Suggested fix**, either of:
  - Distribute a constant factor over a sum in `additiveTerms` (`k × (a ± b)`, `(a ± b) ÷ k`, a nested unary
    minus). That is exact: the parts still sum to `evaluate`, and it is the same algebra that already flattens
    `-(a + b)`.
  - At least word the reason by its cause: "…this formula multiplies a sum by a number" vs "…multiplies or divides
    one stat by another".

### C7 (low): a split strip with 1–4 players still prints quartiles

- **Measured**: `split=pos` with one C among five players prints "C · 1 player / 25th 10.00 · median 10.00 · 75th
  10.00".
- The Spread gate is at least 5 values for the whole chart (spec US5), but each strip prints its own percentiles
  regardless.
- **Suggested fix**: below `SPREAD_MIN` in a group, print the n and the values ("1 player: 10.00"), and draw dots
  with no band or median.

### C8 (low): Breakdown's legend rounds coefficients

- **Measured**:
  - `pts / 3 - 0.5 * stl` gives the legend "0.333 × PTS" and "0.500 × STL".
  - `ast / 4` gives "0.250 × AST".
- The values are exact, but the label approximates a formula the member typed exactly. "0.333 × PTS" can't be
  typed back to give the same result.
- **Suggested fix**: keep the term's own text from the tree (`readable(n)` of the term, "PTS ÷ 3"), with the sign
  folded in as now.

### C9 (low, inferred): one render with the previous comparison board after a `cmp` switch

- `useBlock` keeps the old `ok` state until its effect sets `loading`. So the first render after a `cmp` change
  joins the old board.
- Labels come from `cmpD`, so they match the data. The exceptions are PREV's filter rule and prefix, which come
  from `state.cmp`.
- It lasts one commit, then "Loading the comparison…" shows. Not seen in a test.
- Fixing C4 by keying the join on the board's own window and league also closes this.

### C10 (low): Spread keyboard and narrow-container notes

- **Tab stops (read only)**: every dot is a tab stop (`tabIndex={0}`), as in the scatter. With ~200 qualified
  players, more with the split's duplicates, reaching the table below takes hundreds of Tab presses. Consider one
  tab stop with arrow keys, or rely on the table view and take the dots out of the tab order.
- **Narrow containers (read only)**: the SVG is at least 260 px wide and scales down by `max-width: 100%` below
  that. The tooltip is then positioned in unscaled pixels, so it drifts in a container under 260 px (a phone under
  about 300 px). At 375 px the container is about 310 px and nothing scales.
- **Spec gap**: spec US5 says "the players the member picks are highlighted". Today only the top N are. A tapped
  dot shows a tooltip, but nothing stays highlighted. Flag it for T034, or amend the spec.

### US5: checked and held up

- **`additiveTerms` / `contributions` (measured, 17 formulas × 7 players, raw and standardized)**: the largest gap
  between the sum of parts and `evaluate` was 1.8e-15. Cases:
  - Nested minus: `usg - (vor - tov)` → +USG −VOR +TO; `-(usg - (ast - (tov - blk)))` → −USG +AST −TO +BLK.
  - Negation: `-(usg + vor)`; `usg - -vor`.
  - Coefficients: `usg * -2 + -(ast / -4)`; `ast / 4 + 3 - tov * 1.5`; `0 * usg + ast`.
  - Duplicates: `usg + usg - usg`.
  - Constants: `3 - usg`; `usg + (1 + 2)`, which gives two constant parts.
  - Correctly refused: `k / stat`, `stat × stat`, `stat ÷ stat` and `usg / (2 - 2)`.

  In standardized mode a term is `coef × z(stat)` and a constant stays itself, exactly as in `evaluate`. Which rows
  are included or excluded agrees with `evaluate` for every row.
- **`quantiles` (measured)**: [1..5] gives 2/3/4, and [1..4] gives 1.75/2.5/3.25. That is numpy's "linear".
  - The overall median is taken over `ranked.included`: filters and qualification applied, one entry per player.
  - The split duplicates multi-position players in each of their strips only, and the page says so.
- **Availability and the disabled picker (read, plus existing tests)**:
  - `aria-disabled` buttons stay focusable. Enter and Space fire `onClick`, which is guarded, so a disabled chart
    can't be selected by keyboard.
  - A link with `chart=parts` (or spread or change) that doesn't fit draws nothing and says "This chart isn't
    shown: …".
  - Change's standardize gate also covers `z=1` with a formula that is invalid under z (`state.z || display.z`).
- **Requests (read, plus an existing test)**: the comparison fetch's deps are
  `[changeFits, cmpLeague, cmpWin, version]`. Changing the mode, filters, sort or top N, or typing a formula, makes
  no request. Leaving Change makes the block idle.
- **`lastOk` and the main board**: untouched. The US1–US4 page tests pass unchanged.
- **URL (read, plus tests)**:
  - A `cmp` that equals `w`, or is invalid, falls back to `defaultCmp(w)`. It is written only when it differs from
    both.
  - `split` and `chg` round-trip, and unknown values fall back to the defaults.
- **375 px (read, CSS)**:
  - The new rows reuse `.sc-bar-row`'s two-row grid at ≤640 px, with `minmax(0, 1fr)`, and the `.sc-db-row`
    override keeps that.
  - Legends wrap with `overflow-wrap: anywhere`.
  - The five-option chart picker and "Compare with" wrap under the existing ≤700 px `.segmented` rule.
  - `.sc-chart-controls` selects have `min-width: 0`.
  - No new fixed width over 260 px was found. Not yet checked in a real browser (T034).

## Disposition (US5 fix pass, 2026-10-13)

All of C1–C10 were fixed in the web code; none was declined. Finding, change and tests are in `verification.md` under "US5 review fixes (C1–C10)".

- **C1**: Change is (more recent view) minus (older view); the ends are labelled by recency ("Full season → Last 10").
- **C2**: Change is disabled in Totals mode and when the formula reads GP, each with its reason.
- **C3**: the dumbbell uses `--teal` (higher) and `--wr` (lower); the legend says "higher/lower".
- **C4/C9**: rows are drawn only when both boards match the windows the chart names; otherwise "Updating…".
- **C5**: the join starts from the current view's players; the left-out list names the comparison view and season.
- **C6/C8**: a constant factor distributes over a bracketed sum; the legend shows coefficients as typed.
- **C7**: a strip under 5 players prints its count and "too few players for quartiles".
- **C10**: Spread dots are not tab stops (the table is the keyboard path), the tooltip is clamped to the container's real width, and the "players the member picks" highlight was dropped from the spec rather than built.
- Spec US5 and `contracts/url-state.md` carry visible "amended after US5 review" notes. Not yet done: live verification in a real browser (T034).

## Must fix before live verification (T034)

- **C1**: required. T034's "Change values for 3 players against the SEASON and LAST_10 payloads" would pass on the
  inverted sign: both values match the payload, and only their order and colour are wrong.
- **C2**: required. Gate Totals (and GP) out of Change, with a reason.
- **C3**: recommended in the same pass, since it touches C1's lines.
- **C4, C5**: recommended. Both are small, and T034 will click the window control and the filters with Change open.
- **C6–C10**: can ship with a note in `verification.md`. If they are deferred, say so there in those words.
