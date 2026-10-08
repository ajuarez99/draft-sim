# Adversarial plan review

This review read the spec 022 plan documents cold on 2026-10-07, before any code was written:
spec.md, plan.md, research.md, data-model.md, contracts/api.md, quickstart.md and tasks.md. It
checked them against branch `022-player-stat-analysis` (worktree `draft-sim-022`, at `8c848a7`),
and against the local DB (Postgres on 5433, read-only queries only). No code was run. No server,
gradle or third-party call was made.

Each item is labelled:

- **measured**: a query run today, with its output quoted;
- **read**: taken from code or docs, not executed;
- **inferred**: reasoned from the two above, not checked directly.

## Verdict

The plan's direction holds up. Most of the measurements reproduce, R5's formulas match the
standard definitions, and the reuse of R2 to R4 is the right instinct. **It is not ready to build
as written.** Four findings would ship wrong output on the first real use:

- **F1**: once the 2026 draft is done, every in-season leaderboard would say "draft hasn't
  happened".
- **F2**: "your players" in the nightly report is unavailable for the nights that matter most.
- **F3**: the nightly report's completeness rule is a second, different implementation of a rule
  Spotlight already has, and it breaks on stored data.
- **F4**: the season picker can't move forward from a past season.

Seven more findings (F5 to F11) are medium. They would cause rework or mislabelled output. F12 and
F13 are spec gaps that would cause rework at build time. Fix F1 to F4 and settle F7 and F8 before
T005.

## Claims that held up

| Claim | Where checked | Result |
|---|---|---|
| R1: NBA 2025 rows | `player_game` (measured) | ✅ 29,143 rows and 1,232 game ids. But see N1: the 29,143 **includes** 2,463 `TEAM_` rows (2,462 coded plus the bare All-Star `TEAM_`), so there are 26,680 player rows. 2024: 28,798 |
| R1: two team rows per complete game | `sport_schedule` ⋈ team rows (measured) | ✅ 0 of 1,231 `complete` 2025 games lack exactly 2 team rows. Schedule `game_date` and `week` agree with `player_game` on all 1,231 |
| R1/R5: team rows carry every rate input | `jsonb_object_keys` over 2025 team rows (measured) | ✅ `pts reb oreb dreb ast stl blk to pf fgm fga tpm tpa ftm fta sp plus_minus` are present. Zero-valued keys are omitted on team rows too: `blk` is absent on 36, `stl` on 1 |
| Team row = sum of his team's player rows | 2,462 team-games (measured) | ✅ pts, reb, oreb, to and pf match exactly; fga and sp each differ once. There are no separate team rebounds or turnovers, so the rates are self-consistent |
| R5: `TmMP` = team `sp/60` | 2025 team rows (measured) | ✅ 240 min ×2,354, 265 ×92, 290 ×16. The bare All-Star row is 60 |
| R5 formulas | Definitions as published (read, from the reviewer's knowledge of the glossary, not re-fetched) | ✅ TS%, eFG%, FTr, 3PAr, USG%, AST%, ORB/DRB/TRB%, STL%, BLK%, TOV%, possessions and game score all match the standard forms |
| R3: usage leaders | Pooled SQL over 2025, ≥ 20 games (measured) | ✅ Dončić 38.4, Brown 36.4. Antetokounmpo (37.2, 36 games) sits between them, presumably below R3's games cut |
| R6: read cost | psql over NBA 2025 (measured) | ✅ 14.5 MB of text, 472 ms including the client. `sum(length(stats::text))` is 13.3 MB |
| R6: token query cost (V9 was going to measure it) | `explain analyze` (measured) | ✅ 20 ms, via a bitmap scan on `player_game_week_idx (sport, season, week)` |
| R6: no cache in the backend | grep for `@Cacheable`, Caffeine and cache maps (read) | ✅ none |
| R6: token soundness | `PlayerGameRepository.upsert:55-73`, `deleteByGame:83`, `PlayerGameIngestService:141-149` (read) | ✅ The only writer is single-flight per `sport:season`. Each upsert autocommits and always sets `fetched_at = now()`, and a delete drops the count. So `(count, max)` catches every write, provided the token is read **before** the rows (F8) |
| R8: roster-weeks | `roster_week_points` (measured) | ✅ 2025: 12 × 21 weeks = 252, 15.5 keys on average (12–17). 2024: 12 × 24. No `"{}"` rows locally |
| R8: `roster_season.players` | DB (measured) | ✅ 2026: 12 rows, all `{}`. 2024 and 2025: null on all 12 |
| R9: drafts | `draft`, `draft_pick` (measured) | ✅ 2024 and 2025 `complete`, 168 picks each, with 0 null `player_id` and 0 null `manager_id`. 2026 `pre_draft`, `start_time` 2026-10-10 19:15Z |
| R10: ADP | `adp_snapshot` (measured) | ✅ `blend`: 09-08, 09-09, 09-14 and 09-28 (535 ×3 + 553 = 2,158 rows). `sleeper_search_rank` has 3,833 rows (its third capture is 09-23, not 09-14). 0 of 336 NBA picks carry `adp_at_time` |
| R11: `isEligible(Player, String)` | `BasketballRules.java:418` (read) | ✅ PG/SG/SF/PF/C/G/F/UTIL/BN. NBA `roster_positions` = `PG SG G SF PF F C UTIL UTIL` + 5 BN, with `total_rosters` 12 (measured) |
| R12/R13 cited lines | `RefreshProperties.java:41`, `daily-refresh.yml:10`, `Dockerfile:26`, `SimulationService.java:173` (read) | ✅ all as stated |
| Highest migration V29 | `db/migration` (read) | ✅ |
| Unknown players | 2025 players with no `player` row, and with empty `positions` (measured) | ✅ 0 and 0 of 582. The `known: false` path exists for 2026 call-ups, not for the 2025 fixtures |

## Findings: the plan was wrong or incomplete

Ranked by severity.

**F1 (high). The draft state is keyed on the wrong status, so every in-progress season reads
"draft hasn't happened".** (measured + read)

data-model.md "Draft and ADP" defines `NOT_HAPPENED` as "the league is not `complete`, or there
is no draft row". T041 tests exactly that: "a league that isn't `complete` gives `draft.state
NOT_HAPPENED`".

But `league.status` is the **season's** status. Measured:

| League | Season | `league.status` | `draft.status` |
|---|---|---|---|
| 4 (NFL) | 2026 | `in_season` | `complete` |
| 3 (NFL) | 2026 | `in_season` | `complete` |
| 9465 (NFL) | 2026 | `in_season` | `complete` |
| 210 (NBA) | 2026 | `pre_draft` | `pre_draft` |

After 10-10, NBA 2026 has this same shape as the NFL rows. So for the whole 2026 season, the draft
columns, value vs. draft cost, and US4 scenario 5 would all read "the draft has not happened". That
is the season this feature exists for. 2024 and 2025 happen to work, because both statuses are
`complete`, so every check run on 2025 would pass.

**Amendment**:

- `draft.state` reads `draft.status`: `complete` gives `COMPLETE`; a draft row that isn't
  `complete` gives `NOT_HAPPENED`; no draft row gives `NONE`.
- That also reconciles data-model.md with contracts/api.md, which already has `NONE` while the
  data model folds it into `NOT_HAPPENED`.
- `DraftGradesService.read` already gates on `draft.status()` (`DraftGradesService.java:176`).
  Use the same field.
- Add a T041 case for an `in_season` league with a `complete` draft.

**F2 (high). "Your players" and the free-agent standout are unavailable for every night of the
current week, which includes last night.** (measured + read)

R8 and data-model say a past night's ownership is "the roster of the fantasy week that night
belongs to", from `roster_week_points`. With nothing stored for that week, it is `UNAVAILABLE`.

The in-progress week is never stored until it is scored. Measured on the NFL leagues, which run
the same ingest:

| League | `leg` | `last_scored_leg` | max stored week |
|---|---|---|---|
| 3 | 4 | 3 | 3 |
| 4 | 5 | 4 | 4 |
| 9465 | 4 | 3 | 3 |

So on any NBA night in season, the report's default night sits in the unscored current week:

- `mine` would read `OWNERSHIP_UNAVAILABLE`;
- `MINUTES_JUMP_FREE_AGENT` would never be evaluated;
- this lasts for up to 7 days at a time.

US3 scenario 3 and FR-021 fail on exactly the night a member opens the page for.

**Amendment**:

- A night in the league's **current, unscored** week uses the V28 current rosters, labelled
  `{kind: CURRENT, fetchedAt}`, with the same `pre_draft`/`drafting` gating.
- A night in a scored week uses that week's `players_points`, as `{kind: WEEK}`.
- `UNAVAILABLE` only applies when neither exists, for example NBA game weeks 22–25 of 2025, which
  come after the league's last week (21).
- Add both cases to T054.

**F3 (high). The nightly report's completeness rule is a second implementation of a rule Spotlight
already has, and as written it breaks on stored data.** (measured + read)

The plan says it found every existing rule (R2–R4, R9, R11). It missed three in the nightly area:

- **"Is this night complete"** is already `PlayerSpotlightService.isComplete` and `choosePeriod`
  (`PlayerSpotlightService.java:474-525`, spec 014 R6). A night is complete once every week its
  rows belong to was fetched at or after D+1, 10:00 UTC (`NIGHT_COMPLETE_HOUR_UTC = 10`, `:58`).
  R13 invents a different rule: schedule `complete` plus both team rows.
- **"Which schedule rows are real games"** is already `ScheduleGridService`
  (`ScheduleGridService.java:54-65,127-137`): postponed, canceled and exhibition sides are
  excluded. It was fixed in PR #19 because the schedule lists an All-Star final as `complete`.
- **"Default night"**: Spotlight picks the latest complete night. C3 picks "the most recent date
  with at least one complete game", so it would land on tonight's partial slate.

R13's rule then fails on real rows:

- **2024 has no schedule at all.** `sport_schedule` has NBA 2025 (1,235 rows) and 2026 (1,200)
  only (measured). Every 2024 night would read `NO_GAMES_ON_DATE` or `INCOMPLETE`, although
  2024's games are stored. FR-019 says any night of any stored season.
- **Postponed games block their nights forever.** Three are `postponed`: 2026-01-08 (CHI–MIA) and
  two on 2026-01-25 (MEM–DEN, MIL–DAL). They were replayed under new ids, so "every scheduled game
  on that date is `complete`" can never hold for those two nights. Both would show as incomplete
  indefinitely (measured).
- **The All-Star game is in the schedule as `canceled`**: game `1304923502905663488`, 2026-02-15,
  STP vs STR (measured). It still has a bare `TEAM_` row and 15 player rows. A schedule-driven
  game list has to apply the exhibition rule, or 02-15 lists a game with no team rows.
- **Two different answers for one link.** Spotlight's "Top of the night" links
  `/nightly?date=` to a night Spotlight calls complete. The report can call that same night
  incomplete (a postponement), or call a night complete before Spotlight's 10:00 UTC cutoff.

**Amendment**:

- Extract Spotlight's period choice and night-completeness rule into a shared helper, and use it
  for C3's default date and its `complete` flag.
- Build C3's game list from the stored team rows, not from the schedule. Team rows exist for 2024,
  and they are already All-Star filtered by `NbaGameLines`' team-code rule.
- Use the schedule only for `missingGames` (games scheduled but not stored), excluding postponed,
  canceled and exhibition rows by calling `ScheduleGridService`'s rule, not by copying it.
- Add T054 cases for a postponed-game night, the All-Star night, and a 2024 night with no schedule.

**F4 (high). The season picker cannot move forward from a past season.** (read)

C1's `seasons` is "every chain season with stored games, newest first". The only chain walk is
`LeagueRepository.chainBySleeperId` (`LeagueRepository.java:328-339`). It follows
`previous_league_id`, which points **backwards** only.

So on `/leagues/{2025 id}/players/X`, `seasons` would be [2025, 2024]. On the 2024 page it would
be [2024]. A member who picks 2024 can't get back to 2026 from the picker. Nothing on the server
looks up a league's successor.

The web side already has the whole chain: `ctx.lineage.seasons` (`leagueLineage.ts`,
`destinations.ts:32-38`).

**Amendment**: build `seasons` from the chain **head**, either by:

- the web passing `lineage.current.sleeperLeagueId`, and the server walking back from it and
  marking each season `hasGames`; or
- adding a successor lookup (`where previous_league_id = ?`) and walking forward first.

Then add a T023 test that, from the 2024 id, `seasons` is [2026?, 2025, 2024]. 2026 is listed only
once it has games, or as "no games yet", per N15.

**F5 (medium). "End of regular season, week N" is the wrong label, and V6's week number is
wrong.** (measured)

R8 labels a completed season's ownership "end of regular season, week N", using the last stored
week. Measured from `settings_json`:

| Season | Last stored week | `playoff_week_start` | What the last stored week is |
|---|---|---|---|
| 2025 | 21 | 19 | the third playoff week; the regular season ended at week 18 |
| 2024 | 24 | 22 | a playoff week |

- quickstart V6 expects "week-21 ownership" for **2024**. It is 24.
- In playoff weeks, eliminated teams' rosters are often abandoned (inferred), so the last week
  isn't a neutral "end of season" view.
- The NBA season itself runs to game week 25 (04-12), four weeks after the league's last week
  (03-15).

**Amendment**: choose and name one rule. Either take week `playoff_week_start − 1`, labelled "end
of regular season (week 18)", or take the last stored week, labelled "final week (21, playoffs)".
Fix V6's number. State in the UI that later NBA games aren't covered by any roster.

**F6 (medium). From 10-10 to 10-20, the default player page shows March's owners for a league
that has just drafted.** (inferred from F5's data and R7)

Before 2026 has games, the resolver falls back to 2025 (R7). Ownership then follows the
**answered** season: 2025's last week, with I8 forbidding current rosters on a past season.

Those ten days right after the 10-10 draft are when members look up the players they just
drafted. The page would say "rostered by X (week 21)", a March 2026 roster, while the spotlight
and Trends show the new 2026 rosters. That is labelled, but it is the wrong answer to the
question the header asks: who has him now?

**Amendment**: when `requestedSeason != null` (a fallback), also return the requested season's
current ownership as a separate, labelled `currentOwnership` ("on a roster now, 2026–27"), and keep
`ownership` for the season shown. I8 still holds, because the stats season never borrows it.

**F7 (medium). The extraction and the cache switch change Trends' inputs, and the plan doesn't say
what the cache stores.** (measured + read)

- **The cache drops rows Trends still reads.** `PlayerTrendsService.read` passes the raw
  `SeasonGame` lists (`cur.games()`/`prev.games()`, `:203-205`) to `oneGameShare`, which groups
  **every** non-team row by week. That includes the All-Star game, which `prepare` drops. If the
  cache's value is "the `NbaGameLines` result" (data-model, T014), that input changes.
  - Measured on 2025: 15 player rows in the All-Star game (week 17), and 13 week-17 starter slots
    in league 211 belong to All-Star participants.
  - 5 of those 15 players had exactly one other week-17 game, so the All-Star row is what makes
    their week multi-game in `oneGameShare`'s count.
  - Either the T016 JSON gate fails, or someone quietly "fixes" an existing behaviour. Decide it
    explicitly.
- **`isHome` has no input.** data-model and T009 read it "from the schedule", but `NbaGameLines`
  takes only `SeasonGame` and `TeamGame`. Neither carries `is_away`, and nothing passes it the
  schedule.
  - `player_game.is_away` already exists (`PlayerGameRepository.Row`, `:44`). It is populated on
    100% of 2024 and 2025 player and coded team rows (measured; only the bare All-Star row is
    null).
  - Read it from there, which means extending `SeasonGame` (or the cache's own row) by one field.
- **"Trends' tests pass unchanged" conflicts with any signature change.**
  `PlayerTrendsServiceTest` builds `SeasonGame` and `SeasonData` directly (`:95,197,709`), and
  its usage assertions run through `compute()` (`:217-273`). So T010's "move them, unchanged"
  can't work either (N12).

**Amendment**:

- The cache stores raw rows: `SeasonGame` + `TeamGame` + `isAway`, compacted. `NbaGameLines` is a
  pure function applied over them, so Trends can get the raw lists it uses today.
- State in data-model whether `oneGameShare` keeps the All-Star row. Recommended: keep it in this
  spec, so V1 stays byte-identical, and file the exclusion as a follow-up.
- Add `isAway` to `SeasonGame` with a test-helper update, and say so in T009 rather than claiming
  the tests are unchanged.

**F8 (medium). The cache's lock, its read order and its reload behaviour are unspecified, and the
obvious implementations are wrong here.** (read + inferred)

- **Lock.** T014 says "reloads under a per-key lock". Request handlers run on virtual threads
  (`application.yml`, `spring.threads.virtual`). On Java 21, a virtual thread blocked in or
  waiting on a `synchronized` monitor **pins its carrier**; that was fixed in JDK 24, not 21. A
  0.5–1 s JDBC read plus JSON parse inside `synchronized`, with a handful of requests waiting,
  can pin every carrier and stall unrelated requests. That is lessons #23's failure through a
  different door.
  - The repo already has a lock-free single-flight, `refresh/SingleFlight`: a
    `ConcurrentHashMap` of `CompletableFuture`s.
  - Reuse that pattern in a separate instance (its javadoc explains why instances must not be
    shared), or use a `ReentrantLock`. Don't use `synchronized`.
- **Read order.** The token must be read **before** the rows. If it is read after, a refresh that
  lands between the two leaves a cache that matches a token newer than its rows, so it never
  reloads.
- **Reload storms.** Every refresh re-upserts every row of every non-final week, with
  `fetched_at = now()`, even when nothing changed (`PlayerGameIngestService:248-249`). So each
  hourly refresh, and each refresh-on-visit, invalidates the season. While one is running (each
  of ~2,000 entries a week autocommits separately), every request sees a new token and triggers
  another full reload.
  - Consider an explicit `invalidate(sport, season)` at the end of `refreshSportSeason`, with
    the token as the safety net.
  - Alternatively, accept that cost and have V9 time a request during a running refresh.
- **Immutability.** Trends' `prepare` sorts its per-player lists in place (`:446`). A cached
  value shared across requests must expose unmodifiable lists and maps, or the first in-place
  sort corrupts it for concurrent readers. Add a T013 case.

**F9 (medium). Last-5 and last-10 windows have no recency, the lesson from spec 019's F2.**
(read + inferred)

A LAST_N window is the player's last N **played** games. For a player out since November, his
"last 5" in March are November's games. The leaderboard sorts and the stat leaders (FR-035) would
show them as current form.

Spec 019's review measured how common this is in season. 41 to 109 players with ≥ 3 games had
last played more than 14 days earlier. Trends then grew `recencyDays` for it.

data-model's qualification is "games ≥ ceil(share × maxTeamGamesSoFar) … in the same window
span". That is undefined for LAST_N, because each player's span is different. Any player with N
games trivially meets it.

**Amendment**:

- Define LAST_N qualification explicitly.
- Carry `lastGameDate` (and the window's first date) in `WindowStats`.
- Exclude players whose last game is older than a stated, ARBITRARY recency window (or reuse
  `player-trends.recency-days`) from LAST_N ranks, percentiles and leaders, and label them
  "hasn't played since {date}".
- Add a test with a player whose last game is 60 days old.

**F10 (medium). `missedTeamGames` has no rule, and Trends already has a field with the same name
that means something else.** (read)

FR-013 and US1 scenario 4 require "how many of his team's games he missed", and C1 carries
`missedTeamGames: 17`. But data-model never defines it, and no task tests it beyond the page
render (T029).

`PlayerTrendsService.TrendRow.missedTeamGames` (`:64-69`, `missedRun` `:473-484`) is the
**consecutive most-recent run** of game-level absences. An implementer reusing that name would ship
a recent run, not a season count. The open questions:

- A traded player: which team's games count?
- A mid-season signing: are games before he joined "missed"?
- A two-way call-up?

**Amendment**: state the rule in data-model. For example: for each team he played for, his team's
games between his first and last game with that team, minus the games he played. Or count
game-level `ENTRY_WITHOUT_PLAY` absences, which only exist where Sleeper had an entry. Name the
field differently from Trends' run (for example `teamGamesMissed`), and add a traded-player test.

**F11 (medium). FR-014, SC-001 and V5 require a link on League Analysis, which basketball leagues
cannot reach. And T031's gate names an input the pages don't have.** (read)

- **League Analysis is football-only.** `destinations.ts:203-221` (`analysis`) has
  `sports: ['nfl']`, deliberately, because two of its three blocks are projections. V5's "click a
  name on League Analysis" is impossible for an NBA league, and the PlayerLink in T031's
  `LeagueAnalysis.tsx` would never render for a basketball player.
- **The pages don't receive a sport rule.** T031 says "Branch on the league's sport rule the
  pages already receive". None of `PlayerSpotlight`, `WeeklyReport`, `PlayerTrends` or
  `RosterManagement` receives one. They have the payload's `sport` field, and `PlayerSpotlight`'s
  props are just `{spotlight, weekly}` (`PlayerSpotlight.tsx:76`), so it doesn't have the league
  id `PlayerLink` requires either.
- **Some of these pages render football players too.** `WeeklyReport` and `RosterManagement`
  serve both sports. Without a gate, NFL names would link to a `NOT_BASKETBALL` page.

**Amendment**:

- Replace League Analysis in FR-014, SC-001 and V5 with a basketball page that names players.
  Superlatives does. Or drop it, with a dated amendment.
- Define one gate, derived from the destinations table so the client still never compares a sport
  string: for example, `playerPagesFor(sport) = LEAGUE_DESTINATIONS.find(d => d.key === 'stats')
  .sports.includes(sport)`. In US1, before `stats` exists, use a dedicated row (N5).
- Pass `sleeperLeagueId` into `PlayerSpotlight`.

**F12 (medium). The draft-value and ADP join is missing state, inputs and a name rule.** (read +
measured)

- **`draftValue` is null in cases that aren't "undrafted".** `DraftGradesService.read` returns
  `available: false` for `NOT_CONFIGURED`, `DRAFT_NOT_COMPLETE` and `NO_SCORED_WEEKS`
  (`:175-186`). It also flags `gradesEarly` for the first weeks (`:225`). C2 has no field for any
  of these. A drafted player's null `draftValue` reads as undrafted, and an early-season grade is
  shown with Draft Grades' own caveat dropped. That is the honesty rule. Add `draftGrades:
  {available, reason, gradesEarly, weeksCounted}` to C2.
- **The draft has no start date to hand to ADP.** `DraftRepository.DraftRow` has no `start_time`
  (`DraftRepository.java:170-172`; the column exists, and is nullable). T045 needs it, and no task
  adds it. A null `start_time` needs a stated outcome too, such as `NO_ADP_STORED` or a separate
  reason.
- **`BoardRepository.asOf` has never run, and doesn't give the date.** It has **no callers** today
  (grep). It also returns rows without the capture date the contract needs (`capturedOn`). Bug
  classes #3 and #6 both apply: SQL that has never run. Add a repository method that returns the
  date with the rows, plus an IT that actually runs it against 2025 (empty) and 2026 (09-28),
  rather than only the T041 unit test.
- **Two naming rules for one manager.** T045 names managers through `RosterOwners.ownerNames`,
  which is keyed by **roster id** and prefers `league_member.team_name`. Picks carry
  **`manager_id`**, and the Draft Grades page names them with `managers.names()`, the display
  name (`DraftGradesService.java:310-329`). The leaderboard's "drafting manager" would differ
  from the Draft Grades page it links to. Use Draft Grades' rule (slot → manager → display name)
  for the draft column, since the spec says "drafting manager".
- **The cost is per request.** `DraftGradesService.read` loads every NBA player, the drafted
  players' season games and the roster inputs on each call, and C2 would call it on every window
  switch. Have V9 time it, and expect to memoise it on the season token.

**F13 (low-medium). Rank, percentile and contract details contradict each other, which will cause
rework.** (read)

- **Ranks.** data-model (and T018) says ties "are broken by value, games, name, id" **and** that
  tied players "share the lower number". Those are different rules. State the intended one: rank
  numbers are competition ranks on equal **wire-rounded** `fpPerGame` (so the number matches what
  the reader sees), and games, name, id is only the display order. Ranking on unrounded doubles
  while showing 2 decimals can give two visibly equal players different ranks.
- **Percentiles.** data-model says "a player outside the group gives null with a reason", but
  C1's `Pct.reason` has no code for "not rostered in this league" (a free agent's
  `LEAGUE_ROSTERED` cell). As written, that violates the exactly-one rule (I2). Either add
  `NOT_IN_GROUP`, or rank a free agent against the rostered group (arguably more useful), and say
  which.
- **Percentile windows.** C1's `percentiles` is keyed by rate only, `{ts: [Pct, Pct]}`. But T036
  says "both groups, per rate, **per window**". The contract needs the window dimension, or T036
  narrows to the season.
- **Ownership fields.** C1's `Ownership` example omits `rosterId`, which data-model and T022 have.
  FR-024's "a given manager's roster" filter needs it.

## Notes (smaller)

- **N1.** R1 says "29,143 rows … plus 2,462 `TEAM_xxx` rows". Measured, 29,143 is the total
  including 2,463 team rows (2,462 coded and the bare All-Star one). Player rows: 26,680 (2025)
  and 26,335 (2024). Fix the wording. Payload and memory estimates should use 26,680.
- **N2.** R6's memory estimate assumes "about 25 values per game". Measured, it is **31.0 keys on
  average, 51 max** per player row.
  - **`float` precision.** `float` is exact for every key a league scores (all are integers), but
    `pts_std` and `pts_std_dfs` hold tenths (e.g. −0.3, 49,790 values), which `float` can't
    represent exactly.
  - **Recommendation.** Store `double`, or document that only integer-valued keys survive exactly,
    and keep the guess labelled "estimated" until V9 measures it.
- **N3.** `score` rebuilt on `contributions`:
  - **Summation order.** Keep the naive left-to-right sum in scoring-key order, from a
    `LinkedHashMap` (`scoringOf` returns one, `LeagueRepository.java:97`). Don't use
    `DoubleStream.sum()`, which is compensated and can differ in the last ulp.
  - **Football.** `GameScoringService` also scores football. I1 should cover NFL 2025 too, or
    lean on `NflScoringParityIT`.
- **N4.** `KeyedByLeague` (`App.tsx:71-74`) keys only on the league id. Going from one player page
  to another (for example via the nightly report or a leaderboard row) reuses the element, and the
  previous player's data stays up while the new one loads, which is the bug that wrapper exists
  to prevent. Key the player route on both ids.
- **N5.** T030: "add a `match` to the relevant entry … without adding a rail item" doesn't say
  which entry. Whichever destination claims `/players/` is highlighted in the rail, and its href
  is where the rail's year links go, so switching season would leave the player page. The table
  has no "hidden" flag. Design it: a `players` row with a new `inRail: false`, or the `stats` row
  (which doesn't exist until US4).
- **N6.** `RosterWeekPointsRepository.WeekBreakdown`'s javadoc says `players_points` can be
  `"{}"` for a week Sleeper returned nothing for. None are stored locally (measured). A week with
  one empty roster would read that roster's players as free agents, which is the partial-map trap
  `rosteredPlayers` already guards against (`RosterSeasonRepository.java:128-150`). Apply the
  same rule: any empty roster makes the week `UNAVAILABLE`.
- **N7.** `didNotPlay`: "his team played that night" needs his team **that week**, not
  `player.team`, which is today's team, so it is wrong for a player traded since. `player_absence`
  already has game-level `ENTRY_WITHOUT_PLAY` rows with `team` and `game_id`, and Trends reads
  them. Use those.
- **N8.** The breakdown's keys are the league's scoring keys: `dd td ff tf bonus_pt_40p
  bonus_pt_50p bonus_reb_20p bonus_ast_15p` as well as the box-score keys (measured on 210–212).
  `statCopy.ts` (T027) needs a label for each. `share` also needs a rule when the season total is
  ≤ 0 (a 1-game, 0-point, 1-turnover player divides by a negative).
- **N9.** C1 uses `reason: NO_GAMES` both for "this season has no stored games" (the shared-field
  table) and for "this player has no games this season, `available: true`". Give them different
  codes, for example `NO_PLAYER_GAMES` for the second.
- **N10.** Opening night is **2026-10-20** (3 games, measured in `sport_schedule`), not 10-21 as
  T053 and V12 say. The first measurement chance is the 10-20 slate.
- **N11.** `draft.start_time` is nullable. The pre-draft league 460 has a `start_time` in the past
  (`2026-09-03`) while still `pre_draft`, so it is the **scheduled** time (measured). `captured_on`
  is a date, so a capture made on draft day after the draft counts as "on or before". Both are
  minor, but state them in R10.
- **N12.** T010 ("move Trends' usage assertions there, unchanged") can't be done. Those tests
  assert through `PlayerTrendsService.compute` (`PlayerTrendsServiceTest.java:217-273`). Leave them
  in place as Trends' guard, and write direct `AdvancedStats.usage` tests alongside.
- **N13.** The V1/T005 baseline is `player-trends` JSON on the 2026 league:
  - **Time-varying fields.** It carries `rostersFetchedAt`, `currentWeek` and
    `staleReferenceDate`, and a refresh between T005 and T016 changes them. Strip those, or pin
    the refresh off.
  - **Path coverage.** The 2026 league only exercises the fallback path. Add the 2025 league
    (211) as a second baseline.
- **N14.** C2's size: 582 players with ≥ 1 game in 2025 (measured, not "about 550"). Inferred: 45
  counting numbers, 16 `Rate` objects and an ownership object per row is roughly 1.2–1.5 KB, so
  ~0.75 MB per window uncompressed, re-fetched on each window switch. Expect V9 to need
  compression.
- **N15.** The spec's edge case says "a member who picks the current season explicitly sees that
  statement, not an empty table". With URL-only seasons, the default link and an explicit pick of
  2026 are the same URL, so both fall back. The fallback note ("2026–27 has no games yet; showing
  2025–26") arguably is "that statement". Say so in the spec instead of leaving it to the build.
- **N16.** Links from Trends land on a player page whose default season can differ from the one
  Trends shows. Trends switches seasons at "half the teams have 5 games"; this feature switches on
  the first stored game. Expected and labelled on both pages, but worth one line in the player
  page's fallback note.
- **N17.** `STORED_GAMES` runs the token query once per chain row, at ~20 ms each (measured), plus
  the per-read token check. It's fine, but the resolver should share the token with the cache's
  check instead of querying twice.
