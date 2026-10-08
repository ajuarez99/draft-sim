# Code review: spec 022 foundation + US1 (bug hunt)

Scope: `git diff origin/main` in `draft-sim-022` (foundation commit `8d6d1f4` plus the uncommitted US1
tree, untracked files included). Reviewed 2026-10-08. No code was edited.

**Verdict: one real wrong-output defect (B1) should be fixed before US1 ships. B2–B4 are low and can
ship with a note.** The rest of the risky surface (Trends refactor, cache map view, rank direction,
ties, api.ts parity, link gating, rail behaviour) held up.

Labels: **measured** = run against the local DB (5433) or a test run; **read** = traced in code;
**inferred** = follows from the code but not executed.

Suites, measured: web `tsc -b` clean; `vitest run` 89 files, 1,131 tests passed. Backend
`./gradlew test` was UP-TO-DATE against this tree. Its JUnit XML (10:52 today) sums to 1,439 tests,
0 skipped, 0 failures, so no IT skipped silently.

---

## B1 (medium-high): `teamGamesMissed` misses every game after a player's last appearance

- **Evidence**: `AdvancedStats.teamGamesMissed`, `AdvancedStats.java:194-217` (read). The range per
  team is his first to his last game with it, so a season-ending injury adds nothing. The page prints
  it as fact: `PlayerPage.tsx:147-149` "Missed N of his team's games in 2025–26".
- **Measured**: Jimmy Butler (id `1000`), NBA 2025, GSW all season (`player.team` is still GSW).
  - He played 38 of GSW's 82 games, and his last game was 2026-01-19.
  - The F10 rule gives **6**: the GSW games between his first and last game that he didn't play.
    The page would say "Missed 6".
  - `player_absence` holds **44** game-level `ENTRY_WITHOUT_PLAY` rows for him (2025-11-05 to
    2026-04-12). 82 − 38 = 44.
- **Scale (measured)**: in 2025, 99 players with stored games have 10 or more team games after their
  last appearance. For 67 of them, `player_absence` has 10 or more game-level rows after that last
  game. These players were on a team and not playing; they had not left. So the rule's "after he left"
  case swallows real absences.
- **Failure scenario**: any injured-out or shut-down player (Butler, Steven Adams with 42 after his
  last game, Lonzo Ball and others). FR-013 and US1 scenario 4 ask for "how many of his team's games
  he missed". The page states a number about 7× too small, as fact. This is bug class 5: the number
  is correct for the rule but wrong for what the reader assumes.
- **Suggested fix**:
  - For the team of his **last** line, extend the range to that team's last stored game. Do this
    only when there is evidence he stayed, either:
    - game-level `player_absence` rows after his last game (they exist for the 67 above), or
    - `player.team` equal to that code for an in-progress season.
  - Keep the first-to-last rule for teams he left mid-season, which was F10's purpose (traded or
    waived).
  - Add a test with a season-ending injury next to the traded-player test. Amend data-model F10
    visibly; don't rewrite it silently.

## B2 (low): `markRefreshing` is a set, not a count, so overlapping refreshes clear it mid-run

- **Evidence**: `SeasonBoxCache.markRefreshing`, `SeasonBoxCache.java:126-130`, is add/remove on a
  `Set`. `PlayerGameIngestService.refreshSportSeason`, `:149-158`, sets it before `inFlight.run` and
  clears it in `finally` (read). `SingleFlight.run` releases the key **before** completing its future
  (`SingleFlight.java` "Release the key BEFORE completing").
- **Failure scenario (inferred)**:
  1. Run A for `nba:2025` finishes, and its key is released.
  2. Before A's caller reaches `finally`, a second chain (another user's NBA league; on-visit or cron
     refresh, `LeagueRefreshService.java:205`) calls `refreshSportSeason` and starts run B. B's
     `markRefreshing(true)` is a no-op, because the key is already in the set.
  3. A's `finally` removes the key while B is running.
  4. For the rest of B, every `get` sees the `fetched_at` re-stamps as a changed token and reloads
     the whole season. That is about 29k rows of JSON parsing per Trends or player-page request,
     which is the "reload continuously" case the javadoc says the flag exists to prevent.
- **Impact**: performance only. Correctness is no worse than pre-cache Trends, which read live rows.
  It becomes likelier as more NBA leagues share a season.
- **Suggested fix**: replace the set with a per-key `AtomicInteger` counter, incremented and
  decremented in the same try/finally. "Refreshing" is then `count > 0`. Add a test with two
  overlapping marks.

## B3 (low): links on Weekly Report, Roster Management and Trends use the route's league id, not the season they show

- **Evidence (read)**:
  - `WeeklyReport.tsx:659,668,677` and `RosterManagement.tsx:423` pass `sleeperLeagueId` from the
    URL. `PlayerTrends.tsx:386,394,402` passes `id`.
  - Those pages resolve with `Rule.PLAYED_WEEKS` (the default `resolve`), and their payloads carry
    no resolved league id (`api.ts` `WeeklyReport` and `RosterManagement` have only
    `requestedSeason`).
  - The player page resolves with `STORED_GAMES` (`PlayerStatsService.java:192-193`).
  - Superlatives does this correctly through `data.leagueSleeperId` (`Superlatives.tsx:494`).
- **Failure scenario (inferred)**: the window from NBA 2026 opening night (10-21) to the first scored
  fantasy week.
  1. `/leagues/<2026>/weekly-report` shows a 2025 week (PLAYED_WEEKS fallback), with a
     `requestedSeason` note.
  2. Clicking a 2025 best-night player opens `/leagues/<2026>/players/X`.
  3. That page now has 2026 games, so it shows 2026 with one or two games and no fallback note.
  4. The row the reader clicked was about a different season.
- Today (pre-opening) both rules fall back to 2025, so it agrees. The window is a few days per
  season.
- **Suggested fix**: when `requestedSeason` is set, link to the resolved season's league id. Either
  add a `leagueSleeperId` to those payloads, as Superlatives does, or pick it from the lineage by
  `data.season`. Otherwise, document it next to `fallbackNote`'s existing "Trends may show a
  different season".

## B4 (low): a `get` after `invalidate` can join a load that started before it

- **Evidence**: `SeasonBoxCache.reload`, `:136-153` (read). A caller that finds an in-flight future
  joins it, whatever generation that load was stamped with.
- **Failure scenario (inferred)**:
  1. A load starts mid-refresh, with no entry yet and the flag set.
  2. The refresh ends and calls `invalidate`.
  3. A request arriving now joins the still-running mid-refresh load and gets its partly-old rows.
  4. The stored entry carries the old generation, so the **next** `get` reloads. The staleness lasts
     one response.
- **Suggested fix**: optional. Either have `join` re-check the generation and reload once if it
  moved, or accept and note it in the javadoc's "Validity" paragraph.

---

## Checked and fine

- **SeasonBoxCache**: single flight is correct; the in-flight key is released on both success and
  throw, and a load that throws propagates its cause to joiners. Memory visibility is through CHM
  and CompletableFuture. The read-only `Dictionary` `HashMap` is safely published.
  - The token is read before the rows (F8), and an invalidate during a load leaves it stale.
  - `invalidate` and `markRefreshing(false)` are reached on every path of `refreshSportSeason`,
    including a `doRefresh` throw and a SingleFlight joiner.
  - No other writer of `player_game` exists. `upsert` and `deleteByGame` are called only inside
    `doRefresh`, and the manual ingest endpoint goes through `refreshSportSeason`.
- **CompactStats vs consumers**: every consumer reads values through `instanceof Number` or
  `num()`: `GameScoringService`, `AdvancedStats`, `NbaGameLines`, Trends' `oneGameShare` and
  `stat`, `PlayerStatsService`. None iterates stats order, calls `containsKey` semantics, checks
  `instanceof Integer`, or serialises the map. `BasketballRules.playedIn` and `PlayerSpotlight` read
  `PlayerGameRepository.Row`, which is not cached. Measured: every stored NBA stat key the code
  reads (`reb oreb dreb ast stl blk to pf fgm fga tpm tpa ftm fta pts sp plus_minus`) exists in 2025
  `player_game`.
- **Trends refactor**: equivalent on paths the two baseline leagues do not exercise.
  - NFL is gated before any `get`.
  - Absences are still read live.
  - The previous-season measured branch uses the compacted `games()`, which has the same numeric
    values.
  - A player with no team row gets `team` null. That matches the old `teamCode` null, and
    `usage` → `NO_TEAM_ROW` → `value()` null matches the old null.
  - `teamGameCount` is a distinct count in both versions.
  - Team-row pairing (last non-opponent row wins) is unchanged.
  - `byPlayer` is now unmodifiable, and Trends never mutates it.
- **GameScoringService**: still a naive left-to-right sum in scoring-key order, with null inputs
  giving 0. The parity IT (78,572 comparisons, 0 mismatches) agrees.
- **Ranks**: value descending, so rank 1 is the highest FP/G and PTS/G. Competition ranks are on
  the 2-dp value. The inner `.reversed()` applies to games only. The target is always found in his
  own lists. `rankMove = pointsRank − leagueRank` matches the ▲ and the "higher" sentence.
- **Qualification**: `maxTeamGames` is one row per team-game. Measured: 0 duplicate
  `(code, game_id)`; 82 per team, 83 for the two NBA Cup finalists, which are real games. The bare
  `TEAM_` row is excluded.
- **Game score and per-game math**:
  - Game score matches the Basketball Reference formula.
  - per36 is pooled; it is null only with 0 minutes, which can't happen since `sp > 0`.
  - An empty window gives null per-game and zero totals.
  - fpPerGame and seasonTotal both sum the per-game rounded scores. The breakdown sums unrounded
    contributions, so shares sum to about 1.
- **Scoring league**: scoring comes from the resolved season's league row (`scoringOf(league.id())`),
  and ownership from the resolved league. `currentOwnership` comes from the requested league.
- **States**: 404 means no games and no player row. `NO_PLAYER_GAMES` is available with a player
  row. `NO_GAMES` means no player rows in the resolved season. There is no `Map.of` with a possible
  null on these paths.
- **SQL**: `successorOf` and `seasonToken` (`.single()`, `getObject(OffsetDateTime)`) ran in the
  ITs. `getObject(7)` for `is_away` keeps null.
- **Ownership**:
  - Completed seasons read week `playoff_week_start − 1`. Measured: 18 for 2025, 21 for 2024, and
    12 of 12 rosters non-empty in weeks 17–21.
  - A missing start coalesces to 0, which gives UNAVAILABLE.
  - A `{}` roster poisons the week (N6).
  - A null status with no rostered players gives NOT_DRAFTED. `pre_draft` gives NOT_DRAFTED.
- **LeagueSeasonResolver**:
  - `resolve(id)` still defaults to PLAYED_WEEKS, so existing callers are unchanged.
  - `seasons` is cycle-guarded, and an unknown id gives `[]`.
  - With multiple successors the highest season wins, and the walk back always passes through the
    requested league.
- **api.ts vs Java**: every record matches field-for-field:
  - `PlayerStatsPage`, `PlayerRef`, `Window`, `Counting` (15 fields), `Shooting`, `Rate`, `Ranks`,
    `Fantasy`, `BreakdownRow`, `GameLogRow`, `SeasonOption`, `Ownership`, `AsOf`
  - EnumMap keys as enum names
  - `isMe`, the same record-component naming already used elsewhere
- **Web**:
  - `PlayerLink` gates on `playerPagesFor(sport)` only.
  - `inRail:false` is filtered in the rail and the palette, while `destinationFromPath` and
    `leagueIdFromPath` still see the row, so the rail's league context resolves on a player page.
  - `LeagueHome` only looks up `schedule`.
  - A rail switch to an NFL league falls back to league home. `players` isn't offered for NFL, so
    the user lands on home, not on a NOT_BASKETBALL page.
  - A switch to a season with no games lands on the server's labelled fallback.
  - `KeyedByLeagueAndPlayer` remounts on either id.
  - NFL names stay plain through the same gate. Superlatives links use the resolved
    `leagueSleeperId`.

## Dispositions (2026-10-08, parent session)

All four findings were accepted and fixed in a Sonnet pass. The parent session read the diff and
re-checked the fixes live.

| # | Disposition | Evidence |
|---|---|---|
| B1 | **Fixed.** For his last team only, `teamGamesMissed` now adds the team games after his last appearance that have a game-level `ENTRY_WITHOUT_PLAY` absence row for him. A row whose team is a different team is ignored, and a row with a null team is accepted. Later games with no row are not counted, because he may have been waived or traded. data-model "Team games missed" is amended with a dated note. | Measured live: Jimmy Butler, 2025, 38 games, **44 missed**; before the fix it read 6. SQL agrees: 6 inside his span plus 38 later GSW games with absence rows. `PlayerStatsReadIT` asserts 44. |
| B2 | **Fixed.** Refresh marks are a per-key counter, never below 0. | `SeasonBoxCacheTest` has 2 new cases. |
| B4 | **Fixed.** A joiner whose load predates an invalidate reloads once instead of returning stale rows. | `SeasonBoxCacheTest` has 1 new case. |
| B3 | **Fixed.** Weekly Report, Roster Management and Trends link each row to the league id of the season its data came from, via `leagueIdForSeason`. On Trends, the streaming and the risers/fallers tables are resolved separately, because each can fall back on its own. | Measured live: the 2026 Trends page (lists fall back to 2025) has 20 player links, all to the 2025 league `1229352720222134272`. |

After the fixes:

- **Backend**: 1,447 tests, 0 skipped, 0 failures.
- **Web**: `tsc` clean, 1,139 tests passing, build OK.
- **Trends**: after the cache changes, `player-trends` JSON for both leagues is still
  **byte-identical** to the T005 baselines (re-measured live).
