# Research: spec 047, Hall of fame

All measurements were taken 2026-10-24 against the local 5433 DB (main `ebf4ee8` schema) and the live
Sleeper API. "Read" means checked in code, and "measured" means executed.

## R1. Where does the runner-up come from? (measured)

**Decision:** use the loser of the stored winners-bracket match with `placement = 1` (V33
`league_bracket_match`, written by `BracketIngestService`). If that match's `winner_roster_id` isn't
the roster `ChampionRule` names, the row keeps the stored champion and carries
`sourcesDisagree = true` (spec AS 1.2).

**Measured:**

| League | Season | Bracket p=1 winner | Stored placement 1 | Bracket stored |
|---|---|---|---|---|
| Ball Knowers (NBA) | 2024 | roster 2 | roster 2 | yes |
| Ball Knowers (NBA) | 2025 | roster 12 | roster 12 | yes |
| West Coast FF | 2025 | roster 6 | roster 6 | yes |
| (Foot) Ball Knowers | 2025 | — | roster 1 | **no rows locally** |

That's 3 of 3 agreeing, and 1 of 4 seasons has no local bracket. The runner-up cell for that season
reads "not available" (spec edge case) until a bracket backfill runs. `BracketIngestService.backfillChain`
already exists, and verification runs it.

**Alternatives rejected:** second place from standings or from points (FR-003 forbids inferring).

## R2. Last place: fetch Sleeper's `losers_bracket`? (measured: **yes, with a refusal rule**)

> **Amended after review, 2026-10-24 (plan-review F1). The original R2 below is WRONG and is kept
> for the record.** It said the bracket's direction "depends on a setting the API returns as null",
> so reading it would be a guess. The setting is null, but the stored scores show the direction
> per league-season:
>
> | League | Season | `w` scored less than `l` | `w` scored more | Reading |
> |---|---|---|---|---|
> | (Foot) Ball Knowers | 2025 | 7 of 7 | 0 | toilet bowl → p=1 `w` = roster 2 (2-12) |
> | Ball Knowers NBA | 2024 | 1 (p=3) | 6 | consolation, but not unanimous → refuse |
> | Ball Knowers NBA | 2025 | 1 (p=3) | 6 | consolation, but not unanimous → refuse |
> | West Coast FF | 2025 | 0 | 7 of 7 | consolation → loser of the bottom placement match |
>
> The reviewer measured the table. I re-checked FBK week 15 by hand: roster 6 (123.96) advanced
> over roster 7 (141.60).
>
> **Decision (Allan, 2026-10-24): build it, and refuse on doubt.**
> - Fetch `losers_bracket` alongside the winners bracket and store it in a new table
>   `league_losers_bracket_match` (**V40**, same columns as V33). A separate table is used so
>   no existing winners-bracket reader can pick up losers rows.
> - A season names last place only when all of these hold:
>   1. the losers bracket is stored, and every match is decided;
>   2. its rosters are exactly the non-playoff rosters (`total_rosters − playoff_teams`);
>   3. every match whose weeks have stored scores for both sides agrees on one direction;
>   4. the bottom-placement arithmetic holds:
>      - consolation: `playoff_teams + max(p) + 1 == total_rosters`;
>      - toilet bowl: the p=1 match exists.
> - **Amended after code review (2026-10-24, S2/S3), stricter than item 3 above:** every match must vote, not
>   just those with scores. One match with no stored points on either side refuses with `NO_SCORES`; an exact
>   tie, or votes for both directions, refuses with `MIXED_DIRECTION`; and before a match votes its winner and
>   loser must have shared a `league_matchup` matchup id in every mapped week, else `UNSUPPORTED_SHAPE` (the
>   round-to-week alignment is only trusted where the stored pairing confirms it).
> - Then toilet bowl → p=1 `w`, and consolation → the max-p match's `l`.
> - Otherwise the row carries a reason (`NO_LOSERS_BRACKET | INCOMPLETE | MIXED_DIRECTION |
>   UNCOVERED_ROSTERS | UNSUPPORTED_SHAPE`) and the cell reads "not reported".
> - Match weeks come from `RivalryGameKind.roundOfWeek` applied to the losers bracket (two-week
>   finals included), so there's no second round-to-week rule.
>
> The 14-team West Coast 2026 shape is unmeasured. Rule 4 refuses anything it doesn't fit.
>
> The original text follows.

**Decision:** don't fetch it, and don't name last place anywhere. The spec allows this outcome
(Assumptions: "not reported" everywhere is accepted).

**Measured:** `GET /league/{id}/losers_bracket` for the three 2025 Sleeper leagues returns a 7-match
bracket with `p` = 1, 3 and 5, the same shape as the winners bracket. On "(Foot) Ball Knowers" 2025:

- The two byes are rosters 2 (2-12, the worst record) and 10 (5-9). So the bracket is seeded
  worst-first.
- In round 1, `w` advances (`t2_from: {"w": 1}`).
- The p=1 match is roster 2 vs roster 5 (7-7), with `w = 2`.

Whether `w` of p=1 means "last place" (the toilet-bowl "winner") or "9th/7th place" depends on a
Sleeper league setting the API returns as `loser_bracket_type: null` for all three leagues. Reading it
either way would be a guess made into a rule. Reading it wrong names the wrong manager as last,
publicly. That's the "two definitions of last" bug the design doc warned about.

**Spec amendment (2026-10-24, plan stage):** FR-004's per-row "not reported by Sleeper" cell would be
true in **every** row of every league. A column that's always empty is lesson #5 (correct, and still
the wrong thing to show). So the trophy case has **no last-place column**. Instead the section prints
one note: *"Sleeper doesn't report last place for these leagues, so it isn't shown."* FR-004's
substance (never infer last place) is unchanged. See the amended note in `spec.md`.

## R3. Is `roster_season.points_for` regular-season only? (measured: **yes**)

**Decision:** the points leader reads `points_for` directly. It's Sleeper's own regular-season figure,
which is what the History standings already show.

**Measured:** for all 48 rosters of the 4 complete seasons, `points_for` equals the sum of
`roster_week_points.starters_points` over weeks `< playoff_week_start`:

- (Foot) Ball Knowers 2025, West Coast FF 2025 and Ball Knowers NBA 2024 match to the cent.
- Ball Knowers NBA 2025 differs by 0–44 points per roster. That's the known NBA 2025 matchup drift
  (memory `project_sleeper_nba_2025_matchup_drift`): stored week points have drifted, and Sleeper's
  `points_for` hasn't.
- The points **leader** is the same by both measures (roster 3, 4583.0 vs 4563.0).

Every roster's total including playoff weeks is higher, so `points_for` excludes the playoffs.

**Alternative rejected:** summing week points ourselves. That would be a second implementation of
Sleeper's number, and it's the one that has drifted.

## R4. The champion rule already has two copies (read)

- `LeagueHistoryController.java:318`: `finalPlacement != null && finalPlacement == 1 && seasonComplete`
- `ManagerCareerService.java:203`: `TRUE.equals(complete) && finalPlacement != null && finalPlacement == 1`

FR-002 needs one implementation. **Decision:** add `engine/ChampionRule.isChampion(Integer
finalPlacement, boolean seasonComplete)`. Both existing sites and the trophy case call it, and a
unit test pins it. Behaviour is unchanged (the before/after capture must show identical `champion` and
`titles` fields).

## R5. Discovery reuses spec 034's pair records (read)

`RivalryService.forLeague` already builds one `PairCalc` per pair of **current-season** managers
(`RivalryService.java:282-293`), with totals, `byKind` (regular/playoff kinds via `RivalryGameKind`)
and `finals`.

**Decision:** discovery is computed from that same `calc` map, inside `forLeague`, and added to
`Response` as `discovery`. That makes "every number matches the matrix cell" (SC-003) true by
construction, and a test still asserts it.

- Discovery covers current-season managers only, the same population as the matrix, so every entry
  has a matrix cell to click.
- Former managers appear in US3 (nemesis), not here. This narrows the spec's wording ("the league's
  notable rivalries") to the matrix's population. Recorded in plan.md.

## R6. Nemesis/victim need former opponents too (read)

The matrix drops pairs where either manager isn't in the requested season. But the spec's edge case
says a manager who left "still counts as someone's nemesis", and the manager page also exists for
managers who left.

**Decision:** split two pieces out of `forLeague` so the per-chain game list and `pairCalc` can be
called for **all** managers of a chain:

- `chainGames(chain)`: the `pairedWithScores` + `classify` loop at `RivalryService.java:229-247`
- `pairCalc`

The current-season matrix is then the restriction of that to current managers. The numbers are the
same by construction, and the parity test pins it.

A new `RivalsService.forManager(managerId)` finds each chain the manager has a roster in (the chain
head via `LeagueRepository`'s forward step, `:459`, the same approach `ManagerCareerService.java:295`
uses), and computes nemesis and victim there.

## R7. Superlative winners (US4): reuse the existing endpoint (read)

**Decision:** no backend work. The expanded row calls the existing
`GET /api/leagues/{seasonSleeperId}/superlatives` for that season, and renders only the winners from
the same response the Superlatives page renders. That makes FR-012 (same winners) true by
construction.

It's lazy (fetched on expand), so the History page's load cost doesn't grow with the number of
seasons.

## R8. Disputed finals (read)

`RivalryService.Final.scoresAgreeWithWinner` is false when the bracket's winner had fewer stored
points (code-review B2 of spec 034, measured on NBA 2024/2025). The trophy case shows the same note
wherever that's false.

**Decision:** move the check into a static helper both services call (one implementation), and
reuse the Rivalries page's sentence. The plan's tasks name the exact method once it's located
(`pairCalc` area). Note it can't be done on the trophy-case side without the summed final legs, so
the helper takes the bracket match plus the chain games.

## R9. Ranking rules (decided, hand-set where marked)

These define the claims. They aren't tuning knobs:

- **Most-played:** max `meetings`.
- **Closest:** min `|aWins − bWins|` among pairs with `meetings ≥ N`, then more meetings first. Pairs
  still tied all show.
- **Most lopsided:** max `|aWins − bWins|` among `meetings ≥ N`, then higher winner's share of
  meetings. Pairs still tied all show.
- **Nemesis:** opponents with `meetings ≥ N` and a strictly losing record. Worst means the lowest
  `(wins + ties/2) / meetings`, then more meetings. Ties all show.
- **Victim:** the mirror, with a strictly winning record.
- **N** = `draftsim.rivalry.min-meetings` (`config/weights.yml:191`, value 3, already labelled
  hand-set). No new constant.

A tie-break by "more meetings" isn't a hidden pick, because the reason is printed.
