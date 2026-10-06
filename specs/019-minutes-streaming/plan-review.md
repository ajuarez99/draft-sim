# Adversarial plan review

This review read the spec 019 plan documents cold on 2026-10-06, before any code was
written. It checked them against branch `019-minutes-streaming` (worktree, based on
`origin/main` @ `787abd9`, plan files uncommitted), the local DB (Postgres 17 on 5433,
read-only), and the public Sleeper API.

Each item is labelled **measured** (run today, output quoted) or **read** (from code,
not executed). Scratch scripts scored games with each league's own `scoring_json`
(211 and 210 are identical; 212 differs, see N6) and reproduced R3's 2025 table
exactly, so the re-measurements below use the same data and the same rule as the
research.

## Claims confirmed

| Claim | Where checked | Result |
| --- | --- | --- |
| R1 / SC-001: `sp` is seconds, Jokić 2025-10-23 = 40.83 min | `player_game` 1658 (measured) | ✅ `sp` 2450, 21 pts, 13 reb, 10 ast, 23 FGA. Every numeric-id NBA row has `sp`, and none has `sp` = 0, so `player_game` holds played games only |
| R2: team box-score rows share `game_id` and pair by opponent | join `TEAM_*` on `(season, game_id, opponent)` (measured) | ✅ 2025: 26,665 of 26,680 player rows find exactly their team row. The 15 that don't are the All-Star game (F9). 2024: 26,320 / 26,335, same cause |
| Usage formula on real rows | SQL over 2025 (measured) | ✅ mean 19.1, median 18.6 over 24,509 games of ≥ 5 min, which is where league-average usage should sit. Jokić 2025-10-23 = 30.4. Team `sp` 15,900 in the DEN OT game, so `TmMIN/5` handles overtime |
| Team-code vocabularies agree | `sport_schedule` 2026 home/away, `TEAM_*` suffixes, `player.team` (measured) | ✅ the same 30 codes in all three (`GSW`, `NOP`, `SAS` …). No mapping needed |
| R3 table, NBA 2025 | own script (measured) | ✅ identical: 89 / 458 / 966 / 736 weeks, credited 26.4 / 25.7 / 26.8 / 27.1, credited = best 76% / 71% / 63%. 1,507 of 2,171 multi-game weeks credit the best game |
| R4: `ingestStandings` fetches `/rosters` and drops `players` | `LeagueHistoryIngestService:178-199` (read) | ✅ line 178 is the `sleeper.rosters(...)` loop. Nothing reads `players`, `reserve` or `taxi` |
| `Upsert` has one producer | grep for `new RosterSeasonRepository.Upsert` / `Upsert(` (read) | ✅ only `LeagueHistoryIngestService:185`. Tests only capture it (`LeagueHistoryIngestServiceTest:110,157`, `ChampionOnlyWhenCompleteTest:97`). Nothing else writes `roster_season` |
| Sleeper `/rosters` shape | NBA 2026 and 2025 rosters (measured) | ✅ pre-draft (2026): 12 rosters, `players: []`, `reserve: []`, `taxi: []`, `keepers: []`, so not null. 2025: `players` 12–16, `reserve` null or 1–2 ids, `taxi` null. `reserve ⊆ players` on all 12, so the union is harmless |
| NBA 2026 reaches the standings loop pre-season | `league_refresh` 210 (measured) | ✅ `loaded_complete f`, `last_success_at 2026-10-06 01:25Z`. But see F3 for 211 / 212 |
| `ScheduleGridService.forLeague(String)` and `currentWeek` pre-season | `ScheduleGridService:60-133`, league 210 `leg` (read + measured) | ✅ reads the URL's league row, not the resolver. `currentWeek` = `leg` = 1 for 2026. Week 1 is 2026-10-20 … 10-25 in `sport_schedule` |
| `GameScoringService.score(Map, Map)` | `GameScoringService:38` (read) | ✅ |
| `LeagueMembership.visibleLeague(sleeperId, userId)` | `LeagueMembership:164`, used the same way by `ScheduleController:32` (read) | ✅ |
| `playsMultipleGamesPerScoringPeriod()` | `BasketballRules:137`, `DraftGradesService:171` (read) | ✅ |
| `destinations.ts` `sports: ['nba']`, season-scoped href | `destinations.ts:155-168` (the `schedule` row) (read) | ✅ copy that row |
| `useLeagueDataVersion` | `web/src/leagueDataVersion.tsx`, used by `ScheduleGrid.tsx` (read) | ✅ |
| `AccessControlMvcIT` exists with hand-listed routes | `backend/src/test/.../api/AccessControlMvcIT.java` (read) | ✅ |
| V27 is the highest migration | `db/migration` (read) | ✅ V28 is next |
| No 2026 NBA `player_game` rows yet | DB (measured) | ✅ 2024 and 2025 only, so the fallback branch is what runs today |

## Findings: the plan was wrong or incomplete

Ranked by severity.

**F1 (high). The season switch on 2026-10-20 empties every list for week 1.**
(measured)

R6 reads the previous season "until the season has a game". After the first night,
the season has a game, so the page switches to 2026. Then every player has 0–1 games:

- `formPts` needs 3 games, so the streaming list is empty.
- `seasonMin` needs 5, so risers and fallers are empty.

Measured on 2025's opening, which used the same calendar shape. Dates when teams
reached N games:

| N games | first team | half the teams | every team |
| --- | --- | --- | --- |
| 1 | 10-21 | 10-22 | 10-23 |
| 3 | 10-24 | 10-26 | 10-27 |
| 5 | 10-28 | 10-29 | 11-01 |

The 2026 week 1 is 10-20 … 10-25 (`sport_schedule`). So streaming would be empty or
near-empty for all of week 1 and the week-2 pickup window. Risers and fallers would be
empty until about 11-01. That's the window the spec calls the one that matters most.
No test catches it, because the fallback test only covers "zero games".

**Amendment**: make the switch a stated rule, not "first game seen". For example:
stay on the previous season until half the teams have `formMinGames` games in the new
one. Do it per list if needed: streaming switches at 3, the role lists at 5. Put the
threshold in `weights.yml` (ARBITRARY). The payload already carries
`dataSeason`/`fallback`, so the label stays honest. Add a test with a season of
1–2 games per player. Don't mix seasons inside one player's window. That's still
right.

**F2 (high). "Last 5 games played" has no recency, so streaming promotes stale,
unsigned and injured players. The fallback window is the worst case.** (measured)

What the plan's streaming list would show for NBA 2026 after the draft, simulated with
the end-of-2025 rosters standing in for the 2026 draft. Top 20 unrostered by last-5
form:

- **April tank minutes**: Jeremiah Fears form 32.6 vs season 15.7, Konchar 28.1 vs
  12.8, Rupert 26.9 vs 8.2, Cisse 24.1 vs 10.9, Leonard Miller 22.8 vs 9.8. Last games
  2026-04-10…12. That's end-of-season rest and tanking, not a 2026 role.
- **Long-injured players whose "last 5" are months old**: Sabonis (last game 02-04),
  Kessler (10-31, 5 games), Edey (12-07), Morant (01-21). In 2026 these would be
  drafted, but in season the same shape applies to anyone out long-term.
- **No NBA team now**: 2 of the top 20 (Nembhard, L. Williamson). Across 2025, **86
  players with ≥ 3 games have `player.team` null** today (71 `FA`, 3 `RET`). Examples:
  Westbrook, Valančiūnas, Cam Thomas, Batum.

The same gap exists in season. Players with ≥ 3 games whose last game was more than 14
days earlier: **41 of 458** on 2025-12-01, **70 of 490** on 01-15, **109 of 521** on
03-01. Their "recent minutes" and "form" describe a different month. The spec's
injury-game median handles a short game, not an absence.

There's a related blind spot: a player benched outright (DNP-CD) has **no** row, so
his last 3 *played* games predate the benching and he never reads as a faller.
`player_absence` already classifies `TEAM_PLAYED_NO_ENTRY` per team game (16,852
rows in 2025) *(corrected during code review, 2026-10-06: 16,852 is both bases added together. `TEAM_PLAYED_NO_ENTRY` has 160 rows in 2025, all week-level with `game_id` null. Per-game misses are `ENTRY_WITHOUT_PLAY`. Code built from this number never fired; see code-review B1.)*, so "missed his team's last N games" is computable without new data.

**Amendment**:
- Streaming excludes players whose `player.team` is null, with a count shown ("N
  players with no NBA team left out").
- A recency gate on both windows: the last game must fall within the last K of his
  team's games, or the last D days (ARBITRARY, `weights.yml`). Otherwise the row is
  excluded or flagged "hasn't played since {date}".
- Use `player_absence` to show "missed last N team games" on trend rows, so benched
  players surface as fallers.
- For the fallback season, either rank streaming by season mean (see F7) or label the
  form column "last 5 games of 2025 (April)". A reader in October can't tell that
  otherwise.
- Add a test with a player whose last games are 60 days old.

**F3 (high). Completed seasons never get roster players, so the NBA 2025 page says
"Rosters haven't loaded yet. They load on the next refresh." forever.** (read + measured)

`LeagueRefreshService.trigger` passes `loaded_complete` seasons as `skip`, and
`ingestChain:99` `continue`s before `ingestStandings` for them. In `league_refresh`,
211 (2025) and 212 (2024) are `loaded_complete t`. So after V28 their `players` stay
null, and `rosteredPlayers` is empty for them. The plan then says:

- quickstart V3: "Streaming: no player in the season's final rosters". That can't
  happen. It will be `ROSTERS_NOT_LOADED`.
- spec US1 "Independent Test" on the 2025 league: lists "split into rostered and free
  agents". There's nothing to split on (F4).
- the UI sentence promises a refresh that will never come. Spec 017's review already
  hit this exact shape (R1: "A complete season is never refreshed again, so it must
  not promise a refresh").

The rail's Trends link is season-scoped (`ctx.season`), so anyone browsing the 2025
season lands here.

**Amendment**: pick one and state it:
(a) a one-time backfill. The manual `POST /api/ingest/league-history/{id}` passes
    `Set.of()` (`IngestController:117`), so it writes them. Run it locally and in
    production, and record it in the deploy steps.
(b) a third reason, `SEASON_COMPLETE`: "Streaming is for the current season". Copy
    must not promise a refresh.
Option (b) is more honest for a finished season, because end-of-season rosters aren't
a waiver wire. Fix quickstart V3 either way.

**F4 (medium-high). `rostered` is a boolean, but "unknown" is a real state.** (read)

`TrendRow.rostered` is `bool`, and risers/fallers are split "Free agents / Rostered" in
the UI. Under `ROSTERS_NOT_LOADED` (F3: every completed season, plus any league before
its first post-V28 refresh) and `NOT_DRAFTED`, the plan never says what `rostered`
holds. `false` would label every riser a free agent. That's the exact claim the plan
refuses for streaming ("'Free agent' is refused when rosters aren't loaded"), made one
section lower.

**Amendment**: either make `rostered` a `Boolean` that's null when rosters are unknown
(and mirror it as `boolean | null`), or don't split the role lists when
`streamingAvailable` is false, and say why. Add an invariant:
`streamingReason != null ⇒ every row's rostered is null`. Test it.

**F5 (medium). `NOT_DRAFTED` is inferred from an empty map. Use the league's status
instead.** (measured + reasoned)

- The NBA 2026 league has **`max_keepers: 1`**. Its rosters show `keepers: []` today,
  but if keepers are declared before the draft, the map isn't empty and the page would
  call every other player a free agent before a single pick.
- During the draft (`status: drafting`), partly filled rosters would produce a
  half-correct "free agent" list. Whether Sleeper fills `/rosters` live during a draft
  hasn't been measured.

**Amendment**: `NOT_DRAFTED` when `league.status` is `pre_draft` or `drafting`.
Emptiness is only a fallback for a null status. `LeagueRow.status` is already stored
and refreshed by the same walk. Add a test with a non-empty map and `pre_draft`.

**F6 (medium). R3's +5% compares games *played*, but the column shows games
*scheduled*.** (measured)

R3 groups starter-weeks by games the player played. That mixes the team's schedule
with rest, injury and returns. The page's column is the team's scheduled games (the
grid). Re-measured both ways, within player (players with weeks in both buckets, so
player quality is held fixed):

| Grouping | Season | Players | 4 vs 2 | 4 vs 3 | 3 vs 2 |
| --- | --- | --- | --- | --- | --- |
| Games **scheduled** (team, `sport_schedule`) | 2025 | 153 / 206 / 148 | **+4.4%** | **−1.6%** | +3.4% |
| Games **played** | 2025 | 164 / 192 | +6.7% | +2.0% | — |
| Games **played** | 2024 | 157 / 182 | **+10.9%** | +5.3% | — |

There's also a small quality confound in R3's unpaired table: 4-game starter-weeks have
slightly weaker players (season PPG 20.3 vs 20.7). So the unpaired number
*understates* the edge a little.

So the conclusion holds for the variable the page shows: in 2025, extra scheduled games
are worth a few percent, and 3 vs 4 is noise. **But 2024 can't be checked by schedule**
(no 2024 `sport_schedule`), and by games played it's about double 2025. The note's
numbers (27.1 vs 25.7, unpaired, games played) are the wrong pair to print beside a
games-scheduled column.

**Amendment**: quote the scheduled-games, within-player number (2025: +4.4% for 4 vs
2), say "one season, one league", and say 2024 by games played was +11%. Or drop the
number and say "a few percent in 2025". Amend R3 visibly. The "≥ 4 games" filter
stays dropped.

**F7 (medium). "Rank by form" is asserted, not measured. Season-to-date mean predicts
next week better.** (measured)

For starters with ≥ 10 prior games, correlation with next week's credited points:

| Season | n | last-5 mean | season-to-date mean | last-5 max |
| --- | --- | --- | --- | --- |
| 2025 | 1,815 | 0.476 | **0.517** | 0.399 |
| 2024 | 2,131 | 0.474 | **0.494** | 0.438 |

This sample is rostered starters, not free agents. Form may matter more for a player
whose role just changed, which is the case the page is for. But the plan presents
form as the obvious ranking, and the only available measurement says the opposite.

**Amendment**: either rank by season-to-date mean and show form beside it, or keep
form and label the choice ARBITRARY in `weights.yml`, citing this table. The page
already has `minDelta`: "season mean, flagged where minutes rose" uses both signals
without pretending form is the better predictor. Whichever you pick, add a
preference-ordering test (lessons bug class #1).

**F8 (medium). `gameCountNote` puts a measured, league-specific number in a Java
string and shows it on every basketball league.** (read)

- The plan's constraints say "Reasons are codes, and sentences live in the web". The
  contract then ships a server-held sentence with two hard-coded numbers. Nothing makes
  the Java string, R3 and the doc amendment agree, and that's the two-copies shape.
- One-game crediting was measured in **one** NBA league chain ("Ball Knowers"). Spec
  018 R2 couldn't tell whether the manager or a rule picks the game. This is a
  multi-user app, so another basketball league may sum games, and there the note would
  be false and "game count barely matters" wrong.

**Amendment**:
- Put the sentence in web copy, keyed by a code (e.g. `ONE_GAME_CREDITED`). Put the
  figures in one place on the server, as a labelled measurement constant or in
  `weights.yml` with provenance, and send them as numbers.
- Only send it when *this* league's own stored weeks show one-game crediting. The
  check is cheap: `players_points` matches a single game for ≥ 90% of multi-game
  starter-weeks. A league with no scored weeks inherits nothing; for a chain, use its
  previous season. Otherwise send null and say nothing.

**F9 (medium). All-Star game rows count as played games.** (measured)

Each season has 15 player rows from the All-Star game:

- 2025: `game_id 1304923502905663488`, 2026-02-15, opponents `STP`/`STR`, 6–12 min each.
- 2024: `20250216_CHK_SHQ`.

There's also a team row whose id is a bare `TEAM_` (that's R2's "31st id" and its
"1 of 1,232 games without both team rows"). These are the league's best players, and
an All-Star game is a 6–12-minute, ~5-point "game" in their last-3 and last-5 windows
in mid-February.

**Amendment**: exclude them in the season read. Any of these works, cheapest first:
- `opponent` not one of the 30 codes in the season's `TEAM_*` rows;
- the game has a `TEAM_` row with an empty suffix;
- `sport_schedule.status = 'canceled'` (2025 has it stored as `canceled`; 2024 has no
  schedule).

Test it on the real 2025 game id. Check whether the league credited week 17 from that
game. That isn't needed for this spec.

**F10 (medium). The spec and data-model disagree on a player's team, and the spec's
rule is wrong during the fallback.** (measured)

- Spec edge case: "Team comes from his latest game's `TEAM_*` pairing, else
  `player.team`."
- R7 / data-model: `player.team`.

Across 2025's 582 players with a team-row pairing, **139 are on a different team now
and 90 have no team**. During the fallback, the pairing rule would count 2025 teams'
2026 games for 139 players.

**Amendment**: `player.team` only, null shows "—". Delete the spec edge case and
replace it with "traded players: Sleeper's current team, refreshed daily". In-season
there's a lag of up to a day after a trade, which is fine. State it.

**F11 (medium). "Owner naming reuses the `SpotlightOwnership` rule": that rule isn't
callable.** (read)

The naming (team name, then manager name, then "Roster N") lives inline in the
package-private `SpotlightOwnership.build` (`:60-97`), tangled with the latest-week
breakdown. Its javadoc says it is itself a copy of `WeeklyReportService#forWeek`. A
builder told to "reuse" it will either call `build` with fake week breakdowns or write
copy number three. Lessons #25 and #29 cover that pattern.

**Amendment**: name the extraction in the plan. A
`static Map<Integer, String> ownerNames(standings, members)` (plus avatar and isMe if
wanted) that both `SpotlightOwnership` and `PlayerTrendsService` call. Have
`OneEfficiencyImplementationTest`-style guard coverage, or at least one test that
both produce the same name for the same roster.

**F12 (low-medium). Averaging per-game usage is noisy. Pool it instead.** (measured)

Per-game usage in games under 5 minutes (2,156 in 2025): median 12.9, **max 128.3**.
Over 5 minutes, the max is 60.2. "Mean of non-null usg" over a 3-game window lets one
2-minute cameo dominate.

**Amendment**: pool the window,
`Σ(FGA+0.44FTA+TO)·(TmMIN/5) / Σ MIN·(TmFGA+0.44TmFTA+TmTO)`, which weights by minutes.
Also, absent stat keys mean 0: in 2025, 1,308 played rows have no `fga` key and 9,784
no `to` key (e.g. `{"sp": 135, "plus_minus": -2}`). The key is `to`, not `tov`. A
reader that treats a missing key as "no data" will drop real games. Test both.

**F13 (low). One request reads about 13 MB of JSON, with no cache.** (measured)

The 2025 NBA season's `stats` text is 13 MB. The DB side of the season read is 99 ms
locally. Parsing ~29k JSON objects and scoring them on every page view is unmeasured,
and production's DB is across a network. The plan's "well under a second" is a guess
(it says so).

**Amendment**: V3 should time it in production too. If it's slow, either project only
the needed keys in SQL (`sp`, `fga`, `fta`, `to`, plus the league's scoring keys), or
cache the per-season compute keyed on `max(fetched_at)`. Rosters and scoring are per
league; the per-player game math isn't.

## Lower-severity notes

- **N1. R2's counts are slightly off.** (measured) NBA has **31** `TEAM_*` ids and
  2,463 rows per season, not 30 / 2,462. The 31st is the bare `TEAM_` All-Star row
  (F9). The single game without both team rows is that game.
- **N2. NFL has `TEAM_*` rows too.** (measured) 544 (2025) and 128 (2026), plus D/ST
  rows keyed by a bare team code (`DEN`, `SEA` …). So `TEAM_ID_PREFIX` isn't
  NBA-specific. Every read that uses it should also filter by sport, as
  `teamTotals(sport, season)` does. No existing `main` code names `TEAM_` (read), so
  nothing depends on them today.
- **N3. Escaping the LIKE.** In a Java text block, `'TEAM\_%'` is a compile error
  (`\_` isn't a valid escape). It must be `'TEAM\\_%'`. Building it from the constant
  as `TEAM_ID_PREFIX + "%"` without escaping makes `_` a wildcard. That's harmless on
  today's data, but it isn't what the constant says. `left(sleeper_player_id, 5) <>
  'TEAM_'` or `starts_with(...)` avoids escaping entirely.
- **N4. The `text[]` bind needs a `Connection`.** `RosterSeasonRepository` has only a
  `JdbcClient`. Copy `LeagueRepository:29-54` (`JdbcTemplate` +
  `ps.getConnection().createArrayOf("text", …)`). Nothing calls
  `new RosterSeasonRepository(` (read), so a constructor change is safe. The IT
  round-trip is the right check, and it should cover null, `{}` and a non-empty array.
- **N5. Null vs empty.** Sleeper sends `[]`, not null, before the draft (measured). The
  ingest should always write a non-null array, the union of whatever is present, so
  the only null `players` is "never fetched since V28". Keep the repo's "clear, not
  skip" convention: `players = excluded.players` without `coalesce`. There's one
  producer, so a null can't arrive from elsewhere.
- **N6. Which scoring applies in the fallback.** (measured) 210 (2026) and 211 (2025)
  have identical `scoring_json`. 212 (2024) differs (`dd` 1, `td` 2, no ast/reb
  bonuses). Scoring 2025 games with the viewed league's (2026) scoring is the right
  choice for a pickup decision. Say so in data-model, since "league's scoring" is
  ambiguous when the data season differs.
- **N7. Games this week for a finished season.** For 2025 (`leg` 21, complete),
  `gamesThisWeek` for week 21 is meaningless. Send null when the grid says
  `seasonOver`. Mid-week, the count includes games already played (017 review N8), so
  label it "games this week, incl. played", or add a remaining-games count.
- **N8. Grid indexing.** `ScheduleGridService.Team.games` is indexed by position in
  `weeks`, not by week number (`:101-104`). Map through `weeks` and don't index by
  `currentWeek - 1`. A test should use a schedule whose weeks don't start at 1.
- **N9. Roster freshness.** Rosters are re-read at most hourly, and only when someone
  opens a page (`RefreshProperties.STALE_AFTER` = 1h). `rostersFetchedAt` should be
  the **min** over the league's rows (data-model doesn't say), and the page should
  show "rosters as of {time}".
- **N10. Waivers.** NBA 2026 has `waiver_clear_days: 2`. "Unrostered" includes
  players on waivers, who can't be added straight away. Say "unrostered" rather than
  "free agent", or note it.
- **N11. A hidden constant.** Streaming is "top `list-size × 2`". The ×2 is hand-set
  and not in `weights.yml`. Give it its own key (`streaming-list-size`).
- **N12. R5's counts don't match the rule.** They were taken over players with ≥ 8
  games, but the baseline needs ≥ 5. The contract example's `risersTotal: 41 /
  fallersTotal: 33` is the 12-20 snapshot, not the end of the season the fallback
  shows. Re-measure at 2026-04-12 with ≥ 5, or mark the example as illustrative.
- **N13. Health flag.** Spec 018 added a `draftGradesLoaded` flag to
  `/api/health` (`HealthController:44`). Add `playerTrendsLoaded` the same way, so a
  missing `weights.yml` block shows up in production before a user sees
  `NOT_CONFIGURED`.
- **N14. Spotlight and Trends will disagree from 10-10 to 10-20.** Spotlight reads
  ownership from the latest scored week, so everyone is unrostered there; Trends
  reads V28. This is a named follow-up, but it should also go on HANDOFF, so nobody
  "fixes" one to match the other in live verification.
- **N15. The fallback role lists describe April.** Risers and fallers "as of
  2026-04-12" are end-of-season rest and tank rotations (see F2's examples). The
  `fallback` label is honest about the season, but not about the month. Consider
  "end of 2025 season" in the header sub.
- **N16. Not re-measured.** "The Weekly Report payload for NBA 2025 week 5 has no
  `TEAM_` string" needs a running server. This review didn't start one.
- **N17. Fine as written.** The median-of-3 recent window, season mean including the
  recent games (slightly damping `minDelta`), and the ≥ 5 baseline. The 2026 schedule
  holds 1,200 games (Cup knockouts TBD), which doesn't affect week 1.
