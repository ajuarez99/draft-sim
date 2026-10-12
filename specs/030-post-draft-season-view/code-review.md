# Code review: spec 030 (bug hunt, not a style pass)

**Reviewed**: 2026-10-11. I read the uncommitted diff and the untracked files in
`.claude/worktrees/030-post-draft-season-view` against `HEAD` (943b303), with
`contracts/wire-final.md`, the tasks.md amendments and plan-review.md alongside.

**How each claim is known.** *Verified (ran)* means I executed something this session.
*Verified (read)* means I traced it in the code. *Inferred* means I reasoned it from what I
read and didn't execute it.

**What I ran:**
- **Backend unit tests**: `LeagueAnalysisServiceTest` (30), `SeasonStartFavoriteTest` (9),
  `SeasonStateServiceTest` (9), `SeasonProjectionIngestServiceTest` (7), `ScheduleGridServiceTest`
  (25), `DailyRefreshServiceTest` (14), `PreseasonPropertiesTest` (3) and `IngestControllerTest`
  (9). All passed, 0 skipped.
- **Backend ITs**: `PlayerSeasonProjectionRepositoryIT` (4) and `SeasonStartControllerIT` (4) ran
  against Postgres 5433. They passed with 0 skipped, so the jsonb and timestamptz binds,
  including a null `source_modified_at`, work at runtime.
- **Web**: `npx tsc -b` is clean. Vitest on LeagueHome, LeagueAnalysis, PreseasonStrengthBlock,
  projectionCopy and destinations: 143/143 passed.
- **Not run**: the full backend suite. The 8080 server wasn't reachable when I tried a read-only
  GET, so no live check was made.

---

## Bugs

### B1. The NFL favorite headline labels rest-of-season totals as "pts/week"
- **Where**: `web/src/components/PreseasonStrengthBlock.tsx:28`, `:37`, `:53`.
- **The problem**: `Headline` hard-codes "projected pts/week" after `leaderTotal` and
  `nextTotal`. On the football path those are **rest-of-season** totals (`unit:
  REST_OF_SEASON`). Only `marginPoints` is divided down to per week (`SeasonStartService.java:224-225`, `:270`).
- **Failure scenario**: an NFL league in `DRAFTED_UNSCORED` (next August, or any NFL league
  between its draft and its first final week) gets a headline like this:

  > Projected strongest roster: X, by 4.1 pts/week (3.8%)
  > 1156.7 projected pts/week. Next-best roster: 1114.2 projected pts/week.

  The total is about 14× too large for its label. The rows underneath correctly say "pts
  (projected)" through `unitLabel`, so the block contradicts itself.
- **Why the tests miss it**: every PreseasonStrengthBlock test fixture is `PER_WEEK`
  (`testSeasonStart.ts:44`). No test covers `REST_OF_SEASON`.
- **Fix**: label the two totals with `unitLabel(strength.unit)`, which means passing `unit`
  into `Headline`, and keep "pts/week" only on `marginPoints`. Add a `REST_OF_SEASON`
  fixture test.
- **How known**: verified (read).

### B2. "The week 1 schedule isn't loaded yet." shows when the schedule *is* loaded but the projections aren't
- **Where**:
  - `web/src/pages/LeagueHome.tsx` `idleStartersNote` (the `!swg` branch).
  - `backend/.../engine/SeasonStartService.java:186` (`if (!projections.available()) return null;`).
- **The problem**: `startersWithoutGame` is null in three cases: football, schedule not
  loaded, **or projections unavailable**. That last case covers `PROJECTIONS_NOT_LOADED`,
  `ROSTERS_EMPTY`, `UNSUPPORTED_SLOTS` and an empty scoring map. The client can't tell them
  apart and always blames the schedule.
- **Failure scenario**: NBA preseason with the schedule ingested, but the daily
  season-projection step hasn't run yet or failed (best-effort). The NBA home then says
  "The week 1 schedule isn't loaded yet." beside a "Projections haven't loaded" strength
  block. The schedule page shows the schedule fine. That's a false statement.
- **Fix**:
  - When `strength.available` is false, render no idle-starters note at all. Starters only
    exist when there's a lineup.
  - Or have the server distinguish the cases, for example a `reasonCode` on
    `StartersWithoutGame`, or a non-null object with `mine: null`.
  - Also note that `wire-final.md` §3 says "nullable: NFL, or schedule not loaded", but the
    code adds a third null cause. Amend the contract or the code.
- **How known**: verified (read).

---

## Risks

### R1. `RosterProjection.total` is summed in an order that changes every JVM start. This explains the NFL parity diff, and it isn't caused by 030.
- **Where**:
  - `LeagueAnalysisService.java` `projectRosters`: `total += a.value()` over the lineup.
  - `FootballRules.startingLineup` (`:281`) iterates `LeagueSettings.dedicatedStarters()`.
    That's a `Map.of(...)` (`LeagueSettings.java:58-66`), and its iteration order is salted
    per JVM. The existing comment at `LeagueAnalysisService.java:798-802` already says so
    about card order.
- **Evidence (verified, ran a diff of `parity/`)**: `nfl-analysis-after.json` and
  `nfl-analysis-before2.json`, with the new fields stripped, differ in exactly one value:
  - Roster 7's `total` is 1156.6 after and 1156.7 before.
  - The starters, slots, per-player points, `byPosition`, bench, `byWeek` and `missing` are
    all identical.
  - `numeric(7,2)` per-week values summed into doubles land on a .x5 boundary, and the
    addition order decides which way `round(…,1)` goes.
- **Consequence (inferred)**:
  - T042's "byte-identical NFL" can fail on a server restart with no code change.
  - A rank tie and the favorite's tie and margin can flip by 0.1 between deploys.
  - The extraction didn't change the order. This is pre-existing, so it shouldn't be read
    as a 030 regression.
- **Fix (separate change, because it moves NFL output)**: sum `total` in a fixed order. For
  example, sum after `starters.sort(byLineupCard(...))`, or sum the unrounded values sorted
  descending, or use `BigDecimal`. For this spec, record in `notes.md`/T042 that the one
  0.1 diff is explained by this, not by the 030 code.

### R2. One Sleeper or DB failure in the strength pass takes down the whole `season-start`, state included
- **Where**: `SeasonStartService.java:133`. `analysis.projectionsRead(...)` has no
  try/catch, and `LineupPass`'s constructor calls `sleeper.rosters(...)` live
  (`LeagueAnalysisService.java:847`).
- **Failure scenario**:
  1. A Sleeper hiccup during `DRAFTED_UNSCORED` makes `/season-start` return 500.
  2. The home takes the error fallback and drops to the pre-030 rule. The draft is complete,
     so it renders the "not pre-season" layout: StandingsBlock, PowerBlock and AwardBlock
     come back (the blocks FR-003 removes), and neither the strength block nor the draft
     block shows.
  3. The state is computable, but it gets lost with the failed pass.
- **Fix**: catch around `projectionsRead`. Return `strength` as unavailable with a reason
  code and a null `firstWeek.startersWithoutGame`, and still answer `state`. The same idea
  applies to `draftGrades.read` in `IN_SEASON`.
- **How known**: verified (read); the failure itself is inferred.

### R3. The `IN_SEASON` home now waits for `/season-start`, and that endpoint runs the full draft-grades read on every load
- **Where**:
  - `LeagueHome.tsx`: `settling` hides Matchup, Standings, Power and Awards until
    `seasonStart` resolves.
  - `SeasonStartService.java:139`: `draftGrades.read(draft)` in `IN_SEASON`.
- **What that read does**: `players.findAll(sport)`, all picks, and every drafted player's
  game rows, each scored (`DraftGradesService.java:165-215`).
- **Consequence (inferred, not measured)**:
  - On the live NFL league, the four main blocks no longer render their own skeletons. They
    are absent until the grades pass finishes.
  - Every rail-driven `version` bump repeats this, because `useBlock` resets to `loading`.
  - That's a visible change to `IN_SEASON` beyond "the grades block", which the amended
    FR-004/SC-005 allow, plus a per-load fan-out cost. The multi-user audit memory shows
    that cost has bitten this app before.
- **Fix**: measure `/season-start` latency on the NFL league. If it's material, don't gate
  the `IN_SEASON` blocks on `settling` once a previous state is known, or fetch the grades
  summary separately.

### R4. A rejected or empty season-projection fetch reports `DONE` on the daily refresh
- **Where**: `SeasonProjectionIngestService.java:73-75`, `:90-93`, `:110-114` and
  `DailyRefreshService.java:174-176`.
- **The problem**: a `gp != 1.0` rejection, a null body and a set with no `pts` row all
  return a normal `Result` with `rowsStored=0`. The daily step then reports `Outcome.DONE`,
  detail `rowsStored=0 rowsDeleted=0`.
- **Failure scenario**: Sleeper switches the endpoint to season totals. Every daily run
  "succeeds", the stored set silently ages, and the only signals are a WARN log and, after
  3 days, the page's "N days old" line.
- **Fix**: add `rejected`/`reason` to `Result` and map it to
  `DONE_SEASON_PROJECTIONS_FAILED_BEST_EFFORT` with that reason. That's still not `FAILED`,
  so the run still answers 200.
- **How known**: verified (read).

### R5. A truncated but non-empty payload prunes every missing player
- **Where**: `PlayerSeasonProjectionRepository.replaceAll` (`:202-215`).
- **The problem**: the guard covers an empty set only (`SeasonProjectionIngestService.java:110`).
- **Failure scenario**: a partial response (say 40 of 529 projected rows) is accepted and
  deletes 489 lines. Rosters then project with most starters "missing", at 0. The ranking
  is wrong until the next good fetch 6+ hours later.
- **Fix**: refuse a set that shrinks the stored count by more than a stated, ARBITRARY
  share. For example, keep the stored rows when `rows.size() < 0.5 × stored.rows()`, log it,
  and surface it as best-effort failed (see R4).
- **How known**: inferred.

### R6. NBA strength is gated on football window rules it doesn't use
- **Where**: `LeagueAnalysisService.projectionBlocks`. The `playoffWeekStart < 2` gate
  (`NO_PLAYOFF_WEEK_START`) and the `fromWeek > toWeek` gate (`SEASON_OVER`) run before the
  NBA branch.
- **The problem**: for `PER_WEEK` the window doesn't value anything. An NBA league whose
  playoff format isn't stored (or reads 0) gets "this league has no playoff_week_start, so
  there is no regular season to project to the end of". That sentence is about a
  rest-of-season projection the NBA page doesn't make.
- **Status**: both live NBA leagues pass, per the parity captures. This would be a latent
  false message, not a live one.
- **Fix**: for NBA, keep only the season-over gate (or derive it from `league.complete()`)
  and skip `NO_PLAYOFF_WEEK_START`.
- **How known**: verified (read).

### R7. `firstWeek` is "the current leg", which can move past week 1 while the state is still `DRAFTED_UNSCORED`
- **Where**: `SeasonStartService.java:149-167`.
- **The problem**: the state keys on `latestFinal == 0`, but the week and date key on
  `leagues.currentLeg`.
- **Failure scenario (inferred)**: if Sleeper advances `leg` to 2 before week 1 is final, the
  header says "first games {week 2's first date}" and the idle-starter note talks about
  week 2. Both are wrong.
- **Fix**: use week 1, or `latestFinal + 1`, for the "first games" date, independent of
  `leg`. V8 should note when `leg` and finality flip relative to each other.

### R8. NFL in `DRAFTED_UNSCORED` runs two full lineup passes per home load
- **Where**: `LeagueHome.tsx` fetches `/analysis` for NFL, and `/season-start` computes
  strength through `projectionsRead`.
- **The problem**: each pass fetches the 12 Sleeper rosters again. S2 removed this cost for
  `IN_SEASON` only.
- **Status**: not live until next August.
- **Fix**: accept it and say so in the plan, or have the NFL home take its next-opponent
  block from the same read.
- **How known**: verified (read).

---

## Nits

- **N1. `PROJECTIONS_NOT_LOADED` is overloaded.**
  - `LeagueAnalysisService.basketballBlocks` uses the code for an empty scoring map as well
    as for no stored lines.
  - `projectionCopy.reasonText` maps the `PER_WEEK` code to "Basketball projections haven't
    loaded yet. They refresh daily…", which is false when the problem is missing scoring
    settings.
  - Fix: give the scoring case its own code, or don't map that sentence when `reason`
    mentions scoring.
  - Verified (read).
- **N2. Zero-weight scoring keys would show as "Not projected".** `unprojectedCategories`
  is `scoring.keySet()` minus the projected keys. A league whose `scoring_json` carries a
  key at weight 0 would have it listed. The live NBA league has 15 keys and none at 0
  (verified, live Sleeper call), so this isn't live. Fix: filter `v != 0`.
- **N3. The slot guard is order-sensitive.** `basketballBlocks` compares `starting.equals(BASKETBALL_STARTING_SLOTS)`
  as lists, so the same nine slots in a different order are rejected as `UNSUPPORTED_SLOTS`.
  Compare as multisets if Sleeper ever reorders. Inferred.
- **N4. Row labels are inconsistent.** PreseasonStrengthBlock gets team names only from
  `favorite.leaders`, so a leader row shows the team name and a non-leader row in the top
  three shows the username. In `season-start-1414306784801239040.json`, roster 4 has no
  manager and no team name, so it renders as "roster 4". Consider carrying `teamName` on
  the strength rows, or using the shared team-label lookup.
- **N5. `marginPct` can be null while `nextTotal` isn't.** That happens with an infinite gap,
  when the next total is ≤ 0. The TS doc says the margins are null only "when every roster
  is a leader". The headline handles it by dropping the margin. Just align the comment and
  the contract.
- **N6. A "you play every game" claim for an empty roster.** `idleStartersNote` says "All
  your projected starters play in week N" when `mine` is `[]`. That's also the answer for a
  member whose roster has no starters, for example an empty roster in a league that isn't
  `ROSTERS_EMPTY`. It's an edge case, but the sentence makes a claim from nothing.
- **N7. Top rows still carry `byWeek`.** `SeasonStartService.trimmed` empties `starters` and
  `bench` but keeps `byWeek`. For NFL that's three 10–14-week series on every home payload
  that nothing reads. Clear it too. That's harmless to the wire type, which is still an array.
- **N8. A partial week 1 shows in `DRAFTED_UNSCORED`.** `scoredThisSeason` lets the latest
  matchup block render during the stored-but-not-final week 1 (B2's window), with partial
  live points, next to the preseason strength block. That's arguably correct, since it's
  this season's data. Make sure V8 looks at it.
- **N9. A null id element is now dropped.** Before, a JSON null inside `players` counted as
  `missing`; now `ids()` drops it. Sleeper doesn't send that, so NFL parity is unaffected.
  Recorded only for completeness.

---

## Checked and found correct

- **NFL behaviour after the static-core extraction** (verified, read plus the parity diff):
  - Same player iteration order, same `missing` rule, same bench sort (stable, points
    descending), same `isMe` string compare, and the same manager and avatar sources.
  - `reserve` is always 0 because `excludeReserve` is NBA-only.
  - Same `byLineupCard` sort, same rounding, `rankAll` and `withWeekly` unchanged apart from
    carrying `reserve`.
  - The matchup block reads the same one-week query, now lazily after the fixtures check.
  - The new behaviour is intended: `ROSTERS_EMPTY` when every roster is empty, plus one extra
    provenance query per `/analysis`.
  - The only NFL JSON diff in `parity/` is R1's pre-existing 0.1.
- **NBA reserve handling** (verified, read; the capture shows `reserve: 1`/`2`):
  - Reserve players are excluded before valuation, aren't counted as `missing`, and are
    listed on the bench at 0 with slot `IR`.
  - `players: null` and `reserve: null` are handled by `ids()`.
  - The matchup block is fed the same `pointsByPlayer`, and `pass.over` re-applies the IR rule.
  - `byWeek` is `[]`. Scoring comes from `leagues.scoringOf`.
  - The `unprojectedCategories` rule matches the amendment (keys on zero lines, nothing
    hard-coded). The live capture lists 5 and 3 keys.
- **Ordering** (verified, read): `rankAll` ranks the highest total first. `favorite` sorts
  descending and takes the max, `leaders` uses `gapPct(t1, x)` over the lower total, and
  `marginPoints` is divided by the window's weeks for NFL and by 1 for NBA. Rounding is to
  1 dp. `SeasonStartFavoriteTest` covers threshold equality and an unsorted input.
- **SeasonStateService** (verified, read): the order is COMPLETE, then PRE_DRAFT, then
  `ScoredWeeks.latestFinal == 0`, then IN_SEASON. It uses the draft row when one exists and
  falls back to `league.status`. The "stored but not final" case is tested.
- **Wire gating** (verified, read): `firstWeek` and `strength` are set only in
  `DRAFTED_UNSCORED`, and grades only in `IN_SEASON` when available. No `Map.of` with
  nullable values in a response path. `countByRoster` is a `LinkedHashMap`.
- **V31 and the repository** (verified, ran the IT):
  - `?::jsonb` and `OffsetDateTime`/null binds work. `replaceAll` is `@Transactional`
    through the proxy.
  - An empty set and a `gp != 1.0` rejection never call `replaceAll`.
  - V31 is the next free number.
- **Daily step** (verified, read): NBA only, and every path is caught as best-effort, so it
  never returns `FAILED` and `/refresh/daily` stays at 200. "fresh" reports `DONE`.
- **`api.ts` against the Java records** (verified, read): field names, order and nullability
  match for `AnalysisProjections`, `AnalysisRosterProjection.reserve`, `ProjectionProvenance`,
  `SeasonStart`, `FirstWeek`, `StartersWithoutGame`, `IdleStarter`, `SeasonStrength`,
  `SeasonFavorite`, `SeasonLeader`, `SeasonDraft`, `DraftGradeSummary` and `TeamGradeLite`.
- **LeagueHome** (verified, read):
  - Loading hides the state-dependent blocks, and an error falls back to `legacyPreSeason`.
  - `DRAFTED_UNSCORED` drops standings, power and awards, and shows the matchup only when it
    holds a week of this season.
  - `PRE_DRAFT`, `IN_SEASON` and `COMPLETE` are unchanged apart from the grades block, R3's
    loading behaviour, and `preSeason` now coming from the server state.
  - The `/analysis` fetch is NFL only, there's exactly one `lh-next-h` for NBA (tested), and
    the NBA link is still the schedule grid.
  - `formatIsoDate` splits the string, so there's no UTC shift. `ageInDays` parses full ISO
    instants only.
- **LeagueAnalysis** (verified, read):
  - For `PER_WEEK`: the week-by-week section and the week picker are hidden, the scoring
    label and every "rest-of-season"/"bye" sentence are replaced, and the primary-position
    note is added. Notes render for NBA only.
  - Football copy is unchanged. Unavailable reasons go through `reasonText`, which returns
    the server's sentence for football.
- **Probability words**: none in the new copy. `PreseasonStrengthBlock.test` scans the
  rendered text for them.
- **Team codes** (verified, read the capture): the schedule codes and the player codes line
  up. The live NBA `season-start` capture has `countByRoster` at all 0 for week 1. A code
  mismatch would have counted as "no game".
