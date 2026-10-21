# Tasks: Morning Tweet Digest (spec 043)

**Input**: `specs/043-tweet-digest-admin/` — spec.md, plan.md (its "Amended after review" section
overrides the body), research.md, data-model.md, contracts/admin-digest-api.md, quickstart.md,
plan-review.md (F1–F16).

**Tests**: included. This repo's bar (AGENTS.md, recurring bug class 1) needs a test that asserts
ordering for any ranking. The spec's SC-002 and SC-003 are testable as unit assertions.

Backend root: `backend/src/main/java/com/ballknowers/draftsim/` (abbreviated `B/`). Tests:
`backend/src/test/java/com/ballknowers/draftsim/` (`BT/`).

## Phase 1: Setup

- [x] T001 Re-list every worktree's and remote branch's `db/migration` directory and confirm this feature needs none (no V39 claimed). Record the result in `specs/043-tweet-digest-admin/tasks.md` under T001.
  - **Done 2026-10-20.** Listed every worktree's `db/migration` directory and the remote branches' trees (`git ls-tree` on each `origin/*` after a fetch). The highest version anywhere is `V38__conduct_entry_through_week.sql`; no branch, worktree or remote claims a V39. This feature adds no migration, so nothing collides.
- [x] T002 Confirm the local DB facts this build relies on, on 127.0.0.1:5433 with `PGPASSWORD=draftsim`: the `tpm` and `pass_int` keys, `pts_std` and `pts_ppr` present-or-absent (an absent field is 0), and the `sleeper_search_rank` newest `captured_on` per sport. Note the numbers under T002.
  - **Measured 2026-10-20** on 127.0.0.1:5433. `tpm` (NBA 3PM) is present in 35,253 `player_game` rows and `pass_int` in 749. NBA 2026 has 42 rows with `pts_std` and 1 without (the TEAM_ rows are included in those counts). NFL 2026 player rows (TEAM_ excluded): 2,097 with `pts_ppr`, 6,766 without; an absent field is 0 points (F1). Newest `sleeper_search_rank` capture: NBA 2026-10-20 (959 players), NFL 2026-10-20 (2,002 players). Seasons with rows: NBA 2024, 2025, 2026; NFL 2025, 2026. `sport_trending_fetch.league_season` is 2026 for both sports.

## Phase 2: Foundational (blocks all stories)

- [x] T003 Make `isComplete` package-private in `B/engine/PlayerSpotlightService.java` (F10). No behaviour change, and add no other edits to that file.
- [x] T004 [P] Add `List<Integer> seasonsOn(Sport sport, LocalDate date)` to `B/store/PlayerGameRepository.java` (distinct seasons with rows on that date, newest first). Follow the file's existing `JdbcClient` style.
- [x] T005 [P] Add a read of the top N players by the newest `sleeper_search_rank` snapshot for a sport. Return `(sleeperPlayerId, rank)` ordered by `adp` ascending. Put it in the repository that already owns `adp_snapshot` reads (`B/store/BoardRepository.java`) or a new `B/store/SearchRankRepository.java` if that file is the wrong owner. Join `player` on `adp_snapshot.player_id = player.id`.
- [x] T006 Create the response records in `B/engine/AdminDigestService.java`, exactly per `contracts/admin-digest-api.md` (as amended):
  - `AdminDigest(generatedAt, yesterday, sports)`
  - `SportDigest(sport, season, pointsBasis, status, statsFetchedAt, date, week, gamesCount, lastSlateDate, inProgressDate, nextGameDate, trending, topPerformers, keyPlayers, positionLeaders)`
  - `DigestEntry(playerId, name, position, team, opponent, isAway, points, line)`
  - `TrendingCard(status, lookbackHours, fetchedAt, stale, omittedUnknownPlayers, entries)`
  - `TrendingItem(rank, addCount, outcome, entry)`
  - `KeyEntry(rank, entry, played, expectation, expectationBasis, verdict)`

  `line` is a mutable `LinkedHashMap` of non-zero headline keys only: NBA `pts, reb, ast, stl, blk, tpm`; NFL `pass_yd, pass_td, pass_int, rush_yd, rush_td, rec, rec_yd, rec_td`. Hand-set constants are labelled arbitrary in comments: `KEY_PLAYER_COUNT=15`, `KEY_SEASON_MIN_GAMES=5`, `KEY_LAST_SEASON_MIN_GAMES=10`, `TOP_LIMIT=10`, the points map `{pts_std:1.0}` (NBA) / `{pts_ppr:1.0}` (NFL), and labels "Sleeper default points" / "Sleeper PPR".
- [x] T007 [P] Mirror every record from T006 field-for-field in `web/src/api.ts`, with nullable fields typed `| null`. Add `getAdminDigest(token: string, date?: string)` and `refreshDigestStats(token: string)`, both through the existing `apiFetch` with an `X-Admin-Token` header (F13). A 403 with `code: 'admin_token_required'` surfaces as a typed error the page can detect.

## Phase 3: User Story 1 — trending adds, how they did (P1) 🎯 MVP

**Goal**: an admin opens `/admin/digest`, sees a copyable "most-added and how they did" card for last night, and copies it.
**Independent test**: on a READY night, every PLAYED trending entry's points equal that game's Sleeper points. NO_GAME entries are left out of the tweet but counted. A played-zero line shows 0, not DNP.

- [x] T008 [US1] Implement night selection in `B/engine/AdminDigestService.java`, per sport (plan Design note 1 plus amendments F2 and F9):
  - Read `datesForSeason`, `sport_week_stats` and `sport_schedule.forSeason`, then call `PlayerSpotlightService.choosePeriod(true, …)` and `isComplete`.
  - Default mode, with `yesterday = NextGames.today(clock).minusDays(1)`:
    - READY if yesterday is complete.
    - NIGHT_INCOMPLETE if yesterday has rows but is not complete.
    - NOT_FETCHED if the schedule has games on yesterday and there are no rows.
    - Otherwise NO_GAMES_LAST_NIGHT.
  - `?date=` mode: the season comes from `seasonsOn`; with no rows on the date, NOT_FETCHED if the schedule lists games that date, otherwise NO_GAMES_ON_DATE.
  - Fill `statsFetchedAt` (from the covered or scheduled week), `lastSlateDate`, `inProgressDate` and `nextGameDate`. `nextGameDate` is the first schedule date after the reference day, with no season filter on the read. Use the current season, then the next season if the current one is exhausted.
  - With no current season known, return NO_SEASON.
- [x] T009 [US1] Score the covered night's rows with `PlayerSpotlightService.scoreAll(rows, POINTS_MAP, gameScoring, players.byIds(...))` (F1: an absent field scores 0). Exclude `TEAM_` ids; unresolved ids are already dropped by `scoreAll` (F4).
- [x] T010 [US1] Build the `TrendingCard` with `PlayerSpotlightService.trending(snapshot, period, new PeriodRows(rows, scored), Map.of(), byId, now)`. Map its entries to `TrendingItem`, using the PLAYED line from the scored row.
  - Status NEVER_FETCHED when there is no snapshot.
  - `?date=` mode gives `TRENDING_NOT_HISTORICAL` and no entries.
  - Default mode returns the card whenever a snapshot exists. With no READY night, pass `period = null`, so every outcome is NO_PERIOD (F16).
- [x] T011 [US1] Create `B/api/AdminDigestController.java`:
  - `GET /api/admin/digest` with an optional `date` param. A malformed date returns 400 `{"code":"bad_date"}`, built as a mutable map.
  - `POST /api/admin/digest/refresh-stats` runs `PlayerGameIngestService.refreshSportSeason(sport, currentSeason, clock.instant())` per sport. A per-sport failure is caught and reported in `error`.
  - Admin gating is already covered by `/api/admin/**` in `WebConfig` (do not add a second check).
- [x] T012 [P] [US1] Add `web/src/digest/tweetText.ts`, a pure module:
  - `weightedLength(text)`: NFC-normalize first. Code points in 0–4351, 8192–8205, 8208–8223 and 8242–8247 weigh 1. Each other grapheme weighs 2, and an emoji ZWJ/VS sequence counts as one grapheme of weight 2 (via `Intl.Segmenter`).
  - `formatLine(sport, line)`: NBA as `34 PTS/12 REB/8 AST/3 STL`; NFL as `300 PASS YD, 2 TD` / `112 REC YD, 9 REC, 1 TD`.
  - `fitToLimit(header, lines, limit=280)`: drops trailing lines until the text fits and returns `{text, shown, total}`.
  - `trendingCard(sportDigest)`: header `📈 Most added on Sleeper, and how they did last night:`. Rows read `{rank}. {name} (+{adds}) — {pts} FP ({line})` or `— DNP`. NO_GAME items are left out (F8). A footer count is returned separately and is not part of the text.
- [x] T013 [US1] Add `web/src/pages/AdminDigest.tsx` and register the route `/admin/digest` in `web/src/App.tsx`, with no nav link anywhere.
  - A token prompt stores `sessionStorage['bk.adminToken']`, with every access wrapped in try/catch. A 403 clears it and re-prompts.
  - Per sport, show a header with the covered date, week (NFL), status text for each non-READY status, the basis label, `statsFetchedAt`, and the trending window and fetch time.
  - The trending card has a Copy button (`navigator.clipboard.writeText`, falling back to a textarea with `execCommand('copy')`) and a "Copied" confirmation.
  - Add a "Fetch latest stats" button that calls `refreshDigestStats` and reloads.
  - Basketball first.
- [x] T014 [P] [US1] `BT/engine/AdminDigestServiceTest.java` (pure; feed rows, schedule, snapshot and a fixed clock):
  - (a) a played row with no `pts_std` scores 0 and its trending outcome is PLAYED, not DID_NOT_PLAY (F1);
  - (b) trending order is Sleeper's rank, and NO_GAME players are classified NO_GAME;
  - (c) default mode with yesterday scheduled and no rows gives NOT_FETCHED, never NO_GAMES_LAST_NIGHT (F2);
  - (d) yesterday unscheduled gives NO_GAMES_LAST_NIGHT with `nextGameDate` set and empty cards;
  - (e) `?date=` gives TRENDING_NOT_HISTORICAL;
  - (f) a TEAM_ id never appears.
- [x] T015 [P] [US1] `web/src/digest/tweetText.test.ts`:
  - weighted length of `Nikola Jokić` (decomposed and composed) is 12;
  - `🔥` counts 2, and a family ZWJ emoji counts 2;
  - `fitToLimit` never returns > 280 and reports shown/total;
  - the trending text leaves out NO_GAME entries and keeps true rank numbers;
  - a DNP renders `DNP`, and 0 points renders `0 FP`.
- [x] T016 [US1] `BT/api/AdminDigestControllerIT.java` against the real DB, using the test token `it-admin-token`: no token gives 403 `admin_token_required`; a token gives 200 with sports `[nba, nfl]` in order; `date=bad` gives 400 `bad_date`.

## Phase 4: User Story 2 — top performers and key players (P2)

**Goal**: copyable "top fantasy scores" and "how the stars did" cards, plus a single stat-line spotlight card.
**Independent test**: the top 10 equals SQL ordered by Sleeper points desc, then player id, over resolved non-TEAM players (NFL: QB/RB/WR/TE). Key-player expectations use only earlier games.

- [x] T017 [US2] In `AdminDigestService`, add `topPerformers`: scored rows (NFL filtered to QB, RB, WR, TE by `player.primary()`), sorted by points desc then player id, limit `TOP_LIMIT`.
- [x] T018 [US2] In `AdminDigestService`, add `keyPlayers`. Take the top `KEY_PLAYER_COUNT` from T005.
  - Include a player if he has a scored row that night. In default mode only, also include him if his current `player.team` is a home or away team in that night's `sport_schedule` games; he is then `played=false`, verdict DNP (F7).
  - The expectation comes from `forPlayers(sport, season, ids)` restricted to `game_date < covered date`. Use the THIS_SEASON mean if there are at least `KEY_SEASON_MIN_GAMES` games; else LAST_SEASON (season-1) if at least `KEY_LAST_SEASON_MIN_GAMES`; else NONE with verdict NO_BASELINE (F5/F6).
  - Verdict ABOVE when points ≥ expectation, otherwise BELOW.
  - Order by search rank.
- [x] T019 [P] [US2] Add `topPerformersCard`, `keyPlayersCard` and `spotlightCard` to `web/src/digest/tweetText.ts`:
  - Top performers: `🔥 Top fantasy scores last night:` then `{i}. {name} — {pts} FP ({line})`.
  - Key players: `⭐ How the stars did:` then `{name} — {pts} FP ✅|❌` or `— DNP`; NO_BASELINE shows no mark.
  - Spotlight: `{name} last night: {long line} — {pts} fantasy points.`
- [x] T020 [US2] Render the three cards in `web/src/pages/AdminDigest.tsx`, with the same Copy behaviour. Under each key player, show which expectation was used (this season's or last season's average, or "no baseline").
- [x] T021 [P] [US2] Extend `AdminDigestServiceTest`:
  - top performers are ordered by points desc with a player-id tie-break (an ordering assertion, AGENTS bug class 1);
  - the expectation excludes the covered night and later games;
  - fewer than 5 games this season falls back to last season;
  - a traded player on a `?date=` night is included only via his row;
  - an NFL K or DEF never appears in top performers.
- [x] T022 [P] [US2] Extend `tweetText.test.ts` for the three new cards, covering the ≤ 280 limit and the ✅/❌/DNP/no-mark rendering.

## Phase 5: User Story 3 — football mornings (P3)

**Goal**: NFL sections on mornings after Thursday, Sunday or Monday games, plus position leaders.
**Independent test**: `?date=2026-10-19` (MNF) returns NFL READY with the week taken from the rows. `?date=2026-10-20` returns NFL NO_GAMES_ON_DATE.

- [x] T023 [US3] In `AdminDigestService`, add `positionLeaders` (NFL only, null for NBA): the best scored row per QB, RB, WR and TE, skipping any position with no rows. Set `week` from the covered rows' own `week`.
- [x] T024 [P] [US3] Add `positionLeadersCard` to `tweetText.ts` (`🏈 Week {w} leaders: QB {name} {pts} · RB … · WR … · TE …`, with ≤ 280 enforced by shortening names to last name if needed), and render it in `AdminDigest.tsx`.
- [x] T025 [P] [US3] Tests: position leaders are picked per position and are null for NBA (`AdminDigestServiceTest`); the card fits within 280 (`tweetText.test.ts`).

## Phase 6: User Story 4 — edit before copying (P3)

- [x] T026 [US4] In `AdminDigest.tsx`, each card's text is an editable textarea seeded from the generated text, with a live `weightedLength` counter that turns red past 280. Copy copies the current text. Edits stay in component state only, and "Reset" or a reload restores the generated text (FR-013).
- [x] T027 [P] [US4] `web/src/pages/AdminDigest.test.tsx`:
  - the token prompt shows with no token;
  - the cards render from a fixture `AdminDigest`;
  - an edit updates the counter and Copy receives the edited text (mock clipboard);
  - a 403 clears the stored token;
  - a NOT_FETCHED status shows the fetch button and no cards.

## Phase 7: Polish & verification

- [x] T028 Run `cd backend && ./gradlew test`. Report the pass/fail/skip counts and check the skip count (memory: ITs skip silently when Postgres is down).
  - **Ran 2026-10-20 (builder pass):** `./gradlew test`: 2,132 tests, 1 failed, 6 skipped. The failure is `BoardRepositoryLatestBeforeIT.theDraftRowCarriesTheScheduledStartTimeFromBothQueries` (expected 2026-10-10T19:15:08Z, got 2026-10-10T21:16:19Z: shared-DB timestamp drift, a known pre-existing failure and unrelated to this change). The 6 skips are all `PlayerStatsLeaderboardReadIT` ("2026 has stored games now, so it did not fall back", a data assumption), not an unreachable Postgres. Digest tests: `AdminDigestServiceTest` 19/19, `AdminDigestControllerIT` 4/4, 0 skipped.
- [x] T029 Run `cd web && npx tsc -b && npm run build && npx vitest run`.
- [x] T030 Bug-hunting code review: write `specs/043-tweet-digest-admin/code-review.md`, then fix what it finds.
- [x] T031 Live verification per `quickstart.md` V1–V13 against this worktree's server (confirm the bootRun classpath is this worktree's). Write `specs/043-tweet-digest-admin/verification.md`, keeping "verified" and "not verified" apart. V11 (pasting into X's composer) is Allan's and is listed as owed.
- [x] T032 Update `HANDOFF.md` (top entry) and `README.md` (one short feature line) with what exists, where, and what is owed (V11; the daily-cron stats fetch follow-up from F2; whether `ADMIN_TOKEN` is set in prod).

## Dependencies

- Phase 1 comes first, then Phase 2 (T003–T007).
- US1 (T008–T016) is the MVP. US2 depends on T008/T009 (night and scoring). US3 depends on T017's filter. US4 depends on T013's page.
- T007 (TS types) must land in the same change as T006 (constitution: api.ts mirrors Java).

## Parallel opportunities

- T004, T005 and T007 can run in parallel (different files).
- T012, T014 and T015 can run in parallel once T006 is done.
- T019, T021 and T022 can run in parallel; so can T024 and T025.

## Implementation strategy

- Build the MVP (US1) end to end, through the controller and page, then layer in US2–US4.
- One Sonnet coding agent builds backend and web in sequence (AGENTS.md). The parent reads the diff.
