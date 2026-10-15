# Implementation Plan: ESPN League Ingest

**Branch**: `035-espn-yahoo-ingest` (worktree `.claude/worktrees/035-espn-yahoo-ingest`, cut from `origin/main` 8346cdf) | **Date**: 2026-10-14 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/035-espn-yahoo-ingest/spec.md`. ESPN only; Yahoo is deferred to its own spec (Q3 = A).

## Summary

An ESPN league (football or basketball, points scoring) can be added by link or id. It shows up in the league picker with an ESPN label, and the in-scope league pages work on it: standings, matchups, rosters, transactions, power rankings, analysis and history.

The approach is **translate at the boundary**. The read side of the app speaks one dialect, Sleeper's: settings keys, slot names, transaction types, and Sleeper player ids as JSON keys. A new ESPN ingest fetches a season, translates it into that dialect, and writes **the same tables** the Sleeper ingest writes, all in one database transaction (research R6, R16).

Only three things on the read side change:

1. A `LeagueRosterSource` seam replaces the five live `sleeper.rosters` / `sleeper.state` calls that happen while a page is being served (R5).
2. `LeagueMembership`'s `me` clause also includes ESPN teams a user has claimed (R12).
3. Pages that can't be produced honestly for an ESPN league say so (FR-008) instead of rendering zeros (R7).

Three measured findings shaped this:
- Sleeper's ESPN-id crosswalk matches only 61 of the 200 most-owned ESPN NFL players, and at least one of those matches is wrong (R2). Players are therefore matched by name.
- Name matching reaches 242/244 real NFL players and 299/300 NBA players (R3).
- `PlayerMatcher` silently picks the first of two same-name players (R3). The ESPN path needs an ambiguity-refusing build.

## Amended after review (2026-10-14)

[plan-review.md](plan-review.md) found 6 blockers, 9 should-fix and 6 nits. I spot-checked F1, F5 and F6 myself:
- F1: `DraftRepository.allCompletedPicks` has no platform filter (read).
- F5: 14 `idsBySleeperUserId().get(` sites (grep count).
- F6: 033's worktree now holds `V34__trade_assets.sql` (listed).

All 21 are accepted. **These amendments override anything below, or in research, data-model or contracts, that they contradict.** Where the review left a choice open, the call is made here and labelled **(decided in-session; Allan can overturn)**.

- **F1 (blocker): ESPN never feeds the draft model.**
  - `DraftRepository.allCompletedPicks` and `BoardService`'s `adp_at_time` backfill filter to `l.platform = 'SLEEPER'`.
  - ESPN draft rows store no `start_time` either, but the filter is the rule.
  - `ProfileService.fit` and `/api/managers` exclude ESPN managers. They're never fitted, and they're never seatable in mocks; mocks are out of scope.
  - SC-005 parity adds `GET /api/managers?sport=nfl|nba` and the fit summary log line, before and after.
  - A test asserts that `allCompletedPicks` ignores an ESPN draft.
- **F2 (blocker): a stored credential is used only to refresh an existing league, or for a re-add by a caller who can already see that league (`membership.canSee`).** Anyone else's add without cookies is treated as public and gets `ESPN_PRIVATE_NO_ACCESS`, with no league data in the body. An IT covers the stranger case.
- **F3 (blocker): the claim is part of the add request.**
  - `POST /api/setup/espn/league` takes `claimRosterId`.
  - If it's absent, the first response returns the team list and **no rows are written**. The UI then re-posts with the chosen team.
  - So the adder always leaves with a `manager_link`, which is their membership path. There's no separate ungated claim step.
  - `POST …/claim` stays, for moving your claim later. It requires `canSee`.
  - 409 `ALREADY_CLAIMED` if the ESPN member is linked to anyone else.
  - **A link is per ESPN member, not per league**, so it reaches every stored ESPN league that member plays in. That's correct, because that member *is* the user (decided in-session; Allan can overturn).
  - A claim never moves silently: re-claiming a different team in the same league replaces only that league's member link.
- **F4 (blocker): backend platform guard, not just frontend gating.**
  - `LiveDraftPoller.track` returns "not followed" for a non-Sleeper draft without ticking.
  - These routes return 422 `PLATFORM_NOT_SUPPORTED` for a non-Sleeper league or draft:
    - every `/api/drafts/{id}/*`, `/api/mocks/from-draft`, `/api/sims`;
    - `/api/setup/league*`, `/api/setup/league-history*`, `/api/ingest/*`.
  - `PowerRankingService.sportState` gets a per-league variant: Sleeper delegates as today, ESPN reads `settings_json.leg`. All four `LeagueHistoryController` callers switch to it.
  - tasks.md re-derives the full list from `grep -rn "sleeper\.\w\+("`.
  - A test walks every mapping that takes a league or draft key, with an ESPN fixture and a `SleeperClient` mock that fails on any call.
- **F5 (blocker): one per-league caller resolver.**
  - `LeagueMembership.callerManagerIn(leagueId, sleeperUserId)` returns the header's manager if it has a roster in that league, otherwise the linked manager that does.
  - All 14 `idsBySleeperUserId().get(header)` sites switch to it. This is identical for Sleeper, because the linked set is empty.
  - `MANAGER_LEAGUE_IDS_CTE` (by `manager.id`) is **not** expanded, so career, head-to-head and manager history stay per-platform. That matches the spec's "no cross-platform merging".
- **F6 (blocker): the migration is `V35__espn_platform.sql`.**
  - Re-check every worktree's migration directory right before committing.
  - Measured history on 5433: 31, then 34 "trade assets" (rank 32), then 33 "league bracket match" (rank 33, applied out of order). There's no V32.
  - The scratch DB `draftsim035` inherits those rows. V35 validates as long as 33 and 34 resolve, so the scratch build runs with `spring.flyway.ignore-migration-patterns=*:missing`, scratch only. Prod's merge order is recorded in HANDOFF.
  - R17 is corrected.
- **F7: data-model gets a per-table mapping section** covering:
  - `league_member` (team names, commissioner = ESPN league manager flag);
  - `roster_season` (record from `record.overall`, `points_possible` **null**, `final_placement` champion-only and only when complete);
  - `league_week_fetch` POINTS rows with `final`;
  - points written only for decided periods, with future pairings in `league_matchup` only;
  - `starters` in slot order, null when unknown;
  - `matchup_id` shared per pair, null for a bye;
  - a new `roster_season.reserve text[]` (V35) for IR;
  - NBA transaction week = matchup period.
- **F8: settings are derived, never defaulted to the plain rule.**
  - `playoff_seed_type`, `playoff_round_type`, `divisions` and `waiver_type` are written from measured ESPN settings.
  - Where ESPN's rule isn't one the app models, a sentinel `-1` is written, so `modelable()` is false and playoff odds say so. Nothing is omitted.
- **F9: for NFL, an ESPN week is the NFL week.**
  - Store `scoringPeriodId` when each matchup period is exactly one scoring period.
  - If any period spans more than one, analysis is marked unavailable (`MULTI_WEEK_PERIODS`).
  - NBA keeps matchup period as its week, and that's stated in the UI copy.
  - Measured on the real league first.
- **F10: which columns may hold `espn:` keys.**
  - Allowed: `roster_season.players` / `reserve`, `roster_week_points.players_points` / `starters`, and `league_transaction.adds` / `drops`.
  - `PlayerGameIngestService.rosteredPlayers` filters the prefix.
  - The roster, lineup and transaction readers on in-scope pages render `platform_player_link.external_name` instead of dropping the key. One of them is tested.
- **F11: measure against the real matcher input.**
  - R3 is re-run against the app's `player` table, for the current season and one history season.
  - `buildStrict` also refuses the name-only fallback when the ESPN position is known but unmodelled (IDP, HC, TQB → `ESPN_ONLY_POSITION`).
  - Sleeper's `espn_id` is a **tie-breaker only** among same-name candidates; both must agree.
  - Manual fixes go in `platform_player_link` as `outcome = 'MANUAL'`, not in `player_alias`, which nothing reads and which has no sport column.
- **F12: there is no league scheduler, so none gets built.**
  - FR-010, SC-006 and R15 are reworded to "on visit, same staleness rule as Sleeper" (spec amended).
  - The ESPN branch sets `loaded_complete` when ESPN reports the season complete and a refresh after that succeeds.
  - Transaction fetches are bounded by `league_week_fetch` TRANSACTIONS rows. The NBA transaction view is measured before deciding per-day vs. one bulk call.
- **F13: Home is gated per block, not as a page.**
  - Blocks: weekly-report matchup, superlatives, spotlight, draft grades, next matchup. Each one that's unavailable hides with a one-line FR-008 note.
  - **ESPN matchups are shown in the analysis/history score grid**, which is in scope (decided in-session; Allan can overturn).
  - ESPN managers are filtered out of `/api/managers` and mock seating. F4's guard covers `/drafts/*`.
  - Manager history and versus pages work, per platform.
- **F14: credential hygiene.**
  - `EspnCredential.toString()` returns `"[redacted]"`, with a test.
  - ESPN exceptions carry only codes and field names.
  - `EspnClient` uses `Redirect.NEVER` and refuses any host other than the configured one.
  - `last_failure` stores only the code.
  - The leak check runs at `LOG_LEVEL=DEBUG` and also greps `league_refresh`.
  - The setup panel says plainly that these cookies are an **ESPN login**, not a league-only key.
  - It's stated that a header impersonator can replace or delete stored cookies. That's denial of service, not a read, and it's an accepted residual of the unverified-identity model.
- **F15:** add and refresh share one single-flight key (the chain's oldest-season key), and the write order is fixed: league → managers → members → rosters → weeks → transactions.
- **F16:** `ppr` is derived from `scoring_json.rec`. For an ESPN league with `scoring_untranslated`, the preseason power baseline is marked unavailable rather than reading as standard scoring.
- **F17:** `MANUAL` outcome added (see F11).
- **F18:** `roster_season.manager_id` = `primaryOwner`. A co-owner can claim the same team: a link for each member is allowed, since they're different ESPN members.
- **F19:** `draft.reversal_round` = 0, with a comment "N/A: ESPN drafts are never simulated (F4)".
- **F20:** `league_credential.owner_manager_id … on delete cascade`.
- **F21:** R5 table line refs corrected via F4.

**Residual risk after the code review (S4, 2026-10-14; decided in-session, Allan can overturn).** A link is per ESPN member (F3), and identity is an unverified `X-Sleeper-User` header. So claiming a team in one league reaches every other stored ESPN league that member plays in, including private ones whose cookies someone else stored. ESPN member GUIDs are public for any public league the member is in, so anyone who adds a public league can claim an unclaimed member and then see that member's other stored leagues. This is inside the declared "scoping, not security" model, but R13/FR-009's rationale ("an impersonator can only refresh a league that is already visible to them") understates it: visibility of a private ESPN league is obtainable by anyone who knows one of its members' GUIDs. What the fix pass closed: a header shaped like another platform's key (`espn:{...}`, or containing `:`, `{` or `}`) is treated as no identity everywhere (`LeagueMembership.isAnonymous`), so a GUID can no longer be sent as the header directly. What it did not close: scoping a link to the leagues it was claimed through, which needs a design change and a migration. Real auth is the fix for both.

## Amendment to the spec (shown, not hidden)

**FR-009 said credentials are stored "one set per user." The plan stores them per league instead (research R13).**

The app's user identity is an unverified header (`LeagueMembership` javadoc: "This is scoping, not security"). A per-user credential that could add *any* league would let anyone who sends Allan's Sleeper id read any private ESPN league Allan's cookies can see. Stored per league, the only thing an impersonator can do with the cookies is refresh a league that's already visible to them.

The user-facing difference: adding a *second* private ESPN league means pasting the cookies again. The spec's FR-009 carries an "amended by plan" note.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5, virtual threads); TypeScript 5 / React 18 (Vite)

**Primary Dependencies**: existing `java.net.http` client pattern (`SleeperClient`), Spring `JdbcClient` / `TransactionTemplate`, `javax.crypto` AES/GCM (JDK, no new dependency). No new npm packages.

**Storage**: Postgres. One migration, **V34** (R17): new columns on `league`, `manager`, `draft` and `league_transaction`; new tables `manager_link`, `platform_player_link` and `league_credential`.

**Testing**: JUnit 5 + Testcontainers-style ITs against local Postgres 5433 (note memory: the suite skips ITs silently when Postgres is down, so check the skip count). Vitest for web.

- **Fixtures**: ESPN fixture JSON is recorded from the **real** league once it's available. Until then, tests use hand-built minimal payloads clearly labelled as synthetic.

**Target Platform**: Railway (two services, deployed independently), local Windows dev.

**Project Type**: web application (backend/ + web/).

**Performance Goals**:
- First ESPN add of one season with history: under 60 s end to end (SC-001 allows 2 minutes including the user's own steps).
- Refresh of the current season: under 10 s.

**Constraints**:
- FR-013: zero behaviour change for Sleeper leagues.
- Credentials never leave the server in any form.
- Every ESPN shape assumption is confirmed against the real league before it's relied on (R1, R9).

**Scale/Scope**: one real ESPN league to start, roughly 10–14 teams, up to roughly 10 seasons of history.

## Constitution Check

| Rule | How this plan complies |
|------|------------------------|
| Migrations append-only | One new `V34__espn_platform.sql`. Nothing edits V1–V31. Collision with 033 (V32) and 034 (V33) is handled per R17: renumber at merge if needed, and verify on a scratch DB copy. |
| `api.ts` mirrors Java records | `DraftSummary` gains `platform`. A new `LeagueAvailability` and the ESPN setup request/response types are added in the same change as their Java records. The T-list pairs each record with its TS type. |
| Never retune a constant to match a guess | There are no model constants in this feature. The ESPN statId→Sleeper scoring table is a *translation*, and each entry is confirmed against the real league's scoring page, not tuned (R7). |
| `Map.of` throws on null | ESPN team `abbrev`, member `firstName`, a bye's `away` and a player's `proTeamId` can each be absent. Every response map in the new code is built mutably (`LinkedHashMap`). A code-review checklist item covers it. |
| Ask before committing | Nothing is committed without Allan's go-ahead. The worktree isolates this branch from peers. |
| Planning doc ≠ verified spec | `claude/adp-sources.md` §6's ESPN description is treated as unverified (R9), and so is the espn-api fixture data. |

**Gate: PASS**, with the FR-009 amendment shown above.

## Design

### Backend

**`ingest/espn/` (new package)**
- `EspnClient`: GETs for league views, players, proTeams and transactions. Cookies are a per-call argument. The client maps 401 `AUTH_LEAGUE_NOT_VISIBLE` → `EspnAccessDenied`, 404 → `EspnNotFound`, and anything else non-200 → `EspnHttpError(code)`. Exception messages never include cookies or full request headers.
- `EspnLeagueRef`: parses a pasted link (`fantasy.espn.com/{football|basketball}/league?leagueId=…&seasonId=…`, `…/team?leagueId=…`) or a bare id plus sport plus season. Malformed input is rejected before any network call.
- `EspnTranslator`: pure functions from the ESPN payload to app rows:
  - settings → `settings_json` in Sleeper keys (`leg`, `playoff_teams`, `playoff_week_start`, …);
  - `lineupSlotCounts` → `roster_positions`;
  - `scoringItems` → `scoring_json` or `scoring_untranslated`;
  - `scoringType` → accept/refuse;
  - teams → roster rows;
  - schedule → `league_matchup` + `roster_week_points`;
  - draft → `draft` + `draft_pick`;
  - transactions → `league_transaction` with the V19 type set.

  Any required field that's missing throws `EspnShapeChanged(field)`.
- `EspnPlayerResolver`: `platform_player_link` lookup first, then the ambiguity-refusing `PlayerMatcher`. Unmatched and ambiguous players are recorded with a reason and **never** given a Sleeper id.
- `EspnProTeams`: a hand table from `proTeamId` to Sleeper abbreviation, per sport (R4).
- `EspnLeagueIngestService`:
  - `add(ref, cookies?, claimingUser)`: fetch every season (current, then `previousSeasons`), translate, write everything in one transaction, then store the credential encrypted against the league.
  - `refreshCurrent(league)`: same as `add`, for the current season only.

**Shared seams (changed)**
- `engine/LeagueRosterSource` (new): `rosters(LeagueRow)` and `currentWeek(LeagueRow)`. On Sleeper it delegates to `SleeperClient`. On ESPN it reads `roster_season.players` plus owners, and `settings_json.leg`. The five R5 call sites switch to it.
- `store/LeagueMembership`: the `me` CTE is expanded through `manager_link`, in one place.
- `refresh/LeagueRefreshService.trigger`: dispatches on `league.platform`. The scheduled refresh includes ESPN leagues.
- `ingest/PlayerMatcher`: a new `buildStrict(...)` that refuses ambiguous name+position keys. The existing `build` is unchanged (FR-013).
- `store/DraftRepository.DraftSummary`: adds a `platform` field.

**Credentials**
- `security/CredentialCipher`: AES-256-GCM using the `BK_CREDENTIAL_KEY` env var. If the key is absent, `isConfigured()` is false and private-league setup refuses with `CREDENTIALS_NOT_CONFIGURED`.
- `store/LeagueCredentialRepository`: put, get-for-ingest and delete. There is no read path that returns cookies to a controller.

**API** (see [contracts/espn-setup.md](contracts/espn-setup.md))
- `POST /api/setup/espn/league`: add or refresh. The request carries the ref and optional cookies. The response gives the league key, seasons, teams to claim, and unmatched/refused counts.
- `POST /api/setup/espn/league/{key}/claim`: claim a team.
- `PUT|DELETE /api/setup/espn/league/{key}/credential`: replace or delete stored cookies.
- `GET /api/leagues/{key}/availability`: the platform plus a map of page → reason for every page that can't be produced (FR-008). The frontend reads it once per league route.

### Frontend
- **Setup**: an "Add an ESPN league" panel in the existing setup flow (`SignIn.tsx` stage list), with link input, sport/season inference shown back to the user, an optional cookie disclosure with plain-language instructions and a warning, then the team-claim list.
- **Platform badge**: shown wherever a league name is: picker card, rail switcher and page header (FR-003). Driven by `DraftSummary.platform`.
- **`useLeagueAvailability(key)`** plus a `<NotAvailableForPlatform page reason>` block: every out-of-scope or untranslatable page renders the block instead of its body. That covers draft board, live draft, mock seeding, draft grades, stats/calculator/spotlight/trends (scoring-dependent), schedule grid, superlatives, weekly report, expected wins and forecast. `PlayerPage` renders an unmatched ESPN player by name only.
- **Picker**: an ESPN draft card links to the league home, not the draft room.

## Project Structure

### Documentation (this feature)

```text
specs/035-espn-yahoo-ingest/
├── spec.md
├── plan.md              # this file
├── research.md          # R1–R17
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── espn-setup.md
│   └── availability.md
├── checklists/requirements.md
├── plan-review.md       # adversarial review (next stage)
└── tasks.md             # /speckit-tasks
```

### Source Code

```text
backend/src/main/java/com/ballknowers/draftsim/
├── ingest/espn/        EspnClient, EspnLeagueRef, EspnTranslator, EspnPlayerResolver,
│                       EspnProTeams, EspnScoringMap, EspnLeagueIngestService, Espn*Exception
├── engine/             LeagueRosterSource (new); LeagueAnalysisService, PowerRankingService,
│                       MemberRankingService, SeasonStartService (switch to the seam)
├── api/                EspnSetupController (new), LeagueAvailabilityController (new),
│                       LeagueHistoryController (seam)
├── security/           CredentialCipher (new)
├── store/              LeagueMembership (me ∪ linked), LeagueCredentialRepository,
│                       ManagerLinkRepository, PlatformPlayerLinkRepository (new),
│                       DraftRepository (platform), LeagueRepository (platform)
└── refresh/            LeagueRefreshService (dispatch)
backend/src/main/resources/db/migration/V34__espn_platform.sql
backend/src/test/java/...  EspnTranslatorTest, EspnLeagueRefTest, PlayerMatcherStrictTest,
                           CredentialCipherTest, EspnLeagueIngestIT, LeagueRosterSourceIT,
                           LeagueMembershipLinkIT, SleeperUnchangedIT (FR-013 parity)
web/src/
├── api.ts              DraftSummary.platform, LeagueAvailability, Espn setup types
├── components/         PlatformBadge, NotAvailableForPlatform, EspnLeagueSetup
├── useLeagueAvailability.ts
└── pages/              SignIn (setup panel), DraftPicker (badge + link), out-of-scope pages (gate)
```

**Structure Decision**: the existing backend/ + web/ layout. All ESPN-specific code sits in one new `ingest/espn` package, so Yahoo can later be a sibling package that targets the same seams.

## Verification bar (from AGENTS.md)

These are the live-verification stage's acceptance criteria. A green suite alone is not "verified."

1. **SC-005 parity**: dump the payloads of league analysis, history, power rankings and standings for every Sleeper league on the scratch DB **before** and **after** the change, then diff. They must be byte-identical apart from the new `platform` field on `DraftSummary`.
2. **The real ESPN league** is added through the real UI on a running local stack, and SC-002 is compared against ESPN's site. If Allan's league is private, he pastes the cookies himself; the agent never handles them.
3. **Unmatched list** is reviewed by eye, and 20 matches are hand-checked (SC-003).
4. **Every in-scope page** is opened in the browser. Every out-of-scope page shows the platform message, not an empty chart (SC-004).
5. **Credential checks**: grep the server log and every response body captured during verification for the cookie values. Expect zero hits.

## Complexity Tracking

No constitution violations to justify.
