# Research: Player spotlight on the league home

All measurements taken 2026-10-01 against the local dev database (`localhost:5433`, refreshed overnight
by spec 009's automatic refresh) and the live Sleeper API, from worktree branch
`014-home-player-spotlight` cut at `origin/main` 2dd47f2. **Measured** and **assumed** are kept apart:
anything not run is labelled as an assumption.

## R1 — Per-game rows exist for both sports, for every player

**Measured.**

| sport | season | rows | distinct players | weeks | latest game_date |
|---|---|---|---|---|---|
| nba | 2024 | 28,798 | 600 | 1–25 | 2025-04-13 |
| nba | 2025 | 29,143 | 613 | 1–25 | 2026-04-12 |
| nfl | 2025 | 26,490 | 2,252 | 1–18 | 2026-01-04 |
| nfl | 2026 | 4,683 | 1,743 | 1–3 | 2026-09-28 |

`sport_week_stats` shows NFL 2026 weeks 1–3 `final = true` and week 4 `final = false` (fetched
2026-10-01 15:45 UTC). **There are no NBA 2026 rows.** Sleeper's `state/nba` reports
`season_type: pre`, `season_start_date: 2026-10-20`, and the NBA 2026 league is `pre_draft`.

**Decision**: Read `player_game` for all three sections in both sports. No new stat ingest is needed.
**Consequence**: the basketball sections ship into their pre-season state, and that state is
first-class (see R7).

## R2 — Scoring a stored football game reproduces Sleeper's points exactly

Spec 005 proved this for basketball only. `GameScoringService.score` is a plain dot product of league
scoring weights × raw stats, and football scoring has buckets (points allowed, yards allowed) and bonus
thresholds that could break a dot product.

**Measured**: for all three NFL 2026 leagues (league ids 3, 4, 9465), weeks 1–3, every rostered player
with a `player_game` row was scored in SQL with the same rule and compared with
`roster_week_points.players_points` (Sleeper's own figure). **1,686 player-weeks matched to the cent;
0 mismatches; worst difference 0.00.** Team defenses are included in that set.

**Decision**: Score football trending and rookie entries with `GameScoringService` and the league's
`scoring_json`, the same as basketball. **Why it matters**: these players are mostly *not* rostered, so
`players_points` (Sleeper's figure) does not exist for them. Read-time scoring is the only source, and
it has now been checked against the one place both figures exist.

## R3 — "Did not play" is distinguishable from a zero; "bye" has to be inferred

**Measured**: in "(Foot) Ball Knowers" 2026, every rostered player with no `player_game` row for a week
stored 0.0 points, and each had a `player_absence` row (`ENTRY_WITHOUT_PLAY`, `TEAM_PLAYED_NO_ENTRY` or
`UNCLASSIFIED`). Bases present for NFL 2026: ENTRY_WITHOUT_PLAY 2,357; TEAM_PLAYED_NO_ENTRY 25;
UNCLASSIFIED 4. **No bye basis exists**: `PlayerGameIngestService` (line ~327) writes nothing when a
player's team had no game that week.

`TEAM_PLAYED_NO_ENTRY` is computed only for players rostered in some league. An unrostered trending
player whose team played but who has no entry therefore has **no row at all**, the same as a bye.

**Decision**: Three states for a trending entry's figure: *played* (points), *did not play* (no game
row), and *bye*. Bye is shown only when it can be shown to be true: the player has no game row
**and** his current team appears as no row's `opponent` in that week. Otherwise the entry says "did not
play". Never 0 for a non-game (FR-005). Basketball has no byes, and that falls out of the same rule
without a sport check.

**Alternative rejected**: adding a BYE absence basis to the ingest. It is correct but touches spec 009's
parity-checked walk for a label. Revisit if the inferred rule proves wrong in verification.

## R4 — Rookie = `years_exp = 0`, and it is current

**Measured**: Sleeper's experience figure has already rolled over for 2026. 2026 draftees: AJ Dybantsa
0, Darryn Peterson 0, Fernando Mendoza 0, Jeremiyah Love 0. 2025 draftees: Cooper Flagg 1, Dylan
Harper 1, Ashton Jeanty 1, Cam Ward 1, Travis Hunter 1. Counts at 0: NBA 172, NFL 470. NFL has 42
players with null experience, including every team defense (e.g. "Las Vegas Raiders": null).

**Decision**: rookie ⇔ `years_exp = 0`; null is never a rookie (FR-007), which also keeps defenses out
with no special case. **Constraint discovered**: `years_exp` is *today's* value. For a past season it
mislabels (Flagg would not be a rookie in 2025). The spotlight therefore applies **only to the league's
current season** (R8). Deriving experience for past seasons is not attempted.

## R5 — Trending: source, attribution, freshness

**Measured**: `GET /players/{nba|nfl}/trending/add?lookback_hours=24&limit=25` returns
`[{player_id, count}]` for both sports, `Content-Type: application/json` (checked with `curl -D -`,
lesson class 4), and `cache-control: s-maxage=600`, so Sleeper's own data moves at most every 10
minutes. Counts are **platform-wide** across all Sleeper leagues (NFL top count ≈ 541k, NBA ≈ 600), not
this league. All 25 NBA and 25 NFL ids resolved against the local `player` table (0 unresolved).
**Sleeper's API documentation asks consumers to "give attribution to Sleeper" when using trending
data** (fetched from docs.sleeper.com).

**Decisions**:
- New table `sport_trending` (V26), replaced wholesale per fetch, with `fetched_at` and
  `lookback_hours` stored beside the rows (FR-011).
- Fetched as a **sport-wide, best-effort step of the existing league refresh** (spec 009), gated on the
  stored `fetched_at` being older than `RefreshProperties.STALE_AFTER` (1 h). Also run by the daily
  route. A trending failure is logged and recorded; it **does not fail the league refresh**, because
  trending is decoration on a page whose scores matter more.
- The section credits Sleeper visibly and says the counts are adds across all Sleeper leagues.
- **Rejected**: fetching at request time with an in-memory cache. That breaks FR-016 and the repo's
  "pages read stored data; refresh runs behind them" model, and it loses its cache on every Railway
  cold start.
- **Rejected**: a separate scheduler. Railway is kept serverless on purpose (spec 009), so an hourly
  in-process timer would hold an instance awake.

**Assumed, not measured**: that the hourly-on-visit cadence is fresh enough to read as "right now".
With a 24 h lookback, an hour of lag moves the list little, but this has not been observed over a day.

## R6 — "The most recent completed night"

`PlayerGameIngestService` stores no game dated today-in-UTC or later (lines 145–155, 255). A US evening
game on date D becomes storable at 00:00 UTC on D+1, which is 8 pm Eastern on D itself, while games are
still being played. **So "latest game_date with rows" is not "a completed night".** Rows for D may be
partial box scores until a refresh after the slate ends.

**Decision**: night D is *complete* when the `sport_week_stats.fetched_at` of the week containing D is
at or after **D+1 10:00 UTC** (6 am Eastern, comfortably after the latest West Coast finish). The
spotlight shows the latest complete night. If a later date has rows but is not yet complete, the
response says so (`laterNightInProgress: <date>`) instead of hiding it silently.

**Assumed, not measured**: 10:00 UTC as the cutoff. This cannot be measured until NBA games exist
(2026-10-20). It is a named constant, flagged for the first live check (quickstart V6). Postponed and
overtime games past 10:00 UTC would be rare, and the next refresh corrects them.

The same rule applies to football without a sport check: an NFL "night" is never asked for, because
football's sections are keyed by week (R9).

## R7 — Pre-season and "nothing yet" states

`SleeperClient.state(sport)` exists, but it is a live call. **Decision**: the empty state says "No games
yet this season" from stored data alone (no `player_game` rows for the season). The season start date
is shown only if it is already known without a request-time call. The plan stores
`season_start_date` with each trending fetch, which already reads Sleeper's state in the same refresh
step. Otherwise no date is shown. A guessed date is never shown.

## R8 — Which season the spotlight covers

**Decision**: only the league's current season (the sport's `league_season` as stored by the refresh,
or the league row's season when it is the newest in its chain). For a past-season league home, the
endpoint returns `applies: false, reason: PAST_SEASON` and the home renders nothing for these sections:
idle, not an empty state, following `useBlock`'s "null load = does not apply". **Why**: trending is
inherently "now", and rookie status is only correct for the current season (R4).

## R9 — Football: which week, and which top-players list

The home's latest-matchup block calls `getWeeklyReport(id, 0)`, which resolves to the latest **final**
week, or the latest stored week while nothing is final. Today that is NFL week 3. That payload already
carries `topPerformers` for football.

**Decisions**:
- **Top players of the week (football)** renders `weekly.topPerformers` from the payload the home
  *already fetches*. No new endpoint, so it cannot disagree with the Weekly Report (FR-008, SC-004).
- **Amended scope**: that list is **starters only** (`WeeklyReportService`: "Only players who were
  actually started"). The spec said "rostered players". The plan follows the Weekly Report, since
  matching it is the stronger requirement. The spec is amended visibly.
- Trending and Rookie watch for football are scored against **the same week**, resolved by the same
  rule. The rule is extracted from `WeeklyReportService.forWeek` into one shared static so the two
  cannot drift. The spotlight response echoes `week`, and the client labels both from it
  (count-and-label-one-source).

## R10 — Ownership without a live roster call

Current rosters are not stored; `PowerRankingService` and others call `sleeper.rosters()` live.
**Decision**: ownership = the roster whose **latest stored week's** `players_points` contains the
player. The refresh refetches the in-progress week hourly, so this is current to within the refresh
window. It needs no request-time call (FR-016). The label reads "rostered by", not "scored for".
**Known gap, accepted**: a pickup since the last refresh shows as unrostered until the next one.
Pre-draft leagues (NBA 2026 today) have no weeks, so everyone is truthfully unrostered.

## R11 — One endpoint or three

`LeagueHome` loads one endpoint per block. **Decision**: one new endpoint,
`GET /api/leagues/{id}/player-spotlight`, returning the sections, each with its own
`unavailable` reason computed server-side. A section that throws is caught and reported as
`SECTION_FAILED`, never failing its siblings. A failure of the whole endpoint costs only the spotlight
block, never the rest of the home (FR-012). **Rejected**: three endpoints. They would share every lookup
(league, scoring, players, ownership, the night or week), so three requests would do that work three
times on a page that already makes five calls.

## R12 — Migration number

Highest migration on `origin/main` is `V25__manager_note_private.sql`. No local branch carries
V26–V29 (checked with `git ls-tree` across all branches). **Decision**: `V26__sport_trending.sql`. The
number must be re-checked at merge time, because concurrent sessions are a recorded occurrence.

## R3 — amended after review (2026-10-01)

**The original R3 was wrong.** It said basketball "has no byes, and that falls out of the same rule without
a sport check." It does not. A NIGHT period is one calendar date, and on most NBA nights only some teams play.
Measured in the dev DB, NBA 2025: 2026-04-09 had 12 teams with a game, 04-08 had 14, 04-07 had 20. A trending
player whose team was off that night would have been labelled "Bye". The rule also misfired for football: in a
week that isn't final, a player whose game has not kicked off yet has no row, and his team is not yet anyone's
opponent, so he would read "Bye in Week 1".

**Corrected decision:** the outcome is renamed `NO_GAME` (his team had no game in the period). It is inferred only
when the period is settled: a NIGHT, which is complete by construction, or a WEEK with `weekFinal = true`. Otherwise
the entry is `DID_NOT_PLAY`. The page words `NO_GAME` by period kind, never by sport: "No game on Wed, Oct 21" for a
night, "Bye in Week 4" for a week. Found by the T041 bug-hunting review.

## R6 — owed (2026-10-01)

The 10:00 UTC night-complete cutoff is still **unverified**: no NBA 2026 game has been played yet. The check is
quickstart V6, on the first morning after 2026-10-21. It is recorded as an open item in HANDOFF.md. Until it has
run, treat the cutoff as a guess.

## R7 — amended: the NBA preseason (2026-10-01)

**Measured** on `api.sleeper.app`, the host `SleeperPlayerStatsClient` uses:
- Sleeper publishes an NBA preseason: `/schedule/nba/pre/2026` lists 66 games, 2026-10-03 to 2026-10-16.
- Preseason box scores exist too: 2025 preseason weeks 1–2 carried stats for about 1,200 player entries.

The app ingests `season_type=regular` only, for both stats and schedule. So during the preseason the spotlight
has no nights, and the earlier sentence "No games yet this season" was false while games were being played.

**Decision (Allan, 2026-10-01): option A.** Reword to "No regular-season games yet … preseason games aren't counted
here." **Not built: option B**, ingesting and showing labelled preseason nights. Preseason week numbers start at 1
and would collide with regular-season weeks in `player_game`, so it would need its own storage, a refresh pass and
labelling. It was judged not worth it for a two-week window this year, and is a candidate for next season,
mainly for rookie watch.
