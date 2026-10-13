# Research: Custom stat calculator (spec 032)

All findings below were read from the code at `origin/main` e9f71aa on 2026-10-12, in the
worktree `.claude/worktrees/032-stat-calculator`. **Nothing here has been executed yet.** These
are design decisions made from reading the code, and the build and verification passes must
confirm them.

## R1. Where the data comes from: the leaderboard payload, unchanged

- **Decision**: The calculator calls the existing `getStatLeaderboard(leagueId, window)`
  (`web/src/api.ts:2925`, which is `GET /api/leagues/{id}/stats?window=`) and evaluates every
  formula client-side over `rows`. There is no new endpoint, no backend change, no migration and
  no change to `api.ts` types.
- **Rationale**: Every input the spec allows is already a field on `LeaderboardRow`, reached
  through `COLUMNS[id].value(row, mode)` (`web/src/statLeaderboard.ts`). The page already loads
  ~582 rows for a full season (from spec 022's verification). Evaluating a formula over that
  many rows is negligible client-side work, so SC-006's under-1-second target is dominated by the
  one request that runs on a window change. The leaderboard has the same cost today.
- **Alternatives considered**: A server-side evaluator endpoint. Rejected because it duplicates
  `COLUMNS`' value logic in Java, which makes two implementations of one rule (the class
  `feedback_optional_params_that_encode_rules` and lessons keep recording). It would also add a
  deploy-order hazard between the two independently deployed services.
- **Constitution consequence**: `api.ts` is untouched, so the field-for-field mirror rule is not
  triggered.

## R2. The input set: `PICKABLE`, minus text columns

- **Decision**: The calculator's inputs are `PICKABLE` (`statLeaderboard.ts:171`), which is
  every column in the Basic, Shooting, Advanced and Fantasy groups, filtered to
  `format !== 'text'`. The Draft group is excluded, as the spec says. This is the set spec 023's
  stat picker already uses, so FR-002's "one definition" holds by construction.
- **Rationale**: `PICKABLE` is derived from `GROUPS`. A column added to a leaderboard group
  appears in the calculator with no second edit.
- *(Amended after plan review, F5: **this bullet was wrong.** `PICKABLE` already drops Basic's
  `fgPct` and `ftPct` (`statLeaderboard.ts:174`), so its labels are unique. `FG%` means `fgPctMA`.
  The original text is kept below for the record.)*
- ~~**Two duplicate labels found**~~: `fgPct` (Basic) and `fgPctMA` (Shooting) are both labelled
  `FG%`, and `ftPct` and `ftPctMA` are both labelled `FT%`. Each pair reads the same value
  (`r.stats.shooting.fgPct`). Labels therefore can't serve as unique identifiers (see R3).

## R3. Formula form: typed expression over column ids, with label aliases

- **Decision**:
  - The canonical form, used in the URL and saved formulas, is an expression over column
    **ids**, for example `(usg + vor) / tov`.
  - The editor is a text field. A stat picker inserts tokens, and the field also accepts labels
    typed by hand (`USG`, `VOR`, `TO`, `3PM`, `FG%`), case-insensitively.
  - ~~A label shared by two columns resolves to the Basic-group column.~~ *(Amended after plan
    review, F5: no label is shared, see R2. A test asserts that formula names are unique.)*
  - *(Amended after plan review, F1)* Each input has one **formula name**. It is the label when
    the label matches `[A-Za-z0-9%]+`; otherwise it is a hand-set alias. Measured against
    `PICKABLE`'s labels, three need one: `FP/G` → `FPG`, `Pos rank` → `POSRANK`,
    `Pts rank` → `PTSRANK`. `USG%` is accepted as an extra alias of `USG` (N5). The readable form
    is printed **from the parse tree** using formula names, never by string substitution. VOR's
    "(fantasy)" tag is rendered beside the token, outside the text.
  - Readable display renders ids back to labels, with `×` and `÷`.
  - The grammar is in `contracts/formula-grammar.md`.
- **Rationale**: Ids are stable and unique, so links survive a label rename. Accepting labels
  keeps typing natural.
- **Tokenizer trap, recorded before code**: `3PM` and `3P%` start with a digit. The tokenizer
  must try the identifier alternatives before the number rule. Otherwise `3PM` lexes as
  `3`, then `PM`, which is an unknown stat, or worse, an implicit product.
- **Alternatives considered**: A chip-only builder with no free text. It is safer against parse
  errors, but brackets and constants become awkward, and FR-004 (errors with position) would be
  moot rather than met. Rejected.
- *(Amended after plan review, F1)* **Token boundary**: a STAT match counts only if the next
  character is not a letter, digit or `%`. Otherwise the lexer tries the next-longest match.
  This is what keeps `fp/gp` from being read as `FP/G` followed by `p`.
- **No implicit multiplication.** `2usg` is an error ("put an operator between 2 and USG"), not
  `2 × usg`. That keeps one reading per formula.

## R4. Precision: evaluate the payload's full values, not the displayed ones

- **Finding**: `formatValue` rounds for display. Counting stats show 1 decimal, fantasy stats
  show 2, and percentages show 1 decimal followed by `%`. The payload carries more precision
  than that.
- *(Amended after plan review)* `fpPerGame` and VOR are already rounded to 2 decimals by the
  server (`ReplacementLevel.java:45,129`), and VOR displays at 1 decimal. "Full precision"
  means "whatever the payload carries".
- *(Amended after plan review, N9)* Large values (totals mode, × 1000) are shown as integers with
  thousands separators once |value| ≥ 1000.
- **Decision**: The calculator evaluates on the payload's full-precision values, the same
  numbers the leaderboard sorts by. Results are displayed at 2 decimals, or 3 significant
  figures when |value| < 1.
- **Spec consequence**: SC-002 as written ("computed by hand from the leaderboard's displayed
  values matches … to the displayed precision") will **fail on purpose** for ratios. For
  example, `AST ÷ TO` with displayed 9.0 and 1.0 but payload values 8.96 and 1.04 gives 9.00
  from the display and 8.62 from the calculator. SC-002 is amended to check the payload's values
  exactly, plus a looser check against the displayed values. The amendment note is in the spec.
- **Percentages are 0–100, not 0–1** (`formatValue` 'pct' prints `v` followed by `%`). So
  `PTS × TS% / 100` is the right form, and the preset in R11 uses it.

## R5. Undefined results: exclude with a reason, never coerce

> **Amended after plan review, F3.** The null rules below were wrong in three ways:
> - `fpPerGame` is **not** null for an unqualified player (`PlayerStatsService.java:156-157`);
> - VOR **can** be null for a qualified player whose positions have no replacement level
>   (`ReplacementLevel.java:113-129`);
> - counting stats (per 36 at 0 minutes, `min`, game score) can be null too.
>
> "With the switch on, this case can't happen" is struck. The replacement is R15: one
> reason function, shared with the leaderboard's `Cell`, so the table cell and the exclusion
> list can't disagree.

- **Decision**: A player is excluded from ranking, and listed under "Couldn't compute", when:
  1. an input reads `null`. The reason comes from the input:
     - a `Rate` column uses its `RateReason`, through the existing `statCopy` sentences;
     - VOR, FP/G and the rank columns are null unless `qualified` (`api.ts:2880`), so their
       reason is "not qualified, so no VOR". With the qualification switch on, this case can't
       happen for those inputs.
  2. a division has a denominator of exactly 0, for example "0 turnovers, so ÷ TO is undefined";
  3. any intermediate value is not finite. This is a defensive check.
- **Rationale**: FR-007 and SC-003. The evaluator returns a tagged result
  (`{ ok: number } | { why: Reason }`), so the UI can't render a missing value as 0.

## R6. Qualification: on by default for every formula

- **Decision**: "Qualified players only" defaults to on for every formula, whatever its inputs,
  and can be turned off. The rule sentence comes from `statCopy.qualificationRule`, the same as
  on the leaderboard.
- **Rationale**: On the leaderboard, `qualificationApplies` bites only on rate and rank columns
  (`statLeaderboard.ts:290`). For a custom formula that test is too weak: `AST ÷ TO` uses two
  counting stats, yet a 3-game player with 0.3 turnovers tops it. That is exactly the spec's
  small-denominator edge case. The leaderboard's per-column rule can't see what a formula does
  with its inputs, so the calculator uses the simpler blanket rule and says so.
- **This differs from the leaderboard**, deliberately. The page states that the rule applies to
  every formula.
- *(Amended after plan review, F10)* **The rationale above is wrong.** The calculator has the
  parsed formula, so a formula-aware rule *could* see what the formula does. The blanket default
  is kept for different reasons:
  - in a ranked top N, a short sample is noise whatever the operators;
  - one rule is easier to state than a rule that changes as you type;
  - it costs nothing in totals mode.

  What it hides must be visible. The page says "Showing *n* of *N* players", where 2025 SEASON
  was 303 of 582 in spec 022's measurement. It also gives `statCopy.qualificationRule` verbatim,
  including the last-5/last-10 recency clause (`NOT_QUALIFIED_STALE`). The leaderboard's "applies
  to rate and rank columns" sentence is **not** reused. A player below the threshold is labelled
  with `reasonSentence(row.reason)`, not the player page's minutes-based `smallSample` flag.

## R7. Standardizing: z-scores over the shown reference group

- **Decision**: With "standardize inputs" on, each input `x` becomes `(x − mean) / sd`:
  - the reference group is the rows that pass the current filters and qualification switch and
    have a non-null value for that input;
  - `sd` is the population standard deviation;
  - if a group has fewer than 2 players, or `sd = 0`, the input can't be standardized, and every
    player is excluded with that reason;
  - lower-is-better inputs are not flipped, as the spec says;
  - the page names the reference group and its size.
- *(Amended after plan review, F2: the warning below is **replaced by a block**.)* With
  standardize on, a `÷` whose right-hand side contains any stat is a validation error, raised
  through the FR-004 path with its position. Dividing by a constant stays allowed. If standardize
  is switched on with such a formula present, the last result stays, labelled "previous formula".
  In raw mode, if the denominator's values over the reference group include anything ≤ 0, the
  page warns in words. One rule, "the denominator crosses zero in this group", covers the
  case where blocking isn't warranted.
- ~~**Ratios under standardization**~~ (superseded): a z-scored denominator crosses zero for about half the
  players. As FR-006 says, denominators ≤ 0 exclude the player. That alone would still let
  near-zero denominators explode. So whenever a standardized input sits under a `÷`, the page
  also shows a warning in words ("dividing by a standardized stat is unstable near the average;
  consider × or + instead"). This is a hand-written guard and is labelled as one.
- **Rationale**: SC-007 needs a hand-checkable definition. Mean and population SD over a named
  group is the simplest definition a reader can reproduce.

## R8. Detecting mixed units: small unit inference over the expression

- **Decision**: Each input carries a unit class derived from its column:
  - `pct` (percentage points);
  - `count:<mode>` (counting stats, which follow the mode);
  - `fp` (`fp`, `vor`);
  - `rank` (ranks and rank move);
  - `games` (`gp`);
  - `minutes` (`min`);
  - `gmsc` (game score).

  Constants are unitless and take on the unit of whatever they meet. `×` and `÷` produce a
  composite unit, `+` and `−` require equal units, and a mismatch produces a warning (not an
  error) naming both sides, for example "USG (percentage points) + VOR (fantasy points per
  game)". The user's own example triggers it, which the spec wants.
- **Not a blocker**: The formula still runs. The spec chose raw-by-default with a switch, so the
  warning points at the switch.
- *(Amended after plan review, F11)* Three corrections:
  1. **Constants are dimensionless.** `unit(k × X) = unit(X)`, `unit(X ÷ k) = unit(X)`,
     `unit(k ± X) = unit(X)`, and `unit(X ÷ X)` = dimensionless. So `2 * usg + usg` and
     `ast/tov + stl/tov` don't warn.
  2. **`min`, `fp`, `vor` and `gameScore` are per game in every mode.** `min` is
     `counting: true`, but in per 36 its value is minutes per game (`statLeaderboard.ts:94`). Its
     unit is `minutes:perGame`, and in per-36 mode the readable form and table header mark it "per
     game".
  3. **Same unit, different spread**: decided, **in scope as a note**. When the standard
     deviations of two added inputs differ by more than 3× over the reference group, the page says
     the larger one will dominate. The 3× ratio is hand-set and labelled as such in the copy and in
     `weights`-style comments. It is not tuned to any measurement.
- **Unit classes are hand-assigned** in one map next to `COLUMNS`. A new column without a class
  falls back to a unit of its own id, so it warns rather than silently matching.

## R9. Charts: hand-rolled, no new dependency

- **Decision**:
  - **Ranked bars (US1)**: HTML rows with a CSS width proportional to |value|, from a zero
    baseline. When the top N mixes signs, negative values extend left of the baseline. The exact
    value and the player name sit beside each bar. One colour is used, because bar length is the
    only encoding.
  - **Scatter (US4)**: an SVG with a `viewBox`, labelled axes, and points with a tap target of at
    least 24 px. Hover or tap shows the name and both exact values.
- **Rationale**: `web/package.json` has only react, react-dom and react-router-dom. Existing
  charts (`components/BumpChart.tsx`, `RankBoard.tsx`) are hand-drawn SVG, so adding a charting
  library for one bar chart would be a new pattern. HTML bars reflow at 375 px with no
  `viewBox` maths, which serves SC-005.
- The project's `dataviz` skill is to be loaded at build time, before any chart code is
  written.

## R10. Share links: all state in search params

- **Decision**:
  - The season lives in the path, `/leagues/:sleeperLeagueId/stats/calculator`, so a season
    switch is a navigation, exactly as on the leaderboard (`StatLeaderboard.tsx:285`).
  - Everything else goes in search params: formula, name, window, mode, top N, qualified,
    standardize, position, team and availability. See `contracts/url-state.md`.
  - The leaderboard keeps its state in React only, so this is new. The calculator uses
    `useSearchParams`, as `WeeklyReport.tsx` and `DraftView.tsx` already do.
- **Rationale**: FR-015 plus SC-004 (an identical chart) need every input that changes the
  chart in the link. Filters change the chart, so they are included even though FR-015 didn't
  list them. This is recorded as an amendment.
- ~~**A roster filter in a different league**: the `rosterId` may not exist there.~~
  *(Amended after plan review, F9)* Roster ids are 1..N in **every** season, so "doesn't exist"
  never fires within a chain. The rules are now:
  - on read, `pos`, `team` and `own` values that aren't among the current rows' options are
    dropped, with one sentence naming them;
  - the in-page season picker strips `own=r:` when it changes season;
  - a `+` in `f` round-trips, which is tested.
- *(Amended after plan review, F6)* A `dir` param (`desc` by default, or `asc`) is part of the
  link.
- *(Amended after plan review, F7)* The **rail's** season switch (`switchTarget`,
  `LeagueRailSection.tsx:178`) returns the row's `href`, which is `/stats`, and so dropped the
  calculator. `switchTarget` learns to keep `/calculator` and the search string when the current
  destination is `stats` and the pathname ends in `/calculator`, with a rail test. The player
  row's pathname handling (`:182-186`) is the precedent.

## R11. Presets (US2)

| Name | Formula | Mode | Note |
|---|---|---|---|
| Usage value (your example) | `(usg + vor) / tov` | per game | Mixes units, so it triggers the R8 warning. The description says brackets were added (F12), and that usage measures role, not quality (N6) |
| Assist-to-turnover | `ast / tov` | per game | |
| Stocks per 36 | `stl + blk` | per 36 | |
| Efficient volume | `pts * ts / 100` | per game | |
| Fantasy points per usage point | `fp / usg` | per game | Usage note (N6) |

- **Amendment**: The spec named "points per shot attempt" as an example preset. It needs
  FGA and FTA, which are in `PlayerCounting` (`api.ts:2690`) but are **not** leaderboard
  columns. FR-002 limits inputs to columns, so that preset is replaced by "Efficient volume".
  Adding FGA and FTA as columns would be a leaderboard change, out of scope.
- A preset carries its own mode, so loading "Stocks per 36" switches the mode to per 36.

## R12. Route and navigation

- **Decision**:
  - The calculator's route is `/leagues/:sleeperLeagueId/stats/calculator`, wrapped in
    `KeyedByLeague` like its sibling (`App.tsx:133`).
  - The `stats` destination's `match` (`destinations.ts:339`) widens to
    `/^\/leagues\/([^/]+)\/stats(?:\/calculator)?\/?$/`, so the rail keeps "Player stats"
    highlighted on the calculator.
  - ~~A "Table · Calculator" switch~~ *(Amended after plan review, F13)*: a `nav` named "Stats
    pages" with two `Link`s, "Leaderboard" and "Calculator", using `aria-current`. The
    leaderboard already has a "View: Table | Stat leaders" control, so a second "Table" button
    would be ambiguous.
  - There is no new rail item.
- **Why it matters**: `destinations.test.ts:220` fails the build if a league-scoped route in
  `App.tsx` has no matching destination. Widening the existing row satisfies it without adding
  a rail entry.

## R13. Shared pieces from the leaderboard page: extract, don't copy

- **Finding**: `SeasonPicker`, `FilterBar`, `Segmented`, `PlayerCell`, `OwnerCell` and the
  `WINDOWS`/`MODES` lists are file-local in `pages/StatLeaderboard.tsx`.
- **Decision**: Move the ones the calculator needs into `web/src/components/statControls.tsx`
  (`SeasonPicker`, `FilterBar`, `Segmented`, `PlayerCell`, `WINDOWS`, `MODES`), and import them
  in both pages. `SeasonPicker` gains a path suffix parameter (`''` or `'/calculator'`).
  Behaviour on the leaderboard must be unchanged, and its existing tests are the guard.
- *(Amended after plan review, F7, F8, N10)* Also extracted, as pure functions, not copied:
  - `boardContext(d, leagueId)` into `statLeaderboard.ts`, returning
    `{ shownLeagueId, fellBack, nowLabel, ownershipLine }`. These are the leaderboard's inline
    lines at `StatLeaderboard.tsx:324-335`, and both pages use it. A wrong `shownLeagueId` would
    send player links to 2026 while showing 2025 numbers.
  - `scoringSentence(d)` into `statCopy.ts`, from `draftRoomStats.ts:94-95`. The draft room, the
    leaderboard and the calculator then share one sentence.
  - `PlayerCell` takes `sport`, not the whole board (N10). It keeps the "Unknown player" fallback
    for a null name (N2).

## R14. Saved formulas (US3): `localStorage`, versioned key

- **Decision**: The key is `bk.statCalc.saved.v1`, with the value
  `{ v: 1, formulas: SavedFormula[] }`, capped at 50 entries (oldest dropped, with a sentence).
  It uses the same try/catch discipline as `statChoice.ts`: a blocked store reads as empty, and
  a failed write keeps the in-memory list for the page load and says it wasn't saved.
- *(Amended after plan review, N7)* Two tabs saving at once: last write wins. Each write re-reads
  the store first, which narrows the race, and C3 says so.
- **Validation on read**: entries whose expression no longer parses are kept but marked "uses a
  stat that no longer exists", rather than silently dropped.

## R15. One missing-value reason function *(added after plan review, F3)*

`missingReason(col, row, mode)` lives next to `statCells.Cell`, and `Cell` calls it, so the two
can't disagree. It returns:

- for a rate column: the `RateReason`;
- for a counting column in per 36 with null `per36`: `NO_MINUTES`;
- for a counting column with no games: `NO_GAMES`;
- for a rank or VOR column on an unqualified row: `row.reason` (`NOT_QUALIFIED` /
  `NOT_QUALIFIED_STALE`);
- for VOR null on a qualified row: "no replacement level at his position";
- for `positionRank` null on a qualified row: "no listed position";
- otherwise: `UNKNOWN_NULL`. A test asserts this never occurs on the fixtures.

`Cell`'s current last fallback, which says "not qualified" for any null (`statCells.tsx:95-96`),
is replaced by this function. That fixes the same mislabel on the leaderboard. The leaderboard
tests must still pass, and any visible copy change there is listed in the build notes.

## R16. Data reloads don't unmount the editor *(added after plan review, F4)*

`useBlock` goes to `loading` on every dependency change (`useBlock.ts:17`), and the leaderboard
gates the page on `d`. The calculator:

- gates the **whole page** only on `d.reason`, covering football, `NO_GAMES` and
  `NOT_CONFIGURED`. That keeps US1 scenario 9's "no partial layout" (N3);
- keeps the editor, the formula state and "previous formula" outside the data gate;
- holds the last `ok` payload on screen, marked "updating…", while the next one loads.

The hold is page-local state (`lastOk`), not a change to `useBlock`, so no other page is
affected. SC-006 is split: mode and formula changes are request-free, which a Vitest test
asserts, and window/season changes are timed live, warm and cold, with the result reported.

## Open items for the adversarial review

*(Resolved 2026-10-12 by `plan-review.md`: R6 is kept with a corrected rationale (F10), and R7
now blocks instead of warning (F2).)*

- R6's blanket qualification differs from the leaderboard's per-column rule. The review should
  decide whether that inconsistency confuses more than it protects.
- R7's division warning is a hand-written guard. The review should decide whether `÷` should be
  disabled outright in standardized mode instead.
