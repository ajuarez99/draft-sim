# Tasks: Hall of fame (trophy case and discovered rivalries)

**Input**: `specs/047-hall-of-fame/`: spec.md, plan.md (**the "Amended after review" items A1–A16
override its Design section**), research.md (R2 amended), data-model.md (amended header wins),
contracts/api.md, quickstart.md, plan-review.md.

**Tests**: required. The spec's success criteria SC-001..SC-003 are test assertions, and plan A12
lists the ordering tests (lessons #1).

**Paths**:
- `B` = `backend/src/main/java/com/ballknowers/draftsim`
- `BT` = `backend/src/test/java/com/ballknowers/draftsim`
- `W` = `web/src`

## Phase 1: Setup and baseline

- [x] T001 Create a scratch DB as a copy of 5433 (docker `draftsim-pg-047` on a free port, e.g.
  5437). Record its port in `specs/047-hall-of-fame/baseline/README.md`. Run bootRun **from main
  `ebf4ee8` code** against it, with the poller and daily refresh off (plan A16).
- [x] T002 Capture the baseline JSON into `specs/047-hall-of-fame/baseline/before/`:
  - `/api/leagues/{id}/rivalries` and `/api/leagues/{id}/history` for "(Foot) Ball Knowers" 2026,
    "Ball Knowers" NBA 2026 and West Coast 2026;
  - `/api/managers/{id}/history` for managers 7, 23 and 55.
  Use the same `X-Sleeper-User` per call, and write the script to `scripts/047/capture.sh`.

## Phase 2: Foundational (blocks every story)

- [x] T003 Create `B/engine/ChampionRule.java` with `public static boolean isChampion(Integer
  finalPlacement, boolean seasonComplete)` (`finalPlacement != null && finalPlacement == 1 &&
  seasonComplete`). Replace the inline checks at `B/api/LeagueHistoryController.java:318` and
  `B/engine/ManagerCareerService.java:203` with calls to it. Keep the careers `counted` gate at
  `:185` (plan A10). Add `BT/engine/ChampionRuleTest.java` covering null, 1, 2, and incomplete.
- [x] T004 Refactor `B/engine/RivalryService.java` with **no output change**:
  - extract the chain loop (`:224-257`: formats, brackets, `pairedWithScores` + `classify`,
    `managersOfScoredLeague`, `byPair`) into a package-visible `ChainGames chainGames(List<LeagueRow>
    chain)`;
  - make `Game`, `PairKey`, `PairCalc` and `pairCalc` package-visible so `HallOfFameService` (same
    package) can call `pairCalc` for any pair of the chain;
  - `forLeague` calls `chainGames` and still restricts the matrix to current managers.
  All existing `BT/engine/Rivalry*Test`/`IT` must pass unchanged.
- [x] T005 Add migration `backend/src/main/resources/db/migration/V40__league_losers_bracket_match.sql`:
  - table `league_losers_bracket_match` with **exactly** V33's columns and PK: `league_id bigint not
    null references league(id) on delete cascade, match_id int not null, round int not null,
    t1_roster_id int, t2_roster_id int, winner_roster_id int, loser_roster_id int, placement int,
    primary key (league_id, match_id)`;
  - a header comment citing research R2 (amended).
  Re-check first that V40 is unused on `origin/main`.
- [x] T006 Add `losersBracket(String leagueId)` to `B/ingest/SleeperClient.java`, mirroring
  `winnersBracket` at `:85` (`/league/{id}/losers_bracket`). Add a `LeagueLosersBracketRepository` in
  `B/store/`, mirroring `LeagueBracketRepository` (`replaceForLeague`, `forLeagues`, `count`,
  `isComplete`) over the new table.
- [x] T007 Extend `B/ingest/BracketIngestService.ingestSeason` so its one write path also fetches and
  stores the losers bracket, with the same null/empty "leave stored rows alone" rule and the same
  `force`/`isComplete` skip. `backfillChain` therefore covers both brackets. Never touch `/matchups`
  (keep the class javadoc's guarantee). Update `IngestController`'s result if it reports counts.
  Add/extend the unit test with a stub Sleeper client.
  > **Amended during build (2026-10-24):** `BracketIngestService.Result` gains `losersMatches` and `SeasonResult` gains
  > a trailing `losersMatches` (so `/api/ingest/brackets/{id}` reports both counts). The two brackets skip
  > independently: a season stored before V40 has a complete winners bracket and no losers bracket, and
  > `ingestIfMissing` / `ingestSeason` fetch exactly the missing half instead of treating "winners complete" as "done".
  > `ingestSeason`'s `skipped` is true only when both halves were left alone.

**Checkpoint**: the suite is green, and the T002 captures re-run identical (only the code moved).

## Phase 3: User Story 1, the trophy case (P1) 🎯 MVP

**Goal**: one row per completed season with champion, runner-up, points leader(s) and last place (or
the reason it's refused).
**Independent test**: SC-001, champion = 🏆 row for all 4 complete local seasons.

- [x] T008 [P] [US1] Create `B/engine/LastPlaceRule.java`, a pure function. Inputs:
  - the losers-bracket matches;
  - the season's `PlayoffFormat`;
  - `total_rosters`;
  - the non-playoff roster set;
  - a lookup of stored week points by (week, roster).
  > **Amended during build (2026-10-24):** one reason is added to the list, `NO_SCORES` (seven reasons in
  > total: the five specified, `NO_WINNERS_BRACKET` from analyze U1, and this one): when no match has stored points on both sides, the
  > direction can't be read, and none of the specified reasons describes that. It is in the `api.ts` union and has its
  > own refusal text and test. Rule order is: `NO_LOSERS_BRACKET`, `NO_WINNERS_BRACKET`, `INCOMPLETE`,
  > `UNCOVERED_ROSTERS`, direction (`UNSUPPORTED_SHAPE` for an unmappable round type, `NO_SCORES`,
  > `MIXED_DIRECTION`), then the shape arithmetic (`UNSUPPORTED_SHAPE`). The roster set of rule 2 is the union of
  > every non-null `t1`, `t2`, `w`, `l` in the bracket, because a late-round slot can be a pointer.

  Output: `Result(Integer rosterId, String bracket /*TOILET_BOWL|CONSOLATION*/, String reason
  /*NO_LOSERS_BRACKET|INCOMPLETE|MIXED_DIRECTION|UNCOVERED_ROSTERS|UNSUPPORTED_SHAPE*/)`, with
  exactly one of rosterId or reason non-null. Rules are verbatim from research R2 (amended):
  1. Every match is decided.
  2. The rosters are exactly the non-playoff rosters.
  3. Every match whose weeks (from `RivalryGameKind.roundOfWeek` applied to the losers bracket,
     two-week finals summed) have stored points for both sides agrees on direction: `w` lower →
     toilet bowl, `w` higher → consolation.
  4. Shape: consolation needs `playoff_teams + max(p) + 1 == total_rosters`, and a toilet bowl needs
     a p=1 match.

  Then toilet bowl → p=1 `w`, and consolation → the max-p match's `l`.
- [x] T009 [P] [US1] Create `BT/engine/LastPlaceRuleTest.java`:
  - one test per refusal reason;
  - a toilet-bowl and a consolation success;
  - fixtures from the four measured seasons (research R2 amended table): FBK 2025 → roster 2
    TOILET_BOWL, West Coast 2025 → CONSOLATION (the p=5 loser), NBA 2024 and 2025 → MIXED_DIRECTION.

  Use the bracket JSON measured from Sleeper (`/league/{id}/losers_bracket` for 1254190892974084096,
  1262506916429430784, 1229352720222134272, 1141438340626231296), saved under
  `BT/resources/fixtures/047/`, plus the matching week points.
- [x] T010 [US1] Create `B/engine/HallOfFameService.java` with `trophyCase(String sleeperLeagueId)`.
  The chain is `leagues.chainBySleeperId(id)`, **backward only** (plan A5). For each `complete()`
  league, newest first, build `TrophySeason`:
  - **champion:** the `ChampionRule` roster;
  - **runner-up:** the loser of the V33 `placement == 1` match. `runnerUpReason` is `NO_BRACKET`
    when no p=1 match exists, `NO_LOSER` when it's undecided, and `SOURCES_DISAGREE` when the p=1
    winner ≠ the champion roster (then the runner-up is null; plan A6). A null champion means no
    runner-up;
  - **`finalDisputed`:** `Final.scoresAgreeWithWinner == false` from `pairCalc` for the p=1 match's
    two rosters' managers, that season (plan A7);
  - **`pointsLeaders`:** every roster tied at max `points_for`;
  - **last place:** via `LastPlaceRule`. The non-playoff roster set is the season's rosters minus
    every roster in that season's V33 winners bracket. With no winners bracket, `LastPlaceRule`
    returns the new reason `NO_WINNERS_BRACKET` (add it to T008's reason list and to the api.ts
    union; analyze U1).
  > **Amended during build (2026-10-24):** `ManagerRef.name` is the manager's display name and `teamName` is that
  > season's team name (null when none); the UI shows `teamName ?? name`. `PointsLeader` is flattened (the same
  > fields plus `pointsFor`), as Jackson sends it. `sourcesDisagree` from the original data model is gone (plan A6
  > replaced it with `runnerUpReason: SOURCES_DISAGREE`).

  `ManagerRef{Long managerId /*nullable, plan A14*/, String name, String teamName, String avatarId,
  int rosterId}` uses team-name-first, as `RivalryService` does (`:276-283`). Records only, no
  `Map.of`. `TrophyCase{String sport, String status /*OK|NO_COMPLETED_SEASON*/, List<TrophySeason>
  seasons}`.
- [x] T011 [US1] Create `B/api/HallOfFameController.java` with `GET /api/leagues/{sleeperId}/trophy-case`,
  scoped exactly like `RivalryController.rivalries` (`membership.visibleLeague(...)` → 404).
- [x] T012 [US1] Create `BT/engine/HallOfFameServiceIT.java` (local Postgres, following
  `RivalryParityIT`'s setup). It asserts:
  - (SC-001) for every complete season of every real league, the trophy champion's rosterId equals
    the `champion: true` row of `/history`;
  - the runner-ups are NBA 2024 → 6, NBA 2025 → 7, West Coast 2025 → 3, and FBK 2025 →
    `NO_BRACKET` until backfilled;
  - the points leader equals max `points_for`, including West Coast 2025 → manager 23 (a former
    manager);
  - an in-progress season never appears;
  - (plan A10) for every manager, career `titles` = the count of trophy champion rows over counted
    seasons.

  Add a fixture case for `SOURCES_DISAGREE` (runner-up null, never equal to the champion).
- [x] T013 [P] [US1] In `W/api.ts`, add the types mirroring T010's records field-for-field (`TrophyCase`,
  `TrophySeason`, `ManagerRef` with `managerId: number | null`, `PointsLeader`, and the reason string
  unions). Add `getTrophyCase(sleeperLeagueId)`, which returns `null` on 404 (plan A11).
- [x] T014 [P] [US1] Move `finalDisagreeText` out of `W/pages/Rivalries.tsx` into `W/rivalryText.ts`,
  and import it back. No wording change.
- [x] T015 [US1] Create `W/components/TrophyCase.tsx` and mount it as a "Hall of fame" section in
  `W/pages/LeagueHistory.tsx`:
  - a table of seasons × (Champion, Runner-up, Points leader, Last place), with names linking to
    `/managers/:id/history` (no link when `managerId` is null);
  - the reason texts: runner-up NO_BRACKET/NO_LOSER "not available", SOURCES_DISAGREE "Sleeper's
    bracket and its season result name different champions; the season result is shown.";
  - last-place reasons in plain words, each saying "not reported";
  - `finalDisputed`: a note built with `finalDisagreeText` from `W/rivalryText.ts` (T014). It's one
    wording, the Rivalries page's (spec edge case; analyze I2 supersedes plan A7's new sentence);
  - NO_COMPLETED_SEASON "No season has finished yet.";
  - the section is hidden when the fetch returns null.

  Style it as a tinted table, not uniform cards (memory: design aesthetic), and make sure it works at
  375px.
- [x] T016 [US1] Add `W/components/TrophyCase.test.tsx` (vitest) covering each reason state, ties in
  points leaders, the null `managerId` (no link), and the hidden-on-404 case.

**Checkpoint**: US1 works alone on History.

## Phase 4: User Story 2, discovered rivalries (P2)

**Goal**: most-played, closest, most lopsided and playoff meetings above the matrix.
**Independent test**: SC-002 and SC-003 on the matrix's own pairs.

- [x] T017 [US2] In `B/engine/RivalryService.java`, add `Discovery discovery(Map<PairKey, PairCalc>
  calc, int minMeetings, List<SplitNote> splitNotes)`, built from `forLeague`'s matrix `calc` (current
  managers only; research R5). Add it to `Response` as `discovery`. Rules (research R9 + plan A2/A3):
  - Every list considers only `meetings >= N`.
  - **mostPlayed:** max meetings.
  - **closest:** min |aWins−bWins|, then more meetings.
  - **lopsided:** max |diff|, then higher winner share, shown only if that |diff| ≥ 2 **and** > the
    closest tier's |diff|.
  - **playoff:** pairs with `PairStats.playoffMeetings > 0`.
  > **Amended during build (2026-10-24):** (1) The `playoff` list has **no** meetings floor. The task text said
  > "every list considers only `meetings >= N`", but spec US2 defines it as "every pair that has met in the playoffs",
  > and its empty state ("no playoff meetings yet") would be false for a 2-meeting pair that met in a final. It is a
  > record of fact, not a claim about rivalry strength. `mostPlayed`, `closest` and `lopsided` keep the floor, and
  > the T019 floor assertion excludes `playoff`. (2) `Discovery.minMeetings` / `minMeetingsLabel` are nullable: when
  > the rivalry settings didn't load the lists are empty and N is null (the UI renders nothing), rather than inventing
  > an N. (3) `discovery(...)` is static so the ordering test needs no Spring.

  `DiscoveredPair` copies the `PairRecord` fields and adds `playoffMatches` (per match; plan A8).
  `playoffUnsplitSeasons` holds the seasons with a split note. Ties: return every tied entry.
  `minMeetingsLabel` = `MINIMUM_LABEL`.
- [x] T018 [P] [US2] Create `BT/engine/RivalryDiscoveryTest.java` with the plan A12 orderings:
  - lopsided: 4-0 above 3-1, and 3-0 above 5-2;
  - closest: 2-2 above 3-2, and 3-2 above 2-1;
  - a 1-meeting-only league → every list empty;
  - FBK's shape (7 × 2-1 plus one 2-2) → lopsided empty;
  - a two-week final counts `playoffMatches == 1`;
  - ties all returned.
- [x] T019 [US2] Extend `BT/engine/RivalryParityIT.java`: every `discovery` entry equals its
  `matrix.pairs` entry, and none has `meetings < N` (SC-002/003). Run it on one NFL and one NBA league.
- [x] T020 [P] [US2] In `W/api.ts`, add `RivalryDiscovery` and `DiscoveredPair`, plus
  `Rivalries.discovery?: RivalryDiscovery` (**optional**, with the comment "An older backend omits
  it"; plan A11).
- [x] T021 [US2] In `W/pages/Rivalries.tsx`, add a "Notable rivalries" block above the matrix:
  - four lists, each entry showing names, the record (`recordText`), "N met", the playoff match count
    where relevant, and a link to `/managers/:a/versus/:b`;
  - more than 3 tied entries render as a count with an expander ("16 pairs are level at 2-2");
  - the empty states "no pair has met {N} times yet ({N} is hand-set)", "no pair is one-sided yet",
    and for playoff "can't tell for {seasons}: the bracket isn't stored" when `playoffUnsplitSeasons`
    is non-empty, otherwise "no playoff meetings yet";
  - render nothing when `discovery` is undefined.
- [x] T022 [US2] Extend `W/pages/Rivalries.test.tsx` with each empty state, the expander, the absent
  `discovery`, and the versus link targets.

## Phase 5: User Story 3, nemesis and victim (P2)

**Goal**: rivals per chain on the manager page, scoped to the caller.
**Independent test**: the records equal the matrix cells, and there's no chain leak.

- [x] T023 [US3] In `B/engine/HallOfFameService.java`, add `rivals(long managerId, Set<Long>
  visibleLeagueIds /*null = admin, all*/)`:
  - Find the manager's seasons; keep only visible ones.
  - Per distinct chain, take the head = the manager's newest visible season, then
    `chainBySleeperId(head)` backward (plan A4: **no forward walk**), deduplicated by head sleeper id.
  - Run `chainGames`, then `pairCalc` for every opponent with a MET pair.
  - Orient the record **from the manager's side**: if the manager is the pair's `hi`, wins = `bWins`.
  - **Nemesis:** opponents with `meetings >= N` and losses > wins, lowest `(wins + ties/2)/meetings`,
    then more meetings, ties all. **Victim:** the mirror.
  - `former` = the opponent has no roster in the head season.
  - `ChainRivals{sport, leagueName, headSleeperLeagueId, status OK|NONE_QUALIFY, minMeetings,
    nemesis, victim}`, ordered by sport, then newest head season.
  > **Amended during build (2026-10-24):** `rivals()` returns `Optional` (empty = no such manager, so the controller
  > can 404 for an operator too, contracts C3). `status` is `NONE_QUALIFY` exactly when no opponent has met the
  > manager N times; under `OK` either of `nemesis` / `victim` can still be empty (a qualifying opponent exists but
  > none with a losing / winning record), and the UI says which. Orientation is a package-visible `orient(...)` so
  > the unit test can exercise the manager-is-the-higher-id case without a database.
- [x] T024 [US3] Add `GET /api/managers/{managerId}/rivals` to `B/api/HallOfFameController.java`:
  - 404 unless `membership.canSeeManager(caller, managerId)`;
  - pass `membership.leagueIdsFor(caller)` (or null for admin, matching how `LeagueMembership` treats
    admin; check `:136`) as the visible set.
- [x] T025 [P] [US3] Create `BT/engine/HallOfFameRivalsTest.java` (unit, synthetic games): the manager
  is `hi` in every pair, a 0-3 opponent ranks above 1-3, a 2-1 opponent is never a nemesis, the
  victim mirror holds, below N gives NONE_QUALIFY, and ties are all listed.
- [x] T026 [US3] Extend `BT/engine/HallOfFameServiceIT.java`:
  - for a current manager, every rival record equals that pair's matrix cell (oriented);
  - a former opponent appears with `former: true`;
  - **scope (plan A4):** a caller who shares only FBK with manager 7 gets exactly one chain (FBK),
    never West Coast or fantasy😍.
- [x] T027 [P] [US3] In `W/api.ts`, add `ManagerRivals`, `ChainRivals` and `RivalEntry` (mirroring the
  records), plus `getManagerRivals(managerId)`, which returns null on 404.
- [x] T028 [US3] In `W/pages/ManagerHistory.tsx`, add a "Rivals" sub-block per chain inside each
  sport's career block:
  - nemesis and victim with the record, "N met", a "former" tag and a versus link;
  - NONE_QUALIFY "No opponent has met them {N} times yet ({N} is hand-set).";
  - hidden when null.

  Add cases to `W/pages/ManagerHistory.test.tsx`.
- [x] T029 [P] [US3] Update the `playoffAppearances` unavailable reason in
  `B/engine/ManagerCareerService.java:126-128` to "the bracket is stored, but appearances aren't
  derived from it yet" (plan A15). Update any test asserting the old text.

## Phase 6: User Story 4, past award winners (P3)

- [x] T030 [US4] Extract the winner-line rendering from `W/pages/Superlatives.tsx` (`:209-360`) into
  `W/components/AwardWinners.tsx`, and use it from Superlatives with **no visual change**. Add a
  vitest per state (plan A9): player holders (JABARI_SMITH_JR), empty with `emptyReason`, tied
  holders, kind-level `available: false`, and response-level `available: false`.
- [x] T031 [US4] In `W/components/TrophyCase.tsx`, add a per-row "Awards" toggle:
  - it lazily calls the existing superlatives fetcher with that row's `sleeperLeagueId`, and caches
    the response per row;
  - if `requestedSeason != null`, or the response's league id ≠ the row's, it shows "Awards aren't
    available for this season." and renders none;
  - otherwise it renders the kinds with `AwardWinners`, and links to that season's Superlatives page.

  Add tests to `TrophyCase.test.tsx`.

## Phase 7: Polish and verification

- [x] T032 Run `cd backend && ./gradlew test` and check the skip count (memory: ITs skip silently
  when Postgres is down). Run `cd web && npx tsc -b && npx vitest run && npm run build`. Record the
  numbers in `specs/047-hall-of-fame/verification.md`.
- [x] T033 Re-run T002 against the same scratch DB with the 047 code, into `baseline/after/`. Diff:
  - only `rivalries.discovery` may be added;
  - `champion`/`titles` must be identical;
  - the `playoffAppearances` reason text changes (T029), as intended.
- [x] T034 Run the bracket backfill (`IngestController`'s bracket backfill endpoint, admin token) for
  each chain on the scratch DB. Confirm that FBK 2025 gains its winners and losers brackets, its
  runner-up appears, and its last place = roster 2. Confirm NBA shows MIXED_DIRECTION.
- [x] T035 Live UI check (Browser pane), at desktop and 375px, for one NFL and one NBA league:
  - History → Hall of fame, including expanding Awards on one season and comparing against
    `/superlatives`;
  - Rivalries → Notable rivalries;
  - manager 7's page as a caller in FBK only (one chain) and as admin.

  Write the results to `verification.md`.
- [x] T036 Update `HANDOFF.md` and `README.md`. Include the **post-deploy step**: bracket backfill
  per chain after V40 ships, never during a live draft.

## Dependencies

- Phase 1 → Phase 2 → stories.
- T004 blocks T010, T017 and T023. T005–T007 block T012 (the IT reads stored losers brackets) and
  T034. T008 is pure and needs neither.
- US1, US2 and US3 are independent after Phase 2. US4 needs T015 (the TrophyCase component).
- Each api.ts task (T013/T020/T027) ships with its Java record task, never later (hard rule).

## Parallel examples

- After T004: T008+T009 (LastPlaceRule), T018 (discovery test) and T025 (rivals test) are separate
  files.
- Web: T013, T014 and T020 in parallel, once their Java records exist.

## Implementation strategy

- **MVP:** Phases 1–3 (the trophy case).
- **Then:** US2 and US3, which share the extracted `pairCalc`, and US4 last.
- Coding runs on Sonnet subagents (AGENTS.md). The parent reads every diff.
