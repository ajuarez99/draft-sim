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

## US2 review (2026-10-08)

Bug-hunting review of the uncommitted US2 diff against 38ee108 (AdvancedStats.advanced, PlayerPercentiles,
PlayerStatsService wiring, PlayerOwnership.rosteredAtSeasonView, api.ts, statCopy.ts, PlayerPage Advanced
section, styles, tests). Read every changed file. Re-ran PercentilesTest + AdvancedStatsWindowTest (green),
and PlayerPage.test + statCopy.test (38/38). Curled C1 live for Jokić (1658) and Gobert (1350). Ran
read-only SQL on :5433. Did not run the full backend suite or a browser.

**Verdict: the backend math and percentile ranking are correct; I found no wrong-direction or crash
defect.** Every R5 formula matches Basketball Reference. TOV% is the only inversion, and it points the
right way. The ownership set agrees with the page's ownership. Findings U1–U3 are gaps between what the page
renders and spec requirements. U4–U7 are wording and display issues.

### U1 (medium): per-36, game score and plus-minus are shown for the season only, not for last 10 and last 5

- **Evidence (read)**: `web/src/pages/PlayerPage.tsx` SeasonLine renders `per36`, `gameScorePerGame` and
  `plusMinusPerGame` from `d.windows.SEASON` only, in the `pp-facts` `<dl>`. AdvancedCard (`:420–492`)
  renders only the 13 rates. The wire already carries all three figures for every window (`Window` record).
- **Failure scenario**: FR-016 and US2 acceptance 1 require "every stat listed above … for the season and for
  the last 5 and last 10 games, side by side". The list includes per-36, game score and plus-minus. A reader
  cannot see a player's last-5 game score or per-36 anywhere. The season per-36 also shows only
  pts/reb/ast, while FR-016 asks for "per-36-minute traditional stats".
- **Fix**: add rows to AdvancedCard for per-36 (the counting set), game score per game and plus-minus per
  game (noisy), one value per window column. These rows get no percentile. Add a test that asserts last-5
  and last-10 values appear.

### U2 (medium): an unranked player is never told why, and every reason sentence in the Advanced card needs hover

- **Evidence (read)**: `PctLine` (`PlayerPage.tsx:394–403`) and `RateValue` show only `reasonShort`
  ("not ranked", "no att.", "group too small"). The sentence is only in `title`/`aria-label`. The card's
  header prints a sentence only for `NOT_QUALIFIED_STALE` (`stale(kind)`). SeasonLine has a `pp-notes`
  list for this; AdvancedCard does not.
- **Failure scenario**: suppose a player has played 10 games at 12 mpg. His LAST_10 cells read "not ranked"
  and nothing visible says he falls short of the 15-minute rule. On a phone, `title` cannot be reached at
  all. This breaks US2 acceptance 4 ("is not ranked, and the page says why") and FR-018 ("with the reason
  stated"). The SEASON window happens to be covered by RanksCard's sentence, but only because both use the
  same qualification. A test also pins the hover-only form for NO_ATTEMPTS.
- **Fix**: reuse SeasonLine's notes pattern. Below the table, list each distinct reason code present in the
  shown cells (rates and the selected group's percentiles) as `short: sentence`. Optionally put a
  `NOT_QUALIFIED` sub-line in the column header, as is already done for stale.

### U3 (medium): the peer group's qualification rule and the rostered group's as-of are not stated

- **Evidence (read)**: the card's copy says "other qualified players" and `pctGroupLabel` gives "vs Cs
  across the NBA" / "vs players rostered in this league". C1 does not carry the rule
  (`rank-min-games-share` 0.5, `rank-min-minutes-per-game` 15, recency 14 days). The rostered group's
  as-of is not shown next to the toggle.
- **Failure scenario**: US2 acceptance 4 requires naming the peer group's "minimum-minutes rule". FR-030
  requires the rostered group's "size and ownership refresh time are stated, alongside". Measured on the
  live 2025 league: the rostered group is the week-18 (end of regular season) rosters
  (`ownership.asOf.week = 18`). If the route is a 2026 league that fell back to 2025, the button still
  says "players rostered in this league" without saying it means last season's week-18 rosters. A reader
  takes that as today's rosters (bug class #5).
- **Fix**: add the qualification numbers to C1 (or reuse C2's `qualification` block) and print them, e.g.
  "qualified: 15+ min/game, half his team's games; last-N: all N, within 14 days". Print the ownership
  as-of next to the rostered toggle with the Header's `ownershipAsOf(...)`, e.g. "rostered as of week 18,
  2025".

### U4 (low-medium): the "Share of team minutes" definition is wrong by a factor of 5

- **Evidence (read + measured)**: the formula is `MP / (TmMP/5)`, the share of the game's minutes he was on
  the floor (data-model:118). The definition (`statCopy.ts` ADVANCED_DEFINITIONS.minutesShare) says "The
  share of his team's total player minutes that he played". Live, Jokić shows 71.6% and Gobert 64.9%. Read
  literally, no player can play 71% of his team's 240 player-minutes; the literal share is about 14%.
- **Failure scenario**: a correct number displayed as the wrong thing (bug class #5).
- **Fix**: reword it to "The share of his team's game minutes he was on the floor (48 of 48 is 100%)." The
  label could be "Share of game minutes".

### U5 (low): bar meters on style stats read as good or bad, and "fewer is better" sits beside a percentile where higher is better

- **Evidence (read + measured)**: every percentile gets the same teal `Meter`. TOV% is inverted so that a
  fuller bar means better, which teaches the reader that a full bar is good. FTr, 3PAr, USG and minutes
  share are style or role stats, not quality stats. Live, Gobert's 3PAr is the 4th percentile among
  rostered players and his USG is the 4th percentile, both with near-empty bars. Separately, the TOV cell
  reads "16th percentile · 80 others · fewer is better". That phrasing invites "a low percentile is good",
  which is the opposite of the inverted value. Only the collapsed `<details>` definition says "a high
  percentile here means few turnovers".
- **Fix**: say in the card that a percentile is a position in the group, not a grade. Either drop or
  neutralise the meter for ftr/tpar/usg/minutesShare, or label it "more often than X% of …". For TOV,
  print "higher percentile = fewer turnovers" instead of "fewer is better".

### U6 (low): rounding can print "100th percentile" for someone who is not alone at the top

- **Evidence (inferred from code)**: `ordinal` rounds `Math.round(value)`. With n = 162 and one tie at the
  top, the value is (161 + 0.5)/162 = 99.69, which prints "100th". A value of 0.3 prints "0th".
- **Fix**: floor the value, clamp it to 1..99 unless it is exactly 0 or 100, or show one decimal near the
  ends.

### U7 (low): two small copy and doc mismatches

- `GROUP_TOO_SMALL` is also returned when the player has **no position** (`PlayerPercentiles.pct`,
  `position == null`, n = 0). The sentence says "Too few other players qualify", which is false there.
- The `PlayerPercentiles` javadoc says values are compared "at the wire precision (two decimals) … so
  shown-equal figures tie". The UI shows rates to one decimal (`fixed(r.value, 1)`), so two cells that both
  show "61.6%" can still rank apart. The behaviour matches data-model's 2-decimal rule; only the "shown"
  claim is untrue.
- C1 (`contracts/api.md:39`) lists four Pct reasons. The code also returns the player's own rate reason
  (`NO_ATTEMPTS`/`NO_MINUTES`/`NO_TEAM_ROW`). `api.ts` has it right; the contract text is stale.

### Checked and fine

- **R5 formulas against Basketball Reference**: TS, eFG, FTr, 3PAr, TOV% (FGA + 0.44 FTA + TOV), USG via
  `usage()`, AST% (`MP/(TmMP/5)·TmFG − FG`), ORB%/DRB%/TRB% (team plus opponent reboundable), STL% (opponent
  possessions = R5 `Poss`, and the `T(A,B)` ORB share uses `A.oreb + B.dreb`), BLK% (OppFGA − Opp3PA). Units
  are percent points throughout. TmMP = team `sp/60`; the team row's `sp` is 14,400 in regulation and
  17,400 with overtime (measured). The team-row keys `to`, `oreb`, `dreb`, `reb`, `tpa`, `fgm` and `sp`
  are present (measured).
- **Exclusion is consistent**: `pooled()` drops a game from the numerator, the denominator and the minutes
  together. Shot-based rates use every game. All 1,231 stored games in each of 2024 and 2025 have both team
  rows (measured), so exclusion is theoretical today.
- **Jokić AST% −1.7 is pooling, as the parent attributed (measured)**: SQL over his 65 games gives 48.58
  pooled per game, and 50.34 using Denver's whole-season TmFG/TmMP. Basketball Reference shows 50.3.
- **Percentile direction**: higher ranks higher everywhere except tovPct. Live, Jokić's TOV% 15.3 is the
  16th percentile among Cs (inverted correctly) and his AST% is 100th. No other R5 rate here is
  lower-is-better. Formula `100(below + 0.5·ties)/n` with self excluded; a free agent ranks against the
  whole group; members with a null rate are dropped per rate; ties compare at round2 on both sides; the
  value stays in [0, 100].
- **Groups and reasons**: the first listed position on both sides; per-window qualification (stale
  included) uses the same `qualify` and `minGames` as the target. Precedence: not qualified → own rate →
  OWNERSHIP_UNAVAILABLE → GROUP_TOO_SMALL (n < 2). `rosteredAtSeasonView` uses the same gates as
  `forSeasonView`: pre_draft/drafting and null-status-empty go to OWNERSHIP_UNAVAILABLE; a completed
  season uses week `playoff_week_start − 1` with the N6 poison rule; the facts come from the answered
  (fallback) season's league. The test asserts that the set and `forSeasonView` agree.
- **Performance**: the population is about players × 3 windows × one `advanced()` pass (linear in lines),
  and ranking is about 3 × 13 × 2 × members. Nothing is O(players²) per rate. This agrees with the
  measured 0.18 s warm.
- **Wire and types**: `Advanced.byKey()` is not serialized (measured: a window's `advanced` has exactly
  the 13 keys). `api.ts` `PlayerAdvanced`, `Pct`, `PctReason` and `PlayerPercentiles` (window → key →
  [NBA_POSITION, LEAGUE_ROSTERED]) match the Java records field for field, including nullability. The
  `unavailable()` path sends `{}`, which the optional chaining handles.
- **Web states**: an own-rate reason hides the percentile and shows the rate reason, never 0% or a blank.
  A real 0.0 still shows. Missing Pct → "unavailable". "N other(s)" is shown. The toggle uses
  `aria-pressed`. Definitions are `<details>`, reachable by tap. `.pp-adv` scrolls inside `.pp-wrap`
  (`overflow-x: auto`) and the toggle wraps, so the CSS should not cause page-level horizontal scroll
  (inferred from the CSS, not checked in a browser).
- **Tests**: PercentilesTest asserts the ordering both ways (TOV inversion and higher TS → higher). The
  AdvancedStatsWindowTest hand values recompute correctly against the R5 formulas. I found no test that
  cannot fail.

### US2 dispositions (2026-10-08, parent session)

All seven findings were accepted and fixed (Sonnet pass), then re-checked in the browser on Gobert
and Ayton (2025):

- **U1**: per-36 (pts, reb, ast, stl, blk, 3PM, TO), game score and plus-minus (noisy) now show
  for Season, Last 10 and Last 5. Live, Gobert's points per 36 read 12.5 / 14.1 / 10.2.
- **U2**: a visible reason-notes list sits under the tables; Ayton shows "no att.: No attempts in
  these games…".
- **U3**: the qualification rule is stated on the page. The fix pass first **hard-coded** the
  thresholds in `statCopy.ts`, mirroring `weights.yml`. The parent rejected that, because it is a
  second copy of one rule, and had it moved to the wire. C1 now has a new
  `qualification: QualificationRule(minGamesShare, minGames, maxTeamGames, minMinutesPerGame,
  recencyDays)` field (contracts C1 amended).
  - Live: "Ranked among players with at least 42 games (half of the most any team has played, 83)
    and 15+ minutes per game; last-5/last-10 also require a game in the last 14 days."
  - The rostered group names its as-of, and on a fallback it states that those are the earlier
    season's rosters.
- **U4**: the label is now "Share of game minutes played", and the definition is corrected
  (MP / (TmMP/5)).
- **U5**: style stats (FTr, 3PAr, USG, minutes share) have neutral bars, 12 of them on Gobert's
  page. TOV% reads "higher percentile = fewer turnovers".
- **U6**: ordinals are clamped to 1st–99th; no "100th" or "0th" appears live.
- **U7**: `GROUP_TOO_SMALL` has its own sentence for a player with no position on file, and the
  contracts C1 reason list is completed.

**Totals after the fixes**:
- Backend: 1,472 tests, 0 skipped.
- Web: 1,171 tests passing; `tsc` and build clean.
- Phone (375 px): no page-level sideways scroll.

**One number to watch**: "the most any team has played, 83" is the NBA Cup final finding (see
verification.md). Only NYK and SAS have an 83rd game.
