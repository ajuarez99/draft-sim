# Tasks: Custom stat calculator with a ranked chart

**Input**: design documents in `specs/032-stat-calculator/`: plan.md, spec.md, research.md (R1–R16),
data-model.md, contracts/formula-grammar.md (C1), contracts/url-state.md (C2),
contracts/saved-formulas-storage.md (C3), quickstart.md and plan-review.md (F1–F13, N1–N10).

**Worktree**: `C:\Users\allan\source\draft-sim\.claude\worktrees\032-stat-calculator`, branch
`032-stat-calculator`, cut from `origin/main` e9f71aa. All paths below are relative to that
worktree. The main checkout is on an unrelated branch, so don't build there.

**Frontend only**: there is no Java, no migration and no `api.ts` change (research R1). If a task
seems to need one, stop and say so. That would be a plan change.

**Tests**: included. The plan keeps every rule (grammar, evaluator, units, z-scores, URL,
storage, missing-value reasons) in plain `.ts` modules *so that* they can be unit-tested.
AGENTS.md bug class 1 requires an ordering test for any ranking. Live verification
(quickstart.md) is a separate, required phase, because a green suite is not this project's bar
for "verified".

**Coding agents run on Sonnet** (AGENTS.md). The parent session reads every diff before marking a
task done.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US4, from spec.md

---

## Phase 1: Setup

- [X] T001 Run `cd web && npm ci` in the worktree, which has no `node_modules` (plan review header). Then run `npx tsc -b && npx vitest run && npm run build` and record pass/fail counts in a new `specs/032-stat-calculator/verification.md`, under the heading "Baseline". Also run `npx vitest run src/destinations.test.ts src/pages/StatLeaderboard.test.tsx` and record them separately: these are the two suites this feature's refactors must keep green.

---

## Phase 2: Foundational (blocks every story)

**Purpose**: the shared pieces both pages use. These are extractions, not copies. Each one must
leave the leaderboard and draft room behaving the same, except for the missing-value copy in
T004, which is a deliberate, recorded change.

- [X] T002 [P] Extract the file-local controls of `web/src/pages/StatLeaderboard.tsx` into a new `web/src/components/statControls.tsx`: `SeasonPicker`, `FilterBar`, `Segmented`, `PlayerCell`, `WINDOWS` and `MODES` (lines ~60–296). Two changes:
  - `SeasonPicker` gains a `suffix: '' | '/calculator'` prop and a `keepSearch?: (search: URLSearchParams) => URLSearchParams` prop, so the calculator can carry its query string and strip `own=r:` (C2, F9).
  - `PlayerCell` takes `sport` instead of the whole board, and keeps the "Unknown player" fallback for a null name (N2, N10).

  `StatLeaderboard.tsx` imports them. `web/src/pages/StatLeaderboard.test.tsx` must pass **unchanged** (research R13).
- [X] T003 Add a pure `boardContext(d: StatLeaderboard, leagueId: string)` to `web/src/statLeaderboard.ts`. It returns `{ shownLeagueId, fellBack, nowLabel, asOf }`, moved from `StatLeaderboard.tsx:324-335` (F7). Rewire the leaderboard to use it, and add unit tests in `web/src/statLeaderboard.test.ts`:
  - no fallback;
  - a fallback with `requestedSeason` set, where `shownLeagueId` must be the *shown* season's league id, from `d.seasons`.
- [X] T004 [P] Add `missingReason(col: StatColumn, row: LeaderboardRow, mode: StatMode): MissingReason` to `web/src/statCells.tsx`, per research R15. It returns:
  - for a rate column, its `RateReason`;
  - for a counting column in `per36` with null `per36`, `NO_MINUTES`;
  - for a counting column with `stats.games === 0`, `NO_GAMES`;
  - for a rank or VOR column on an unqualified row, `row.reason` (`NOT_QUALIFIED`/`NOT_QUALIFIED_STALE`, with `lastGameDate`);
  - for `vor` null on a qualified row, `NO_REPLACEMENT_LEVEL`;
  - for `positionRank` null on a qualified row, `NO_POSITION`;
  - otherwise, `UNKNOWN_NULL`.

  Replace `Cell`'s last fallback (`statCells.tsx:~94`, which says "not qualified" for any null) with a call to it, keeping the existing draft-column branches above it. Add a sentence for each new code in `web/src/statCopy.ts`'s `reasonSentence`. Write tests in a new `web/src/statCells.test.ts`, one per branch, including that `fpPerGame` is **not** treated as qualification-gated (F3). If `StatLeaderboard.test.tsx` asserts the old "not qualified" text for a case that is now more specific, update that assertion and list it in verification.md under "Visible changes outside 032".
- [X] T005 [P] Move `shownSeason()` from `web/src/draftRoomStats.ts:~91` into `web/src/statCopy.ts`, re-exported from `draftRoomStats.ts` so its callers are unchanged. The calculator will reuse its `scoringLabel` and `scoringChanged` (FR-012, F8). Existing draft-room tests must pass unchanged.
- [X] T006 Add formula metadata next to `COLUMNS` in `web/src/statLeaderboard.ts`:
  - `FORMULA_TOKEN: Record<string, string>`. Each value is the label when it matches `[A-Za-z0-9%]+`, otherwise the alias: `fp → 'FPG'`, `positionRank → 'POSRANK'`, `pointsRank → 'PTSRANK'`. Add the extra alias `USG%` → `usg` (N5).
  - `FORMULA_UNIT: Record<string, string>`, per research R8 as amended (F11): `pct` for every `format === 'pct'` column; `count:<mode>` for counting columns except `min`; `minutes:perGame` for `min`; `fp` for `fp` and `vor`; `rank` for `leagueRank`, `positionRank`, `pointsRank` and `rankMove`; `games` for `gp`; `gmsc` for `gameScore`. A missing entry falls back to `id:<id>`.
  - `FORMULA_TAG = { vor: 'fantasy' }` (FR-013).
  - `FORMULA_INPUTS`: `PICKABLE`'s columns with `format !== 'text'`.

  Add tests in `web/src/statLeaderboard.test.ts`, **generated from `PICKABLE`**:
  - every input has a token;
  - tokens are unique case-insensitively, aliases included;
  - every token matches `^[A-Za-z0-9%]+$`;
  - no token equals another column's id with a different meaning (F1, F5).
- [X] T007 Widen the `stats` row's `match` in `web/src/destinations.ts:~339` to `/^\/leagues\/([^/]+)\/stats(?:\/calculator)?\/?$/` (research R12). Add the route `/leagues/:sleeperLeagueId/stats/calculator` to `web/src/App.tsx`, next to `:133`, wrapped in `KeyedByLeague`, pointing at a placeholder `StatCalculator` page created in T016. Add a `destinations.test.ts` case asserting that `destinationFromPath('/leagues/X/stats/calculator') === 'stats'`. Run `npx vitest run src/destinations.test.ts`: the App.tsx coverage test ("is league-scoped in App.tsx but no row … matches it") must pass.
- [X] T008 Teach `switchTarget` in `web/src/components/LeagueRailSection.tsx:178` to return the row's `href` + `/calculator` + the current search string, minus `own=r:…`, when `current === 'stats'` and `pathname` ends in `/stats/calculator` (F7, F9). Plumb `search` alongside `pathname` from its callers (`:463`, `:504`). Add rail tests in the existing `LeagueRailSection` test file for:
  - the calculator, where the query is kept and `own=r:3` is dropped;
  - the plain `/stats` page, which is unchanged.

**Checkpoint**: the leaderboard, draft room and rail suites are green, and the calculator route
exists.

---

## Phase 3: User Story 1 - Build a stat and see who leads it (P1) 🎯 MVP

**Goal**: type a formula over leaderboard stats, then see a ranked bar chart and a table, for any
season, window and mode.

**Independent test**: quickstart V2 (10 of 10 hand-checks against the payload) and V3's US1
steps. Build `(PTS + AST) ÷ TO` for 2025–26, check five players against the payload, and check
that the chart order matches the table.

### Tests for US1 (write first, see them fail)

- [X] T009 [P] [US1] Grammar tests in a new `web/src/statFormula.test.ts`, one per C1 row:
  - every error row in `contracts/formula-grammar.md`, each asserting the message and span: `(usg + vor`, `usg + * tov`, `2usg`, `usg + foo`, empty input, `usg +`, `.5`, `1e3`;
  - the valid cases: `usg + -tov`, `usg * -2`, `-(usg)`;
  - lexing traps: `3PM`, `3P%` and `3PAr` lex as stats; `fp/gp` lexes as `fp`, `/`, `gp`; `usgx` is an unknown stat;
  - `−`, `×` and `÷` are accepted;
  - precedence: `2 + 3 * 4` = 14 and `(2 + 3) * 4` = 20;
  - the canonical round trip `(USG+VOR)/TO` → `(usg + vor) / tov`, with readable `(USG + VOR) ÷ TO` parsing back to the same canonical form;
  - a **generated** round trip over every `FORMULA_INPUTS` column: id → readable → parse → id (F1);
  - with `{ standardize: true }`: `usg / tov` is a `ParseError` whose span covers the `÷` and its right side, with the C1 message, while `pts * ts / 100` and `usg / 2` are valid (F2, C1).
- [X] T010 [P] [US1] Evaluator tests in `web/src/statFormula.test.ts`, using `LeaderboardRow` fixtures built the way `StatLeaderboard.test.tsx` builds them:
  - **ordering** (AGENTS.md bug class 1): for `ast / tov`, a player with 10 AST and 2 TO ranks above one with 6 AST and 2 TO, and `dir: 'asc'` reverses that;
  - `÷ 0` gives `DIVIDE_BY_ZERO` with "0 turnovers, so ÷ TO is undefined";
  - a null input gives `INPUT_MISSING` with `missingReason`'s code;
  - no outcome is ever `NaN` or `±Infinity`;
  - a tie breaks on games played, then name, then id;
  - per 36 changes counting inputs but not rate inputs;
  - `min` reads per game in per-36 mode;
  - **standardize** (FR-006, SC-007): over a 4-row fixture group with known values, `standardize` returns the hand-computed population mean, SD and z for each row; a group of 1, or one with SD 0, gives `NOT_STANDARDIZABLE`; a row with a null input is left out of that input's group and excluded with `INPUT_MISSING`; and the group follows the filters and qualification switch.
- [X] T011 [P] [US1] Unit-inference tests in `web/src/statFormula.test.ts` (research R8, F11):
  - `usg + vor` warns, naming both units;
  - `2 * usg + usg`, `ast/tov + stl/tov` and `pts * ts / 100` don't warn;
  - `pts + stl` gives a **spread** note when the fixture SDs differ by more than 3×;
  - `fp / vor` in raw mode gives the zero-denominator warning when a fixture VOR is ≤ 0 (F2).

### Implementation for US1

- [X] T012 [US1] Create `web/src/statFormula.ts`, implementing C1:
  - a lexer that tries the longest STAT match over ids and `FORMULA_TOKEN` values case-insensitively, but only at a token boundary (the next character isn't a letter, digit or `%`), then falls back to NUMBER (`digits ('.' digits)?`);
  - a recursive-descent parser for `expr`/`term`/`unary`/`primary`, with no implicit multiplication;
  - `ParseError { message, start, end }`, using the C1 messages verbatim;
  - `canonical(ast)` over ids, with minimal brackets;
  - `readable(ast)` printed from the tree with tokens and `×`/`÷`. Never use string substitution;
  - `inputsOf(ast)`;
  - `validate(ast, { standardize })`. With standardize on, it raises the standardized-÷ `ParseError` for any `÷` whose right side contains a STAT (C1, F2).

  Make T009 pass.
- [X] T013 [US1] Add evaluation and units to `web/src/statFormula.ts`:
  - `evaluate(ast, row, mode) → { value: number } | { excluded: Reason }`, using the data-model Reason union and short-circuiting on a null input or `÷ 0`;
  - `inferUnits(ast, mode) → string[]`, where constants are dimensionless, `×` and `÷` compose, `X ÷ X` is dimensionless, and `+`/`−` mismatches warn;
  - `spreadNotes(ast, group)`, with the hand-set ratio `SPREAD_RATIO = 3` commented as hand-set, not tuned;
  - `zeroDenominatorWarning(ast, group, mode)`, raw mode only;
  - `standardize(group, inputs, mode) → Record<id, { n, mean, sd } | { notStandardizable: 'GROUP_TOO_SMALL' | 'NO_SPREAD' }>`, using the population SD over the shown group (the rows passing filters and the qualification switch with a non-null value for that input). `evaluate` takes an optional z-map and substitutes `(x − mean) / sd` for each input;
  - `rankResults(rows, ast, mode, dir)`, which returns included rows ordered by value in `dir`, reusing `compareRows`' tiebreak tail (export it from `statLeaderboard.ts` rather than copy it), plus excluded rows grouped by reason.

  Results display at 2 decimals, at 3 significant figures when |v| < 1, and as an integer with thousands separators when |v| ≥ 1000 (N9). Make T010 and T011 pass.
- [X] T014 [P] [US1] Create `web/src/statCalcUrl.ts` implementing C2:
  - `readCalcState(params, rows)` and `writeCalcState(state) → URLSearchParams` for `f`, `n` (≤ 40 chars), `w`, `m`, `top` (clamped to 5–50, default 15), `q` (default `1`), `z` (default `0`), `dir` (`desc`/`asc`, default `desc`), `pos`, `team` and `own`;
  - on read, drop `pos`/`team`/`own` values not present among `rows`' options, and return `dropped: string[]` for the sentence (F9);
  - `stripRoster(params)`, which removes `own=r:*`.

  Tests in a new `web/src/statCalcUrl.test.ts`: defaults, clamping, an unknown team being dropped, a roster id not in the rows being dropped, `f` containing `+` round-tripping through `URLSearchParams` (written as `%2B`), and `stripRoster`.
- [X] T015 [P] [US1] Create `web/src/components/RankedBars.tsx`. Load the project `dataviz` skill **before** writing any chart code.
  - Draw HTML rows with the player name (`PlayerLink`, or "Unknown player" for a null name), a bar whose width is proportional to |value| from a zero baseline, and the exact formatted value beside it.
  - Negative values extend left of the baseline. When the shown values mix signs, the baseline sits at the proportional position; when all are negative, it sits at the right.
  - Use one colour, from the existing tokens.
  - Add a title prop.

  Tests in a new `web/src/components/RankedBars.test.tsx`: all positive, all negative (the leader is the bar closest to zero, with its sign shown), mixed sign, a null name, and that each row's text holds the exact value (F6).
- [X] T016 [US1] Create `web/src/pages/StatCalculator.tsx`:
  - **Loading**: load with `useBlock(() => getStatLeaderboard(leagueId, w), [leagueId, w, version])`. Keep the last `ok` payload in page state (`lastOk`) and show it marked "updating…" while the next one loads (R16, F4).
  - **Gating**: gate the **whole page** only on `d.reason`, using the leaderboard's reason branch, so football, `NO_GAMES` and `NOT_CONFIGURED` get no partial layout (N3). The editor and formula state live outside the data gate.
  - **State**: all state comes from `readCalcState(useSearchParams()…)`. Control changes write with `{ replace: true }`.
  - **Header and nav**: a `PageHeader`, then a `nav` named "Stats pages" with `Link`s "Leaderboard" and "Calculator" using `aria-current` (F13). Then `SeasonPicker` (suffix `/calculator`, with `keepSearch: stripRoster`), the window `Segmented`, the mode `Segmented`, a "Rank by: highest first / lowest first" `Segmented` (F6), the top-N input (5–50), the qualified-only and standardize toggles, and `FilterBar`.
  - **Editor**: a text input plus a stat picker grouped like `PICKABLE`. Each entry shows its label, `title` and the "lower is better" note, plus a "(fantasy)" tag for `vor`. Choosing one inserts the token at the cursor.
  - **Errors**: run on Enter or a Run button. Show `ParseError`s with their position, and keep the previous result labelled "previous formula" (US1 scenario 5).
  - **Readable form**: show it beside the result, with VOR's tag as a styled suffix and `MIN` marked "per game" in per-36 mode (F11).
  - **Readable form, mode**: show the mode beside the readable form, for example "(per 36)". In every mode, mark MIN, FPG, VOR and GmSc as "per game" (US1 scenario 8, F11).
  - **Warnings**: unit warnings, spread notes and the zero-denominator warning, each in words. The qualification sentence also says the rule is the guard against tiny denominators (spec edge case).
  - **Standardize** (FR-006): when on, label the result "standardized" and add one plain sentence explaining it. Show a readout giving each input's group size *n*, mean and SD, and show the table's raw values beside each z-value. Run `validate(ast, { standardize: true })`. When it errors, keep the previous result labelled "previous formula".
  - **Every player excluded**: when no row has a computable value, show one sentence giving why (the most common reason), and no chart (spec edge case).
  - **Sentences**: the scoring sentence from `shownSeason()` (F8); the fallback note and owners line from `boardContext()` (F7); "Showing *n* of *N* players", with the full `statCopy.qualificationRule` sentence including recency (F10); and a sentence naming any filters dropped from the link (F9).
  - **Chart**: `RankedBars` for the top N, titled with the name or readable formula, the season label, the window and the direction (FR-011). Truncate the title with an ellipsis, never the readable form.
  - **Table**: every included row with the result plus one column per input (raw values), sortable with the leaderboard's `SortHeader` pattern, with the player column pinned. The chart never follows the table's sort.
  - **Couldn't compute**: excluded rows listed with their reason sentences.
  - **Player links**: use `boardContext().shownLeagueId`.

  Add CSS beside the leaderboard's `pp-*` rules in the existing stylesheet, following its naming.
- [X] T017 [US1] Page tests in a new `web/src/pages/StatCalculator.test.tsx`, mocking `../api` as `StatLeaderboard.test.tsx` does:
  - typing `(USG + VOR) / TO` renders a chart and a table in the same order, with the unit warning;
  - an invalid formula shows the error and keeps the previous result;
  - the URL round trip;
  - a football board (`reason: 'NOT_BASKETBALL'`) shows only the basketball-only sentence;
  - a fallback board with `scoringMatchesRequested: false` shows the scoring-changed sentence;
  - qualification on shows "Showing n of N";
  - **changing the mode or formula makes no new `getStatLeaderboard` call** (SC-006, F4);
  - changing the window keeps the typed, unrun editor text;
  - standardize on shows the n/mean/SD readout, and `usg / tov` with standardize on shows the error and keeps the previous chart;
  - a formula every row is excluded from shows the "no player has a computable value" sentence and no chart.
- [X] T018 [US1] Add the "Leaderboard · Calculator" nav to `web/src/pages/StatLeaderboard.tsx`, under its header, using the same markup as T016. It keeps `StatLeaderboard.test.tsx` green, because the new nav uses links, not a second "Table" button (F13).

**Checkpoint**: US1 is usable end to end, and every unit and page test is green.

---

## Phase 4: User Story 2 - Start from a ready-made formula (P2)

**Goal**: pick one of five presets, load it into the editor, run it or edit it.

**Independent test**: load each preset. Its formula, name and description agree, and each
produces a chart for 2025–26.

- [X] T019 [P] [US2] Create `web/src/statCalcPresets.ts` with the five presets from research R11. Each has `{ id, name, expr, mode, description, mixesUnits }`:
  - "Usage value": `(usg + vor) / tov`. Its description says the brackets were added to the request "usage rate + vorp / turnovers", which by precedence would read USG + (VOR ÷ TO), and that usage measures role, not quality (F12, N6).
  - "Assist-to-turnover": `ast / tov`.
  - "Stocks per 36": `stl + blk`, mode `per36`.
  - "Efficient volume": `pts * ts / 100`.
  - "Fantasy points per usage point": `fp / usg`, with the usage note.

  Test in a new `web/src/statCalcPresets.test.ts`: every `expr` parses; `canonical(parse(expr)) === expr`; `mixesUnits` equals `inferUnits(...).length > 0`; and every `expr` is valid with standardize **off**.
- [X] T020 [US2] Add a preset list to `web/src/pages/StatCalculator.tsx`, shown when the editor is empty and available from a "Presets" control at other times. Choosing one sets `f`, `n` and `m` (US2 scenario 1, so "Stocks per 36" switches the mode to per 36). Once the member edits the expression, the preset label is dropped (scenario 3). Add tests to `StatCalculator.test.tsx`.

---

## Phase 5: User Story 3 - Keep and share a formula (P2)

**Goal**: copy a link that restores the identical chart, and keep formulas in a list saved in
this browser.

**Independent test**: copy a link, open it in a second signed-in profile, and get an identical
chart (SC-004). Save a formula, reload, and it's listed. In a private window the list is empty,
and the page says why.

- [X] T021 [P] [US3] Create `web/src/statCalcSaved.ts` implementing C3, modelled on `web/src/statChoice.ts`'s try/catch:
  - the key is `bk.statCalc.saved.v1`, with the value `{ v: 1, formulas: SavedFormula[] }`, where `settings` is `{ window, mode, top, qualifiedOnly, standardize, dir }`. Filters are not saved;
  - "at most 50 entries", and saving the 51st drops the oldest and returns a notice;
  - each write re-reads the store first (N7);
  - entries whose `expr` doesn't parse are kept and flagged `stale: true`;
  - a blocked store reads as empty, and a failed write returns `{ saved: false }`;
  - include `rename` and `remove`.

  Tests in a new `web/src/statCalcSaved.test.ts`: round trip; a bad `v` reading as empty; malformed entries skipped; the 51st entry; a stale expression flagged; and `localStorage` throwing.
- [X] T022 [US3] Add to `web/src/pages/StatCalculator.tsx`:
  - a "Copy link" button that writes the full URL with `navigator.clipboard`, with a text fallback the user can select when the clipboard is unavailable;
  - a "Saved in this browser" panel that says, in those words, that it isn't synced to the account or other devices, with save (asking for a name of up to 40 characters), load, rename and delete;
  - stale entries shown as "uses a stat that no longer exists";
  - an empty or blocked store saying it is empty, while share links still work.

  Add tests to `StatCalculator.test.tsx`.

---

## Phase 6: User Story 4 - Compare two stats on a scatter (P3)

**Goal**: plot any two stats, built-in or custom, against each other.

**Independent test**: plot USG against TS% for 2025–26, and check five points against the
payload.

- [X] T023 [P] [US4] Create `web/src/components/StatScatter.tsx`: an SVG with a `viewBox` that scales to its container width.
  - Both axes are labelled with the stat name and unit.
  - Points have a tap target of at least 24 px, and hovering or tapping shows the name and both exact values in a tooltip positioned inside the container, so it never causes page scroll.
  - Excluded rows are not plotted, and their count is stated.

  Load the `dataviz` skill first. Add tests in a new `web/src/components/StatScatter.test.tsx`.
- [X] T024 [US4] Add a "Chart: Ranked | Scatter" control to `web/src/pages/StatCalculator.tsx`. The scatter's axes are each either a built-in input or the current formula. Put the choice in the URL as `chart=scatter&x=<id|formula>&y=<id|formula>`, and add those params to `contracts/url-state.md` and `statCalcUrl.ts` with tests.

---

## Phase 6b: User Story 5 - More charts, each only when it fits (P3) *(added 2026-10-13)*

**Goal**: add Spread (distribution strip, optionally split by position), Change (dumbbell, two
views) and Breakdown (additive contributions). Each is gated by an `availability` rule that
returns either "ok" or a reason sentence.

**Independent test**: spec US5's independent test, checked live in T034.

- [X] T029 [P] [US5] Add the pure helpers to `web/src/statFormula.ts`, with tests in `web/src/statFormula.test.ts`:
  - `quantiles(values) → { n, min, q1, median, q3, max }`, using linear interpolation (the same method as numpy's default, "linear"; state it in a comment). Test it against hand-computed values for odd and even n.
  - `additiveTerms(ast) → { terms: { sign: 1 | -1, coef: number, id: string, text: string }[] } | { reason: string }`. It flattens top-level `+`/`−` and unary minus, and allows `k × stat`, `stat × k` and `stat ÷ k` (k a constant expression) inside a term. Anything else gives a reason such as "Breakdown needs a sum of terms; this formula multiplies or divides stats." A bare constant term is allowed, shown as its own part, "constant".
  - `contributions(terms, row, mode, z?) → { parts: { id, text, value }[], total } | excluded`. **Test that the parts sum to `evaluate(...)` within 1e-9** for several formulas, raw and standardized.
  - `chartAvailability(kind, ctx) → { ok: true } | { ok: false, reason }`, implementing the US5 rules: Spread needs ≥ 5 values; split is allowed only with position filter All; Change needs standardize off, and "previous season" needs an earlier stored season in `d.seasons`; Breakdown needs `additiveTerms` ok. Test each reason.
- [X] T030 [P] [US5] Extend `web/src/statCalcUrl.ts` (C2), with tests in `statCalcUrl.test.ts`:
  - `chart` gains `spread`, `change` and `parts`;
  - `split=pos` for Spread;
  - `cmp` is one of `LAST_10` (default), `LAST_5`, `SEASON` or `PREV` for Change. A `cmp` equal to the current window falls back to the default when the window isn't the season, otherwise to `LAST_10`;
  - `chg=up|down` (default `up`).

  Unknown values fall back to defaults. Update `contracts/url-state.md` with the new rows, marked "(added 2026-10-13, US5)".
- [X] T031 [P] [US5] New components, each with a test file. Load the `dataviz` skill first, and reuse `RankedBars`/`StatScatter` conventions, `--teal` for the primary series, and the existing tokens.
  - `web/src/components/SpreadStrip.tsx`: a horizontal SVG strip sized to its container width. Dots are deterministically jittered (by a seeded hash of the player id, so two loads look identical), with a 24px tap/hover target that shows the name and exact value. The median and quartile ticks are labelled with their values, and highlighted dots (the top N) use the accent with the rest muted. It supports `groups` (one strip per position, with a label, n and median on a shared axis).
  - `web/src/components/ChangeDumbbell.tsx`: HTML rows with the name, two dots on a shared scale, a joining line, and both exact values plus a signed change. The colour encodes direction (rise or fall) using the app's existing up/down tokens, and the direction is also shown by the sign in text, so it isn't colour alone.
  - `web/src/components/ContributionBars.tsx`: HTML stacked bars from a zero baseline, with negative parts to the left. Each part is labelled with its term and value in a legend and a tooltip, and the total is shown beside the bar. Use at most 6 distinct part colours; a formula with more terms groups the smallest into "other", with exact values still in the tooltip.
- [X] T032 [US5] Wire the new charts into `web/src/pages/StatCalculator.tsx`:
  - The chart picker reads **Ranked | Spread | Change | Breakdown | Scatter**. Unavailable ones are disabled, with `aria-disabled` and their reason shown beside the picker (not just in a tooltip).
  - **Change** fetches the comparison board: for `LAST_10`/`LAST_5`/`SEASON` with `getStatLeaderboard(leagueId, cmp)`; for `PREV`, with the previous season's league id from `d.seasons` and the current window. The fetch happens only while Change is shown. Players are joined by `sleeperPlayerId`, and filters and the qualification switch apply in **both** views. Players missing from one view are listed by reason. When the comparison board falls back or uses different scoring, say so with `shownSeason()`.
  - Controls are Split by position (Spread), Compare with (Change) and Biggest rise / Biggest fall (Change), all in the URL.
  - Charts must not drop the `lastOk` hold or the request-free mode change for the main board.

  Add page tests to `StatCalculator.test.tsx`:
  - each disabled reason;
  - Change doing a second `getStatLeaderboard` call only while it is shown;
  - Breakdown parts summing to the result;
  - Spread median text;
  - URL round trip.
- [X] T033 [US5] Bug-hunting review of T029–T032, on the session model, appended to `code-review.md` as "US5 review". Fix its findings with a Sonnet agent.
- [X] T034 [US5] Live verification, appended to `verification.md` under "US5 live":
  - Spread median and quartiles for `ast / tov` against a script over the payload;
  - Change values for 3 players against the SEASON and LAST_10 payloads;
  - Breakdown parts for `usg + vor` against the payload;
  - the disabled reasons;
  - 375px with no sideways scroll for each new chart;
  - screenshots.

---

## Phase 7: Polish, review and ship

- [X] T025 Run `cd web && npx tsc -b && npx vitest run && npm run build`. Record the counts in `verification.md` next to the T001 baseline. Any change in the `StatLeaderboard`, draft-room or destinations suites must be explained there.
- [X] T026 Bug-hunting code review: a separate pass on the session's own model, not a style pass. Write it to `specs/032-stat-calculator/code-review.md`. It must cover the lexer boundary rule, `÷ 0` and null handling, the z-score reference group, URL filter validation, the `lastOk` hold, and `missingReason`'s effect on the leaderboard. Fix findings with a Sonnet agent, and re-run T025.
- [X] T027 Live verification per `quickstart.md`, V2–V5, against a real backend and the dev server **started from this worktree** (check which checkout Vite serves; memory: "Worktree preview serves main"). Record each check's result in `verification.md`. Include:
  - SC-002's 10 of 10 against the payload JSON;
  - SC-003's text scan for `NaN`, `Infinity` and empty cells;
  - SC-004's 3 of 3 link reproduction;
  - SC-005 at 375 × 812 (`scrollWidth <= innerWidth`);
  - **SC-006's warm and cold times for window and season changes, reported as measured, not tuned**;
  - SC-007's 5 z-score hand checks;
  - SC-001, labelled as judged;
  - the rail season switch keeping the formula;
  - a stale `team=ZZZ` link;
  - the leaderboard after the extraction and `missingReason` change.

  Screenshot the chart at desktop and phone widths.
- [X] T028 Update `HANDOFF.md` and `README.md` (the stats page section) with what shipped and what is verified versus assumed. Add a durable lesson to `claude/lessons.md` if the build or verification found one. Ask Allan before committing (AGENTS.md hard rule), and diff what's staged first.

---

## Dependencies and execution order

- **Phase 1 → Phase 2 → stories.** T002, T004 and T005 are independent of each other ([P]). T003 edits `StatLeaderboard.tsx` too, so it runs after T002 (amended at analysis, D1). T006 must come before T009/T012, because tokens are the lexer's vocabulary. T007 needs T016's file, so create a placeholder export first, which T016 then fills. T008 is independent.
- **US1**: tests T009–T011 come first ([P]), then T012 → T013. T014 and T015 run in parallel with T012/T013. T016 needs T012–T015, then T017 and T018.
- **US2** needs T012/T013 (parse, canonical, inferUnits) and T016.
- **US3** needs T014 (URL state) and T016.
- **US4** needs T013 and T016, and can be dropped without affecting anything else.
- **Phase 7** comes after whichever stories ship.

## Parallel examples

- After T001: T002, T004, T005 and T008 together, then T003 once T002 lands.
- In US1: T009, T010 and T011 (one test file, but separate `describe` blocks, so write them together); then T014 and T015 alongside T012 and T013.
- In US2/US3: T019 and T021 together, since neither touches the page.

## Implementation strategy

1. **MVP = Phases 1–3 (US1)**, then the code review (T026) and live verification (T027) for US1
   alone. Report that before going further. A calculator with a chart is the whole request.
2. Then US2 (small) and US3 together, then re-verify the US3 checks.
3. US4 last. If it slips, the spec's P3 marks it as optional.
4. At each stop, verification.md keeps "verified" (run against the real page) visibly apart from
   "assumed" (unit tests only).
