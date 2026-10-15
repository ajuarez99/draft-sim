---
description: "Task list for spec 035: ESPN league ingest"
---

# Tasks: ESPN League Ingest

**Input**: `specs/035-espn-yahoo-ingest/`: spec.md, plan.md (**its "Amended after review" section overrides everything else**), research.md, data-model.md, contracts/, plan-review.md (F1–F21).

**Tests**: required. The plan review named specific tests (F1, F2, F4, F10, F14), and AGENTS.md's bar needs preference and parity tests, not only structural ones.

**Paths**:
- `B` = `backend/src/main/java/com/ballknowers/draftsim`
- `T` = `backend/src/test/java/com/ballknowers/draftsim`
- `W` = `web/src`
- `M` = `backend/src/main/resources/db/migration`

**ESPN shapes are unverified until T-verify (Phase 7).** Build against the shapes in research R9. Every place that reads an ESPN field goes through `EspnTranslator`, which throws `EspnShapeChanged(fieldPath)` when a required field is missing, so a wrong guess fails loudly. Test fixtures under `T/ingest/espn/fixtures/` are **hand-built and labelled `"_synthetic": true`** until real ones are recorded.

## Phase 1: Setup

- [x] T001 Re-list every worktree's `M` directory (`ls ../*/backend/src/main/resources/db/migration .claude/worktrees/*/backend/...` from the main checkout). Confirm `V35` is still free. If it isn't, use the next free number and note it in plan.md's F6 bullet.
- [x] T002 Add `espn.base-url: https://lm-api-reads.fantasy.espn.com/apis/v3` and `credentials.key: ${BK_CREDENTIAL_KEY:}` to `backend/src/main/resources/application.yml`. Add `BK_CREDENTIAL_KEY` to DEPLOY.md's env var list with "32 random bytes, base64; absent = private ESPN leagues disabled".

## Phase 2: Foundational (blocks every story)

### Schema

- [x] T003 Write `M/V35__espn_platform.sql`, additive only:
  - **Platform columns**: `league.platform text not null default 'SLEEPER' check (platform in ('SLEEPER','ESPN'))`. Add the same column and check to `manager`, `draft` and `league_transaction`.
  - **League columns**: `league.platform_league_id text null`, `league.scoring_untranslated text[] null`, `league.unmodelled_slots text[] null`.
  - **Roster column**: `roster_season.reserve text[] null`.
  - **`manager_link`**:
    - `manager_id bigint not null references manager(id) on delete cascade`
    - `linked_manager_id bigint not null references manager(id) on delete cascade`
    - `league_id bigint not null references league(id) on delete cascade`
    - `created_at timestamptz not null default now()`
    - `primary key (manager_id, linked_manager_id)`, `unique (linked_manager_id)`
  - **`platform_player_link`**:
    - `platform text not null`, `sport text not null`, `external_id text not null`
    - `player_id bigint null references player(id)`
    - `outcome text not null check (outcome in ('MATCHED','AMBIGUOUS','UNMATCHED','ESPN_ONLY_POSITION','MANUAL'))`
    - `external_name text not null`, `external_position text null`, `external_team text null`
    - `resolved_at timestamptz not null`
    - `primary key (platform, sport, external_id)`
  - **`league_credential`**:
    - `platform text not null`, `sport text not null`, `platform_league_id text not null`
    - `owner_manager_id bigint not null references manager(id) on delete cascade`
    - `ciphertext bytea not null`, `nonce bytea not null`, `key_version int not null default 1`
    - `updated_at timestamptz not null`, `last_worked_at timestamptz null`
    - `primary key (platform, sport, platform_league_id)`
  - **Comments**: cover the `sleeper_*` column reuse convention (R14) and the `espn:` key rule (F10).
- [x] T004 Extend `B/store/LeagueRepository.java`:
  - `LeagueRow` gains `Platform platform` (new `B/domain/Platform.java` enum `SLEEPER, ESPN`), `platformLeagueId`, `scoringUntranslated` and `unmodelledSlots`.
  - `upsert` gains an overload that writes them. The existing `upsert` signature is unchanged and writes the default `SLEEPER`.
  - `ROW_COLUMNS` selects them.
- [x] T005 [P] Add `platform` to `DraftRepository.DraftSummary` and its select/mapper in `B/store/DraftRepository.java`. Mirror it in `W/api.ts` `DraftSummary` as `platform: 'SLEEPER' | 'ESPN'`, with a comment (constitution: same change).
- [x] T006 [P] Create `B/store/ManagerLinkRepository.java`: `link(managerId, linkedManagerId, leagueId)` (throws `AlreadyClaimed` on a unique violation of `linked_manager_id` held by a different `manager_id`), `unlinkInLeague(managerId, leagueId)` and `linkedOf(managerId)`.
- [x] T007 [P] Create `B/store/PlatformPlayerLinkRepository.java`: `find(platform, sport, externalIds)` → map, `upsert(row)` (never downgrades `MATCHED`/`MANUAL` to another outcome; F17), and `externalNames(platform, sport, ids)`.

### Credentials (F14)

- [x] T008 [P] Create `B/security/CredentialCipher.java`:
  - AES-256-GCM, 12-byte `SecureRandom` nonce, key from `credentials.key` (base64, must decode to 32 bytes or startup logs one WARN and `isConfigured()` = false).
  - `encrypt(byte[]) → Sealed(nonce, ciphertext)` and `decrypt(Sealed)`.
  - No plaintext fallback.
- [x] T009 [P] Create `B/ingest/espn/EspnCredential.java`: a final class (not a record) holding `espnS2` and `swid`. `toString()` returns `"EspnCredential[redacted]"`. There are no getters other than package-private ones used by `EspnClient`; no Jackson serialization (`@JsonIgnoreType`).
- [x] T010 Create `B/store/LeagueCredentialRepository.java`:
  - `put(platform, sport, platformLeagueId, ownerManagerId, EspnCredential)` encrypts through `CredentialCipher`.
  - `forIngest(platform, sport, platformLeagueId) → Optional<EspnCredential>`.
  - `owner(...) → Optional<Long>`, `delete(...)`, `markWorked(...)`.
  - No other method selects `ciphertext`.
- [x] T011 [P] Write `T/security/CredentialCipherTest.java`: round-trip; two encryptions of the same plaintext differ; the ciphertext bytes do not contain the plaintext; an unconfigured key → `isConfigured()` false and `encrypt` throws. Also `T/ingest/espn/EspnCredentialTest.java`: `toString()` and `String.valueOf` and Jackson serialization contain neither secret.

### ESPN client and translation primitives

- [x] T012 Create `B/ingest/espn/EspnClient.java`:
  - `java.net.http.HttpClient` with `Redirect.NEVER`.
  - Every URL is built from `espn.base-url`, and a request whose host differs from the configured host is refused.
  - Methods: `league(game, leagueId, season, Set<String> views, EspnCredential|null)`, `leagueHistory(game, leagueId, season, views, cred)` (pre-2018 form, R11), `transactions(game, leagueId, season, scoringPeriodId, cred)`, `proTeams(game, season)`.
  - Status mapping: 401 → `EspnAccessDenied`, 404 → `EspnNotFound`, other non-200 → `EspnHttpError(code)`.
  - Cookies are sent as `Cookie: espn_s2=…; SWID=…` only when the credential is non-null.
  - Exceptions carry codes and the game/league id only, never headers, cookies or a request object.
- [x] T013 [P] Create the exception types in `B/ingest/espn/`: `EspnAccessDenied`, `EspnNotFound`, `EspnHttpError(int)`, `EspnShapeChanged(String fieldPath)`, `EspnUnsupported(String code, String detail)`. All extend a sealed `EspnException` with a `code()` (`ESPN_ACCESS_DENIED`, `ESPN_NOT_FOUND`, `ESPN_HTTP_<n>`, `ESPN_SHAPE_CHANGED`, `UNSUPPORTED_SCORING`, …).
- [x] T014 [P] Create `B/ingest/espn/EspnLeagueRef.java`:
  - `parse(String link)`: accepts `fantasy.espn.com/{football|basketball}/(league|team|league/standings|…)?leagueId=N[&seasonId=Y][&teamId=K]`.
  - `of(String leagueId, Sport, int season)`.
  - Malformed input → `IllegalArgumentException("MALFORMED_REF")` with no echo of the input.
  - `key()` → `espn-{ffl|fba}-{id}-{season}`.
  - Write `T/ingest/espn/EspnLeagueRefTest.java` covering every accepted form, missing `seasonId` (defaults to the current season passed in by the caller, never a hard-coded year), and junk input.
- [x] T015 [P] Create `B/ingest/espn/EspnProTeams.java`: `proTeamId → Sleeper abbreviation` per sport, from research R4 (NFL: ESPN `WSH` → `WAS`, the rest equal; NBA: `Bkn→BKN, NY→NYK, GS→GSW, SA→SAS, Utah→UTA, Wsh→WAS, Phx→PHX, Cha→CHA, NO→NOP`, the others upper-cased). `0` → null. Write `T/ingest/espn/EspnProTeamsTest.java` asserting every id 1..34 (NFL) and 1..30 (NBA) present in the R4 measurement maps to a non-null abbreviation.
- [x] T016 Add `PlayerMatcher.buildStrict(Sport, List<Player>, Map<String,Long> manual)` and a `matchStrict(rawName, espnPosition, rawTeam, Long sleeperEspnIdHint)` returning `Outcome{MATCHED(id) | AMBIGUOUS | UNMATCHED | ESPN_ONLY_POSITION}` in `B/ingest/PlayerMatcher.java`:
  - A name+position key held by two or more players is AMBIGUOUS, never the first id.
  - The name-only fallback is used **only** when the ESPN position is null; a known-but-unmodelled ESPN position (IDP, HC, TQB, P) returns `ESPN_ONLY_POSITION` (F11).
  - Sleeper's `espn_id` is used only to break a tie among same-name candidates, and only when exactly one candidate's `espn_id` equals the hint.
  - D/ST goes by team.
  - The existing `build`/`match` are unchanged (FR-013).
- [x] T017 [P] Write `T/ingest/PlayerMatcherStrictTest.java`:
  - two `David Johnson|RB` → AMBIGUOUS;
  - the same pair with a hint naming one → MATCHED that one;
  - a hint naming a different-name player (the Conklin→Izzo case) → ignored;
  - ESPN CB "Lamar Jackson" with only QB Lamar Jackson in the table → ESPN_ONLY_POSITION;
  - D/ST by team;
  - `build()` still returns the first id for a duplicate (it pins today's behaviour).

### Platform guard and caller resolution (F4, F5)

- [x] T018 Create `B/api/PlatformGuard.java`: `requireSleeperLeague(String leagueKey)` and `requireSleeperDraft(String draftKey)`. Both throw `PlatformNotSupported` → 422 `{error:"PLATFORM_NOT_SUPPORTED", message}` via `B/api/ErrorHandler.java` (add the handler; the message is fixed text, not `e.getMessage()` of anything ESPN).
- [x] T019 Apply `PlatformGuard` to every route that would call Sleeper or run the draft engine with a non-Sleeper key:
  - every `/api/drafts/{id}/*` mapping in `B/api/LeagueController.java` (pool, seats, reversal-round, board, track, live-stream, picks);
  - `B/api/MockDraftController.java` from-draft;
  - `B/api/SimulationController.java`;
  - `B/api/MemberSetupController.java` `league` and `league-history`;
  - every `B/api/IngestController.java` route taking a league id;
  - `B/api/DraftGradesController.java`.

  Also make `B/ingest/LiveDraftPoller.java` `track` return a not-followed result for a non-Sleeper draft **before** any tick. Then re-run `grep -rn "sleeper\.\w\+(" B` and list every remaining request-reachable call in a comment block at the top of `PlatformGuard.java`, each with "guarded by …" or "routed through LeagueRosterSource".
- [x] T020 Add `LeagueMembership.callerManagerIn(long leagueId, String sleeperUserId) → Optional<Long>` in `B/store/LeagueMembership.java`: the header's manager if it owns a `roster_season` row in that league, otherwise the `manager_link`-linked manager that does, otherwise empty. Expand the `me` CTE used by `MINE_AND_CHAIN` to `me ∪ linked` (one place). Do **not** change `MANAGER_LEAGUE_IDS_CTE`.
- [x] T021 Replace every `managers.idsBySleeperUserId().get(<header>)` with `membership.callerManagerIn(leagueId, header)` (F5) in:
  - `B/api/LeagueHistoryController.java` (3 sites)
  - `B/api/SuperlativesController.java`
  - `B/engine/LeagueAnalysisService.java`
  - `B/engine/NextMatchupService.java`
  - `B/engine/OwnerSlot.java` (2)
  - `B/engine/PlayerStatsService.java` (2)
  - `B/engine/PlayerTrendsService.java`
  - `B/engine/SpotlightOwnership.java`
  - `B/engine/WeeklyReportService.java`

  Re-grep afterwards and expect zero remaining.
- [x] T022 Create `B/engine/LeagueRosterSource.java`:
  - `rosters(LeagueRow)`: for SLEEPER, delegates to `SleeperClient.rosters`. For ESPN, builds Sleeper-shaped maps `{roster_id, owner_id (the manager's sleeper_user_id), players, reserve}` from `roster_season` (`players`, the new `reserve`). Build maps mutably.
  - `currentWeek(LeagueRow)`: for SLEEPER, delegates to the existing `PowerRankingService.sportState` week. For ESPN, reads `LeagueRepository.currentLeg`.

  Switch these to it:
  - `B/engine/LeagueAnalysisService.java:872`
  - `B/engine/PowerRankingService.java` (`:147`, `:295`, and a per-league `sportState(league)` used by all four `LeagueHistoryController` callers)
  - `B/engine/MemberRankingService.java` (`:105`, `:209`)
  - `B/api/LeagueHistoryController.java:796`
  - `B/engine/SeasonStartService.java:201`
- [x] T023 [P] F1:
  - Add `and l.platform = 'SLEEPER'` to `DraftRepository.allCompletedPicks` and to `BoardService`'s `adp_at_time` backfill query (`B/ingest/BoardService.java` ~`:299-322`).
  - Exclude `manager.platform <> 'SLEEPER'` from `ProfileService.fit` inputs (`B/profile/ProfileService.java`), `/api/managers` (`B/api/ManagerController.java`) and mock seating candidates.
  - Write `T/store/DraftRepositoryPlatformIT.java`: insert one ESPN league + complete draft + picks; `allCompletedPicks` doesn't return them.
- [x] T024 [P] F10: in `B/ingest/PlayerGameIngestService.java` `rosteredPlayers`, skip keys starting with `espn:`. Add a unit/IT assertion.

**Checkpoint**: the Sleeper suite is still green with the same skip count as on `main` (record both numbers).

## Phase 3: User Story 1 – Add my ESPN league (P1) 🎯 MVP

**Goal**: paste a link, claim a team, and the league shows in the picker with an ESPN badge (FR-001…006, FR-009).
**Independent test**: quickstart steps 2 and 5.

- [x] T025 [P] [US1] Create `B/ingest/espn/EspnScoringMap.java`:
  - ESPN `statId → Sleeper scoring key`, per sport, **empty of confirmed entries**. Each entry carries a `confirmed` boolean, and all are `false` until T-verify.
  - `translate(scoringItems) → Result{Map<String,Double> scoring, List<String> untranslated}`. Any item without a **confirmed** mapping goes to `untranslated`.
  - If `untranslated` is non-empty, `scoring` is `{}` (R7).
- [x] T026 [US1] Create `B/ingest/espn/EspnTranslator.java`: pure functions over `Map<String,Object>` payloads → an `EspnSeason` record holding every row to write. Each mapping is per data-model.md plus the plan amendments. A missing required field → `EspnShapeChanged(path)`.
  1. **Accept/refuse**: `settings.scoringSettings.scoringType` in {`H2H_POINTS`,`TOTAL_POINTS`}, else `EspnUnsupported("UNSUPPORTED_SCORING", value)`.
  2. **League row**:
     - key, `platform=ESPN`, `platform_league_id`, `name` (`settings.name`), `total_rosters` (`settings.size`), `previous_league_id` (prior season's key if that season is listed in `status.previousSeasons`, else null), `status` (pre_draft/drafting/in_season/complete).
     - `roster_positions` from `rosterSettings.lineupSlotCounts` via an ESPN-slot→Sleeper-slot table per sport: NFL `0 QB, 2 RB, 4 WR, 6 TE, 16 DEF, 17 K, 23 FLEX, 7 SUPER_FLEX (OP), 20 BN, 21 IR`; `3 RB/WR`, `5 WR/TE`, IDP (8–15), `18 P`, `19 HC`, `1 TQB` → unmodelled. NBA `0 PG, 1 SG, 2 SF, 3 PF, 4 C, 5 G, 6 F, 11 UTIL, 12 BN, 13 IR`; `7–10` combo slots → unmodelled. The Sleeper-side names are taken from what real Sleeper leagues in the DB actually store in `league.roster_positions` (query `select distinct unnest(roster_positions), sport from league`), not from this list Unmodelled slots go to `unmodelled_slots`, and every mapping is flagged "confirm in T-verify".
     - `settings_json` per F8: `leg`, `playoff_teams`, `playoff_week_start`, `playoff_seed_type` (0 only when ESPN's seeding rule is the plain record ladder, else `-1`), `playoff_round_type` (from `playoffMatchupPeriodLength`: 1 → 0; else `-1`), `divisions` (real count), `waiver_type` (FAAB → 2, rolling/priority → 0, else `-1`), `waiver_budget`, `league_average_match` 0.
     - `scoring_json` and `scoring_untranslated` from T025.
  3. **Managers**: one per member, `sleeper_user_id = "espn:" + member.id`, `display_name` = `displayName`, `platform=ESPN`. **`league_member` rows**: `team_name` = `name` or `location + " " + nickname`, else shape error. `is_commissioner` from the member's league-manager flag (confirm the field). Owner = `primaryOwner` (F18).
  4. **`roster_season`**: `roster_id` = team id; `wins/losses/ties/points_for/points_against` from `record.overall`; `points_possible` **null**; `final_placement` set only for the champion and only when status is complete; `players` = non-IR entries; `reserve` = IR entries. Both use the player key from T027.
  5. **Weeks**: matchup period → week. For NFL, week = the period's single `scoringPeriodId`; if any period maps to more than one scoring period, the season gets `unavailable.analysis = MULTI_WEEK_PERIODS` (F9). For NBA, week = `matchupPeriodId`.
     - For **decided** periods only (`winner` not `UNDECIDED`; confirm the value): `roster_week_points` with `starters_points` = ESPN `totalPoints`; `players_points` and `starters` (slot order, null when unknown) only when per-player data is present; `league_week_fetch` POINTS `final=true`.
     - For every period, decided or not: `league_matchup` with a shared `matchup_id` per pair (ESPN schedule `id`), null for a bye.
  6. **Draft**: one row, `sleeper_draft_id = key + "-draft"`, `platform=ESPN`, `status` from `draftDetail`, `rounds` = max `roundId`, `teams` = size, `draft_type` per data-model, `reversal_round` 0 (comment "N/A: never simulated, F19"), `slot_to_manager` from `pickOrder`, `start_time` **null**. Picks from `draftDetail.picks`, with `adp_at_time` null.
  7. **Transactions** (T036, US2) are left out of this task.

  Every output map is built mutably (constitution `Map.of` rule).
- [x] T027 [US1] Create `B/ingest/espn/EspnPlayerResolver.java`:
  - Given the season's ESPN player ids with name/position/proTeam, look up `platform_player_link` first, then `PlayerMatcher.buildStrict` over `PlayerRepository` rows of that sport with manual links as aliases.
  - Write link rows; return `externalId → key` where key = the Sleeper id for MATCHED/MANUAL, else `"espn:" + externalId`.
  - Counts: matched, unmatched, ambiguous, espnOnly.
  - ESPN NFL `defaultPositionId` → Sleeper position: `1 QB, 2 RB, 3 WR, 4 TE, 5 K, 16 DEF`; 7–15 IDP/P/HC/TQB → unmodelled. NBA `1 PG, 2 SG, 3 SF, 4 PF, 5 C`.
- [x] T028 [US1] Create `B/ingest/espn/EspnLeagueIngestService.java`:
  - `add(EspnLeagueRef ref, EspnCredential cred|null, String callerSleeperUserId, Integer claimRosterId) → AddResult`.
  - **Phase 1** (no writes): fetch the current season with views `mSettings, mTeam, mRoster, mMatchup, mDraftDetail` (plus `mStandings` if needed); translate; resolve players; then walk `status.previousSeasons` newest first (pre-2018 via `leagueHistory`; a failure → `missingSeasons` entry, continue).
  - If `claimRosterId` is null, return the team list and **write nothing** (F3).
  - **Phase 2**: one `TransactionTemplate` in fixed order: league rows → managers → `league_member` → `roster_season` → `league_matchup` / `roster_week_points` / `league_week_fetch` → draft + picks → transactions → `manager_link` (caller's Sleeper manager → claimed team's ESPN manager) → credential (if supplied). Everything is an upsert (FR-006).
  - Run under `LeagueRefreshService`'s single-flight key = the oldest season's key (F15).
- [x] T029 [US1] Credential use rule (F2) in `EspnLeagueIngestService.add`: a **stored** credential is used only when `membership.canSee(caller, existingLeague)` for an already-stored season of that ESPN league. Otherwise only a credential in the request is used. With none, read as public → `EspnAccessDenied` → `ESPN_PRIVATE_NO_ACCESS`. If a credential was supplied but `CredentialCipher.isConfigured()` is false, return `CREDENTIALS_NOT_CONFIGURED` **before** any ESPN call.
- [x] T030 [US1] Create `B/api/EspnSetupController.java` per `contracts/espn-setup.md`:
  - `POST /api/setup/espn/league` (request record holds `EspnCredential`, never a `String` pair).
  - `POST …/{key}/claim` (requires `canSee`; 409 `ALREADY_CLAIMED`).
  - `PUT|DELETE …/{key}/credential` (owner-only via `LeagueCredentialRepository.owner`; else 403 `NOT_OWNER`; PUT test-reads before storing).
  - Map `EspnException.code()` → the status table in the contract. Bodies hold the code and fixed human text; never `getMessage()` of a wrapped exception.
  - Response maps are mutable.
- [x] T031 [US1] Mirror the request/response types in `W/api.ts` (`EspnAddRequest`, `EspnAddResult`, `EspnTeamChoice`, `EspnSeasonRow`, `EspnPlayerCounts`) field-for-field with the Java records. Add `addEspnLeague`, `claimEspnTeam`, `putEspnCredential` and `deleteEspnCredential` fetchers.
- [x] T032 [P] [US1] Create `W/components/PlatformBadge.tsx` (small tinted pill "ESPN"; nothing for Sleeper). Render it next to the league name in `W/pages/DraftPicker.tsx` cards, the rail league switcher (`W/components/AppShell.tsx` / `railLeague` consumer) and league page headers.
- [x] T033 [US1] Create `W/components/EspnLeagueSetup.tsx`, mounted in `W/pages/SignIn.tsx`'s setup flow as "Add an ESPN league":
  1. Link input.
  2. Submit (no claim) → show the inferred sport, season and name, and the team list.
  3. The user picks "this is my team" → re-submit with `claimRosterId`.
  4. On `ESPN_PRIVATE_NO_ACCESS`, reveal the cookie fields: two password-type inputs, `autocomplete="off"`, and help text saying where to find `espn_s2`/`SWID` and, plainly, **"these are your ESPN login cookies, not a league-only key; Ball Knowers stores them encrypted and only uses them to read this league"** (F14).
  5. Show every contract error code as human text.
  6. On success, invalidate the rail league cache (`invalidateRailLeagues`) and link to the league home.
- [x] T034 [US1] In `W/pages/DraftPicker.tsx`, an ESPN draft card links to `/leagues/{sleeperLeagueId}`, never `/drafts/{id}/live` or `/board`.

### Tests for US1

- [x] T035 [P] [US1] `T/ingest/espn/EspnTranslatorTest.java` over synthetic fixtures:
  - a category league → UNSUPPORTED_SCORING;
  - missing team name → `EspnShapeChanged("teams[].name")`;
  - `points_possible` null;
  - `final_placement` champion-only when complete, none when in season;
  - undecided periods → no `roster_week_points`, but `league_matchup` present;
  - a bye → null `matchup_id`;
  - an unmodelled slot listed;
  - `playoff_seed_type` `-1` for a non-ladder rule (preference test: the plain ladder gives 0, anything else gives -1, never the reverse).
- [x] T036 [US1] `T/ingest/espn/EspnLeagueIngestIT.java` (Postgres; a stub `EspnClient` returning fixtures):
  - add → rows present with `platform=ESPN`;
  - add twice → row counts unchanged (FR-006);
  - add with a translator failure on season 2 → **zero** rows (FR-005);
  - add without `claimRosterId` → zero rows;
  - the stranger case (F2): user B adds Allan's stored private league without cookies → 403 `ESPN_PRIVATE_NO_ACCESS`, body has no league data, and the stored credential is never passed to the stub (assert on the stub);
  - a second user claiming the same team → 409.
- [x] T037 [US1] `T/api/EspnPlatformGuardIT.java` (F4): with an ESPN league + draft in the DB and a `SleeperClient` mock that fails on any call, call every `/api/drafts/{id}/*`, `/api/mocks/from-draft`, `/api/sims`, `/api/setup/league*` and `/api/ingest/*` route with ESPN keys → 422 `PLATFORM_NOT_SUPPORTED`, no Sleeper call. Then call every in-scope league route (`/api/leagues/{key}/history|power|analysis|roster-management|transactions|availability`) → 200 with no Sleeper call.
- [x] T038 [US1] `T/store/LeagueMembershipLinkIT.java`:
  - linked manager → `canSee` true, and `callerManagerIn` returns the ESPN manager in the ESPN league and the Sleeper manager in a Sleeper league;
  - with no link → empty;
  - a Sleeper-only user's league list is byte-identical before and after an unrelated ESPN league + link is inserted.

**Checkpoint**: US1 is demonstrable end to end against the stub; the real league follows in Phase 7.

## Phase 4: User Story 2 – ESPN league pages show real data (P1)

**Goal**: the in-scope pages render real data; everything else says "not available for ESPN leagues" (FR-007, FR-008, FR-012).
**Independent test**: quickstart steps 3–4.

- [x] T039 [US2] Transactions in `EspnTranslator` + ingest:
  - Fetch via `EspnClient.transactions`. NFL: one call per scoring period. NBA: measure first; until measured, one call per matchup period's **last** day, labelled unverified (F12).
  - Write only periods not already `league_week_fetch` TRANSACTIONS `final`.
  - Map types per data-model (lineup-only moves skipped); `sleeper_transaction_id = "espn:" + id`; `week` = matchup period (NBA) or NFL week; `faab_bid` = `bidAmount` only when the league is FAAB, else null; `adds`/`drops` = `{playerKey: rosterId}`.
- [x] T040 [US2] Create `B/api/LeagueAvailabilityController.java` + `B/engine/LeagueAvailability.java` per `contracts/availability.md`:
  - Sleeper → `unavailable: {}`.
  - ESPN → fixed out-of-scope keys (`draftRoom, draftGrades, schedule, superlatives, weeklyReport, expectedWins, forecast, recap, stats`), plus data-driven keys:
    - `stats` and the preseason power baseline when `scoring_untranslated` is non-empty (F16);
    - `lineups` when no week has `players_points`;
    - `analysis` when `MULTI_WEEK_PERIODS`;
    - `playoffOdds` when `modelable()` is false.
  - Include `unmatchedPlayers` and `lastRefresh` (code only).
  - Mirror `LeagueAvailability` in `W/api.ts`.
- [x] T041 [US2] Server-side refusals that back the availability map: the stats, spotlight, trends, superlatives, weekly-report, expected-wins, forecast, schedule and recap endpoints return 422 `PLATFORM_NOT_SUPPORTED` (or the data-driven code) for an ESPN league, instead of computing over `{}` scoring. Use `PlatformGuard` plus `LeagueAvailability` as the one source of truth, not a second list.
- [x] T042 [US2] Create `W/useLeagueAvailability.ts` and `W/components/NotAvailableForPlatform.tsx` (one line plus the reason; uses the server message, names the platform). Gate these whole pages: StatLeaderboard, StatCalculator, PlayerTrends, ScheduleGrid, Superlatives, WeeklyReport, ExpectedWins, SeasonForecast, CompletedDraftBoard/DraftView/LiveDraftView (by draft platform).
- [x] T043 [US2] `W/pages/LeagueHome.tsx`: gate per block (F13). Weekly-report matchup, superlatives, spotlight, draft-grades summary and next matchup each hide behind the one-line FR-008 note when their key is unavailable, and their fetch isn't made. Standings and the analysis-backed blocks render.
- [x] T044 [US2] Unmatched players render by name (F10): roster, lineup and transaction readers on in-scope pages resolve `espn:` keys through `PlatformPlayerLinkRepository.externalNames` and emit `{id, name, position, team, unmatched: true}` rather than dropping the key. Find the readers by grepping `idsBySleeperId`/`playerBySleeperId` in `LeagueAnalysisService`, `PowerRankingService`, `RosterManagementService` and `LeagueHistoryController` transactions. Mirror any new field in `W/api.ts`. The frontend renders the name with no player-page link. Test one reader in `T/engine/UnmatchedPlayerRenderIT.java`.
- [x] T045 [US2] `B/api/ManagerController.java` and mock seating: ESPN managers are excluded (F13). Manager history/versus pages work per platform; verify that no Sleeper call is reachable from them with an `espn:` manager.

## Phase 5: User Story 3 – Keep the ESPN league current (P2)

**Goal**: refresh on visit, keep the last good data on failure (FR-010, FR-011).
**Independent test**: quickstart step 6.

- [x] T046 [US3] `B/refresh/LeagueRefreshService.java`: `trigger` dispatches on `platform`. ESPN → `EspnLeagueIngestService.refreshCurrent(league)`, which uses the stored credential (F2 rule (a)) and re-reads the **current season only**. `loaded_complete` = ESPN status complete and a successful refresh after that. The same single-flight key as `add` (F15).
- [x] T047 [US3] Failure path: on `EspnException`, `league_refresh.last_failure` = `code()` only (no message text), data untouched, `league_credential.last_worked_at` untouched. On success, `markWorked`. `reasonOf` must not be used for ESPN exceptions. Test that `last_failure` never contains anything but a code from the fixed set.
- [x] T048 [US3] `W`: the league header shows "last refreshed …" and, for the credential owner, the failure code as text with a "replace cookies" action (reuses T033's fields).

## Phase 6: Polish and cross-cutting

- [x] T049 [P] F14 logging: confirm no ESPN code path logs a request object, header map or credential. Run the IT suite with `LOG_LEVEL=DEBUG`, capture the log, and add `T/security/CredentialLeakIT.java`, which runs an add with a sentinel credential (`espnS2="SENTINEL_S2_…"`) through the stub and asserts the sentinel appears in no captured log line, no response body and no `league_refresh` row.
- [x] T050 [P] Update `HANDOFF.md` with the 035 state and the migration merge order (V35 after 033's V34 and 034's V33). Add to `claude/lessons.md` the measured Sleeper `espn_id` crosswalk finding (61/200, one wrong person) and the `putIfAbsent` silent-guess class.
- [x] T051 Full backend suite + `npx tsc -b && npm run build` + vitest. Record the pass/skip counts against `main`'s.

## Phase 7: Live verification (separate pass; quickstart.md)

- [ ] T052 Scratch DB `draftsim035` + SC-005 baseline capture **before** running the 035 build (quickstart §0), including `/api/managers?sport=nfl|nba` and the profile-fit log line (F1).
- [ ] T053 Real-league shape confirmation (quickstart §1): needs Allan's league link. If the league is private, Allan pastes the cookies into the UI himself. Tick or amend R9 field by field, confirm the `EspnScoringMap` entries, re-run R3 against the app's `player` table for the current and one history season (F11), and record sanitized fixtures.
- [ ] T054 Quickstart §2–§8 in the real browser against the running stack; write `specs/035-espn-yahoo-ingest/verification.md` with measured results, each marked verified or not run.

## Dependencies & Execution Order

- **Phase 1 → Phase 2** (T003–T024) blocks everything.
- **US1** (T025–T038) → **US2** (T039–T045). US2 needs ingested rows to render.
- **US3** (T046–T048) needs T028.
- **Polish** after the stories; **Phase 7** last.
- Within Phase 2, the [P] tasks run in parallel: T005–T009, T011, T013–T015, T017, T023, T024.
- T020 → T021 → T022 run in sequence, because they touch the same services.

## Parallel example (Phase 2)

```text
T006 ManagerLinkRepository · T007 PlatformPlayerLinkRepository · T008 CredentialCipher
T009 EspnCredential · T013 exceptions · T014 EspnLeagueRef · T015 EspnProTeams
```

## Implementation strategy

1. **MVP** = Phase 1–3: an ESPN league added and visible with standings, claim and badge, Sleeper untouched.
2. **US2** makes the in-scope pages honest.
3. **US3** keeps the league fresh.
4. **Yahoo** (deferred) adds a sibling `ingest/yahoo` package against the same seams: `Platform`, `PlatformGuard`, `LeagueRosterSource`, `LeagueAvailability`, `platform_player_link`, `league_credential`.
