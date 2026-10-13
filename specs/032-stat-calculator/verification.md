# Verification: spec 032 stat calculator

## Baseline (T001, 2026-10-12, worktree at e9f71aa, before any 032 code)

- `npm ci`: 159 packages added.
- `npx tsc -b`: clean, exit 0.
- `npx vitest run`: **112 files, 1784 tests passed**, 0 failed (142.9 s).
- `destinations.test.ts` and `pages/StatLeaderboard.test.tsx` are included in the 112 passing files. They are the
  suites the T002–T008 refactors must keep green.
- `npm run build`: not run at baseline (it is run at T025).

## Phase 2 (T002–T008)

- `npx tsc -b`: clean, exit 0.
- `npx vitest run`: **113 files, 1810 tests passed**, 0 failed (baseline 112 / 1784: +1 file `statCells.test.tsx`,
  +26 tests: 11 `missingReason`/`Cell`, 3 `boardContext`, 7 formula metadata, 1 `destinations`, 4 rail).
- `pages/StatLeaderboard.test.tsx`, `draftRoomStats.test.ts` and the existing rail/switcher tests passed with no
  assertion edited. Nothing existing was changed to make this phase pass.
- Not run: `npm run build` (T025) and any live check (Phase 7). The extractions are covered by the suites above,
  not by a browser.

### Visible changes outside 032

Only the missing-value copy in `statCells.Cell` (R15), which the leaderboard table and the draft room's stats
table both render. The cell still shows a dash (or the existing short text); the sentence in its tooltip and
`aria-label` is what changes. Old behaviour: any null that wasn't a rate, a draft column or per-36 counting said
"Hasn't played enough games or minutes to be ranked" (or the row's stale-date sentence).

- Qualified player, no position rank: now "He has no listed position, so there is no position rank."
- Qualified player, no VOR: now "There is no replacement level at his position, so there is no value over
  replacement."
- Qualified player, any other null rank/FP column: now "No value is available for this figure." (`UNKNOWN_NULL`).
  Not expected on real data; the fixtures never produce it.
- A counting column on a zero-game row (e.g. MIN): now "No games have been played this season yet." A row only
  exists with a game in the window, so this is not expected to appear.
- Unchanged: unqualified rows (still their own reason), rate columns (still their `RateReason`), per-36 counting
  (still "no min."), and the draft-value, pick and ADP columns.

## US1 (T009–T018)

- `npx tsc -b`: clean, exit 0.
- `npx vitest run`: **117 files, 1903 tests passed**, 0 failed (Phase 2: 113 / 1810). New: `statFormula.test.ts`, `statCalcUrl.test.ts`,
  `components/RankedBars.test.tsx`, `pages/StatCalculator.test.tsx`; added cases in `statCells.test.tsx` (zero-game row, any column) and
  `StatLeaderboard.test.tsx` (the Stats pages nav). No existing assertion was edited.
- `npm run build`: succeeds (the chunk-size warning is the existing one).

**Only unit-tested, not live-verified** (live verification is T027):
- the formula grammar, evaluator, units, z-scores and ranking (plain functions, fixtures built with `testStatBoard`);
- the page against a mocked `getStatLeaderboard`: chart/table agreement, error + "previous formula", URL round trip, football gate,
  fallback scoring sentence, "Showing n of N", no new request on mode/formula/filter/standardize change, `lastOk` hold
  during a window reload, standardize readout, all-excluded sentence, excluded list;
- layout: `.sl-wrap` / `.sl-pin` classes are asserted, but no real width was measured. The 375 px no-sideways-scroll claim (SC-005),
  the phone bar layout, the chart colour in the real theme, and the dataviz look are **unchecked** in a browser;
- real-payload behaviour: SC-002 (10 of 10 against the payload), SC-003 (scan for NaN/Infinity/blank), SC-004, SC-006 timings, SC-007 are not run.

**Deviations / decisions to know about**
- `missingReason`: `games === 0` now returns `NO_GAMES` first for any column (Phase 2 review fix); rate columns on a zero-game row therefore say `NO_GAMES` rather than their own `RateReason`.
- MIN is a season total in totals mode, so there `inferUnits` treats it as `count:totals` and the "per game" tag is not shown for it (the spec lists MIN as per game in every mode; that is only true in per game / per 36).
- `parse` / `validate` return `ParseError[]` (one element from the parser, possibly several from `validate`).
- Chart colour is `--teal`, the player page's meter colour. The stylesheet header says teal is "never data", but `.pp-meter-fill` already uses it; the palette validator was not run for a single series.
- The qualification default is the blanket "on for every formula" (R6); the count shown is after filters, so with a position filter "Showing n of N" counts only that position.

## US2–US4 (T019–T024)

- `npx tsc -b`: clean, exit 0.
- `npx vitest run`: **120 files, 1977 tests passed**, 0 failed (US1: 117 / 1903). New files: `statCalcPresets.test.ts`,
  `statCalcSaved.test.ts`, `components/StatScatter.test.tsx`; added cases in `StatCalculator.test.tsx` (presets, copy link,
  saved list, scatter), `statCalcUrl.test.ts` (chart/x/y) and `statFormula.test.ts` (`unitLabel`). One existing assertion was
  extended: the "reads every parameter" case in `statCalcUrl.test.ts` now also expects `chart: 'ranked', x: '', y: ''`.
- `npm run build`: succeeds (the existing chunk-size warning only).

**Only unit-tested, not live-verified** (live verification is T027):
- presets, the preset-label drop, standardize going off when a preset would be blocked;
- copy link (mocked `navigator.clipboard`, and the selectable fallback); no real clipboard permission prompt was seen;
- the saved list against jsdom `localStorage`, including blocked storage by throwing mocks; a real private window is unchecked;
- the scatter: dots, labels, tooltip clamping (asserted from computed `left`/`width` at the 480 px fallback width), the not-plotted count.
  jsdom has no layout, so the 375 px no-sideways-scroll claim, the real measured viewBox, the 24 px tap target on a touch device,
  the dot colour and the dataviz look are **unchecked in a browser**. The palette validator was not run (one series, `--teal`).

**Deviations / decisions**
- Scatter axis for the current formula is the literal `f` (contract C2 amended), not the formula text.
- `unitLabel(ast, mode)` added to `statFormula.ts` (refactor of `inferUnits` into a shared walk) for axis units.
- Loading a preset turns standardize off only when the preset would be blocked by it (a divide by a stat).
- A name equal to a preset's name with that preset's exact expression counts as "the preset label"; changing the expression clears `n`.


## Code-review fixes (B1–B11)

Fix pass for `code-review.md` (T026). Frontend only. Labels: **executed** = run by the test named; nothing here was driven in a real browser (T027 still owes that).

| Finding | What changed | Test |
|---|---|---|
| B1 (high) | `validate` (`statFormula.ts`) now rejects a `×` with a stat on both sides when standardize is on (`STANDARDIZED_MULTIPLY_MESSAGE`); a stat times a constant stays valid. `loadPreset` already turns standardize off when `validate` fails, and now says so in a note. `loadSaved` runs `validate` with the saved settings and shows the error instead of a chart. Contract C1, spec FR-006, data-model and quickstart carry visible "amended after code review, B1" notes (the earlier claim that `pts * ts / 100` is valid standardized was wrong). | `statFormula.test.ts`: "blocks multiplying two stats…", "allows a stat times a constant…", **"ORDERING: a below-average player never outranks the leader under standardize"** (executed: the old behaviour put Bad first; the formula now errors; raw ranks Star first and Bad last; `pts + ts` standardized ranks Star first). `StatCalculator.test.tsx`: B1 x3 (link error, preset note, saved load). |
| B2 | `lastWrittenF` ref records the `f` this page wrote; an effect on `state.f` treats any other change as authoritative: editor text resyncs, `runError` clears, and the held previous formula (`lastGood`) clears. | "B2: clicking the Calculator tab…" (executed; also confirmed to fail with the effect disabled). |
| B3 | Lexer rejects a non-finite number literal ("That number is too large"). `validate`, in both modes, rejects a formula with no stat ("Use at least one stat…") and a `÷` by a constant that equals 0 ("Dividing by 0 is undefined"). `formatResult` returns "—" for a non-finite value as a last guard. C1 rows added. | `statFormula.test.ts` error-table rows, "requires at least one stat…", "rejects dividing by a constant 0…"; page test B3 (also scans for NaN/Infinity/∞). |
| B4 | `formatResult` rounds first, then picks the style: 999.995/999.996 read `1,000`; below 0.000001 it prints `< 0.000001` (or `> −0.000001`), never exponent notation. A column is not forced to one width; it does get separators from 1,000 up after rounding (**not** a fully consistent column style: a column can still mix `896.00` and `1,984`, which is by N9). | `formatResult (N9)` rows, "formatResult never prints an exponent (B4)". |
| B5 | Warning list and axis notes are de-duplicated (`new Set`) and keyed by their text. | "B5: a repeated warning is listed once…" (spies on `console.error` for "same key"). |
| B6 | `min` in totals mode has its own unit `minutes:totals` ("total minutes"), so `pts + min` warns there. Data-model wording amended visibly. The earlier deviation note under "Deviations / decisions to know about" (MIN treated as `count:totals`) is superseded by this. | `statFormula.test.ts`: "min is per game outside totals mode, a total inside it" (updated). |
| B7 | The standardization sentence now says "the average of the players shown who have a value for it", with "each input's count is listed below, out of N players shown". | "B7: the standardization sentence…". |
| B8 | The calculator's table headers and scatter axis labels print the formula token (FPG, POSRANK, PTSRANK); the header's tooltip names both spellings. Lexer: typing the printed label (`FP/G`, `Pos rank`, `Pts rank`) says "write FPG"; `3PA` is reported whole; `1.5.5` says it has two decimal points. | `statFormula.test.ts` error-table rows; "B8: the table header and the scatter axis print the formula token…". |
| B9 | The zero-denominator warning is a whole sentence: "VOR is zero or negative for 2 of 3 players shown, and the formula divides by it: …". Several parts join with a space. | `zeroDenominatorWarning` test (updated, with a sentence-shape match). |
| B10 | `writeCalcState` keeps `x` and `y` in the link on the Ranked view too (C2 amended: they are short), so a Ranked/Scatter toggle keeps them. When the axis is the formula and there is none, the select shows a disabled selected "Your formula (none yet)". | `statCalcUrl.test.ts` (updated); "B10: toggling Ranked and back…", "B10: with no formula the formula axis reads…". |
| B11 | The formula input has `aria-invalid` and `aria-describedby` pointing at the error block (`id="sc-formula-error"`). | "B11: the formula input is marked invalid…". |

**Declined / judgement calls**
- B4 column consistency: only the separator fix was done, as the finding allowed ("at minimum").
- B3 constant `÷ 0`: also catches a constant that evaluates to 0 (`usg / (2 - 2)`), not only the literal `0`; a denominator like `usg - usg` is left to the runtime `DIVIDE_BY_ZERO` exclusion because it contains a stat.
- B10 changes contract C2 (`contracts/url-state.md` rows for `x` and `y`, amended visibly).

**Suites after the fixes** (executed): `npx tsc -b` clean; `npx vitest run` 120 files, **2017 tests** passed (was 1977); `npm run build` succeeds (chunk-size warning only).

## Live verification (T027, 2026-10-13)

**Setup, as run**:
- Docker Desktop started, then `docker compose up -d` (Postgres only) from the main checkout, so it is the
  `draftsim-pg` container on 5433. The unrelated Postgres on 5432 was left alone.
- Backend: `./gradlew.bat bootRun` **from this worktree** (e9f71aa plus the uncommitted 032 frontend; no Java
  change). `/api/health` reported `weightsLoaded` and `playerStatsLoaded` true.
- Frontend: `npx vite --port 5179` **from this worktree**. It was started with Bash rather than preview_start,
  because preview_start reads the main checkout's launch.json (memory: "Worktree preview serves main").
- Signed in by setting `bk.user.v1` to a 2025 league member's Sleeper id (the app has no password).
- League: "Ball Knowers" NBA 2025 (`1229352720222134272`). The payload has 582 rows, 303 qualified, and
  `dataAsOf` 2026-09-28. That matches spec 022's measurement.

| Check | Result | How |
|---|---|---|
| SC-002, hand-check vs payload | **PASS 11/11** | A Python script over the saved payload JSON, independent of the app's code. `ast / tov`: Wade 5.50, Conley 4.62, Kornet 4.50, McConnell 4.48. `pts * ts / 100`: SGA 20.71, Dončić 20.61, Jokić 18.53, Edwards 17.78. `(usg + vor) / tov`: Hardaway 26.88, Ware 25.96, Bey 25.93. Order matches |
| SC-003, no NaN/Infinity/∞ | **PASS** | Text scan on `usg / tov` (z=1), `usg + ts` (z=1) and the five-input formula at top 50 |
| SC-004, link reproduces the chart | **PASS 3/3**, with a caveat | The same URL (LAST_10, per36, top 20, pos PG, asc) was loaded 3 times and the chart text was identical. All three loads were in the **same browser profile**, not a second signed-in profile as the quickstart asks |
| SC-005, 375px | **FAIL, then fixed, then PASS** | See "Bug found live" below. After the fix: `scrollWidth` 375 = `innerWidth` 375, with 50 bars and a five-input formula |
| SC-006, timings | **PASS** | Window change, click to chart: Last 10 202 ms, Season 288 ms, Last 10 again 189 ms, Last 5 187 ms. Mode change (per 36): 27 ms, no request. "Cold" here means first request for that window in a warm server, not a cold JVM. A season change (navigation) was not timed separately |
| SC-007, standardize hand-check | **PASS 5/5** | `usg + ts` with z=1. The page shows USG n 303, mean 19.63, SD 5.45 and TS% n 303, mean 58.52, SD 4.85; Python's `pstdev` gives the same. Top 5 match (SGA 4.25, Dončić 4.06, Jokić 3.68, Leonard 3.50, Curry 3.46) |
| Standardized ÷ blocked | **PASS** | `usg / tov` with z=1 shows the C1 message, with no chart and no table |
| Mixed-units warning | **PASS** | `(USG + VOR) ÷ TO` names both units and points at the standardize switch |
| Qualification copy (F10) | **PASS** | "Showing 303 of 582 players", the full rule including the 14-day recency clause, and the tiny-denominator reason |
| Scoring sentence (F8) | **PASS** | "Fantasy points under 2025–26 league scoring." appears for a VOR formula |
| Season fallback (2026 → 2025) | **PASS** | The 2026 league opens on 2025–26 and says "2026–27 has no games yet; showing 2025–26". Player links point at the 2025 league (`shownLeagueId`) |
| Rail season links (F7, F9) | **PASS** | On the calculator, the rail's 2026/2025/2024 links go to `/stats/calculator?f=…` and drop `own=r:3` |
| Ranking direction and negatives (F6) | **PASS** | The `dir=asc` title reads "lowest first", and Will Richard's −1.30 shows its sign |
| SC-001 (judged) | **Judged, not measured** | Formula plus Run and a chart is one field and one click. Not timed with a user |
| Leaderboard after extraction | **Partly checked** | Its page loads at 375 px with no sideways scroll. Its columns and sort were not re-clicked live |

**Bug found live (SC-005)**: the "Insert a stat" `<select>` sized itself to its longest option (VOR's
definition), 549 px wide. That pushed the phone layout out to 565 px, and the leaderboard measured 375 px on
the same device. jsdom has no layout, so no unit test could catch it. **Fix**: a `.sc-insert` class on that
field, with `width:100%; max-width:100%; min-width:0` (styles.css, end of file).

**Copy bug found live**: the picker printed "(lower is better)" twice for TO and TOV%, because their titles
already say it. Fixed in `StatCalculator.tsx`, and the T017 test that asserted the doubled text was updated to
require it exactly once.

**Not live-verified**:
- Copy link with a real clipboard, and the saved list in a private window (both are mocked in tests);
- the scatter view at real widths (tooltip clamping, the 24 px tap target);
- the presets panel clicked live (presets are unit- and page-tested);
- a second signed-in profile for SC-004.

**After the live fixes**: `tsc -b` clean, `vitest run` **120 files / 2017 tests passed**.

## US5 build (T029–T032)

Built 2026-10-13: Spread, Change and Breakdown beside Ranked and Scatter (FR-019, FR-020). Frontend only; `api.ts` untouched.

**Results**: `npx tsc -b` clean; `npx vitest run` **123 files / 2073 tests passed** (was 120 / 2017: +3 component test files, +56 tests); `npm run build` succeeds (the existing >500 kB chunk warning only).

**Unit and jsdom tested** (nothing here has run in a browser yet; that is T034):
- `quantiles` against hand values (odd and even n, interpolation); `additiveTerms` shapes and refusals; `contributions` parts sum to `evaluate()` within 1e-9 for 5 formulas, raw and standardized; every `chartAvailability` reason.
- `statCalcUrl`: new params, the `cmp` default and window fallback, and round trips.
- Components: seeded jitter (same markup on two loads), quartile and median text, top-N in the label, dumbbell signs and shared scale, stacked parts left and right of zero, "other" folding to at most 6 colours.
- Page: each disabled reason beside the picker; a linked chart that doesn't fit says so; Spread median text; Breakdown parts sum to the shown result; the second `getStatLeaderboard` call only while Change is shown (mode, sort and chart changes add none); Change values, sort, missing-by-reason lists and position filter in both views; PREV fetches `L24` with the same window and is disabled with no earlier season; URL round trip.

**Not verified (assumed)**: layout and 375 px no-sideways-scroll for the three new charts (CSS written with `min-width: 0`, container-sized SVG, and the `min-width: 0; max-width: 100%` rule on the new field rows; unmeasured); the part colours are existing tokens (`--teal`, `--wr`, `--te`, `--fitted`, `--qb`, `--muted`) and were not run through the dataviz palette validator; the real previous-season payload (PREV is only mocked); how `--up`/`--down` read on the dumbbell.

**Deviations**: see the report to the caller (no player-picking highlight in Spread; "previous season" requires `hasGames`; availability filter not applied to the PREV view).

## US5 review fixes (C1–C10)

Fixed 2026-10-13 after the US5 review (`code-review.md`, "US5 review (T033)"). Frontend only; `api.ts` untouched. Spec US5 and `contracts/url-state.md` carry "amended after US5 review" notes where behaviour changed.

**Results**: `npx tsc -b` clean; `npx vitest run` **123 files / 2086 tests passed** (was 2073: +13), no flaky failure seen in this run; `npm run build` succeeds (the existing >500 kB chunk warning only). All of this is jsdom and unit level; none of it has run in a real browser (T034).

| Finding | Change | Test |
|---|---|---|
| C1 sign inverted | Change is (more recent view) minus (older view); recency is Last 5 > Last 10 > Full season, and a later season beats an earlier one. Ends are labelled by recency ("Full season → Last 10"). | Page: "Change (C1 ordering)" (Hot 10 over the season and 25 over the last 10 is +15 and first under Biggest rise, the slump first under Biggest fall); "Change (C1): the same orientation…" (`w=LAST_10`, and Last 5 vs Last 10); "against the previous season"; the old values test now asserts Bob +6.00 first and Fay −10.00. |
| C2 Totals / GP | `chartAvailability('change')` takes the mode and reads the formula's inputs; disabled for Totals and for GP, each with its reason. | `statFormula.test`: "C2: Change is disabled in Totals mode…"; page: "Change (C2)". |
| C3 colours | Dumbbell line and swatches use `--teal` (higher) and `--wr` (lower); legend says "Higher (+)" / "Lower (−)"; the printed sign stays. | `ChangeDumbbell.test`: "C3…" (reads `styles.css`; none of the four rules uses `--up`/`--down`; no "better/worse" text). |
| C4 + C9 stale join | Rows are drawn only when the board on screen is the window in the link and the comparison board matches the window (and season) named. The comparison request waits for the main board. Otherwise "Updating…" / "Loading the comparison…" with no rows. | Page: "Change (C4)" holds the Last 10 main request open and checks for "Updating…", no list, no "Full season → Full season", no extra SEASON fetch, then rows once released. |
| C5 left-out list | The join starts from the current view's players; "left out" lists only those without a value in the comparison view, naming that view's window and season ("Not in the 2024–25 full season data"). | Page: values test (Gus no longer listed; Dee, Eve by reason) and the previous-season test. The owner-filter-with-PREV shape was not driven by a test; the join no longer iterates the comparison's own players at all. |
| C6 distribute | `additiveTerms` distributes a constant factor over a bracketed sum (`k*(a±b)`, `(a±b)/k`, nested minus). Refusals name the cause ("multiplies one stat by another", "divides by a stat", "divides by zero"). | `statFormula.test`: distribute shapes; parts sum to `evaluate` within 1e-9, raw and standardized, for 6 formulas; refusal causes. |
| C7 small strip | A strip under 5 players keeps its dots and count, prints "too few players for quartiles", draws no band, quartiles or median. | `SpreadStrip.test`: "C7…". |
| C8 legend | Term text is printed from the tree as typed ("PTS ÷ 3", "0.5 × STL", "constant 5"). | `statFormula.test`: "C8…"; the existing shape test now expects "2 × AST". |
| C10 keyboard, tooltip, spec | Dots are no longer tab stops (the table under the chart is the keyboard path). The tooltip is placed in the SVG's rendered pixels and clamped to the container's real width. "Players the member picks are highlighted" removed from the spec by a visible amendment, not built. | `SpreadStrip.test`: "C10: the dots are not tab stops…" and "the tooltip stays inside a container narrower than the SVG minimum" (container width mocked to 200). |

**Not verified (assumed)**: the real layout, the two new hues against the dark and light themes, and the narrow-container tooltip in a real browser; the owner filter with "previous season" end to end; T034 is still to do.

**Deviations from the brief**: none declined. A Totals click on the Change view now makes the chart disappear with its reason (it is in the link), so the existing "request-free" page test uses Per 36 for its mode round trip; a Totals round trip refetches the comparison once, which is the intended consequence of C2.

## US5 live (T034, 2026-10-13)

The setup is the same as the T027 section: worktree backend on 8080, Vite on 5179, NBA 2025. Reference
values come from a Python script over payloads fetched with curl (`SEASON`, `LAST_10`, and the 2024 league
for PREV). It doesn't use the app's code.

| Check | Result | How |
|---|---|---|
| Spread quartiles, `ast / tov` | **PASS** | The page shows "25th 1.49 · median 1.83 · 75th 2.33"; numpy `percentile(..., [25,50,75])` over the 303 qualified rows gives 1.49 / 1.83 / 2.33 |
| Spread split by position | **PASS (visual)** | Five strips (PG 92, SG 145, SF 140, PF 117, C 82; they sum to more than 303 because multi-position players repeat, which the page states). Each has its own quartiles. Not hand-checked per position |
| Change, season → last 10, `ast / tov` | **PASS 3/3 + sign** | Kornet 4.50 → 10.00 (+5.50), McConnell 4.48 → 9.86 (+5.37), Queta 1.68 → 6.25 (+4.57). These match the script, and they sort first under "biggest rise" (C1 fix confirmed live) |
| Breakdown, `usg + vor` | **PASS** | Jokić 56.35 = USG 30.22 + VOR 26.13, which matches the payload (usg 30.221, VOR 26.13). Ranked Dončić, Jokić, Wembanyama, SGA |
| Disabled reasons | **PASS** | Breakdown for `ast / tov` ("divides by a stat"). Change with standardize on (two windows don't share a scale). Change in Totals (different game counts). A link with `chart=change&z=1` draws nothing and says why |
| 375px | **PASS** | `scrollWidth` = `innerWidth` = 375 for Spread (split), Change and Breakdown. Screenshots were taken of all three |
| NaN/Infinity/∞ scan | **PASS** | Spread page text |

**Not live-verified**:
- Change against the previous season (PREV) was not driven in the browser; its fetch is page-tested only.
- The C4 window-switch "Updating…" state was not observed live; it is page-tested.
- Spread's per-position quartiles were not hand-checked.
- The colour-blind separation of the Breakdown part colours was not validated (`--teal`/`--wr` are existing tokens).

**Suite after US5 and its fixes**: `tsc -b` clean, `vitest run` **123 files / 2086 tests passed**.
