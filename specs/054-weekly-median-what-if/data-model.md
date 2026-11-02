# Data model: Weekly median what-if (spec 054)

No schema change, no migration. Everything is derived per request from stored data:
`league.settings_json` (median flag, playoff format), `roster_season` (official W-L-T, PF),
`league_matchup` + `roster_week_points` via `LeagueMatchupRepository.pairedWithScores`, and
`ScoredWeeks` (which weeks are final).

## SeasonMedian (one per season in the history response)

| Field | Type | Notes |
|---|---|---|
| status | `WHAT_IF` \| `ALREADY_ON` \| `NO_FINAL_WEEKS` | `ALREADY_ON` iff `league_average_match > 0`. `NO_FINAL_WEEKS` iff no final regular-season game exists (takes precedence over the other two). |
| weeksCounted | int | Distinct final regular-season weeks in the games used. |
| regularSeasonWeeks | int \| null | `playoff_week_start − 1`; null when unset. |
| realSource | `OFFICIAL` \| `COUNTED_GAMES` \| null | research R4. Null unless status is `WHAT_IF`. |
| playoffTeams | int \| null | Null when the format isn't modelable. |
| playoffLineWithheld | string \| null | Reason the playoff marker is withheld (e.g. "divisions"), null when shown. |
| teams | SeasonMedianTeam[] | One per roster that appears in the counted games. Empty for `NO_FINAL_WEEKS`. |

## SeasonMedianTeam

| Field | Type | Notes |
|---|---|---|
| rosterId | int | Joins to the standings row. |
| median | WinLossTie | Always present. From `ExpectedWinsService.median`. |
| allPlay | WinLossTie | Always present. From `ExpectedWinsService.allPlay` (FR-014). |
| real | WinLossTie \| null | `WHAT_IF` only. Official or counted per `realSource`. |
| withMedian | WinLossTie \| null | `WHAT_IF` only. `real + median`, field by field. |
| winsGained | int \| null | `WHAT_IF` only. `= median.wins`. |
| actualPlace | int \| null | `WHAT_IF` only. Rank of `real` under the R5 comparator. |
| whatIfPlace | int \| null | `WHAT_IF` only. Rank of `withMedian` under the same comparator. |
| playoffCross | `MADE` \| `MISSED` \| null | Non-null only when the line is shown and the two places sit on opposite sides of it. |

## Invariants (each one a test)

1. `median.w + median.l + median.t == weeksCounted` for every team.
2. `withMedian == real + median` field by field; `winsGained == median.wins`.
3. `actualPlace` and `whatIfPlace` are each a permutation of `1..teams.size()`.
4. More median wins with an equal real record ⇒ a better-or-equal `whatIfPlace` (preference
   ordering; lessons bug class 1).
5. `ALREADY_ON` ⇒ every what-if field null.
6. For the season the Luck page covers, `median`/`allPlay` equal its figures exactly (SC-004).

## Amended after review (2026-11-02)

Per [plan-review.md](plan-review.md) and plan.md "Amended after review" (the override):
- `status` adds `NO_REGULAR_SEASON_BOUND` and `UNAVAILABLE`; `realSource` value `SLEEPER_OFFICIAL`
  renamed `OFFICIAL` (ESPN shares the path).
- SeasonMedian adds `driftGames` (int: rosters whose re-counted head-to-head ≠ official record).
- SeasonMedianTeam adds `gamesCounted` (int). **Invariant 1 is now per team:**
  `median.w + median.l + median.t == gamesCounted`.
- `OFFICIAL` requires every roster's `gamesCounted == regularSeasonWeeks` AND official
  `W+L+T == regularSeasonWeeks`, no null official fields, identical roster sets; else `COUNTED_GAMES`.
- `playoffLineWithheld` is a code: `DIVISIONS` | `SEED_TYPE` | `NO_PLAYOFF_TEAMS` | `BRACKET_UNKNOWN` | `ORDER_DISAGREES`. (amended after code review 2026-11-02: `BRACKET_UNKNOWN` = finished season with no stored winners bracket; `ORDER_DISAGREES` = the stored bracket field differs from this app's top N; the field is read from `league_bracket_match`, not `final_placement`.)
- Invariant 4 is strict: equal real records, A higher PF, B more median wins ⇒ B's what-if place
  beats A's.
