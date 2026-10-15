# Research: ESPN League Ingest (spec 035)

Every finding below is labelled **measured** (a request or query was actually run on
2026-10-14 and the number is from its output), **read** (from this repo's code, with a
file reference), or **third-party / unverified** (from the open-source `cwendt94/espn-api`
client's source or its recorded test fixtures, which date from 2015–2019). Nothing in the
third-party category is relied on until T-verify confirms it against the real league.

---

## R1. ESPN's read API is reachable and its errors are distinguishable — measured

`GET https://lm-api-reads.fantasy.espn.com/apis/v3/games/{ffl|fba}/seasons/{season}/segments/0/leagues/{id}?view=mSettings`
with no cookies, from this Windows machine:

| Case | Status | Body `details[0].type` |
|------|--------|------------------------|
| Existing private league (ffl id 1, 336358) | 401 | `AUTH_LEAGUE_NOT_VISIBLE` |
| No such league (ffl 12345678, every fba id tried) | 404 | `GENERAL_NOT_FOUND` |

Both are `application/json`. So the three setup failures User Story 1 scenario 3 asks to
tell apart (malformed / not found / private) are distinguishable from the response alone:
malformed is caught before any request, 404 is not-found, 401 is private-without-access.

**Not measured:** a 200 for any league. A scan of 20 ids per game for a public 2025 league
found none, so no live 200 payload has been seen yet. That is the first thing T-verify does
with the real league.

**Decision**: one `EspnClient` over `java.net.http` (same style as `SleeperClient`), base URL
in `application.yml` (`espn.base-url`), cookies passed per call, never stored on the client.
**Alternatives**: the older `fantasy.espn.com/apis/v3` host. Rejected because the
`lm-api-reads` host is the one that answered here, and the espn-api client also uses it.

## R2. ESPN's player catalogue is public, and Sleeper's ESPN-id crosswalk is not good enough — measured

`GET .../games/{g}/seasons/2026/players?view=players_wl` with
`x-fantasy-filter: {"filterActive":{"value":true}}` returned **2,675 NFL / 1,097 NBA**
players. Each player has `id`, `fullName`, `defaultPositionId`, `proTeamId`,
`eligibleSlots` and `ownership.percentOwned`. No auth is needed.

Sleeper's `/players/{sport}` carries an `espn_id`. Joining on it:

| Sport | Sleeper players with `espn_id` | ESPN active matched | Top-200 most-owned ESPN matched |
|-------|-------------------------------|---------------------|---------------------------------|
| NFL | 6,727 | 856 / 2,675 | **61 / 200** |
| NBA | **0** | 0 / 1,097 | 0 / 200 |

Among the NFL hits, 63 have different names. Most are suffix noise, but **one is a wrong
person**: ESPN `Tyler Conklin` maps to Sleeper `Ryan Izzo`.

**Decision**: do **not** use Sleeper's `espn_id` as a key. Match by name through the existing
`PlayerMatcher` (`backend/.../ingest/PlayerMatcher.java`). Remember each resolved
`(platform, external id) → player` in a new link table, so a player is matched once and then
stays stable.

## R3. Name matching against Sleeper's active players works (**amended after review F11: measured against the wrong population; re-measure against the app's `player` table**), and needs one fix — measured + read

The simulation reimplements `PlayerMatcher.normalize` (suffix strip, diacritics,
punctuation). It runs the 300 most-owned ESPN players per sport against Sleeper's active
players, using `fantasy_positions`:

| Sport | name+position | name only | ambiguous | miss | not a player |
|-------|---------------|-----------|-----------|------|--------------|
| NFL | 178 | 64 | 2 (Byron Young, Daniel Whelan) | 0 real players | 16 D/ST, 24 "TQB", 16 "Coach" |
| NBA | 282 | 17 | 0 | 1 (Chris Paul, 20.8% owned) | — |

- **Real NFL players: 242 / 244 matched.** The 2 ambiguous are genuinely two people with
  one name.
- **NBA: 299 / 300.**
- **ESPN-only positions:** "TQB" (team QB, `defaultPositionId` 15) and "Coach"
  (`defaultPositionId` 14) have no Sleeper equivalent. They match nothing and are recorded
  as unmatched with the reason `ESPN_ONLY_POSITION`.

**Read: a defect that FR-007 forbids.** `PlayerMatcher.build` uses
`byNameAndPosition.putIfAbsent`. When two app players share a name and position, the
**first one silently wins**, so Byron Young would be guessed, not refused.

**Decision**: add an ambiguity-aware build to `PlayerMatcher`. A name+position key held by
two or more players resolves to "ambiguous", never the first-inserted id. ESPN ingest uses
that build.

Whether the existing FFC ADP caller should switch too is **out of scope**. Switching would
change board inputs for Sleeper leagues, which FR-013 forbids. It's flagged in HANDOFF as a
separate finding.

**Decision, D/ST**: map `proTeamId` → team abbreviation → the existing
`byTeamForDefense` path.

## R4. ESPN team abbreviations disagree with Sleeper's — measured

`?view=proTeamSchedules_wl` returns `settings.proTeams[]` with `id`/`abbrev`:

- **NFL**: 33 rows including `FA`. Mostly Sleeper-compatible, but `WSH` (Sleeper: `WAS`).
- **NBA**: 31 rows in mixed case and ESPN's own spellings: `Bkn`, `NY`, `GS`, `SA`, `Utah`,
  `Wsh`, `Phx`, `Cha`, `NO` (Sleeper: `BKN`, `NYK`, `GSW`, `SAS`, `UTA`, `WAS`, `PHX`,
  `CHA`, `NOP`).

**Decision**: a hand-maintained `proTeamId → Sleeper abbreviation` table per sport, with a
unit test that every ESPN id from that endpoint maps to an abbreviation present in the app's
`player.team` values for that sport. Team is used only for D/ST matching and display, never
as a required key for players (same rule as `PlayerMatcher`'s javadoc).

## R5. Four league-page read paths call Sleeper live — read

These are not ingest-time calls. They happen while serving a page:

| Caller | Call | Fields used |
|--------|------|-------------|
| `engine/LeagueAnalysisService.java:872` | `sleeper.rosters` | `roster_id`, `owner_id`, `players`, `reserve` |
| `engine/PowerRankingService.java:147,295` | `sleeper.rosters`, `sleeper.state` | `roster_id`, `owner_id`, `players`; state `week` |
| `engine/MemberRankingService.java:105,209` | `sleeper.rosters` | `roster_id`, `owner_id` |
| `api/LeagueHistoryController.java:796` | `sleeper.rosters` | `roster_id` |
| `engine/SeasonStartService.java:201` | `sleeper.state` | week |

Called with an ESPN league key, each of these would ask Sleeper for a league that doesn't
exist.

**Decision**: one seam, `LeagueRosterSource.rosters(LeagueRow)`, returns the Sleeper-shaped
`List<Map<String,Object>>` these callers already parse:

- **Sleeper leagues** delegate to `SleeperClient.rosters`, so behaviour is unchanged
  (FR-013).
- **ESPN leagues** build the same shape from stored rows: `roster_season.players` (V28),
  the claimed/owner manager, and IR slots as `reserve`.

The current week for an ESPN league comes from `LeagueRepository.currentLeg`, which already
reads `settings_json->>'leg'`. ESPN ingest writes ESPN's `status.currentMatchupPeriod` there.
`sleeper.state` is only asked for Sleeper leagues.

**Alternatives rejected:**
- An ESPN live call at read time. It would put credential use and ESPN latency on every page
  view.
- Rewriting the four callers to read `roster_season` directly. That changes Sleeper behaviour,
  and FR-013 forbids it.

## R6. Translate at the ingest boundary into the dialect the store already speaks — read

The read side of the app understands one dialect: Sleeper's. That covers:

- `league.settings_json` keys: `leg`, `playoff_teams`, `playoff_week_start`,
  `playoff_seed_type`, `divisions`, `league_average_match`, `playoff_round_type`, and the
  waiver keys read in `store/LeagueRepository.java:258-330`.
- `roster_positions` slot names.
- Sleeper player ids as the keys of `roster_week_points.players_points`,
  `roster_season.players`, and `league_transaction.adds/drops`.
- Transaction types `WAIVER | FREE_AGENT | TRADE | COMMISSIONER` (V19 check constraint).

**Decision**: the ESPN ingest translates into that dialect and writes **the same tables**.
No read path learns a second dialect.

Where ESPN has no honest translation, the ingest **does not write a guess**. It records the
gap on the league, and the page shows the FR-008 message. This applies to:

- an unmodelled roster slot,
- category scoring,
- a scoring rule with no Sleeper key.

**Alternatives rejected**: a platform-neutral store with per-platform read adapters. That
touches roughly every engine service (dozens of files, read) for no user-visible gain in
this spec.

## R7. Scoring: take ESPN's points, never recompute them — read + decision

ESPN reports points it already computed, both per matchup (`schedule[].home.totalPoints`,
third-party shape) and per player. Several engine paths recompute fantasy points from player
stats × `leagues.scoringOf(id)`:

- `PlayerStatsService`, `PlayerSpotlightService`, `PlayerTrendsService`,
  `SeasonSuperlativesService:930`, `WeeklyReportService:341`, `DraftGradesService`,
  `LeagueAnalysisService:593`.

`LeagueAnalysisService:593` is in scope.

**Decision:**
- Standings, matchup scores and weekly player points come from ESPN's own numbers, written
  into `roster_season` and `roster_week_points`.
- `scoring_json` is filled only through a statId → Sleeper-key table covering the common
  stats. If **every** non-zero ESPN scoring item maps, it's written. If **any** item doesn't
  map, `scoring_json` stays `{}`. The league is then flagged `scoring_untranslated`, and any
  page that needs `scoringOf` shows FR-008's message for that league instead of scoring under
  an empty rule set.
- The first build ships the table with **no** entries confirmed. Each entry is confirmed
  against the real league's scoring page in T-verify, before that league's flag can clear.

**Why**: an empty scoring map scores every player 0. That's exactly the "unexplained zeros"
SC-004 forbids, and it's the "more certain than correct" failure AGENTS.md names.

## R8. Category leagues are refused — third-party, unverified

`settings.scoringSettings.scoringType` was `H2H_POINTS` in the 2018 fixture. Other values in
espn-api's code include `H2H_CATEGORY`, `ROTO`, `H2H_MOST_CATEGORIES`.

**Decision**: accept only `H2H_POINTS` (and `TOTAL_POINTS`, if it appears, read as season
points). Refuse anything else at setup with the exact value named. Confirm the value
string on the real league.

## R9. League shape fields — third-party, unverified (2018 fixture)

These come from a 2018 fixture. Every one is listed in the quickstart's "confirm" list.

- **League**: `id`, `seasonId`, `scoringPeriodId`, `status.currentMatchupPeriod`,
  `status.previousSeasons[]`, `status.isActive`, `settings.name`, `settings.size`,
  `settings.isPublic`.
- **Schedule settings**: `settings.scheduleSettings.{matchupPeriodCount, playoffTeamCount,
  matchupPeriods, divisions}`.
- **Roster settings**: `settings.rosterSettings.lineupSlotCounts` (slot id → count).
- **Members**: `members[]{id (a "{GUID}"), displayName, firstName, lastName}`.
- **Teams**: `teams[]{id, abbrev, location, nickname | name, owners[], primaryOwner,
  record.overall{wins, losses, ties, pointsFor, pointsAgainst}, rankCalculatedFinal,
  playoffSeed, roster.entries[]{playerId, lineupSlotId, playerPoolEntry.player{...}}}`.
- **Schedule**: `schedule[]{id, matchupPeriodId, winner, home{teamId, totalPoints},
  away{...}}`. A bye has no `away`.
- **Draft**: `draftDetail{drafted, inProgress, picks[]{overallPickNumber, roundId,
  roundPickNumber, teamId, playerId, memberId, bidAmount, keeper}}` and
  `settings.draftSettings{type, pickOrder[], ...}`.
- **Transactions**: `view=mTransactions2&scoringPeriodId=N` (espn-api's
  `get_league_offers`). This is the least certain shape in this list.

Team names: newer ESPN payloads are reported to use `name`, older ones `location` +
`nickname`. The ingest accepts either and fails loudly if neither is present (spec edge
case: "fails loudly rather than writing nulls").

## R10. NBA weeks: ESPN matchup periods vs. days — open, must be measured

For basketball, ESPN's `scoringPeriodId` is a **day** and `matchupPeriodId` is the
head-to-head week. This app's NBA "week" is Sleeper's leg. Spec 017 measured one game per
week per starter for Sleeper NBA (memory: "NBA one game per week is correct"), and that
rule does **not** carry to ESPN. ESPN NBA leagues sum every game each started player plays
in the matchup period.

**Decision**: an ESPN league's `week` is its `matchupPeriodId`, and its points are ESPN's own
totals for that period (R7). The app never re-derives NBA ESPN points from games, so the
Sleeper one-game rule never touches ESPN numbers.

Per-player weekly points for ESPN NBA need the boxscore view across the period's days.
**Not built until measured on the real league.** If unavailable, `players_points` stays
`{}` for those weeks and lineup-drilldown views show FR-008's message for the league.

Schedule-grid and stat pages that lean on Sleeper's NBA week calendar (spec 017) are
already outside Q1's in-scope list.

## R11. History — third-party, unverified

`status.previousSeasons[]` lists prior seasons. Seasons from 2018 on use the same
`/seasons/{y}/segments/0/leagues/{id}` URL. Earlier seasons use
`/leagueHistory/{id}?seasonId={y}`, and the espn-api client retries the other form on 401.

**Decision**: walk `previousSeasons`, newest first, to the same depth the Sleeper chain walk
reaches. Each season becomes its own `league` row, linked through `previous_league_id`
exactly as Sleeper's chain is, so `chainBySleeperId` and `LeagueMembership.MINE_AND_CHAIN`
work unchanged.

Pre-2018 seasons are attempted once through `leagueHistory`. A failure records the season as
missing (spec edge case), and the walk does not stop.

## R12. Identity and "you" — read

`X-Sleeper-User` → `manager.sleeper_user_id` → `LeagueMembership.MINE_AND_CHAIN`
(`store/LeagueMembership.java`). Membership is the union of `roster_season`,
`draft.slot_to_manager` and `league_member` for the `me` manager.

ESPN managers are their own `manager` rows: `sleeper_user_id = 'espn:' || member GUID`. The
column name is historical, and the prefix can't collide with Sleeper's all-digit ids.

**Decision**: a `manager_link` table, `(manager_id, linked_manager_id)`. Claiming team T in
an ESPN league inserts (signed-in manager → T's ESPN manager). The single `me` CTE becomes
`me ∪ linked`, written once inside `LeagueMembership`, so membership, "your team" and
standings highlighting all follow from one rule.

**Alternative rejected**: rewriting the ESPN manager's `sleeper_user_id` to the signed-in
user's id. That loses the ESPN identity and breaks when two Sleeper users are in one ESPN
league.

## R13. Credentials under an unverified identity — decision, needs plan review attention

FR-009 (Q2 = A) says to store ESPN cookies encrypted, one set per user. But this app's
"user" is an **unverified** `X-Sleeper-User` header (`LeagueMembership` javadoc: "This is
scoping, not security"). Anyone who sends Allan's Sleeper id is Allan, as far as the server
knows.

A per-user credential that can be used to **add any new league** would let an impersonator
read any private ESPN league Allan's cookies can see.

**Decision (amends FR-009's "one set per user"; shown in the plan as an amendment):**
- Credentials are stored **per league attachment**: the league they were first used to add,
  and the seasons of that same league.
- A refresh of that league uses them.
- Adding a *different* private league needs the cookies pasted again.
- The user can paste a new set or delete them from that league's setup page.

An impersonator can then do nothing with stored cookies except refresh a league that's
already in the store and already visible to them through membership. That's harmless.

**Storage**: AES-256-GCM, random 96-bit nonce per row. The key comes from the env var
`BK_CREDENTIAL_KEY` (base64, 32 bytes).
- No key set means private-league setup is **disabled** with a clear message. There is no
  plaintext fallback.
- Cookies never appear in any response body, log line, exception message, or
  `league_refresh.last_failure` text.

A test asserts the stored bytes don't contain the plaintext, and another asserts the failure
text contains neither cookie.

**Alternatives rejected:**
- Plaintext. FR-009 forbids it.
- Per-user storage. The impersonation reach described above.
- Asking every refresh. That was Q2's option C, and Allan picked A.

## R14. Keys and URLs — read

Every league URL and API route is keyed by `league.sleeper_id`, an opaque string:

- `App.tsx` routes `/leagues/:sleeperLeagueId/*`
- `api/*Controller` `@PathVariable String sleeperLeagueId`
- `LeagueRepository.bySleeperId`

ESPN's league id is stable **across seasons** and shared across games (ffl 12345 ≠ fba
12345).

**Decision**: an ESPN season row's key is `espn-{game}-{leagueId}-{season}`, for example
`espn-fba-12345-2026`. It's URL-safe, can't collide with Sleeper's all-digit ids, and is
unique per season like Sleeper's. A new `league.platform` column (`SLEEPER` default, `ESPN`)
records the platform explicitly (FR-002); the key prefix is never parsed to find the
platform. The same holds for:

- **Draft**: `draft.sleeper_draft_id = espn-{game}-{leagueId}-{season}-draft`, plus
  `draft.platform`.
- **Transaction**: `league_transaction.sleeper_transaction_id = espn:{ESPN transaction id}`.

**Alternative rejected**: renaming every `sleeper_*` column to `external_*`. It's churn across
~16 migration sites and dozens of queries for no behavioural change, and it's riskier to
FR-013 than additive columns.

## R15. Refresh — read

`refresh/LeagueRefreshService` + `league_refresh` (V24) already hold last-success,
last-failure and failure text per league. `trigger(sleeperLeagueId)` runs the Sleeper chain
ingest.

**Decision**: `trigger` dispatches on `league.platform`. ESPN runs `EspnLeagueIngestService`
for the current season only; finished seasons are never re-read. ESPN failures land in the
same `league_refresh` columns, with an ESPN-specific, credential-free reason:
`ESPN_ACCESS_DENIED`, `ESPN_NOT_FOUND`, `ESPN_SHAPE_CHANGED: <field>`, `ESPN_HTTP_<code>`.

The daily/scheduled path that refreshes Sleeper leagues includes ESPN leagues on the same
cadence (FR-010).

## R16. Transactional ingest — read

FR-005 requires that a failed add leaves nothing. Sleeper's ingest writes as it goes across
several services.

**Decision**: ESPN ingest has two phases.
1. Fetch everything for a season into memory: settings, members, teams, rosters, schedule,
   draft, transactions, player matches.
2. Write it in **one** `TransactionTemplate` transaction.

A failure in phase 1 writes nothing. A failure in phase 2 rolls back. A league season is at
most a few thousand rows, which is comfortably one transaction.

## R17. Migration number — measured (**amended after review F6: wrong, see below**)

- `main`: highest is V31.
- `033-trade-verdicts` claims V32. Per 034's plan review, it's already applied to the shared
  5433 DB.
- `034-rivalry-schedule-grid` claims V33.

**Decision**: 035 is **`V34__espn_platform.sql`**. Live verification runs against a scratch
DB copy (`draftsim035`), following 034's F1 precedent. The shared `draftsim` DB is never
migrated by 035. If 035 merges before 033 or 034, renumber at merge time; this is recorded in
HANDOFF.

**Amended after review (F6, measured 2026-10-14):** the above was a stale snapshot. 033 renumbered to `V34__trade_assets.sql`, and the shared DB's history holds 31, 34 and 33 (out of order), with no V32. 035 uses **`V35__espn_platform.sql`**.
