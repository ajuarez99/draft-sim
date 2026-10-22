# Tasks: FantasyPros design and feature borrowings (spec 042, US1–US3)

**Input**: `specs/042-fantasypros-design-features/` (spec, plan, research, data-model, contracts, plan-review)

**Tests**: included. This repo's bar (AGENTS.md, lessons class 1/6) needs a rule test per new rule, plus
live verification. Every Java record change and its `web/src/api.ts` mirror land in **the same task**
(constitution).

Paths: `B` = `backend/src/main/java/com/ballknowers/draftsim`, `BT` = `backend/src/test/java/com/ballknowers/draftsim`, `W` = `web/src`.

## Phase 1: Setup (measure before building)

- [x] T001 Measure and record in `specs/042-fantasypros-design-features/t001-measurements.md`, with the commands and raw output. Start Postgres on 5433 per AGENTS.md and re-ingest players for nba and nfl.
  - (a) `select sport, injury_status, status, count(*) from player group by 1,2,3 order by 1,4 desc`. Research R3 / F4.
  - (b) `select sport, season, count(*) from sport_schedule group by 1,2`. Does NFL have rows? Research R5.
  - (c) From V18 `roster_week_points.starters` for an NBA 2025 league: compare `starters[i]`' player positions against the starting entries of `roster_positions[i]` across all weeks, and count mismatches. Research R2 / F12.
  - (d) The latest `status_capture.captured_at` and `daily_capture.completed_at (kind='PLAYERS')` per sport. F2.
  - (e) Whether this league's NBA lineups lock weekly or daily. Read Sleeper league `settings` from `/league/{id}` and note any lock-related key. If it can't be established, write "not established" (F6).

  Stop and report to the parent if (c) shows mismatches. Slot labels depend on it.

## Phase 2: Foundational (shared rules, no behaviour change)

- [x] T002 [P] Make `LeagueAnalysisService.isUnavailable(String)` and `NON_STARTING_SLOTS` package-visible (or move them beside `SportRules`) in `B/engine/LeagueAnalysisService.java`. LeagueAnalysis keeps calling them. No literal is copied anywhere (F4/F3). Run the existing `LeagueAnalysisServiceTest` unchanged and confirm it's green.
  - Log: Done as written; `NON_STARTING_SLOTS` and `isUnavailable` are package-visible in `LeagueAnalysisService`, `LeagueAnalysisServiceTest` unchanged and green (42/42).
- [x] T003 [P] Create `B/ingest/NextGames.java`, a pure helper:
  - `forTeam(List<SportSchedule.Game> games, String team, LocalDate today, int limit)` returns the team's counted, unsettled games with `game_date >= today`, earliest first.
  - `hasCountedGameInWeek(games, team, week)`.
  - Reuse `SportSchedule`'s `SETTLED`/`counts` (make them package-visible) and `ScheduleGridService`'s All-Star exhibition filter. Don't re-list statuses.
  - Javadoc: today is `America/New_York`, unlike `PlayerGameIngestService:168` (UTC), and why (F5a/F5c).

  Test it in `BT/ingest/NextGamesTest.java`: the exhibition is excluded, a postponed game is skipped, today's `pre_game` is included, and a null date is skipped.
  - Log: Deviation: `SportSchedule.isSettled(String)` (package-visible) added instead of exposing `SETTLED`; `ScheduleGridService.exhibitionGames(games)` (public static Predicate) extracted from `compute()` so the exhibition filter has one implementation (compute now calls it). Added `NextGames.today(Clock)`/`TODAY_ZONE` for the ET rule.
- [x] T004 [P] Create a `StatusAsOf` read in `B/store/StatusCaptureRepository.java` (or a small new repository method). It returns `greatest(max(status_capture.captured_at) for sport in the current season, max across weeks, max(daily_capture.completed_at) where kind='PLAYERS' for sport)`, and null when both are absent (F2). Test it in an IT beside the existing repository ITs. Check the IT skip count, which is silent without Postgres (memory).
  - Log: Done as `StatusCaptureRepository.statusAsOf(Sport, season)` -> `Optional<Instant>` (empty = unknown), plus a package-private code-keyed overload so the IT uses a throwaway sport key. IT is `store/StatusCaptureStatusAsOfIT` (5 tests, ran, 0 skipped).

## Phase 3: US1, ownership marks (P1) 🎯 MVP

**Goal**: one ownership mark on the leaderboard, Trends and the player page, plus a "My roster" filter kept in the URL.
**Independent test**: quickstart step 2.

- [x] T005 [US1] Add `rosteredByMe: Boolean` to `PlayerTrendsService.TrendRow`. Set it in `row()` (`B/engine/PlayerTrendsService.java:~388`). The rule is "null exactly when `rostered` is null (ownership unknown). Otherwise `RosterOwner.isMe` of the owning roster, or false for a free agent." Mirror it in `W/api.ts` `TrendRow` in the same task. Test it in `BT/engine/PlayerTrendsServiceTest.java` with three cases: mine, another manager's, unknown.
  - Log: Done; field placed after `rosteredBy`. Rule: free agent => false, unknown owner row (`Roster N`) => false. Existing `PlayerTrends.test.tsx` row builder got `rosteredByMe: null`.
- [x] T006 [P] [US1] Create `W/components/OwnershipMark.tsx`. Input: a `PlayerOwnership`, or the Trends trio (`rostered`, `rosteredBy`, `rosteredByMe`) normalised to it. It renders:
  - ROSTERED: avatar + name, plus a "Yours" text pill when `isMe` (reuse `.lh-yours`).
  - FREE_AGENT: "Free agent" (`.lh-fa`).
  - NOT_DRAFTED / UNAVAILABLE: the existing reason tooltips.

  Text always accompanies colour (FR-002). The asOf goes in the `title`. Test it in `W/components/OwnershipMark.test.tsx` with all four states, plus isMe.
  - Log: Added `ownershipFromTrend()` in the same file to normalise the Trends trio; a `verbose` prop prints the not-drafted/unavailable sentence on the player page (tables keep the short text + tooltip, as before).
- [x] T007 [US1] Replace `OwnerCell` in `W/pages/StatLeaderboard.tsx`, the owner block in `W/pages/PlayerPage.tsx:~110-120`, and the `rosteredBy` text cell in `W/pages/PlayerTrends.tsx:~111` with `OwnershipMark`. Add a `mine` row class on leaderboard and Trends rows when the player is yours. Existing tests must still pass. Update only selectors that named the old markup, and say which ones in the task log.
  - Log: No existing test selector needed changing (82 tests across StatLeaderboard/PlayerTrends/PlayerPage passed untouched). `.mine` row tint added to `.sl-table` and `.pt-table` in styles.css; the leaderboard owner cell now also shows a 'Yours' pill (previously ring only).
- [x] T008 [US1] Add `{ kind: 'mine' }` to the `Availability` union and `matchesAvailability` (`W/statLeaderboard.ts:~296, ~320`). In `FilterBar`, add a "My roster" option shown only when some row has `isMe` (`W/components/statControls.tsx:78-80`, `availKey`/`parseAvail`). Extend `W/statCalcUrl.ts` `readCalcState`/the writer with `own=mine`, so the calculator doesn't drop it (F9). Tests: `W/statLeaderboard.test.ts` (mine filter) and `W/statCalcUrl.test.ts` (`own=mine` round trip).
  - Log: Deviation: the `own=` read/write was extracted into exported `readFilters`/`writeFilters` in `statCalcUrl.ts` (readCalcState/writeCalcState call them) so the leaderboard reuses the one encoding instead of copying it; `hasMine()` added to `statLeaderboard.ts`. `own=mine` is dropped (and named) when no row is the viewer's.
- [x] T009 [US1] Put the leaderboard's filters in the URL with the same `own=` encoding (`W/pages/StatLeaderboard.tsx`, `useSearchParams`). Pass `keepSearch: stripRoster` to its `SeasonPicker`: `r:<id>` is dropped on a season switch, and `mine` is kept. Test it in `W/pages/StatLeaderboard.test.tsx`: reload keeps `own=mine`, and a season switch drops `own=r:3`.
  - Log: Done. Added a muted 'left out: ...' note for dropped link filters on the leaderboard (same sentence as the calculator).

**Checkpoint**: US1 verifiable on its own (quickstart 2).

## Phase 4: US2, player page header + tabs (P2)

**Goal**: the header shows owner, status with as-of, the next game (date, opponent, home/away, no time), and the existing position rank. The body is in `?tab=` tabs.
**Independent test**: quickstart step 3.

- [x] T010 [US2] Extend `PlayerStatsService.PlayerStatsPage` (`B/engine/PlayerStatsService.java:121`) with 4 fields:
  - `status`: `PlayerStatus(injuryStatus, status, asOf)`.
  - `nextGame`: `NextGame(gameId, date, opponent, isHome, week)`, nullable.
  - `nextGameReason`: `NO_TEAM | NO_GAMES_LEFT | NO_SCHEDULE | null`, "null exactly when nextGame is set".
  - `upcoming`: "up to 7 of the team's next qualifying games".

  Per F1 (BLOCKER), compute them from **`requested`**, the URL league row `visibleLeague` returned, and the player's **current** `player.team`. Never use `resolved`/`dataSeason`. Gate only on `requested` being complete (→ `NO_GAMES_LEFT`) or pre-draft. Use `NextGames.forTeam` (T003) and `StatusAsOf` (T004). Records, not `Map.of`, because these are nullable. Mirror all 4 in `W/api.ts` in the same task.

  Tests in `BT/engine/PlayerStatsServiceTest.java`:
  - **an NBA 2026 league with zero 2026 box scores still returns a nextGame** (F1);
  - a complete league returns NO_GAMES_LEFT;
  - no team returns NO_TEAM;
  - a SUS `player.status` is carried.
  - Log: Deviations: (1) `PlayerStatus` has a 4th field `out` (boolean = `LeagueAnalysisService.isUnavailable || rules.isSuspended`) so the client words Out vs Listed without restating the rule (F4). (2) A pre-draft URL league is NOT gated: the team's real next game doesn't depend on the league's draft and the contract has no reason code for it; only a complete league gives NO_GAMES_LEFT (status/nextGame/upcoming then null/null/[]). (3) `PlayerStatsService` now takes `SportScheduleRepository`, `StatusCaptureRepository` and the app `Clock`. F1 test `anNbaLeagueWithZeroBoxScoresForItsSeasonStillReturnsANextGame` also verifies the 2025 schedule is never read.
- [x] T011 [US2] Header in `W/pages/PlayerPage.tsx`:
  - Owner (`OwnershipMark`).
  - Status: "Out" / "Listed: Questionable" / "SUS", with "as of <time>", **printed from asOf, never "7 AM"**. "Status unknown" when asOf is null.
  - Next game: "Wed Oct 21 · vs OKC" or "@ OKC", or the reason sentence.
  - `Ranks.positionRank` ("#2 C").

  It must fit without scrolling at 1366×900 and 375×812 (SC-003; add CSS in the page's existing stylesheet).
  - Log: Done. Status/next-game/position-rank live in a `dl.pp-facts` row under the owner list; times printed in America/New_York with the zone name (`statusAsOfLabel`, `injuryText`, `gameDay`, `opponentText`, `nextGameReasonSentence` in statCopy.ts). SC-003 fit at 1366x900 / 375x812 is NOT verified in a browser (CSS wraps via auto-fit grid; owed to live verification).
- [x] T012 [US2] Split the page body into tabs in `W/pages/PlayerPage.tsx`: Overview (ranks card, fantasy summary), Stats (windows and percentile/advanced tables), Game log, and Schedule (`upcoming`). Use `?tab=overview|stats|log|schedule`, default overview; an unknown value falls back to the default. Use real `role="tablist"`/`tab`/`tabpanel`, with keyboard arrows. **No number may change** (SC-004). Tests in `W/pages/PlayerPage.test.tsx`: each existing assertion still passes after selecting its tab, `?tab=log` opens the log, and `?tab=zzz` falls back to the default.
  - Log: Done. Overview = Ranks + Breakdown cards, Stats = Season line + Advanced, Game log, Schedule (new `upcoming` card); 'Data refreshed' line stays below the tabs. Existing tests: 24 gained an `onTab('stats'|'log')` line (module helper that appends `?tab=`), one clicks the Overview tab; no assertion or number changed. Tab change uses `replace`, so Back doesn't walk tabs.

**Checkpoint**: US2 verifiable on its own.

## Phase 5: US3, lineup check (P3)

**Goal**: on league home, the viewer's live starters who are out, listed, empty, or (NBA) without a counted game in the week, each with eligible bench options. No verdicts.
**Independent test**: quickstart step 4.

- [x] T013 [US3] Create the pure rule `B/engine/LineupCheck.java`. Inputs: starters (ordered ids), starting slots, roster players/reserve/taxi, `Map<id, Player>`, rules, a has-game function or null when `gameCheck` is false, and `Map<id, FpAvg(fp, season, games)>`. Output: the `LineupCheck` records in data-model:
  - reasons in order "EMPTY_SLOT, OUT, LISTED, NO_GAME_IN_WEEK", one per starter;
  - OUT = `isUnavailable(injuryStatus) || rules.isSuspended(player)`;
  - LISTED = any other non-null `injury_status`;
  - `"0"` = EMPTY_SLOT;
  - options = `players − starters − reserve − taxi`, `rules.isEligible(p, slot)`, has a counted game, and "Options never include a player who is himself flagged";
  - `optionsOrder` is `FP_PER_GAME` (NBA, desc), `NAME` (football), or `SLOT_UNSUPPORTED` when `isEligible` can't know the slot.

  Tests in `BT/engine/LineupCheckTest.java`, which **must include an ordering assertion** (lessons class 1): a higher fp/g bench player sorts before a lower one. Also: a SUS NBA starter with null injury_status is OUT, a SUPER_FLEX slot gives SLOT_UNSUPPORTED, taxi is excluded, an empty slot is flagged, and no flags means an empty list.
  - Log: Done in `engine/LineupCheck.java` (the payload records and the pure `flags(...)` in one file, like PlayerOwnership). Deviations: (1) the has-game input is a small `Schedule` interface (`hasGameInWeek`, `gamesLeftInWeek`) because `BenchOption.gamesLeft` needs a count, not only a boolean; null still means no game check. (2) SLOT_UNSUPPORTED is detected by probing `rules.isEligible` with one stand-in player per `Position` (a slot name it has no rule for answers false for all), not from the roster, so a roster with no C does not make `C` look unsupported. (3) FP_PER_GAME only when the sport plays several games per period AND some average exists; otherwise NAME, so the label never overstates. (4) Unknown player row on a starter is not flagged (no reason code exists). `LineupCheckTest` 14/14, includes the ordering assertion (31.4 before 18.0 before no-average, against alphabetical order).
- [x] T014 [US3] Create the loader `B/engine/LineupCheckService.java`:
  - Find the viewer's roster by **NextMatchupService's rule**: caller manager id via `ManagerRepository.caller`, then the lowest roster id (F8).
  - Read live rosters through `LeagueRosterSource.rosters(league)` and take that roster's `starters`.
  - Starting slots come from `league.rosterPositions()` minus `NON_STARTING_SLOTS`.
  - Load players with `players.byIds`, not findAll.
  - Week is the league's current leg.
  - gameCheck is true only when `sport_schedule` has rows for the sport/season (T001b).
  - fp/g comes from **one** `PlayerStatsService.readLeaderboard` call (F10), NBA only.
  - Gate: complete or pre-draft gives `NOT_IN_SEASON` (not the regular-season stop, F12).
  - A failed Sleeper call is caught and logged and returns `ROSTERS_FAILED`.

  Test it in `BT/engine/LineupCheckServiceTest.java` with mocks: the two-roster manager picks the lowest id, a rosters failure gives ROSTERS_FAILED, and a playoff week is still checked.
  - Log: Done in `engine/LineupCheckService.java`. "Mine" reuses a new package-visible `NextMatchupService.myRosterId` extracted from its loop (NextMatchupService now calls it; its 16 tests unchanged and green). Gate: complete, pre_draft or drafting -> NOT_IN_SEASON; the playoff format is never read (test verifies). The leaderboard read is lazy: a first pass without averages, and the one `readLeaderboard` call happens only if some flag has options (NBA only); football never touches it. Anonymous or no roster -> NOT_MEMBER before any Sleeper call. `LineupCheckServiceTest` 8/8.
- [x] T015 [US3] Create `B/api/LineupCheckController.java`: `GET /api/leagues/{sleeperLeagueId}/lineup-check`, with `X-Sleeper-User`, through `membership.visibleLeague`. Empty gives **404** (F7). Add a `lineupCheck` key to `LeagueAvailability` ROUTES/FIXED (`B/engine/LeagueAvailability.java:~63-87`) so ESPN leagues are gated by `PlatformGuard` like the other blocks (F12). Mirror `LineupCheck`/`LineupFlag`/`BenchOption` in `W/api.ts` and add `getLineupCheck`, in the same task. Test it in `BT/api/LineupCheckControllerMvcIT.java` (or the existing MVC test style): anonymous gets 404, a member gets 200, an ESPN league is gated.
  - Log: Done. `LineupCheckController` (404 via `visibleLeague`), `lineupCheck` key in `LeagueAvailability` ROUTES (`lineup-check`) and FIXED ("The lineup check is not available for ESPN leagues yet."), api.ts `LineupCheck`/`LineupFlag`/`BenchOption`/`getLineupCheck`. Tests: `LineupCheckControllerMvcIT` (3: anonymous/blank/stranger 404, member 200 with the contract shape, Sleeper failure 200 ROSTERS_FAILED; Sleeper stubbed), and in `LeagueAvailabilityIT` an explicit ESPN 422 test plus the existing generic ROUTES walk now covers `lineup-check`. ITs ran (Postgres up), 0 skipped.
- [x] T016 [US3] Create `W/components/LineupCheck.tsx`, a "Check your lineup" section:
  - one line per flag: slot, player, reason sentence, and status "as of <time>";
  - a "Bench options" list showing fp/g with "<season> avg · N games" and games this week as a fact;
  - "All starters are active" when there are no flags;
  - "No game check for football yet" once, when `gameCheck` is false;
  - if T001e found weekly locks, the line "Lineups lock for the week. These options apply next week."

  The copy never says start/sit/add/drop or "recommended" (SC-006). Test it in `W/components/LineupCheck.test.tsx`, including a test that renders every reason and asserts that none of those verbs appear.
  - Log: Done in `components/LineupCheck.tsx` + `.lc-*` CSS. Deviations: the football line reads "No game check for football yet." only for sport nfl; for basketball with no schedule it says the schedule isn't loaded. The lock line is absent (t001 (e) not established) and a test asserts no "lock" text. SC-006 test renders every reason x with/without options x unsupported slot x both sports x gameCheck x with/without as-of and asserts no start/sit/add/drop/recommend*/should/must (word-bounded, so "starters" is fine). `LineupCheck.test.tsx` 12/12.
- [x] T017 [US3] Wire it into `W/pages/LeagueHome.tsx`. Use `useBlock` keyed on `version` like its neighbours (`:164-185`), gate it on `allow('lineupCheck')`, and show it only for a signed-in member. When the lineup check is available, **it replaces `idleStartersNote`** (`:683-706`). The note renders only when the check isn't available (F5b). Update `W/pages/LeagueHome.test.tsx` for both cases.

  - Log: Done. `useBlock` keyed on `[id, version, okLineup, viewerIsMember]`, gated on `allow('lineupCheck')` and a standings row with `isMe`; `idleStartersNote` is passed only when the live check is NOT available (ROSTERS_FAILED and not-applicable keep it). Also added `['lineupCheck','Lineup check']` to `BLOCK_LABELS` so an ESPN league gets the one-line note. `LeagueHome.test.tsx`: default spy returns NOT_IN_SEASON (all prior assertions untouched); 4 new tests (replaces the note, fallback on ROSTERS_FAILED, note kept when not applicable, non-member never asks) and the ESPN test now asserts no fetch plus the sentence.
**Checkpoint**: US3 verifiable on its own.

## Phase 6: Polish

- [ ] T018 Run `cd backend && ./gradlew test`. Report pass/fail **and the skipped count**. With Postgres down, the ITs skip silently (memory).
- [ ] T019 Run `cd web && npx tsc -b && npm run build && npx vitest run`, and report the results.
- [ ] T020 Update `HANDOFF.md` with a 042 status line and the open follow-up: the second status refresh needs the `DailyRefreshService.players` skip-if-today gate changed (research R4 / F2). Write durable lessons to `claude/lessons.md` only if one was found.

## Dependencies

- T001 → everything (T001b decides gameCheck, T001c gates slot labels, T001e decides the lock copy).
- T002, T003 and T004 are in parallel, and come after T001.
- US1 (T005–T009) needs no foundational task. It can start right after T001.
- US2 (T010–T012) needs T003 and T004.
- US3 (T013–T017) needs T002, T003, T004, and T010's PlayerStatus shape is reused.
- Within a story, tasks run in order except where marked [P].
- `W/api.ts` is touched by T005, T010 and T015. Run those in sequence, never two at once.

## Parallel examples

- After T001: T002 ∥ T003 ∥ T004 ∥ T005.
- US1: T006 (new file) ∥ T005 (backend). T007 waits for both.

## Implementation strategy

MVP is US1 (T001, T005–T009): small and nearly all frontend. Then US2, then US3, each live-verified per
quickstart before the next is called done. Coding subagents run on Sonnet (AGENTS.md). The parent reads
every diff before reporting a task as done.
