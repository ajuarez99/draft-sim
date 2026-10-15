# Adversarial plan review: spec 035

This review read spec.md, plan.md, research.md, data-model.md, contracts/*.md and
quickstart.md cold on 2026-10-14. It checked their claims against worktree
`035-espn-yahoo-ingest` @ `8346cdf` (code identical to `main`), the shared local Postgres on
5433 (read-only queries), the public Sleeper API, and ESPN's public (no-auth) player and
pro-team endpoints. No private league was touched and no credential was used. No code exists
yet.

Every item is labelled **measured** (run today, output quoted), **read** (from code, not
executed) or **suspected** (inferred, not run).

## Claims confirmed

| Claim | Where checked | Result |
| --- | --- | --- |
| R1: 401 `AUTH_LEAGUE_NOT_VISIBLE` for a private league, 404 `GENERAL_NOT_FOUND` for a missing one, both JSON | `curl` ffl league 1 / 12345678, fba league 1 (measured) | ✅ 401 `application/json`, 404 `GENERAL_NOT_FOUND` ×2 |
| R2: 2,675 NFL / 1,097 NBA active ESPN players, public, with `ownership` | `curl …/players?view=players_wl` + filter header (measured) | ✅ 2675 / 1097, every row has `ownership`, `proTeamId`, `defaultPositionId` |
| R4: ESPN NFL uses `WSH`, Sleeper `WAS`; NBA mixed case | `proTeamSchedules_wl` both games vs `select distinct team from player` (measured) | ✅ NFL 33 rows incl. `FA`,`WSH`; app has `WAS`. NBA `Bkn, NY, GS, SA, Utah, Wsh, Phx, Cha, NO` |
| R3: `PlayerMatcher.build` silently keeps the first of two same name+position players | `PlayerMatcher.java:59` `putIfAbsent` (read) | ✅ |
| R5 line refs for the calls it lists | `LeagueAnalysisService:872`, `PowerRankingService:147,295`, `MemberRankingService:105,209`, `LeagueHistoryController:796`, `SeasonStartService:201` (read) | ✅ lines correct. The list is **incomplete**, see F4 |
| R16 / FR-005: repositories can join one `TransactionTemplate` | repos use `JdbcClient`/`JdbcTemplate` on the Spring `DataSource`; `LeagueIngestService:46` already wraps writes in a `TransactionTemplate` (read) | ✅ precedent exists; no repository opens its own connection or uses `REQUIRES_NEW` (grep, read). See F15 for the residual |
| V19 transaction type set is `WAIVER, FREE_AGENT, TRADE, COMMISSIONER` | `V19__league_transaction.sql` (read) | ✅ |
| An `espn-…` key can't collide with a Sleeper id and Sleeper answers 404 for it | `curl api.sleeper.app/v1/league/espn-ffl-12345-2026{,/rosters,/users}`, `/draft/espn-…-draft{,/picks}` (measured) | ✅ every one `404 application/json`, body `null`. So every unguarded Sleeper call with an ESPN key throws `HttpClientErrorException.NotFound` (RestClient default), not a silent empty |

## Findings

Ranked by severity. 6 BLOCKER, 9 SHOULD-FIX, 6 NIT.

---

**F1 (BLOCKER). ESPN draft picks would change every Sleeper manager's fitted profile and the
positional priors. FR-013 broken, and SC-005's parity set can't see it.**

*Plan:* "FR-013: zero behaviour change for Sleeper leagues"; one draft row and its picks
are written per ESPN season; SC-005 parity diffs analysis, history, power, standings and
`/api/drafts`.

*Found (read):*
- `DraftRepository.allCompletedPicks(sport)` (`DraftRepository.java:479-495`) selects every
  pick of every `complete` draft of the sport, with no league or platform filter.
- `ProfileService.fit` (`ProfileService.java:133-140`) builds the positional priors from
  all of them (`fitPriors`). It computes `leagueMeanReach` (`:176`) across all managers and
  shrinks every manager toward it (`:212`).
- `BoardService` (`BoardService.java:299-322`) backfills `draft_pick.adp_at_time` for any
  draft with a `start_time`, again with no platform filter. So if ESPN draft rows carry a
  start time, ESPN picks become "scoreable" reach picks.
- Net effect: adding one ESPN league with a finished draft moves the priors and the
  shrinkage target that every Sleeper seat simulates with. Mock drafts, the live room's
  resim, `/api/managers` tendencies and draft-day predictions for Sleeper leagues all
  change. None of the SC-005 payloads read profiles, so the parity diff passes.

*Amendment:*
- Exclude non-Sleeper drafts in `allCompletedPicks` and in the `adp_at_time` backfill
  (`join league l … and l.platform = 'SLEEPER'`), or don't store an ESPN draft's
  `start_time` **and** filter it anyway. The filter is the rule; the missing timestamp is
  only luck.
- Spec Assumption "ESPN managers are seated as neutral" then needs its own decision: an
  ESPN manager is fitted from nothing.
- Add to SC-005 parity: `GET /api/managers?sport=nfl|nba` and the `ProfileService.Fit`
  summary line (`profiles: N managers, M scoreable picks, league mean reach X`) before and
  after adding the ESPN league. Add a test asserting that `allCompletedPicks` ignores an
  ESPN draft.

---

**F2 (BLOCKER). A stored credential can be borrowed by any signed-in caller through the add
endpoint.**

*Plan:* contracts/espn-setup.md: "If [credential] is absent and the league already has a
stored credential, the stored one is used." R13: "an impersonator can then do nothing with
stored cookies except refresh a league that's already in the store and already visible to
them."

*Found (read + measured):*
- The add route's gate is "the same membership/identity gate as the existing
  `/api/setup/*` routes", which only checks for a non-blank `X-Sleeper-User` before
  ingesting.
- The credential row is keyed `(platform, sport, platform_league_id)`. Nothing ties its
  *use* on the add path to the caller.
- ESPN league ids are small integers. R1 scanned ids 1… and got 401s for real private
  leagues (measured: ffl `1` → 401).
- So any signed-in user can POST `{leagueId: N, sport, season}` with no cookies. For any
  `N` that has a stored credential, the server reads the private league with Allan's
  cookies and returns its name, seasons, teams and manager names in the 200 body. Then F3's
  claim gives them the pages. R13's reasoning only holds if the stored credential is
  reachable by refresh alone.

*Amendment:*
- A stored credential is used **only** (a) by refresh of a league row that already
  exists, and (b) by an add/re-add from a caller for whom `membership.canSee(caller,
  thatLeague)` is already true.
- Otherwise an add without cookies proceeds as public and gets `ESPN_PRIVATE_NO_ACCESS`.
- Add an IT: user B (a stranger) POSTs Allan's private league id without cookies and gets
  403 with nothing in the body beyond the code.

---

**F3 (BLOCKER). After an add, the adder has no membership path to the league, so the claim
step either can't work or must be ungated.**

*Plan:* add, then `POST /api/setup/espn/league/{key}/claim` "uses the same
membership/identity gate as the existing `/api/setup/*` routes". `me ∪ linked` only takes
effect once a `manager_link` exists.

*Found (read + measured):*
- `LeagueMembership.MINE_AND_CHAIN` (`LeagueMembership.java:63-81`) finds leagues through
  `roster_season`, `draft.slot_to_manager` or `league_member` joined to `me`. Right after an
  ESPN add, none of these contain the adder's Sleeper manager.
- So `canSee` is false, `/api/drafts` doesn't list the league, and `visibleLeague` 404s.
- The existing setup gate, `MemberSetupController.requireLeagueMember` (`:154-175`), falls
  back to `sleeper.leagueUsers(key)`. For an `espn-…` key that is a 404 (measured), so it
  refuses.
- If the build makes claim ungated to escape this, anyone can claim any unclaimed ESPN team
  and see the league. And because one ESPN member GUID is **one** `manager` row across all
  of that person's ESPN leagues and seasons (data-model: `sleeper_user_id = espn:{GUID}`),
  a link to it grants visibility into **every** stored ESPN league that member plays in,
  not just the one claimed in.

*Amendment:*
- Make the claim part of the add request (`claimRosterId` in the body, or a second call
  that's only valid for the caller recorded as the adder), and record the adder
  explicitly. A `league_member` row for the adder, or a small `league_adder` column on
  `league_credential`/`league`, is the membership path until the claim lands.
- The claim route requires `canSee` (which the adder row now satisfies).
- 409 if the ESPN manager is already linked to anyone.
- Write down that a link is per ESPN *member*, not per league, so a claim reaches every
  stored league of that member. "Claim moves" (contract) then removes visibility in the
  others too. Decide whether that's wanted.

---

**F4 (BLOCKER). R5's "five live calls" undercounts. Several request-reachable paths call
Sleeper with an ESPN key, including one that starts a background poll loop.**

*Plan:* "A `LeagueRosterSource` seam replaces the five live `sleeper.rosters` /
`sleeper.state` calls"; the picker's ESPN card "links to the league home, not the draft
room" (frontend only).

*Found (read):* `grep -rn "sleeper\.\w\+(" backend/src/main/java` outside `SleeperClient`.
Reachable from a request with an ESPN league or draft key, and not in R5:

| Path | Call | Effect with an ESPN key |
|---|---|---|
| `POST /api/drafts/{id}/track` (`LeagueController:414`) and `GET /api/drafts/{id}/live-stream` (`:475-495`, auto-tracks any non-`complete` draft) | `LiveDraftPoller.pollOnce` → `sleeper.draft`, `sleeper.draftPicks` (`LiveDraftPoller:461,491,550`) | The first tick throws, and `track` "starts the poller anyway" (`:251-260`). A virtual-thread loop then polls Sleeper with backoff until `preDraftMax`, because a failing tick never sets `everStarted` (`:394-428`). `DraftPicker.tsx:352-387` links every `pre_draft`/`drafting` card to `/live` today, and the URL stays reachable even if the card changes |
| `GET /leagues/{id}/power`, `GET/POST /ballot`, `POST /power/commissioner` (`LeagueHistoryController:529,680,786,994`) | `PowerRankingService.sportState` → `sleeper.state(sport)` (`PowerRankingService:80`) | Doesn't throw, but gives Sleeper's sport week, not the ESPN league's matchup period. Ballot `canSubmit` and the "no backdating" check use the wrong week for ESPN (Power is in scope) |
| `POST /api/setup/league/{id}`, `/api/setup/league-history/{id}` (`MemberSetupController:57-75`) | `leagueIngest.inferSport` → `sleeper.league` | 500 via an uncaught `HttpClientErrorException`. Reachable from the existing "Load past seasons" UI on an ESPN league |
| `/api/ingest/league`, `/league-history`, `/transactions`, `/all/{id}` (`IngestController:76-205`, admin) | `inferSport`, `TransactionIngestService:75,91` | 500, or a half-run Sleeper ingest against an ESPN row |
| `POST /api/mocks/from-draft/{id}`, `/api/sims` with an ESPN draft | seats from `slot_to_manager` and profiles | Not a Sleeper call, but an out-of-scope engine run on an ESPN draft with no backend refusal |

*Amendment:*
- Add a backend platform guard, not just the frontend gate. `LiveDraftPoller.track`
  returns "not followed" for a non-Sleeper draft without ticking.
- Every `/api/drafts/{id}/*`, `/api/mocks/from-draft`, `/api/sims`, `/api/setup/league*`
  and `/api/ingest/*` route refuses a non-Sleeper league or draft with the FR-008 code
  (422 `PLATFORM_NOT_SUPPORTED`).
- `sportState` gets a per-league variant: for ESPN, `leg` from `settings_json`.
- Re-derive the full list from the grep above in tasks.md, and add a test that walks every
  `@*Mapping` taking a league or draft key with an ESPN fixture and asserts no
  `SleeperClient` call (a mock that fails on any call).

---

**F5 (BLOCKER). "You" is resolved in 13 places that don't go through `LeagueMembership`, so
`me ∪ linked` doesn't reach "your team" on any in-scope page.**

*Plan:* R12: "The single `me` CTE becomes `me ∪ linked`, written once inside
`LeagueMembership`, so membership, 'your team' and standings highlighting all follow from
one rule."

*Found (read):* `grep -rn "idsBySleeperUserId"` finds the caller's single manager id
resolved directly from the header at:
- `LeagueHistoryController:103` (standings `isMe`), `:688` (ballot `isMember`), `:771`
  (ballot POST)
- `SuperlativesController:110`
- `LeagueAnalysisService:385` (`isMe` on the score grid)
- `NextMatchupService:76`
- `OwnerSlot:45,96`
- `PlayerStatsService:313,451`
- `PlayerTrendsService:191`
- `SpotlightOwnership:54`
- `WeeklyReportService:214`

For an ESPN league these return the Sleeper manager id, which owns no roster there. So
`isMe` is false everywhere, the home page's "You're 3rd of 10" never appears, and ballots
refuse the caller (`leagueMembers.isMember` on the wrong id). US1 scenario 2 / FR-004 fail
on every in-scope page while membership itself works.

*Amendment:*
- Add one resolver, `LeagueMembership.callerManagerIn(leagueId, sleeperUserId) →
  Optional<Long>`: the header's manager if it has a roster in that league, else the linked
  manager that does.
- Replace all 13 sites with it. They're behaviour-identical for Sleeper, because the
  linked set is empty.
- State explicitly that `MANAGER_LEAGUE_IDS_CTE` (by `manager.id`) is **not** expanded, so
  career, head-to-head and `/managers/{id}/history` stay per-platform (spec: "no
  cross-platform merging").

---

**F6 (BLOCKER). V34 is already taken and already applied to the shared DB.**

*Plan:* R17: "033 claims V32 … 034 claims V33 … 035 is `V34__espn_platform.sql`."

*Found (measured):*
- `flyway_schema_history` on 5433 `draftsim` has `33 | league bracket match` and
  `34 | trade assets`.
- `.claude/worktrees/033-trade-verdicts/…/migration` ends `V33__league_bracket_match.sql`,
  `V34__trade_assets.sql`. 033 renumbered after 034's review.
- `036-projection-foundation` also carries a V33.
- The quickstart's scratch DB is a `pg_dump` of `draftsim`, so it inherits `34 trade
  assets`. A `V34__espn_platform.sql` fails Flyway validation there (description and
  checksum mismatch). *(Validation failure inferred from Flyway's rules, not run.)*

*Amendment:*
- Use **V35** (re-check every worktree directory, not refs, right before committing).
- The scratch copy also needs 033's V34 to be resolvable or removed from history. Either
  rebase onto 033 once merged, or delete the `34` row in `draftsim035` only.
- Correct R17 to what is actually there (measured): there is **no V32** in the history any
  more. Installed order is `31 player season projection`, then `34 trade assets`
  (installed_rank 32), then `33 league bracket match` (installed_rank 33, applied out of
  order). The 034-review snapshot R17 cites is stale.

---

**F7 (SHOULD-FIX). data-model.md leaves out most of the dialect the in-scope pages actually
read.**

*Plan:* "translate into that dialect and write the same tables". data-model lists
`settings_json`, `status`, the manager/draft keys and transactions.

*Found (read):* what the Sleeper ingest writes that in-scope readers depend on, with no
ESPN mapping in data-model:
- **`league_member`** (`V9`; written by `LeagueHistoryIngestService.upsertManagers:140-150`):
  `team_name` is where team names come from (`LeagueMemberRepository:51`,
  `RosterOwners.ownerNames` via `SpotlightOwnership:57`). `is_commissioner` gates
  commissioner rankings (`LeagueMembership.canCommission`). Membership itself counts it.
  Without rows, SC-002's "team names match" fails and ballots refuse everyone.
- **`roster_season`** (`V5:8-21`): `wins/losses/ties/points_for/points_against` (ESPN
  `record.overall`), `points_possible` (Sleeper `ppts`; ESPN has no direct field, so it
  must be **null**, not 0), and `final_placement`. Sleeper writes it for the champion only
  and only when `status = complete` (`LeagueHistoryIngestService:157-200`, the V21 gate).
  Writing ESPN's full `rankCalculatedFinal` 1..N would give ESPN leagues placements Sleeper
  leagues never have.
- **`league_week_fetch` POINTS rows with `final`** (`V24:39`).
  `ScoredWeeks.of` (`ScoredWeeks.java:52-63`) treats **every** stored week as final when a
  league has no POINTS rows. If ESPN writes `roster_week_points` for the in-progress
  matchup period, or zero-point future periods, they count as finished games.
- **Week bounds**: Sleeper writes points only through `last_scored_leg` and future
  pairings only into `league_matchup` (`:244-344`). ESPN must write `roster_week_points`
  only for decided periods (`winner != UNDECIDED`, to be confirmed) and `league_matchup`
  for the rest.
- **`roster_week_points.starters`**: a JSON array in `roster_positions` slot order, null
  when unknown, not `[]` (`:288-296`). `matchup_id`: one shared int per pair, null for a
  bye (`V13:17-19`).
- **`reserve`** for the R5 seam: `LeagueAnalysisService:894` reads `roster.reserve` for
  basketball. `roster_season.players` (V28) is the sorted union of players + reserve +
  taxi with no slot info, so the seam can't rebuild `reserve` from stored rows as R5
  claims. A column (or a jsonb `slots`) is needed.
- **`league_transaction.week`** for NBA: ESPN's `scoringPeriodId` is a day (R10). The row
  needs the matchup period. `status`, `roster_id`, `manager_id`, `faab_bid` (`bidAmount`,
  null when not FAAB) and the `adds`/`drops` value shape (`{player: roster_id}`) are not
  specified.

*Amendment:* add a per-table mapping section to data-model.md covering the above. Each
entry is marked "confirm in T-verify" the way `settings_json` already is.

---

**F8 (SHOULD-FIX). "Omit, never write 0" does nothing for keys the readers coalesce to 0.
And `playoff_seed_type: 0` is a hard-coded rule.**

*Plan:* data-model: `playoff_seed_type → 0`, `league_average_match → 0`; "A key ESPN has no
value for is omitted, never written as 0."

*Found (read):*
- `LeagueRepository.playoffFormat` (`:258-275`) reads `coalesce((…playoff_seed_type)::int,
  0)`, the same for `divisions` and `league_average_match`.
- `waiverFormat` (`:317-326`) does the same for `waiver_type` and `waiver_budget`.
- So an omitted key **is** "plain ladder, no divisions, no median, waiver priority" to
  every reader. `modelable()` (`:253`) then publishes playoff odds for a format nobody
  checked.
- Hard-coding `playoff_seed_type: 0` asserts ESPN seeds by record. ESPN has its own
  seeding rule setting (third-party name `playoffSeedingRule`, unverified). That's the
  "optional params that encode rules" class that has shipped three times.
- `playoff_round_type` isn't in the mapping at all. Absent reads as null →
  `ROUND_TYPE_UNKNOWN`, which is safe but should be stated. ESPN multi-week playoff rounds
  (third-party `playoffMatchupPeriodLength`) map to it.

*Amendment:*
- Derive `playoff_seed_type` and `playoff_round_type` from the measured ESPN settings.
  Where ESPN's rule isn't the plain ladder, write an explicit non-zero sentinel so
  `modelable()` is false. Don't omit.
- Same for `divisions` (write the real count) and `waiver_type`.
- List `playoff_round_type` in the table.

---

**F9 (SHOULD-FIX). "week = matchupPeriodId" isn't the dialect for football, and the in-scope
analysis page joins on it.**

*Plan:* R10: "an ESPN league's `week` is its `matchupPeriodId`". `playoff_week_start =
matchupPeriodCount + 1`.

*Found (read; ESPN side suspected):*
- In the Sleeper dialect, an NFL league's week is the **NFL week**.
  `PlayerProjectionRepository` rows and `player_game`/`sport_week_stats` are keyed by sport
  week, and `LeagueAnalysisService` (in scope) values remaining weeks by week number.
- An ESPN league whose first matchup period is NFL week 2+, or whose playoff rounds span
  two scoring periods (*suspected*: ESPN supports both), would value the wrong NFL week's
  projections and label weeks off by one.

*Amendment:*
- For NFL, store week = the matchup period's single `scoringPeriodId` (the NFL week).
  Refuse with an FR-008 reason, or mark analysis unavailable, when any matchup period
  spans more than one scoring period.
- Keep matchup period as the week for NBA (R10's reasoning holds there), and say plainly
  that NBA ESPN weeks are not Sleeper weeks.
- Measure `matchupPeriods` on the real league before choosing.

---

**F10 (SHOULD-FIX). `espn:{id}` keys for unmatched players are only specified for
transactions. Elsewhere they're silently dropped or leak into sport-wide tables.**

*Plan:* data-model: unmatched `adds`/`drops` keyed `espn:{playerId}`. US2 scenario 3: an
unmatched player "is still shown by name".

*Found (read):*
- Every roster and points reader maps keys through `players.idsBySleeperId` /
  `playerBySleeperId` and skips misses (e.g. `PowerRankingService:156`,
  `LeagueAnalysisService:875`). A roster with an unmatched player renders one player short
  with no mention (lessons #24).
- `PlayerGameIngestService.rosteredPlayers` (`:371-381`) unions `players_points` keys from
  **every** league of the sport into the sport-wide absence pass. An `espn:…` key would get
  an `UNCLASSIFIED` `player_absence` row for every loaded week (`:330-336`). That's junk in
  a table Sleeper pages read by player id. *(The junk rows are inferred from the code path,
  not run.)*

*Amendment:*
- Decide once, in data-model, which columns may hold `espn:` keys (`roster_season.players`,
  `players_points`, `starters`, `adds`/`drops`).
- Filter that prefix in `rosteredPlayers`.
- Name the roster, lineup and transaction readers that must render the
  `platform_player_link.external_name` instead of dropping the key, and test one of them.

---

**F11 (SHOULD-FIX). The match rate was measured against the wrong population, and the
name-only fallback can attach an IDP player to a star.**

*Plan:* R3: "Real NFL players: 242/244 matched … NBA 299/300", measured against Sleeper's
active players. data-model: a hand alias "goes in the existing `player_alias` table …
which `PlayerMatcher.buildStrict` already consults first."

*Found:*
- **(measured)** The app's `player` table (what `PlayerMatcher` is built from) has 4,387
  NFL / 2,121 NBA rows including inactive and retired players. It has **27 NFL / 10 NBA
  exact name+position duplicate keys**, before normalisation adds more:
  - NFL: `David Johnson|RB`, `Mike Williams|WR`, `Frank Gore|RB`, `Ronald Jones|RB`,
    `Kyle Williams|WR`, …
  - NBA: `Jameer Nelson|PG` (retired + PHI active), `Mike James|PG`, …
  - All of these were rostered or drafted in seasons the history walk reads (up to 10
    seasons back), so the strict build refuses them. History-season unmatched counts will
    be well above R3's numbers. SC-003 only checks the current season.
- **(measured)** ESPN's active NFL list has duplicate `fullName`s across offence and IDP:
  `Lamar Jackson`, `Justin Jefferson`, `Chris Jones`, …
  - **(read)** `PlayerMatcher.match` (`:76-95`) falls to `byNameOnly` when the source
    position doesn't map (`Position.fromSleeper` → null for CB/LB).
  - The app table holds only fantasy positions (`PlayerIngestService:90`), so ESPN CB
    "Lamar Jackson" has a single name-only candidate: **QB Lamar Jackson**. That's a wrong
    attachment FR-007 forbids. It only bites if an IDP player is ever rostered or
    transacted (and "roster moves" are read league-wide), but the rule must be in
    `buildStrict`.
- **(read)** `player_alias` has no `sport` column, and V6's own comment says it "is never
  read or written by any Java code". `build(…, alias)` exists, but no caller loads the
  table. "Already consults first" is wrong.

*Amendment:*
- Re-run R3 against `select … from player` (the real matcher input), for the current
  season **and** one history season, and record both numbers.
- `buildStrict` refuses the name-only fallback when the ESPN position is known but
  unmodelled (IDP/HC/TQB → `ESPN_ONLY_POSITION`).
- Consider Sleeper's `espn_id` as a **tie-breaker only** among same-name candidates (both
  must agree, so the Conklin→Izzo case can't happen).
- For aliases, either add `sport` to a new ESPN alias table or key aliases in
  `platform_player_link` (an `outcome = 'MANUAL'` row) instead of `player_alias`.

---

**F12 (SHOULD-FIX). There is no scheduled per-league refresh to "include ESPN leagues" in.**

*Plan:* "`LeagueRefreshService.trigger` dispatches on `league.platform`. The scheduled
refresh includes ESPN leagues." R15: "The daily/scheduled path that refreshes Sleeper
leagues includes ESPN leagues on the same cadence." SC-006: "within one scheduled refresh
cycle."

*Found (read):*
- `DailyRefreshService.runAll` (`:94-105`) loops over **sports**: players, board, trending,
  projections. It never touches a league.
- There is no `@Scheduled` anywhere (grep).
- League refresh is on-visit only: `POST /api/leagues/{id}/refresh` (`RefreshController:104`)
  → `trigger`.
- `refreshChain` also decides `loaded_complete` through `RefreshDecision.completeAfter(status,
  succeeded, perGameWeeksAllFinal)` (`LeagueRefreshService:213-219`), using Sleeper
  per-game weeks, which say nothing about an ESPN season.

*Amendment:*
- Reword FR-010/SC-006/R15 to "on visit, same staleness rule as Sleeper". Don't build a
  scheduler.
- The ESPN branch states its own `loaded_complete` rule (ESPN status complete + a
  successful refresh after that).
- Bound transaction fetches with `league_week_fetch` kind `TRANSACTIONS`. For NBA, R9's
  per-`scoringPeriodId` view is one call per **day**, roughly 150 calls a refresh if done
  naively, against the plan's "under 10 s" target. *(Count suspected; depends on the
  measured view.)*

---

**F13 (SHOULD-FIX). The availability list misses routes, and the in-scope home page is
built from out-of-scope endpoints.**

*Plan:* page keys `draftRoom, draftGrades, stats, schedule, superlatives, weeklyReport,
expectedWins, forecast, lineups, recap`. In scope: home, history, power, analysis,
roster-management, transactions.

*Found (read):*
- `LeagueHome.tsx:129-150` fetches `getWeeklyReport` (its matchup block),
  `fetchSuperlatives` (awards), `getPlayerSpotlight`, `getSeasonStart` (which carries
  `draft.grades` → `GradesSummaryBlock`, `:238`), `getLeagueAnalysis` and `getNextMatchup`.
  Four of those are on the unavailable list. A page-level gate on Home either blanks the
  whole in-scope page or lets those blocks render zeros.
- The spec's in-scope "matchups" has no page in the plan. The weekly report is gated, and
  it's the only matchup view on Home.
- `App.tsx:107-161` routes not covered by a per-league availability key:
  - `/managers` (tendencies, which the spec names explicitly as out of scope and lists
    ESPN managers via `ProfileService.fit` → `ManagerController:55`)
  - `/managers/:id/history`, `/managers/:a/versus/:b`
  - `/mock/new` (seats real managers, including ESPN ones)
  - `/drafts/:id`, `/drafts/:id/board`
- These are manager- or draft-addressed, so a `GET /leagues/{key}/availability` can't gate
  them.

*Amendment:*
- Add per-block availability on Home: hide the block, with an FR-008 line, rather than
  gating the page.
- Name where ESPN matchups are shown (the history/analysis score grid, or ungate the weekly
  report's matchup half).
- Gate manager- and draft-addressed routes on the server: filter ESPN managers out of
  `/api/managers` and mock seating; F4's draft guard covers the rest. Add those routes to
  the SC-004 checklist.

---

**F14 (SHOULD-FIX). The credential leak paths the plan doesn't name.**

*Plan:* R13: "Cookies never appear in any response body, log line, exception message, or
`league_refresh.last_failure` text." The verification greps the log.

*Found (read):*
- `application.yml:70-72` sets the app package to `${LOG_LEVEL:DEBUG}`. Debug is the
  default in every environment that doesn't set it.
- Java records' generated `toString` prints every component. A request record like
  `EspnAddRequest(link, …, credential)`, or a `Credential(espnS2, swid)` record, appearing
  in any `log.debug("{}", req)` or any `"…" + req` exception message, leaks it.
- `ErrorHandler.java` returns `e.getMessage()` verbatim for every
  `IllegalArgumentException`/`IllegalStateException`.
- `LeagueRefreshService.reasonOf` stores `e.getMessage()` in `last_failure`, and `:226`
  logs the full stack.
- R13's "an impersonator can only refresh" also misses `PUT`/`DELETE …/credential`. The
  owner check is the same spoofable header, so an impersonator can delete or replace
  Allan's cookies. That's DoS, not a read, but it should be said.
- `espn_s2` is a whole-account ESPN session (*suspected*: it also authorises the write
  host, `lm-api-writes`), not a read-only league token.

*Amendment:*
- The credential type overrides `toString` to `"[redacted]"`, with a unit test.
- ESPN exceptions carry only codes and field names, never interpolated request objects.
- `EspnClient` uses `HttpClient.Redirect.NEVER` and refuses any host other than the
  configured base.
- The leak check (verification bar 5) runs with `LOG_LEVEL=DEBUG`, and its grep covers
  `league_refresh.last_failure`.
- The setup panel's warning says plainly that the cookies are an ESPN login, not a
  league-only key.

---

**F15 (SHOULD-FIX). FR-005 holds for one request, but not against a concurrent on-visit
refresh of the same league.**

*Found (read):*
- The add path and `trigger`'s `refreshChain` are separate entry points. Only `trigger`
  uses `chainFlight` (`LeagueRefreshService:93-104`).
- A re-add while a visit-triggered ESPN refresh is running runs two transactions upserting
  the same keys in possibly different orders. *(Suspected: a Postgres deadlock aborts one.
  It rolls back cleanly, so it's not data loss, but it's a user-visible failure.)*

*Amendment:* route both through the same single-flight key (chain key = oldest season's
`sleeper_id`) and keep the write order deterministic (league → managers → rosters → weeks →
transactions).

---

**F16 (NIT).** `LeagueRepository.ROW_COLUMNS` (`:146`) derives `ppr` from
`coalesce((scoring_json->>'rec')::numeric, 0)`. An ESPN league with `scoring_json = {}`
reads as standard scoring in `toSettings`, which the in-scope preseason power baseline uses
(`PowerRankingService:139`). Say which in-scope readers of `LeagueSettings` are
scoring-blind, or flag them under `scoring_untranslated`.

**F17 (NIT).** `platform_player_link`: "A `MATCHED` row is reused forever … never back."
A wrong match (F11) then can't be corrected without a manual DB edit. Allow a manual
override outcome.

**F18 (NIT).** Teams with co-owners (`owners[]` > 1) or no owner: data-model doesn't say
which member becomes `roster_season.manager_id`, or what a claim on a co-owned team means.
`primaryOwner` is the natural pick. Confirm it exists on the real league.

**F19 (NIT).** `draft.reversal_round`: data-model is silent. Writing the column default 0
asserts plain snake for ESPN. State it as measured from `draftSettings`, or as N/A because
ESPN drafts are never simulated (F4's guard).

**F20 (NIT).** `league_credential.owner_manager_id` has no `on delete` rule, while
`manager_link` cascades. Pick one so that deleting a manager can't strand an orphan
credential the owner check can never match.

**F21 (NIT).** The R5 table lists `PowerRankingService:147,295` as "`sleeper.rosters`,
`sleeper.state`". The state call is `:80` (`sportState`), and its four callers are F4's.
The line refs themselves are correct.

## Verified vs suspected, in one place

- **Measured today:** ESPN 401/404 shapes; 2675/1097 players; `WSH`/`WAS`; Sleeper 404 +
  `null` for every `espn-…` path tried; Flyway history 33/34 on 5433; 033's
  V33/V34 files; the app `player` table's 27/10 duplicate name+position keys; ESPN
  duplicate `fullName`s across offence and IDP.
- **Read, not executed:** F1, F3, F4, F5, F7, F8, F10, F12, F13, F14's code paths, F16.
- **Suspected:** F6's Flyway validation failure (from Flyway's rules); F9's ESPN multi-period
  rounds and late start weeks; F10's junk absence rows; F12's NBA call count; F14's
  `espn_s2` write scope; F15's deadlock.
