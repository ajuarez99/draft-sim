# Data model: spec 047

> **Amended after review, 2026-10-24 (plan.md A1, A3, A6, A8, A14). These changes win over the
> tables below.**
>
> - **A new table**, V40 `league_losers_bracket_match`, with the same columns as V33. It's written
>   by `BracketIngestService`.
> - **TrophySeason:**
>   - `runnerUpUnavailable` → `runnerUpReason` (`NO_BRACKET | SOURCES_DISAGREE | NO_LOSER | null`);
>   - adds `lastPlace: ManagerRef?` and `lastPlaceReason` (`NO_LOSERS_BRACKET | INCOMPLETE |
>     MIXED_DIRECTION | UNCOVERED_ROSTERS | UNSUPPORTED_SHAPE | null`), plus
>     `lastPlaceBracket: TOILET_BOWL | CONSOLATION | null`.
> - **TrophyCase:** `lastPlaceNote` is removed.
> - **ManagerRef:** `managerId` is nullable.
> - **DiscoveredPair:** adds `playoffMatches` (per match).
> - **RivalryDiscovery:** every list has the `meetings ≥ N` floor, **except `playoff`, which has no floor**
>   (tasks.md "Amended during build"; code review N1). `lopsided` also needs a gap of
>   at least 2, larger than the closest tier's gap. It adds `playoffUnsplitSeasons: int[]`.

There are no tables and no migration. Everything is derived from stored rows on read.

## TrophySeason

| Field | Type | Rule |
|---|---|---|
| season | int | |
| sleeperLeagueId | string | that season's league. Used for the Awards fetch and links |
| champion | ManagerRef? | the roster where `ChampionRule.isChampion(final_placement, league complete)`. Null only if Sleeper named none |
| runnerUp | ManagerRef? | the loser of the bracket match with `placement = 1` |
| runnerUpUnavailable | boolean | true when no p=1 match is stored |
| sourcesDisagree | boolean | the bracket's p=1 winner roster ≠ the champion's roster |
| finalDisputed | boolean | that pair's `Final.scoresAgreeWithWinner == false` (spec 034 B2) |
| pointsLeaders | PointsLeader[] | every roster tied at max `points_for`. Never empty when the season has standings |

`ManagerRef` = `{managerId, name, teamName, avatarId, rosterId}`. A former manager is still named.

`PointsLeader` = `ManagerRef` + `pointsFor`.

Only `league.complete()` seasons produce a row (FR-006).

## TrophyCase

`{sport, seasons: TrophySeason[] (newest first), status: "OK" | "NO_COMPLETED_SEASON"}`

## RivalryDiscovery (on `Rivalries.discovery`)

`{minMeetings, minMeetingsLabel: "hand-set", mostPlayed: DiscoveredPair[], closest: DiscoveredPair[],
lopsided: DiscoveredPair[], playoff: DiscoveredPair[]}`

`DiscoveredPair` = `{aManagerId, bManagerId, meetings, aWins, bWins, ties, byKind, finals}`. These are
copied from that pair's `PairRecord`, so they're equal by construction (SC-003).

- An empty `closest` or `lopsided` list means no pair has `meetings ≥ N`.
- An empty `playoff` list means no PLAYOFF meetings.

## ManagerRivals

`{managerId, chains: ChainRivals[]}`

`ChainRivals` = `{sport, leagueName, headSleeperLeagueId, status: "OK" | "NONE_QUALIFY", minMeetings,
nemesis: RivalEntry[], victim: RivalEntry[]}`

`RivalEntry` = `{opponent: ManagerRef, meetings, wins, losses, ties, former: boolean}`. These are from
the manager's side. `former` is true when the opponent has no roster in the chain's newest season.

**Validation:**

- every entry has `meetings ≥ minMeetings`;
- every nemesis has `losses > wins`, and every victim has `wins > losses`;
- chains are never merged across sports.
