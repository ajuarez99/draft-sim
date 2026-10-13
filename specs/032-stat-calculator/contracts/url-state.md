# Contract C2: Calculator route and URL state

**Route**: `/leagues/:sleeperLeagueId/stats/calculator`

- The path's league id picks the season, exactly as on `/leagues/:id/stats`.
- A season switch navigates to that season's league id and **keeps the query string**, except
  for `own=r:`, which is stripped because roster ids repeat every season (F9).
- This applies to the in-page picker **and** the rail's season switch. `switchTarget` keeps
  `/calculator` and the search string when the current destination is `stats` (amended after
  plan review, F7).
- The `stats` rail destination matches this path (research R12).

## Query parameters

| Param | Meaning | Values | Invalid or absent |
|---|---|---|---|
| `f` | formula, canonical form | the URL-encoded C1 expression | absent: an empty editor with the presets shown. Unparseable: the editor holds the text, the error is shown, and there is no chart |
| `n` | name | up to 40 characters | absent: no name |
| `w` | window | `SEASON`, `LAST_10`, `LAST_5` | `SEASON` |
| `m` | mode | `perGame`, `totals`, `per36` | `perGame` |
| `top` | bars shown | integer from 5 to 50 | 15; out-of-range values are clamped |
| `q` | qualified only | `1` / `0` | `1` |
| `z` | standardize inputs | `1` / `0` | `0` |
| `dir` | ranking direction | `desc`, `asc` | `desc` (F6) |
| `pos` | position filter | a position code | all. A value not among the current rows' positions is dropped, with a sentence (F9) |
| `team` | NBA team filter | a team code | all. A value not among the current rows' teams is dropped, with a sentence (F9) |
| `own` | availability | `free`, `rostered`, `r:<rosterId>` | all. An `r:` id with no matching roster in the rows' ownership is dropped, with a sentence (F9) |
| `chart` | which chart *(US4, FR-018; widened 2026-10-13, US5, FR-019)* | `spread`, `change`, `parts` *(added 2026-10-13, US5)* or `scatter` (the ranked chart is the default and is not written) | `ranked` |
| `x` | scatter X axis *(amended after code review, B10: written whenever set, also on the ranked view, so a chart toggle keeps it)* | a built-in stat id (e.g. `usg`), or `f` for the current formula | `usg`. A value that is neither a stat id nor `f` is replaced by the default, with a sentence. `f` with no formula asks for one and draws nothing |
| `y` | scatter Y axis *(amended after code review, B10: as `x`)* | as `x` | `f` when a formula is loaded, else `ts` |
| `split` | Spread split by position *(added 2026-10-13, US5, FR-020; written whenever set, like `x`/`y`)* | `pos` | off |
| `cmp` | what Change compares the current window with *(added 2026-10-13, US5)* | `LAST_10`, `LAST_5`, `SEASON`, `PREV` (previous season, same window) | `LAST_10` when `w` is `SEASON`, else `SEASON`. A `cmp` equal to `w` is replaced by that default, and is not written when it equals the default or `w` |
| `chg` | Change sort *(added 2026-10-13, US5; amended after US5 review, C1: the change is always the more recent view minus the older one, so `up` lists the players whose recent view is higher)* | `up` (biggest rise), `down` (biggest fall) | `up` |

## Scatter axes *(added at US4 build)*

- An axis is a built-in stat or the current formula, and nothing else (FR-018). Saved formulas
  aren't offered.
- The current formula is written as the literal `f`, not as its text. That keeps the link from
  repeating `f` and stops the axis going stale when `f` is edited. This is a deviation from the
  original `<id|formula>` wording; the formula's text is already in `f`.
- Scatter axes use the page's mode, window, filters and qualification switch. A player missing either
  value is not plotted, and the chart states how many.

## Rules

- A control change replaces the URL (no new history entry), so Back leaves the calculator rather
  than undoing one control at a time.
- Opening the same URL in another signed-in session gives the same players, order and values
  (SC-004), given the same stored data and `dataAsOf`.
- A link opened under a different league id runs unchanged. Fantasy inputs then use the scoring
  the payload names (`scoringSeason`), and FR-012's shared sentence says so (F8).
- A `+` inside `f` must round-trip: the writer encodes it as `%2B`, and a test covers it (F9).
