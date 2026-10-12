# Final wire shapes (after plan review). Both build agents code against this file.

Where this file and `api.md` disagree, this file wins. Java first, then the TS mirror that
goes in `web/src/api.ts`. Field names and order must match exactly.

## 1. `LeagueAnalysisService.Projections` (changed), TS `AnalysisProjections`

```java
public record Projections(boolean available, String reason, String reasonCode,
                          List<String> positionGroups, List<RosterProjection> rosters,
                          String unit,                       // "REST_OF_SEASON" | "PER_WEEK"
                          String basisNote,                  // null | "ONE_AVERAGE_GAME_PER_STARTER"
                          ProjectionProvenance provenance,   // nullable
                          List<String> unprojectedCategories,// never null; [] for NFL
                          int staleAfterDays) {}             // from draftsim.preseason.stale-after-days
public record ProjectionProvenance(String source, Instant sourceModifiedAt, Instant fetchedAt) {}
```
```ts
export type ProjectionUnit = 'REST_OF_SEASON' | 'PER_WEEK'
export type ProjectionReasonCode =
  | 'NOT_BUILT_FOR_SPORT' | 'PROJECTIONS_NOT_LOADED' | 'SCORING_NOT_LOADED' | 'ROSTERS_EMPTY'
  | 'UNSUPPORTED_SLOTS' | 'NO_PLAYOFF_WEEK_START' | 'SEASON_OVER' | 'STRENGTH_FAILED'
export type ProjectionProvenance = { source: string | null; sourceModifiedAt: string | null; fetchedAt: string | null }
export type AnalysisProjections = {
  available: boolean
  reason: string | null
  reasonCode: ProjectionReasonCode | null
  positionGroups: string[]
  rosters: AnalysisRosterProjection[]
  unit: ProjectionUnit
  basisNote: 'ONE_AVERAGE_GAME_PER_STARTER' | null
  provenance: ProjectionProvenance | null
  unprojectedCategories: string[]
  staleAfterDays: number
}
```
Existing unavailable paths get codes too: `playoffWeekStart < 2` gets `NO_PLAYOFF_WEEK_START`,
`fromWeek > toWeek` gets `SEASON_OVER`, and a non-NFL/NBA sport gets `NOT_BUILT_FOR_SPORT`. NFL
no-rows keeps its sentence and gets code `PROJECTIONS_NOT_LOADED`.

*Amended after code review:* (N1) an empty scoring map for basketball gets its own code
`SCORING_NOT_LOADED` instead of `PROJECTIONS_NOT_LOADED`. `STRENGTH_FAILED` is raised only by
`season-start` when the strength pass threw (R2); the Analysis block never carries it. (R6) Basketball
skips the `NO_PLAYOFF_WEEK_START` and window gates; its `SEASON_OVER` is `league.complete()`. NFL is
unchanged. (N2) `unprojectedCategories` ignores scoring keys with weight 0. (N3) The nine-slot guard
compares slots as multisets, so order does not matter.

## 2. `RosterProjection` (changed), TS `AnalysisRosterProjection`

*Amended after live check 2026-10-12:* the appended field is `int unavailable` (was `reserve`),
the count of rostered players tagged Out, IR or Sus (case-insensitive) and set to 0 wherever they
sit (NBA path). It's always 0 for NFL. TS: `unavailable: number`. Those players appear in `bench`
with `slot: "OUT"` and `points: 0`. Players in `roster.reserve` (IR+) who are not so tagged count
normally. `LineupPlayer` gains a last field `boolean onReserve` (TS `onReserve: boolean`), true when
the player is in `roster.reserve`; always false for NFL. For NBA, `byWeek` is `[]`.

## 3. `GET /api/leagues/{sleeperId}/season-start` (new), Java `SeasonStartService.SeasonStart`

```java
public record SeasonStart(String sport, int season, String state, FirstWeek firstWeek,
                          SeasonStrength strength, SeasonDraft draft) {}
// state: "PRE_DRAFT" | "DRAFTED_UNSCORED" | "IN_SEASON" | "COMPLETE"
public record FirstWeek(Integer week, String startDate /* "YYYY-MM-DD" or null */,
                        StartersWithoutGame startersWithoutGame /* nullable: NFL, schedule not loaded, or strength unavailable */) {}
public record StartersWithoutGame(int week, List<IdleStarter> mine /* [] = all play; null = caller has no roster or no projected starters */,
                                  Map<Integer, Integer> countByRoster) {}
public record IdleStarter(String sleeperPlayerId, String name, String team) {}
public record SeasonStrength(boolean available, String reason, String reasonCode, String unit,
                             List<RosterProjection> top /* first 3, starters=[] bench=[] */,
                             RosterProjection me /* nullable: caller's row if not in top */,
                             int teams, Favorite favorite /* nullable */,
                             ProjectionProvenance provenance, List<String> unprojectedCategories,
                             String basisNote, int staleAfterDays,
                             List<LeagueAnalysisService.TeamLabel> teamLabels /* every roster; [] when unavailable */) {}
public record Favorite(List<Leader> leaders, boolean tie, double leaderTotal, Double nextTotal,
                       Double marginPoints, Double marginPct, double minGapPct) {}
// nextTotal/marginPoints null when every roster is a leader; marginPct is also null when nextTotal <= 0
// (infinite gap).
// marginPoints is always pts/week (NFL: rest-of-season gap / window weeks), 1 decimal.
public record Leader(int rosterId, String teamName, String username, String avatarId, double total) {}
public record SeasonDraft(String sleeperDraftId /* nullable */, DraftGradeSummary grades /* nullable */) {}
public record DraftGradeSummary(TeamGradeLite best, TeamGradeLite worst, boolean gradesEarly,
                                int earlyThresholdWeeks) {}
public record TeamGradeLite(String manager, String avatarId, String grade, Integer rank) {}
```

*Amended after code review* (all of the following):
- (B1, client) The headline's `leaderTotal` and `nextTotal` are labelled with the strength's unit
  ("pts (projected)" for `REST_OF_SEASON`, "pts/week (projected)" for `PER_WEEK`); only `marginPoints`
  is always "pts/week".
- (B2/N6) `startersWithoutGame` is null for football, when the schedule is not loaded, or when strength
  is unavailable. `mine` is null (not `[]`) when the caller has no roster or no projected starters. The
  client says nothing about idle starters when strength is unavailable or `mine` is null, and says
  "the schedule isn't loaded" only when strength is available and `startersWithoutGame` is null.
- (R2) A throw in the strength pass (or in the draft-grades read) no longer fails the endpoint: `state`
  and `firstWeek` are still answered, `strength` is `{available:false, reasonCode:"STRENGTH_FAILED"}` with
  a sentence, and `draft.grades` is null.
- (R7) `firstWeek.week` is `ScoredWeeks.latestFinal + 1` (1 in `DRAFTED_UNSCORED`), not Sleeper's current
  leg; the date comes from the schedule grid's week of that number, with the same fallback as before.
- (N4) `SeasonStrength.teamLabels` is appended: every roster's name (team name, else username, else
  "Roster N", the same lookup `/analysis` uses). The client labels every row, top three and "me", from it,
  and `Leader.teamName` is filled from it too. The wire is otherwise unchanged.
- (N7) Trimmed rows carry `byWeek: []` as well as empty `starters` and `bench`.

The rules:
- `firstWeek` is non-null only in `DRAFTED_UNSCORED`.
- `strength` is non-null only in `DRAFTED_UNSCORED`.
- `draft.grades` is non-null only in `IN_SEASON`, and only when grades are `available`.

TS mirrors use the same names: `SeasonStart`, `SeasonState`, `FirstWeek`,
`StartersWithoutGame`, `IdleStarter`, `SeasonStrength`, `SeasonFavorite`, `SeasonLeader`,
`SeasonDraft`, `DraftGradeSummary`, `TeamGradeLite`. Nullable Java fields become `T | null`.
`Map<Integer,Integer>` becomes `Record<string, number>`. Instants become ISO strings.
Add `getSeasonStart(sleeperLeagueId: string): Promise<SeasonStart>`, following
`getNextMatchup`'s pattern (shared not-found handling).

## 4. Favorite math (pure static `SeasonStartService.favorite(List<RosterProjection> ranked, double minGapPct, int weeksForPerWeek)`)

- `gapPct(a, b) = (a − b) / b × 100`, with `b` the lower total. If `b ≤ 0`, the gap is
  treated as infinite.
- `leaders` = rank-1 roster(s), plus every roster x with `gapPct(t1, x) < minGapPct`.
- `tie = leaders.size() > 1`.
- `nextTotal` = the total of the first non-leader.
- `marginPoints = (t1 − nextTotal) / weeksForPerWeek`. `weeksForPerWeek` is 1 for NBA and the
  window's week count for NFL.
- `marginPct = gapPct(t1, nextTotal)`, 1 decimal.
- Return null if there are fewer than 2 rosters.

## 5. Config (`config/weights.yml` under `draftsim:`)

```yaml
  preseason:
    # Hand-set and ARBITRARY, not fitted. ... (see tasks T031 amendment)
    favorite-min-gap-pct: 3.0
    stale-after-days: 3
```
`PreseasonProperties(@Positive double favoriteMinGapPct, @Positive int staleAfterDays)`,
`@Validated`, prefix `draftsim.preseason`, registered in `DraftSimApplication`.
