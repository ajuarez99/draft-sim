# Code review: spec 035 (ESPN league ingest)

**Stage:** bug-hunting code review (not a style pass), 2026-10-14. Reviewed the whole uncommitted
change in the worktree: `git diff` (51 tracked files) plus every untracked file. I read AGENTS.md,
`claude/lessons.md` (#1–#44), spec, plan (its "Amended after review" section overrides the rest),
plan-review, tasks and data-model.

**Evidence labels:** **measured** = I ran it; **read** = from the code; **suspected** = plausible,
not executed (mostly because an ESPN shape is still unverified, T053).

**What I ran (all against the scratch DB `localhost:5435`, never 5433):**
- Baseline: `EspnLeagueIngestIT` 13/13 and `LeagueMembershipLinkIT` 4/4 pass, 0 skipped.
- Three mutation checks, each a temporary edit restored from a byte-for-byte backup. The SHA-1 of each
  file was checked against the backup afterwards, and `git diff --stat` still reads
  `51 files changed, 1226 insertions(+), 99 deletions(-)`, the same as before I started. Details are
  in "Tests that pass for the wrong reason" below.

**Counts:** 1 BLOCKER, 9 SHOULD-FIX, 13 NIT.

---

## BLOCKER

### B1. An ESPN NFL league's Analysis page projects under STANDARD scoring, which FR-012 forbids

- **Where:** `engine/LeagueAnalysisService.java:291-292` (`ScoringKey.forReceptionPoints(league.ppr())`),
  `store/LeagueRepository.java` `ROW_COLUMNS` (`coalesce((scoring_json->>'rec')::numeric, 0) as ppr`),
  `engine/LeagueAvailability.java:135-148`.
- **Failure scenario:**
  1. Every `EspnScoringMap` entry is `confirmed=false` today. So every ESPN league is stored with
     `scoring_json = {}` and a non-empty `scoring_untranslated`, which makes `league.ppr()` equal 0.
  2. For an ESPN **NFL** league, `GET /api/leagues/{key}/analysis` then values rest-of-season projections,
     the matchup preview and LeagueHome's "Next opponent" with the **STANDARD (non-PPR)** projection
     columns.
  3. The page renders those as real numbers. A PPR league is silently scored as standard.
- **Why the gate doesn't catch it:** `LeagueAvailability` refuses `analysis` only for
  `MULTI_WEEK_PERIODS`. `SCORING_UNTRANSLATED` is applied to `stats` and `powerBaseline` only.
- **This is the F16 hole.** F16 fixed the preseason baseline (`PowerRankingService`,
  `SeasonStartService`) but not the analysis projection blocks that read the same `ppr`. It breaks
  FR-012 ("MUST NOT be scored as a different format") and the AGENTS.md rule about looking more
  certain without being more correct.
- **Why no test sees it:** the only fixtures are NBA. NBA's `basketballBlocks` reads `scoringOf`, finds
  it empty and says so, so the NFL path is never exercised.
- **Evidence:** read (traced `analyse` → `windowOf` → `projectionBlocks` → `projections.totalsByPlayer(..., key)`).
- **Fix:**
  - In `projectionBlocks`, return `unavailable(groups, matchupWeek, unit, "SCORING_UNTRANSLATED", reason)`
    when `LeagueAvailability.scoringUntranslated(league)` is present. Do this before the football window
    gates, using the same sentence.
  - Alternatively, or as well: make `analysis` data-driven-unavailable for `SCORING_UNTRANSLATED` in
    `LeagueAvailability.of`. That only hides the projection blocks, not the scores grid, so the first
    fix is the precise one.
  - Add an NFL fixture test asserting the projection block is `SCORING_UNTRANSLATED`, not a STANDARD
    projection.

---

## SHOULD-FIX

### S1. `synchronized` around network I/O pins virtual-thread carriers (a repo rule, broken)

- **Where:** `ingest/espn/EspnLeagueIngestService.java:164` (`add`) and `:183` (`refreshCurrent`).
- **Failure scenario:**
  1. Tomcat handlers and `SingleFlight` runs are virtual threads (lessons #23).
  2. On Java 21, a virtual thread blocked inside `synchronized` (on `HttpClient.send` → future park)
     **pins its carrier**. So does a second caller waiting to enter the monitor.
  3. An add walks up to 15 seasons, each with one league call, one transactions call per matchup period
     and one catalogue call. That is tens to hundreds of ESPN round trips, all inside the monitor.
  4. When the number of concurrent ESPN adds/refreshes (plus their waiters) reaches the carrier count
     (= vCPUs), every other request in the app stalls until they finish.
- **The codebase already states the rule:** `engine/SeasonBoxCache.java:49` says "Never `synchronized`:
  on Java 21 a virtual thread blocked on a monitor pins its carrier."
- **Evidence:** read. Impact suspected; I didn't load-test it.
- **Fix:** replace the `Object` monitors with `ReentrantLock` (`locks.computeIfAbsent(key, k -> new ReentrantLock())`
  and `lock(); try { … } finally { unlock(); }`). `LiveDraftPoller.java:221` has the same pattern, but it
  predates this change.

### S2. The draft-path guard isn't cheap-first, so every Sleeper `/api/drafts/**` request pays for it

- **Where:** `store/LeagueMembership.java` `platformOfVisibleDraft`; `api/PlatformGuard.java:95-97`.
- **Failure scenario:**
  1. For a stored **Sleeper** draft, the interceptor runs `drafts.platformOf` → `visibleDraft`
     (`bySleeperId` plus the recursive membership CTE) → `drafts.platformOf` again.
  2. Only then does the route do its own `visibleDraft`.
  3. That is four extra queries, one of them the recursive CTE, on every draft request. That includes the
     live room's 2-second poll on draft night, for every viewer.
  4. The league variant (`visibleNonSleeperLeague`) does it right: one `platformOf`, and it returns at
     once for SLEEPER. The draft variant doesn't follow its own javadoc's "cheap-first" rule.
- **Not a status change.** I checked anonymous, stranger and unknown-key callers. FR-013's correctness
  holds; this is cost on the hottest Sleeper path.
- **Evidence:** read.
- **Fix:** `Optional<Platform> p = drafts.platformOf(id); if (p.isEmpty() || p.get() == SLEEPER) return Optional.empty();`
  then the visibility check, then return `p`. `platformOfVisibleLeague` (setup/ingest paths) should get
  the same ordering.

### S3. FR-005's Phase-2 rollback has no test. The tx can be removed with the suite still green, and a real Phase-2 failure exists

- **Where:** `EspnLeagueIngestService.java:288-311`, `EspnLeagueIngestIT`.
- **Evidence: measured.**
  1. I set the service's `TransactionTemplate` to `PROPAGATION_NOT_SUPPORTED`, so Phase 2 runs with
     **no transaction**.
  2. All 87 tests in `ingest.espn.*`, `security.*`, `store.*Platform*`, `LeagueMembershipLinkIT` and
     `api.Espn*` still pass.
  3. `aTranslatorFailureOnTheSecondSeasonWritesNothing` fails in Phase 1, before any write, so it can't
     see a rollback (lessons #33).
  4. `aSecondPersonClaimingTheSameTeamIsRefused` hits a Phase-2 failure only on an already-stored league,
     where the rolled-back upserts are identical anyway.
- **A real Phase-2 failure path exists:**
  1. Links are per ESPN member (F3). Allan claims member M in league X.
  2. A second user adds a **new** league Y in which M also plays, claiming M's team.
  3. `writeSeason` writes all of Y's rows, then `links.link` throws `AlreadyClaimed`.
  4. Only the transaction keeps Y, its managers and the claimant's new `manager` row out of the store.
  5. `credentials.put` and any SQL error raised after `writeSeason` are the same case.
- **Fix:** add an IT that throws inside Phase 2 on a never-stored league, then asserts zero `league`,
  `manager`, `platform_player_link` and `league_credential` rows. One way: pre-link the fixture's
  member `espn:{IT035-A}` to another manager, then add with `claimRosterId=1`.

### S4. `X-Sleeper-User: espn:{guid}` is accepted as an identity, and per-member links reach private leagues

- **Where:**
  - `store/LeagueMembership.java` `MEMBER_LEAGUE_IDS_CTE`: `meuser` matches any `manager.sleeper_user_id`.
  - `EspnLeagueIngestService.java:299-303`.
  - `EspnSetupController.java:124-148`.
- **Failure scenario A:** a header of `espn:{X}` makes `me` the ESPN manager X directly. The caller can
  then see every stored ESPN league X plays in, including private ones whose cookies someone else
  stored. With `canSee` they also qualify under F2 to have those stored cookies used on a re-add.
- **Failure scenario B:** anyone can add a **public** ESPN league and claim member X's unclaimed team.
  The link is per member, so the claimant then sees X's other stored, **private** leagues the same way.
- **Where the GUID comes from:** ESPN's public API for any public league X is in.
- **Within the declared model, but under-stated:** this is inside "scoping, not security". R13/FR-009's
  stated rationale ("the only thing an impersonator can do with the cookies is refresh a league that's
  already visible to them") is literally true but misleading. Visibility of a private ESPN league is
  obtainable by anyone who knows one member's GUID.
- **Evidence:** read.
- **Fix:**
  - Treat a header starting with `espn:` as anonymous in `LeagueMembership.anonymous/isAnonymous`, and
    refuse it at claimant creation (cheap; closes A).
  - For B, either scope a link to the stored leagues it was claimed through (or to leagues with no stored
    credential), or state the residual explicitly in plan F3/R13 so Allan can decide.

### S5. `matchStrict` falls back to a name-only match when ESPN *did* give a modelled position

- **Where:** `ingest/PlayerMatcher.java:181-188`.
- **Failure scenario:**
  1. ESPN gives a modelled position, say K, but no Sleeper player has that name at K.
  2. The code falls back to `byNameOnly`.
  3. If exactly one Sleeper player has the name (say a QB), the ESPN kicker is **MATCHED to the QB**, a
     wrong attachment.
- **This contradicts T016 and F11:** "the name-only fallback is used **only** when the ESPN position is
  null." It also contradicts FR-007's "MUST NOT guess". Lessons #42 shows this exact failure (a wrong
  person is worse than a miss).
- **Why no test sees it:** the strict test's "Lamar Jackson" case uses CB, which is unmodelled, so it
  returns `ESPN_ONLY_POSITION` before the fallback is reached.
- **Evidence:** read.
- **Fix:** take the name-only path only when `pos == null`. For a position mismatch, return `UNMATCHED`,
  or `AMBIGUOUS` with a reason. If the real NBA position mapping needs cross-position tolerance (multi-position
  players), decide that in T053 with the R3 re-run. Add a test: ESPN "X|K" with only "X|QB" stored →
  not MATCHED.

### S6. Two "you" sites still compare the raw header, so the FR-004 claim is ignored there

- **Where:**
  - `engine/LeagueAnalysisService.java:946`: `isMe = owner.equals(sleeperUserId)`. Here `owner_id` is a
    Sleeper user id; for ESPN it is `espn:{guid}`.
  - `engine/MemberRankingService.java:205-206`: `managerBySleeperUserId.get(callerSleeperUserId)`.
- **Failure scenario:**
  1. On an ESPN league, the analysis projections, the matchup preview and LeagueHome's "Next opponent"
     never mark the claimant's roster as theirs.
  2. The power-rankings ballot never marks "you".
  3. Spec US1 scenario 2 ("your roster, your matchup use that team") fails on in-scope pages.
- **Why T021 missed them:** its grep (`idsBySleeperUserId().get(`) misses both forms.
- **Evidence:** read.
- **Fix:**
  - Analysis: resolve `ManagerRepository.caller(managers, league.id(), header)` once in `LineupPass` and
    compare `RosterInput.managerId()`, not the owner string. For Sleeper this is the same answer, because
    the header's manager owns the roster with that `owner_id`.
  - MemberRankingService: use `caller(...)`.
  - Re-grep for `equals(sleeperUserId)` and `get(callerSleeperUserId)`.

### S7. F18's co-owner claim can't happen: both claim paths link only the primary owner

- **Where:** `EspnLeagueIngestService.java:281,301` (`claimed.ownerMemberId()`) and
  `EspnSetupController.java:131-133` (`roster_season.manager_id`, which is `primaryOwner`).
- **Failure scenario:**
  1. Co-owner B of team 3 adds the league and claims team 3.
  2. The code links B to the **primary owner's** ESPN manager, which is already claimed by A, and
     returns 409 `ALREADY_CLAIMED`.
  3. B can never see the league, although the plan says "a link for each member is allowed".
- **Evidence:** read.
- **Fix:** when the caller picks a team, link to the first of `[primaryOwner, coOwners…]` that is
  unclaimed, or to the one the caller names. The co-owners' managers and `league_member` rows are
  already written.

### S8. ESPN `divisions` is written as the raw list size, so a one-division league counts as "has divisions"

- **Where:** `ingest/espn/EspnTranslator.java:144`; `LeagueRepository.playoffFormat`
  (`divisions > 0` → `hasDivisions` → `modelable()` false).
- **Failure scenario:**
  1. ESPN's `scheduleSettings.divisions` is, to my knowledge, always a non-empty list (a league with no
     divisions has one, "Division 1"). That is unverified.
  2. If so, every ESPN league stores `divisions: 1` and reads as divisional.
  3. Playoff odds are then always refused with `PLAYOFF_FORMAT_UNSUPPORTED`. It fails closed, but it is
     the wrong reason.
- **The fixture hides it:** it uses `"divisions": []`, the one value ESPN likely never sends.
- **Evidence:** suspected (shape unverified, T053).
- **Fix:** write `size > 1 ? size : 0`, and add it to the T053 field checklist. Change the fixture to a
  one-element list.

### S9. FR-007's unmatched count and list are never shown

- **Where:** `LeagueAvailability.View.unmatchedPlayers` and `AddResult.players` both reach the client, but
  `grep -rn "unmatchedPlayers\|players.unmatched\|\.ambiguous" web/src` finds no consumer outside tests.
- **Failure scenario:** the owner of an ESPN league with 5 refused players sees no count and no list.
  Spec US2 scenario 3 ("the unmatched count is visible to the league owner") and FR-007 ("a visible
  count and list") are not met. Only transaction rows render unmatched names (RosterManagement).
- **Evidence:** read.
- **Fix:**
  - Render the count in `EspnRefreshNote` and in the setup result.
  - Add a list (ESPN name, position, team, outcome) from `platform_player_link` for the league's `espn:`
    keys, e.g. as an `unmatched` array on the availability view, mirrored in `api.ts`.

---

## NIT

- **N1** `store/RosterWeekPointsRepository.java:68-69`: `hasPlayerPoints` was inserted between
  `weeksWithStarters`'s javadoc and its method. The old javadoc now documents the wrong method.
- **N2** `EspnTranslator.java:167-168, 486, 502-503`: bare `(Number)` casts on `matchupPeriods[]`,
  `pickOrder[]` and `lineupSlotCounts` values. A shape change there throws `ClassCastException`, not
  `EspnShapeChanged`. Add rethrows a 500, and refresh records `ESPN_REFRESH_ERROR` instead of
  `ESPN_SHAPE_CHANGED`. Route them through `integer(…, path)`.
- **N3** `EspnLeagueIngestService.unavailable()` (`:398-403`) is a second list of unavailable pages, with
  a `draftBoard` key that `LeagueAvailability` doesn't have. That is "two implementations of one rule"
  (memory: multi-sport landmines). Return the `LeagueAvailability` keys, or drop the field.
- **N4** `lineups`, `playoffOdds` and `powerBaseline` are reported by `LeagueAvailability`, but no frontend
  code reads them (grep). Either consume them or say in the contract that they're informational.
- **N5** The no-claim preview returns `credentialStored = credentials.exists(...)` to any caller. Anyone
  can learn whether a given ESPN league id has stored cookies. Return it only to someone who `canSee`
  a stored season.
- **N6** `EspnLeagueIngestService.java:300`: a caller with no manager row gets
  `managers.upsert(caller, caller, null)`, i.e. a SLEEPER manager whose `display_name` is the raw header
  string. That is harmless today (it is in no league) but surfaces wherever manager names are listed
  later. Use the Sleeper display name if known, else a neutral label.
- **N7** `ManagerLinkRepository.link`: two concurrent claims race between `holderOf` and the insert. The
  loser gets a unique-violation `DataIntegrityViolationException` (500), not 409. Catch
  `DuplicateKeyException` → `AlreadyClaimed`.
- **N8** FR-013 latency:
  - `PlatformGate` renders nothing until `cachedDrafts()` resolves, for Sleeper too. That includes the
    live draft room.
  - LeagueHome now holds `weekly`, `awards`, `spotlight`, `analysis` and `nextMatchup` until the drafts
    block settles.
  - On a cold load or hard refresh, this is a serial wait on `/api/drafts` that did not exist before.
  - It makes no extra request, but HANDOFF's "render as they did before" overstates it. On the live room,
    consider failing open immediately when the list isn't warm.
- **N9** `LeagueRefreshService.triggerEspn` refreshes `chain.getFirst()`, the **visited** season, not
  the newest, despite its javadoc. A non-frozen old season is re-read via `client.league`, which is the
  wrong endpoint before 2018 (R11), so it 404s and records a failure on every visit after back-off.
- **N10** `unlinkInLeague` keys on the provenance season. Re-claiming a different team when re-adding a
  later season leaves the old link, so the user holds two members. `callerManagerIn` then picks the lower
  id (`order by c.ord, c.id`), which may be the wrong "you". Unlink the caller's links to any member who
  has a roster in this league's chain.
- **N11** ESPN NBA analysis shows "This league's scoring settings aren't stored". That is false; they are
  stored and untranslated. Use the `SCORING_UNTRANSLATED` sentence (B1's fix covers this).
- **N12** `LeagueMembershipLinkIT.aSleeperOnlyUsersLeagueListIsUnchanged...` uses a bystander with zero
  leagues. A user with Sleeper leagues *and* a link would be the stronger FR-013 pin. The Sleeper-unchanged
  case for `caller()` with a rostered user is covered; the fallback is covered by existing tests (see
  below).
- **N13** `PlatformNotSupported`'s javadoc says the message "never carries anything from ESPN". The
  `SCORING_UNTRANSLATED` sentence embeds ESPN scoring item labels. That is not a secret, but correct the
  claim.

---

## Tests that pass for the wrong reason (lessons #30/#33): five checked

| Test | Mutation | Result |
|---|---|---|
| `EspnLeagueIngestIT` FR-005 (`aTranslatorFailureOnTheSecondSeasonWritesNothing`) | Phase 2 run with no transaction (`PROPAGATION_NOT_SUPPORTED`) | **measured: still green, 87/87 ESPN tests**. Doesn't guard Phase-2 atomicity → S3 |
| FR-013 `ManagerRepository.caller` fallback (Sleeper user with no roster in the league gets the header's id, as before) | `: header` → `: null` | **measured: guarded.** Full suite 1767 tests, 13 failed (12 from the mutation: `OwnerSlotTest` 5, `NextMatchupServiceTest` 5, `LeagueControllerManualPickTest` 1, `LeagueControllerSeatsOwnerConfiguredIT` 1, plus the pre-existing `BoardRepositoryLatestBeforeIT`), 1 skipped |
| `EspnPlatformGuardIT` / `LeagueAvailabilityIT` (F4/T041) | `PlatformGuard` not registered in `WebConfig` | **measured: guarded.** `everySleeperOnlyRouteRefusesAnEspnKey` and 2 `LeagueAvailabilityIT` tests fail |
| `EspnLeagueIngestIT.aStrangerCannotBorrowTheStoredCookies` (F2) | drop the `canSee` condition | read: guarded. `verify(client, never()).league(..., isNotNull())` fails, and the control half proves the stub would see a credential |
| `DraftRepositoryPlatformIT` (F1) | drop `l.platform = 'SLEEPER'` | read: guarded. The fixture draft is `complete` with non-null `manager_id`, which the test asserts first, so it would be returned |

**Also read, not mutated:** `CredentialLeakIT` isn't vacuous. It asserts the sentinel reached the stub
and that app log lines were captured, and it covers the Spring DEBUG body line that caught the real
leak (lessons #44).

**Process note, so nobody repeats it:** I first queued a cleanup step that would have run
`git checkout --` on `ManagerRepository.java`, a file this change modifies. I stopped the task before
it reached that step and verified the file against the saved diff (identical). The mutation run was
then redone with a copy-based restore. The tree is unchanged: SHA-1s match the backups, and the diff
stat is identical.

---

## Checked and fine

- **FR-013, caller resolution.** All 14 `idsBySleeperUserId().get(header)` sites now use
  `ManagerRepository.caller` / `callerManagerIn`. For a Sleeper league the linked side can never own a
  roster there, so the answer is the header's manager (ord 0) or the old lookup (fallback). That holds
  for commissioners, ex-members and non-members. The fallback is pinned by existing tests (measured above).
- **FR-013, guard status codes.** Sleeper and unknown keys pass the guard. Strangers on ESPN keys fall
  through to the route's own 404 (tested). Anonymous callers aren't refused by the guard. OPTIONS passes.
  Ingest routes still hit the admin gate first (interceptor order).
- **FR-013, write paths.** The 10/11/3-argument Sleeper `upsert`s are unchanged and never touch
  `platform`. `MEMBER_LEAGUE_IDS_CTE` still binds one parameter, and its link side is empty for users
  without links. `MANAGER_LEAGUE_IDS_CTE` is unchanged.
- **F1.** `allCompletedPicks` and the `adp_at_time` backfill filter `l.platform = 'SLEEPER'`.
  `ProfileService` removes ESPN managers. Mock seating refuses them, with a fetch only when seats are
  given.
- **Mock seats / track.** `LiveDraftPoller.track` refuses a non-Sleeper draft before any tick. `/api/sims`
  is guarded in-body.
- **F2.** The stored credential is used only when the caller `canSee` one of that league's stored seasons
  (same `platform_league_id` and sport). The refresh path uses it. PUT/DELETE check the owner against
  the header's manager. PUT test-reads before storing. `put` won't replace another owner's row
  (`where owner_manager_id = excluded…`).
- **F14, credential hygiene.**
  - `EspnCredential` has a redacted `toString`, a Jackson serializer and no public getters.
  - The setup controller never echoes `getMessage()` of wrapped exceptions.
  - `last_failure` stores only codes; generic `RuntimeException` → `ESPN_REFRESH_ERROR`.
  - `EspnClient` uses `Redirect.NEVER` plus a host check. Exceptions carry no cause or URL.
  - `CredentialLeakIT` is green at DEBUG.
  - I grepped for every flow of `EspnCredential` and the cookie strings and found no log, response or
    `toString` path.
- **`EspnLeagueRef`.** The league id is `\d{1,12}` and the season is validated, so a path can't be
  injected into the ESPN URL. The host allow-list covers pasted links.
- **SQL and JDBC (lessons #2/#4/#9/#12).**
  - `text[]` binds use `createArrayOf("text", …)`; null arrays use `setNull(Types.ARRAY)`.
  - `Timestamp` → `timestamptz` binds go through `JdbcClient`, so pgjdbc sends the offset.
  - The V35 conflict targets match their PKs and uniques.
  - All of it was exercised by the passing ITs on Postgres.
  - No `Map.of` sits on a nullable response value (`ErrorHandler`'s new handler has non-null code and
    message; the ESPN maps are `LinkedHashMap`).
- **Translator.** It matches data-model plus the F7/F8/F9 amendments:
  - status ladder;
  - `final_placement` champion-only when complete;
  - points only for decided pairings, with POINTS `final` only when a week is fully decided;
  - `matchup_id` per pair, null for a bye;
  - `points_possible` null;
  - `previous_league_id` chained in app seasons (NBA app = ESPN − 1, consistently via `EspnSeasons`);
  - transaction types mapped into the V19 set, with lineup-only and pending moves skipped;
  - NBA transaction week = matchup period;
  - FAAB bid only on waiver claims in FAAB leagues.
- **Transactions-failure isolation.** Moves fetched before the first failing period are written and only
  those weeks are marked final. Later periods and later seasons are skipped and retried next time. The
  failure code is recorded, and an older season with a failure isn't frozen. This is coherent partial
  progress, not a half-written week.
- **Availability.** `LeagueAvailability.ROUTES` is the one list both the 422s and the frontend gate read.
  Sleeper leagues always get `unavailable: {}` and are never refused. Every out-of-scope league route in
  `App.tsx` is gated: analysis, schedule, trends, stats, calculator, player, expected-wins, forecast,
  weekly-report, superlatives, plus the three draft routes. History, power, roster-management and home
  are in scope (home gates per block).
- **`api.ts`.** `DraftSummary.platform`, `LeagueAvailability` and the ESPN setup types mirror the Java
  records field-for-field.


---

## Fixes applied (fix pass, 2026-10-14)

Every BLOCKER and SHOULD-FIX is fixed, each with a test. For each one the fix was backed out through a
byte-for-byte file copy (SHA-1 re-checked after every restore), the named tests were run, and the copy was
put back. Nothing was committed, and no `git checkout`/`restore`/`reset`/`stash`/`clean` was run.

**Final suites (scratch DB `localhost:5435`):**
- Backend: **1784 tests, 1 failed, 1 skipped** (baseline 1767 + 17 new). The one failure is the pre-existing
  `BoardRepositoryLatestBeforeIT` (scratch data); the one skip is the pre-existing `PlayerStatsLeaderboardReadIT`.
- Web: `npx tsc -b` clean, `npm run build` ok, `npx vitest run` **2104 passed** (baseline 2102 + 2 new).

| Finding | What changed | Test(s) | Fix backed out |
|---|---|---|---|
| **B1** | `LeagueAnalysisService.projectionBlocks` returns `SCORING_UNTRANSLATED` (the sentence `LeagueAvailability.scoringUntranslated` owns) before any window or projection gate, for NFL and NBA. It covers the analysis projections, the matchup preview and Home's "Next opponent" because they all go through that method. No second list; also fixes N11. | `LeagueAnalysisServiceTest.anEspnNflLeagueWithUntranslatedScoringIsRefusedNotProjectedAsStandard` (an ESPN NFL fixture whose stub WOULD return a STANDARD projection; asserts the code and that the projection repo is never read) | **1 failed** (that test) |
| **S1** | `EspnLeagueIngestService`: the per-league `synchronized` monitors became `ReentrantLock` (`lock()`/`finally unlock()`) in `add` and `refreshCurrent`. | `EspnLeagueIngestIT.s1_theLeagueLockIsAReentrantLockHeldDuringTheFetchNeverAMonitor` (inside the ESPN call, the lock is a `ReentrantLock` held by the thread and `Thread.holdsLock` is false) | **1 failed** (that test) |
| **S2** | `LeagueMembership.platformOfVisibleDraft` and `platformOfVisibleLeague` now read the platform once and return empty for SLEEPER before any membership work. | `LeagueMembershipPlatformOrderTest` (a Sleeper draft/league: zero `JdbcClient` interactions, no `bySleeperId`, one platform lookup; the guard doesn't refuse a Sleeper draft and still doesn't act as an existence oracle) | **2 failed** (the two ordering tests) |
| **S3** | No production change was needed: the transaction was already there, it just wasn't pinned. | `EspnLeagueIngestIT.s3_aPhaseTwoFailureOnABrandNewLeagueLeavesNothingBehind` (member A already linked to someone else; a new-league add claiming A's team writes every row, then `AlreadyClaimed`; asserts zero rows in league, roster_season, league_member, league_matchup, roster_week_points, league_transaction, league_week_fetch, league_refresh, draft, platform_player_link, league_credential, no claimant manager row, no other ESPN manager) | Transaction disabled (`PROPAGATION_NOT_SUPPORTED`): **1 failed** (that test). It passes with the transaction. |
| **S4** | `LeagueMembership.isAnonymous` is now also true for a foreign-shaped header (`isForeignShaped`: starts with `espn:`, or contains `:`, `{` or `}`). `ManagerRepository.caller`/`callerManagerIn`, `LeagueAvailability` (owner flag) and `LineupPass` all use it. The add route refuses it (401). plan.md has the "Residual risk" paragraph. **Not done:** a stricter "must be numeric" rule. Ops and test ids such as `it035-allan` and `owner4` are legitimate non-numeric headers across the suite, so only characters that cannot appear in a Sleeper id were used. | `LeagueMembershipPlatformOrderTest.anEspnShapedHeaderIsAnonymousAndSeesNothing` and `theCallerResolverTreatsAnEspnShapedHeaderAsNobody`; `EspnLeagueIngestIT.s4_...` (a real stored ESPN manager's id as the header gets 404 on availability and 401 on add); `LeagueAnalysisServiceTest.anEspnShapedHeaderIsNeverMarkedMeEvenWhenItEqualsAnOwnerId`; `MemberRankingServiceCallerTest.anEspnShapedHeaderIsNobody` | **5 failed** (all of those) |
| **S5** | `PlayerMatcher.matchStrict`: when ESPN gave a modelled position, only the name+position index is consulted; the name-only index is used only when the position is null. The old test that asserted the buggy fallback (`aUniqueNameWithADifferentPositionTagStillMatches...`) was replaced. | `PlayerMatcherStrictTest.aGivenModelledPositionNeverFallsBackToTheNameAlone` (ESPN K "Lamar Jackson" vs app QB: UNMATCHED; QB and null position still match) | **1 failed** (that test) |
| **S6** | `LineupPass` resolves the caller once with `ManagerRepository.caller(...)` and `projectRosters` marks `isMe` when the roster's manager is that caller (the old owner-string compare stays for the Sleeper path); `MemberRankingService.members` uses `caller(...)`. | `LeagueAnalysisServiceTest.theCallersClaimedRosterIsMarkedMeEvenThoughNoOwnerIdEqualsTheHeader`, `MemberRankingServiceCallerTest.aLinkedCallerIsMeOnTheRosterTheirLinkedMemberOwns` | **3 failed** (those two plus the S4 ballot test) |
| **S7** | New `ManagerLinkRepository.claimable(managerId, owners)`: an owner the caller already holds, else the first owner nobody holds, else empty (409 only then). The add path passes `[primary] + coOwners`; the claim endpoint passes the primary plus every `league_member` listed under the same team name (that table keeps no roster id, so a duplicated team name could over-include; same unverified-claim model). `teamChoices.claimedByOther` is true only when every owner is held. | `EspnLeagueIngestIT.s7_aCoOwnerCanClaimATeamWhosePrimaryOwnerIsAlreadyClaimed` (C then D linked, a third person gets 409), `s7_aTeamWithAFreeCoOwnerIsStillClaimableInThePreview` | **1 failed** (the claim test; the preview test passes either way and pins the new `claimedByOther` rule) |
| **S8** | `EspnTranslator` writes `divisions` as the list size only when 2 or more, else 0, marked `// unverified (T053)`. The synthetic fixture now carries a one-element default division. | `EspnTranslatorTest.aSingleDefaultDivisionIsNoDivisionsAtAll` (the existing `settingsAreDerivedNotDefaulted` also runs on the new fixture) | **2 failed** |
| **S9** | Backend: `RosterSeasonRepository.unmatchedPlayers`, `LeagueAvailability.View.unmatched` and `AddResult.unmatched` (name, position, team, outcome). Web: `api.ts` mirrors both (`UnmatchedPlayer`), and a new `UnmatchedPlayersNote` shows in the setup preview, the setup result, and on LeagueHome for an ESPN league. | `EspnLeagueIngestIT.s9_...`, `LeagueAvailabilityIT` (3 named), `EspnLeagueSetup.test.tsx` (preview and after claim), `LeagueHome.test.tsx` (ESPN note) | Backend **2 failed**. The web tests were not backed out (they fail by construction without the component). |

### NITs

- **Fixed:** N1 (javadoc moved back to `weeksWithStarters`), N2 (the four bare `(Number)` casts go through `integer(..., path)`), N3 (`unavailable()` now uses the `LeagueAvailability` key `draftRoom`; contract and web fixture updated), N5 (the no-claim preview reports `credentialStored` only to someone who can see a stored season), N6 (a caller with no manager row is stored as "Ball Knowers user", not the raw header), N7 (`DuplicateKeyException` on the link insert becomes `AlreadyClaimed`, a 409), N11 (covered by B1), N13 (`PlatformNotSupported` javadoc corrected).
- **Deferred, with reasons:**
  - N4: the `lineups`/`playoffOdds`/`powerBaseline` keys are informational; consuming them is a product decision, not a bug.
  - N8: the `PlatformGate` serial wait on `/api/drafts` needs a UI timing decision (fail open on a cold list) and live measurement on the draft room; HANDOFF's "render as before" wording should be softened instead.
  - N9: `triggerEspn` refreshing the visited season instead of the newest needs a decision about which season a visit means, plus a real R11 check; not safe to change blind.
  - N10: unlinking a caller's links to every member with a roster in the chain interacts with F3's "a claim never moves silently"; left for a decision.
  - N12: a stronger FR-013 pin (a Sleeper user with leagues and a link) is test-only polish; the fallback is already pinned.

Nothing in the BLOCKER/SHOULD-FIX list was left unfixed. Still unverified: everything depending on a real ESPN payload (T053); the S1 fix is proven by lock type and monitor state, not by a load test.
