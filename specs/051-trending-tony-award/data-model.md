# Data Model: The Trending Tony Award (spec 051)

*Amended after review (2026-10-27, plan-review.md F1–F13). The original version is in git history; each changed
point below carries its finding number.*

## Storage: `V42__sport_trending_snapshot.sql` (new, append-only)

V41 is main's highest (`V41__league_weekly_pot.sql`). As of 2026-10-27, no branch (origin or local) and no worktree carries V42+. The reviewer
re-checked all 114 branches and 41 worktrees. Check again immediately before writing the file and again before merge (lesson #43).

```text
sport_trending_snapshot
  id              bigserial   primary key
  sport           text        not null           -- 'nfl' | 'nba', same codes as sport_trending_fetch.sport
  fetched_at      timestamptz not null           -- same value written to sport_trending_fetch (F10: from a league
                                                 --   refresh this is the refresh's start, minutes before the call)
  lookback_hours  int         not null           -- window the counts cover; stored, never assumed by the reader
  entry_count     int         not null           -- 0 = Sleeper returned an empty list; such a snapshot judges nothing (F13)
  index (sport, fetched_at)

sport_trending_snapshot_entry
  snapshot_id        bigint not null references sport_trending_snapshot(id) on delete cascade
  rank               int    not null             -- 1-based, Sleeper's order
  sleeper_player_id  text   not null
  add_count          int    not null             -- platform-wide adds over lookback_hours
  primary key (snapshot_id, rank)
```

There is no FK to `sport_trending_fetch`, so history survives anything done to the current-list row.

**Write path**: `SportTrendingRepository.replace(...)` is already `@Transactional` (it is called through the Spring proxy, which the review verified).
It also inserts one snapshot row and that snapshot's entries. A failed fetch (`recordFailure`) writes nothing. The existing two tables and
`read(...)` are unchanged.

**Read path** (new, F4: bounded, F11: new names):

- `snapshotHeaders(sport, from, to)` returns `List<TrendingSnapshotHeader(long id, OffsetDateTime fetchedAt, int lookbackHours, int entryCount)>`
  ordered by `fetched_at`.
- `snapshotRanks(List<Long> ids, int topN, Collection<String> playerIds)` returns `List<TrendingSnapshotRank(long snapshotId, String playerId, int rank, int addCount)>`.
  It filters with `where snapshot_id = any(?) and rank <= ? and sleeper_player_id = any(?)`, binding `Long[]`/`String[]` via `createArrayOf` and
  `bigint[]`/`text[]` (lesson class 3: test it in an IT, don't trust that it compiles).
- `earliestSnapshotAt(sport)` returns `Optional<OffsetDateTime>`, which picks between FR-008's two unavailable reasons.

Range: `[min(createdAt) - 24h, max(createdAt) + 24h]` over the counted pickups. Timestamps bind as `OffsetDateTime`.

## Pure rule: `engine/TrendingPickups` (new, no Spring, no Postgres)

The constants are hand-set and labelled so in the source:

- `TOP_N = 15`
- `MIN_TO_NAME = 2`

The match window is each snapshot's own `lookbackHours`.

Shared with `MostAddedPlayers` (F1): a single predicate `PickupRules.isCountedPickup(CompletedAdd)`, which means
`type ∈ {WAIVER, FREE_AGENT} && week >= 1`. `MostAddedPlayers` is refactored to call it, and its behaviour is unchanged.

```text
judge(List<CompletedAdd> adds, List<TrendingSnapshotHeader> headers, Map<Long, Map<String, TrendingSnapshotRank>> ranks,
      int throughWeek, Predicate<String> eligible) -> List<Judged>

Judged(CompletedAdd add, Verdict verdict, Integer trendingRank, Integer addCount, OffsetDateTime snapshotAt)
Verdict = TRENDING | NOT_TRENDING | NOT_JUDGED
```

Rules (research R1, R4):

1. Keep only adds that pass `isCountedPickup`, have `week <= throughWeek`, and whose player passes `eligible`.
   `eligible` excludes football DEF. Adds that are dropped go into no bucket and are not in `totalPickups`.
   The card names both exclusions (FR-007, F9).
2. If `createdAt == null`, the verdict is NOT_JUDGED.
3. Pick the judging snapshot (F3, after-first). Headers with `entryCount == 0` are skipped (F13).
   - Use the earliest header with `P ≤ fetchedAt ≤ P + lookback`.
   - If there is none, use the latest header with `P − lookback ≤ fetchedAt < P`.
   - If there is neither, the verdict is NOT_JUDGED.
4. If the player has a rank in that snapshot's rank map (already filtered to `≤ TOP_N`), the verdict is TRENDING. Otherwise it is NOT_TRENDING.
   A player missing from the stored 25 counts as NOT_TRENDING, which is sound because 25 > 15.

Invariant (SC-002): `|TRENDING| + |NOT_TRENDING| + |NOT_JUDGED| == totalPickups`.

## Payload additions (`SeasonSuperlativesService`)

- `Kind.TRENDING_TONY` is appended last.
- `TrendingPickupDetail implements DetailRow` has these fields:
  - `playerId`
  - `playerName`
  - `position` (nullable)
  - `rosterId`
  - `week`
  - `addType` (`"WAIVER"` or `"FREE_AGENT"`)
  - `trendingRank`
  - `addCount`
  - `snapshotAt` (`OffsetDateTime`)

  It is added to the sealed `permits` list, and the controller's `detailRow` gets a `"TRENDING_PICKUP"` branch.
- `TrendingCoverage(OffsetDateTime judgingSinceAt, int judged, int notJudged, int totalPickups)` is new. `judgingSinceAt` is the earliest
  snapshot that judged any pickup this season. It is never null on an available card, because no judged pickups means unavailable.
  The browser formats it as a local date (F13).
- `Superlative` gains a trailing 15th component, `TrendingCoverage trendingCoverage`, which is null for every other kind.
  - Add a 14-arg overload that defaults it to null.
  - Switch `withUsernames` and `withStandings` to the 15-arg canonical constructor.
  - Test both rebuilders with a TRENDING_TONY superlative (F12).
- Unit `"PICKUPS"`.
- Standings: every roster. `value` is the count. `note` is `"N of M judged pickups"`, or `"no judged pickups"` (value 0).
- `playerContributions`: every TRENDING pickup across all rosters. `value` is the trending rank. They are ordered by
  rank, then week, then rosterId, and `note` is `"week W · waiver|free agent"`.

## Award states (research R6, amended F2)

| Condition | available | holders | reason / emptyReason | trendingCoverage |
|---|---|---|---|---|
| ESPN league | false | none | "Trending data comes from Sleeper; this league is on ESPN." | null |
| No transactions | false | none | "Transactions for this season haven't loaded yet." | null |
| Zero judged, and (no snapshot at all, or season complete and earliest snapshot > last counted pickup + 24h) | false | none | "Trending history wasn't being recorded yet for this season." | null |
| Zero judged, otherwise | false | none | "Nothing could be judged yet: trending history began on <YYYY-MM-DD> (UTC), and no pickup since has a list within a day of it." | null |

*Amended after code review (2026-10-28, B1 + live NBA finding — spec A5): the rows above replace the
"history did exist / no snapshot ≤ last pickup + 24h" split. `judgingSinceAt` is now the earliest createdAt
among judged pickups (B5). Holder detail is capped at 3 per holder, best rank first (B6). The header query
margin is 48h, the judge applying each snapshot's own lookback (B3).*
| Top count < 2 | true | [] | "nobody has chased the trending list twice yet" | set |
| Otherwise | true | tied top | none | set |

The "<date>" in the fourth row comes from `earliestSnapshotAt(sport)`. It is formatted on the server as an ISO date inside the reason string,
the only date in a reason string. This is acceptable because the reason is shown verbatim, and the UTC date is labelled "UTC".

## Frontend (F6, F7, F8)

- `SuperlativeStandings` gets a new optional prop, `contributionFigure`. It defaults to `figure`, so other awards are unchanged.
- `TrophyCase.tsx` hides `TRENDING_TONY` when it is unavailable. That covers every past season. The Awards page always shows it.
- `LeagueHome.tsx`'s `unitLabel` and `Superlatives.tsx`'s `formatValue` get a `PICKUPS` case.
