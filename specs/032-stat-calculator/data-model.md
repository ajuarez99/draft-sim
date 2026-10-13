# Data model: Custom stat calculator (spec 032)

Everything here is **client-side**. No table, no migration, no Java record and no `api.ts` type
changes. The source data is spec 022's `StatLeaderboard` / `LeaderboardRow`, which this feature
does not change.

## Stat input

One evaluable column. It is derived from `PICKABLE` (`web/src/statLeaderboard.ts:171`) and is
never declared a second time.

| Field | Source | Notes |
|---|---|---|
| `id` | `StatColumn.id` | The canonical token in formulas, links and saved entries |
| `label` | `StatColumn.label` | Shown in the picker and in table headers |
| `token` | the label if it matches `[A-Za-z0-9%]+`; otherwise a hand-set alias (`FPG`, `POSRANK`, `PTSRANK`) | The formula name, which is typed and printed in the readable form. Tokens are unique case-insensitively, and a test asserts it. Extra accepted alias: `USG%` → `usg` *(amended after plan review, F1 and F5: there are no duplicate labels in `PICKABLE`)* |
| `tag` | `"fantasy"` for `vor` | A visual suffix outside the token (FR-013) |
| `title` | `StatColumn.title` | The plain-language definition shown in the picker |
| `lowerIsBetter` | `StatColumn.lowerIsBetter` | Shown as a note in the picker. It never changes the maths |
| `counting` | `StatColumn.counting` | Follows the per game / totals / per 36 mode |
| `fantasy` | derived: group is `fantasy` | Drives FR-012's "this league's scoring" label |
| `unit` | new hand-set map, next to `COLUMNS` | One of `pct`, `count:<mode>`, `fp`, `rank`, `games`, `minutes:perGame`, `minutes:totals`, `gmsc`. `fp`, `vor` and `gameScore` are per game in every mode. ~~`min` is per game in every mode.~~ *(Amended after code review, B6: `min` is per game in per game and per 36, but `COLUMNS.min` returns a season total in totals mode, so there it has its own unit `minutes:totals`, and `pts + min` warns.)* Constants are dimensionless. A column missing from the map gets the unit `id:<id>` (research R8, amended after plan review, F11) |

## Formula (in memory)

| Field | Type | Rule |
|---|---|---|
| `expr` | string | The canonical form over ids, for example `(usg + vor) / tov`. Grammar in `contracts/formula-grammar.md` |
| `name` | string \| null | Up to 40 characters. When null, the chart title shows the readable formula |
| `ast` | parsed tree | Built from `expr`. A parse failure yields `ParseError[]` (each with a message and a character span), and the last good result stays on screen, labelled "previous formula" (US1 scenario 5) |
| `inputs` | `Set<id>` | The columns the formula reads, which become the result table's columns |
| `unitWarnings` | `string[]` | From unit inference (R8). Empty when consistent |
| `spreadWarnings` | `string[]` | Added inputs whose standard deviations over the reference group differ by more than the hand-set 3× (F11) |
| `zeroDenominatorWarning` | `string \| null` | Raw mode only: a denominator with values ≤ 0 in the reference group (F2) |
| standardized-÷ error | `ParseError` | With `standardize`, a `÷` whose right side contains a stat is an error, not a warning (F2) |
| standardized-× error | `ParseError` | *(Amended after code review, B1.)* With `standardize`, a `×` whose two sides both contain a stat is an error: multiplying two z-scores puts a player who is below average on both first. A stat times a constant is valid |
| no-stat and ÷ 0 errors | `ParseError` | *(Amended after code review, B3.)* In both modes, a formula with no stat, and a `÷` whose right side is a constant equal to 0, are errors |

## Calculator settings

Every setting below is in the URL (`contracts/url-state.md`).

| Field | Values | Default |
|---|---|---|
| season | the path's league id | the page's league; the leaderboard's fallback rules apply |
| `window` | `SEASON` / `LAST_10` / `LAST_5` | `SEASON` |
| `mode` | `perGame` / `totals` / `per36` | `perGame` |
| `top` | integer from 5 to 50 | 15 (a hand-set display choice, labelled as such in the assumptions) |
| `qualifiedOnly` | boolean | `true`, for every formula (research R6). The page shows the shown/total count and the full rule (F10) |
| `dir` | `desc` / `asc` | `desc`, highest first (F6) |
| `standardize` | boolean | `false` (Q1: B) |
| `filters` | the existing `Filters` type (`statLeaderboard.ts:249`) | `NO_FILTERS` |

## Result row

| Field | Type | Rule |
|---|---|---|
| `row` | `LeaderboardRow` | Unchanged from the payload |
| `outcome` | `{ value: number }` or `{ excluded: Reason }` | Never a bare `number \| null` |
| `inputs` | `Record<id, number \| null>` | Raw values, shown beside the result even when standardized |
| `z` | `Record<id, number> \| undefined` | Present only when standardized |

**Reason**, a tagged union rendered in words:

- `INPUT_MISSING { id, why }`, where `why` comes from the shared `missingReason` (research R15),
  never assumed:
  - a `RateReason`;
  - `NO_MINUTES` or `NO_GAMES`;
  - `NOT_QUALIFIED` or `NOT_QUALIFIED_STALE`;
  - `NO_REPLACEMENT_LEVEL` (a qualified player whose VOR is null);
  - `NO_POSITION`;
  - `UNKNOWN_NULL`, which a test asserts never occurs on the fixtures.

  *(Amended after plan review, F3.)*
- `DIVIDE_BY_ZERO { denominatorText }`, for example "0 turnovers, so ÷ TO is undefined";
- ~~`NON_POSITIVE_DENOMINATOR`~~: removed. In standardized mode, ÷ by a stat is now a
  validation error (F2);
- `NOT_STANDARDIZABLE { id, why: 'GROUP_TOO_SMALL' | 'NO_SPREAD' }`;
- `NOT_FINITE`, a defensive case.

**Ordering**: included rows are sorted by `value` in `dir` (descending by default, F6), then by spec 022's tiebreak
(games played, then name, then id). That is `compareRows`' tail, reused rather than copied.
Excluded rows are listed after them, grouped by reason.

## Standardization reference group

For each input id: the rows that pass the filters and qualification switch and have a non-null
value for that input. The same group is the reference for the spread warning and the
zero-denominator warning. The page shows the group's size `n`, its `mean` and its population `sd`.

## Saved formula (browser only)

Storage key `bk.statCalc.saved.v1`, with value `{ v: 1, formulas: SavedFormula[] }`.

| Field | Type |
|---|---|
| `id` | string (random) |
| `name` | string |
| `expr` | string (canonical) |
| `settings` | `{ window, mode, top, qualifiedOnly, standardize, dir }`. Filters are not saved, because they are league-specific |
| `savedAt` | ISO string |

- There are at most 50 entries.
- If `v` isn't 1, or the value fails to parse, the store reads as empty.
- An entry whose `expr` no longer parses is kept and flagged, not dropped.

## Preset (bundled)

Fields: `{ id, name, expr, mode, description, mixesUnits }`. There are five, listed in research
R11. When the member edits a loaded preset, it stops carrying the preset's label (US2
scenario 3).
