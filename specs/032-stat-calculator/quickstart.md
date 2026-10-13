# Quickstart: verifying the stat calculator

This project's bar for "verified" is driving the real page. A passing suite is not enough
(AGENTS.md). This feature is frontend-only, but the live checks still need a real backend
serving the real leaderboard payload.

## Prerequisites

- Local Postgres on **5433** (`draftsim`/`draftsim`). Not the unrelated cluster on 5432.
- "Ball Knowers" NBA 2025 (`1229352720222134272`) and 2026 (`1339351318115946496`) ingested,
  with 2025 box scores present. If not, follow `specs/022-player-stat-analysis/quickstart.md`.
- A backend on 8080 serving `/api/leagues/{id}/stats`. It needs no restart for this feature,
  because no Java changes.
- A web dev server started from **this worktree**. A preview started from a worktree can serve
  main's launch config (memory: "Worktree preview serves main"), so check which checkout Vite is
  serving before trusting a live check.

```bash
cd web && npx tsc -b && npx vitest run && npm run build
```

## V1. Unit level: grammar and evaluation (C1)

Vitest covers:

- every error row in `contracts/formula-grammar.md`;
- `3PM`, `3P%` and `3PAr` lexing as stats;
- the canonical round trip, where `(USG+VOR)/TO` becomes `(usg + vor) / tov` and the readable
  form `(USG + VOR) ÷ TO` parses back to the same thing;
- the generated round trip for every `PICKABLE` input, unique tokens, and `fp/gp` lexing as
  three tokens (F1);
- unary minus (`usg + -tov`) being valid, and the standardized-÷ error (F2);
- unit rules: `2 * usg + usg` and `ast/tov + stl/tov` don't warn, and `usg + vor` does (F11);
- `missingReason` for each kind of null, with `UNKNOWN_NULL` never occurring on fixtures (F3);
- a test that a mode or formula change doesn't make a request (SC-006, F4);
- precedence (`2 + 3 * 4` = 14);
- `÷ 0` and null inputs yielding reasons, never `NaN` or `Infinity`;
- mean and population SD for the z-scores.

It also needs one **ordering** test, in line with AGENTS.md bug class 1: for `ast / tov`, a
player with 10 AST / 2 TO ranks above one with 6 AST / 2 TO. A sign-inverted sort must fail it.

## V2. Hand-check against the payload (SC-002, as amended)

1. Fetch `/api/leagues/1229352720222134272/stats?window=SEASON` with curl and save the JSON.
2. Pick 10 qualified players, spread over `ast / tov`, `pts * ts / 100` and `(usg + vor) / tov`.
3. Compute each result in a script from the JSON's values.

**Pass**: 10 of 10 match the page to its displayed precision. Then recompute 3 of them from the
leaderboard's *displayed* values, and confirm that any difference is explained by rounding of
the inputs.

## V3. Live page (`/leagues/1229352720222134272/stats/calculator`)

- **US1**:
  1. Type `(USG + VOR) / TO`.
  2. Check that the mixed-units warning names USG and VOR.
  3. Check that the bar chart shows 15 bars, each with an exact value, and that the table's
     order matches the chart.
  4. Change the window to last 10, then the mode to per 36, and check that the chart title names
     both.
  5. Check the "Showing *n* of *N* players" line and the full qualification rule (F10).
  6. Turn qualification off. Unqualified players rank normally on `fp`, but appear under
     "Couldn't compute" for any VOR or rank formula, with the reason "not qualified" (F3).
  7. Type `tov` and pick "lowest first". The title says lowest first, and the bars are drawn from
     zero (F6).
  8. Type `fp / vor` in raw mode. The zero-denominator warning shows (F2).
- **Errors**: type `(usg + vor`. The error names its position, there is no chart, and the
  previous result is labelled "previous formula".
- **SC-003**: run every preset plus `ast / tov` with qualification off. The page text contains no
  `NaN`, `Infinity` or empty value cells (`get_page_text`, then search).
- **Standardize (SC-007)**:
  1. Switch it on for `usg + ts`.
  2. For 5 players, compute the z-scores from the shown group size, mean and SD, and compare.
  3. Type `usg / tov` and check that it is **blocked** with the standardized-÷ error, and that the
     previous result stays labelled as the previous formula. ~~`pts * ts / 100` stays allowed
     (F2).~~ *(Amended after code review, B1: `pts * ts / 100` is also blocked with standardize on, because it multiplies
     two stats; `usg / 2` and `usg * -2` stay allowed. The "Efficient volume" preset loads with standardize off.)*
- **US2**: load each preset, and check that "Stocks per 36" switches the mode to per 36.
- **US3**:
  1. Copy the link and open it in a second browser profile signed in to the same league, then
     compare players, order and values (SC-004, 3 of 3).
  2. Save a formula, reload, and check it's listed under "Saved in this browser".
  3. Repeat in a private window, where it is empty and says why.
- **Season fallback**: open `/leagues/1339351318115946496/stats/calculator` (2026, no games yet)
  and check that it opens on 2025 and says so, as the leaderboard does. The scoring sentence
  names the scoring season (F8), and player links open the 2025 page (F7, `shownLeagueId`).
- **Rail season switch**: with a formula on screen, switch season in the rail. You land on the
  calculator with the formula kept and any `own=r:` dropped (F7, F9).
- **Stale filter link**: open a link with `team=ZZZ`. The filter is dropped, a sentence names it,
  and the select reads All (F9).
- **Reload keeps the editor**: type a formula without running it, then change the window. The text
  is still there and the old chart shows "updating…" until the new one arrives (F4).
- **SC-006**: time window and season changes from click to chart, warm and cold, and report both
  numbers. Report a cold number above 1 s, don't tune anything.
- **SC-001**: judged, not measured. Note in verification.md whether a three-input chart was
  reachable in under 60 s without instructions.
- **Football**: open the calculator under an NFL league id. It says the calculator covers
  basketball only.
- **Rail**: "Player stats" stays highlighted on the calculator, and the Table/Calculator switch
  works both ways.

## V4. Phone (SC-005)

Resize to 375×812, set top N to 50, and use the formula `(usg + vor + ts + ast) / (tov + 1)`, which has five inputs.
`document.documentElement.scrollWidth <= innerWidth` must hold, the table scrolls inside its own
container, and the player column stays pinned.

## V5. Leaderboard unchanged (R13 extraction)

The existing `StatLeaderboard.test.tsx` passes unchanged. Spot-check the live leaderboard's
season picker, filters and sort after the extraction.
