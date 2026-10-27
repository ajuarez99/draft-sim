# Contract: the comparison page (spec 049)

> **Amended after review 2026-10-26** (plan A1, A2, A4–A8, A10, A11, A14, A15). The earlier row list,
> marking and copy rules are replaced by this one.

## Route

`/leagues/:sleeperLeagueId/compare?a=<sleeperPlayerId>&b=<sleeperPlayerId>`, behind `PlatformGate` `"stats"`,
with a `compare` row in `destinations.ts` (`sports: ['nba']`, `idKind: 'league'`, `inRail: false`).

| URL state | Page |
|---|---|
| the first response's `sport` fails `comparePagesFor` | "Player comparison is available for basketball leagues only." No further request *(amended at analyze 2026-10-26, I1: the sport isn't known before the first fetch)* |
| `a` and `b`, different | two columns |
| only one, or `a == b` | one column, and a chooser in the other |
| neither | two choosers |

Swapping a player rewrites only that param, with `replace` history. A rail season switch drops `a`/`b` (accepted).

## Entry point

The player page's `Header` card, beside the owner: a "Compare" link to `/leagues/:id/compare?a=<this player>`.
It renders only when the page is `available` and the `compare` destination applies to the league's sport.

## Rows, in order (✓ = marked, — = never marked)

| Row | Mark |
|---|---|
| Owner, team, positions | — |
| This week: "1 played · 3 left" or "No game this week", with the note and the schedule as-of | — |
| Per window (Season, Last 10, Last 5): games | — |
| Per window: fantasy points per game | ✓ higher |
| Per window: minutes per game | ✓ higher |
| Season box-score total, labelled "box-score total, not the league's credited points" | — |
| Per game, for the chosen window: PTS, REB, AST, STL, BLK, 3PM | ✓ higher |
| Per game: TOV, PF | — |
| Turnover rate (`advanced.tovPct`) | ✓ lower |
| FG%, 3P%, FT%, each with made-attempted beneath | — |

A window switch (Season / Last 10 / Last 5) picks the window for the box-score and turnover-rate rows. The
fantasy and minutes rows always show all three windows.

## When a ✓ row is withheld

Both values must be present, the two responses' `season` and `dataAsOf` must match (after one refetch of the
older column), and the **displayed** strings must differ. A window is withheld when it is `smallSample`, or, for
LAST_N, when `games < windowQualification[w].lastN` or `reason == NOT_QUALIFIED_STALE`. A withheld cell names
why: "3 of 10 games", "last game 21 days before the newest", or "small sample (84 min)". A `Rate` with a
`reason` reads as missing.

## Marks

A tinted cell plus visually hidden text ("higher", "lower"). Never colour alone.

## Copy

- Intro line: "The marked value is the higher one (lower for turnover rate). Small samples, part-filled or stale
  windows, and shooting percentages aren't marked."
- This-week note: "This league credits one game per player each week: the one you lock in, or his last. More
  games means more chances at a good one, not more games' points."
- Never: a verdict, a recommended player, a tally of rows, projected points.

## SC-006 scan (vitest, over the rendered page's text)

Fails on any of these:
- `/\b(start|sit|bench)\b/i`
- `/\b(better pick|recommend(ed|s)?|winner|edge)\b/i`
- `/\b\d+\s*(of\s*\d+\s*)?(rows?|categories)\s+won\b/i`

Positive controls in the same test: the full page including the this-week note must pass, and a planted
"Start Jokić" must fail.
