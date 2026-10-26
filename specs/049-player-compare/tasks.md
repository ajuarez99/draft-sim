# Tasks: Compare two players (spec 049)

**Input**: `specs/049-player-compare/` — spec.md, plan.md (**its "Amended after review" A1–A15 win over the
design decisions above them**), research.md, data-model.md, contracts/api.md, contracts/ui.md, quickstart.md,
plan-review.md.

**Tests**: required. AGENTS.md bug class #1 demands a preference-ordering test for any marking rule, and the
spec's SC-006 is itself a test.

**Worktree**: `C:\Users\allan\source\draft-sim\.claude\worktrees\048-player-compare`, branch
`049-player-compare`. Never commit; commit is Allan's call. No migration (the highest is V40; add nothing).

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup

- [x] T001 Measure before building, and record the numbers in `specs/049-player-compare/t001-measurements.md`. Use a local backend from **this worktree** (`bootRun`; check the classpath is this worktree's) on Postgres 5433 (start it if it's down; see AGENTS.md "Which environment"). Measure:
  - the SEASON leaderboard (`GET /api/leagues/{nba2025}/stats?window=SEASON`) on the **full NBA 2025** season: raw bytes, gzipped bytes, cold ms and warm ms
  - one warm player page (`GET /api/leagues/{nba2026}/players/{id}`) in ms
  - two parallel warm player pages in ms

  If the warm leaderboard takes > 1 s, stop and report: plan A9 says the chooser then needs a slim endpoint. If Postgres or the ingest can't be brought up, write down exactly what was tried and mark every number "not measured". Do not estimate.

---

## Phase 2: Foundational (blocks every story)

- [x] T002 [P] Move the player page's formatters into a new shared module, `web/src/statFormat.ts`: the module-private `fixed`, `num`, `minutes` from `web/src/pages/PlayerPage.tsx:72-79`, plus the made-attempted text form that `RateValue` (`PlayerPage.tsx:88-104`) prints. Export them, import them back into `PlayerPage.tsx`, and change no output. The existing `web/src/pages/PlayerPage.test.tsx` must pass unchanged. (Plan A10.)
- [x] T003 [P] Make `LockInService.liveWeek` reachable from `PlayerStatsService`, which is in the same package. It is already package-private `static`, so confirm it and change nothing if it is. File: `backend/src/main/java/com/ballknowers/draftsim/engine/LockInService.java:475`. (Plan A3.)
- [x] T004 Backend: add `windowQualification` to `PlayerStatsService.PlayerStatsPage`. It is `Map<WindowKind, WindowQualification>`, with the new record `WindowQualification(boolean qualified, String reason, Integer lastN)`.
  - Build it from the `Computed.qualification` the server already computes (`PlayerStatsService.java:234-236`, `qualify` `:761-775`), with `lastN = kind.lastN()` for LAST_N and null for SEASON.
  - It is an empty map when `available == false`, through `unavailable(...)`.
  - Use a mutable `EnumMap`, never `Map.of`, which throws on a null reason (constitution).
  - File: `backend/src/main/java/com/ballknowers/draftsim/engine/PlayerStatsService.java`. (Plan A7.)
- [x] T005 Backend: add `ThisWeek(int week, int played, int remaining)` and the nullable `PlayerStatsPage.thisWeek`, computed beside `header()` under `header()`'s rule. That means the **URL** league (`requested`), never the resolved or fallback season (plan A12):
  - `week = LockInService.liveWeek(schedules.forSeason(sport, requested.season()), NextGames.today(clock), leg)`, where `leg` comes from `leagues.currentLeg(requested.id())`. Look at how `LockInService.java:449-453` reads it.
  - `played` = his box lines from `boxCache.get(sport, requested.season())` with `week == W`.
  - `remaining` = `NextGames.forTeam(schedule, team, today, Integer.MAX_VALUE)` filtered to `g.week() == W`, with `gameId` not among the played lines. This is lock-ins' counting (`LockInService.java:527-539`), with no date join.
  - Null when the URL league is complete, he has no team, there is neither a schedule nor a `leg`, or `NOT_BASKETBALL`. There is **no** fallback-season null.
  - File: `backend/src/main/java/com/ballknowers/draftsim/engine/PlayerStatsService.java`. Update every constructor call of `PlayerStatsPage`, including tests.
- [x] T006 Mirror T004 and T005 in `web/src/api.ts` in the same change (constitution).
  - Add the types `ThisWeek = { week: number; played: number; remaining: number }` and `WindowQualification = { qualified: boolean; reason: PlayerRanksReason | null; lastN: number | null }`.
  - Add `thisWeek?: ThisWeek | null` and `windowQualification?: Partial<Record<PlayerWindowKind, WindowQualification>>` to `PlayerStatsPage`, **optional** for deploy skew, with doc comments copied from contracts/api.md.
  - Update every fixture in `web/src` that builds a full `PlayerStatsPage` only if `tsc -b` demands it.
- [x] T007 Backend tests for T004/T005 in a new `backend/src/test/java/com/ballknowers/draftsim/engine/PlayerStatsThisWeekTest.java`, or in `PlayerStatsServiceTest.java` if its fixtures fit better:
  - **(a)** a Monday with no games, where `leg` is still last week: `week` is the next week's (liveWeek's gap rule)
  - **(b)** today's game finished but not ingested: counts as remaining; ingested: counts as played, never both
  - **(c)** the URL season has a schedule and no box scores yet (the fallback data season): `thisWeek` is non-null, with `played == 0` (A12)
  - **(d)** a complete league, and a player with no team: null
  - **(e)** `windowQualification`: a 3-game player has LAST_10 `{false, NOT_QUALIFIED, 10}`, and a full but stale LAST_5 has `NOT_QUALIFIED_STALE`
  - **(f)** `PlayerStatsControllerTest` / an MVC test: the JSON carries both fields, and `thisWeek` is `null`, not absent, when null

  Run `./gradlew test` and report the counts, **including the skipped count** (memory: ITs skip silently when Postgres is down).
- [x] T008 Add the `compare` destination to `web/src/destinations.ts`, with `'compare'` added to `DestinationKey`:
  - `sports: ['nba']`, `idKind: 'league'`, `inRail: false`
  - `match: /^\/leagues\/([^/]+)\/compare\/?$/`

  Run `web/src/destinations.test.ts`. Accept that a rail season switch drops `?a=&b=` (plan A8), and say so in a comment on the row.

**Checkpoint**: backend suite green with the skip count reported; `cd web && npx tsc -b` clean.

---

## Phase 3: User Story 1 + User Story 2 — the comparison and its marks (P1) 🎯 MVP

US1 and US2 are one increment: the marking rule *is* US2's guard.

**Independent test**: open `/leagues/{nba}/compare?a=X&b=Y`. Each figure equals the player page's response,
formatted by `statFormat.ts`. Only the ✓ rows of contracts/ui.md carry marks. A short, stale or small-sample window
names why it is withheld.

- [x] T009 [P] [US1] Write `web/src/compare.ts`, pure with no React. It has the row table from contracts/ui.md ("Rows, in order", ✓/— exactly as listed) and `markRow(row, a, b) → { mark: 'A' | 'B' | null, withheld: { A?: string; B?: string } }`. Follow data-model.md's guard list verbatim: "both pages are loaded; `season` and `dataAsOf` are equal; `direction != NONE`; both values are non-null (a `Rate` with a `reason` counts as null); the displayed strings differ; neither window is `smallSample`; for LAST_N, neither has `games < lastN` and neither has `reason == NOT_QUALIFIED_STALE`". Further rules:
  - `lastN` comes **only** from `windowQualification`, with no literal 10 or 5.
  - If `windowQualification` is undefined (an older backend), withhold every LAST_N row.
  - Turnover rate is `advanced.tovPct`, LOWER. FG%/3P%/FT%, TOV/g, PF, games and the season total are `NONE`.
  - The withheld text is "3 of 10 games", "last game N days before the newest", or "small sample (84 min)".
- [x] T010 [P] [US1] Write the preference-ordering tests in `web/src/compare.test.ts` (bug class #1). Each case must be checked to **fail** if the direction is flipped:
  - higher fp/g wins
  - lower tovPct wins
  - 12.04 vs 12.03 (displayed "12.0" and "12.0") is unmarked
  - a 1-of-1 3P% vs 38-of-100 is unmarked (F4)
  - TOV/g 0.6 vs 3.4 is unmarked (F5)
  - SEASON games 4 vs 3 is unmarked (F6)
  - a LAST_10 with 3 games is withheld as "3 of 10 games"
  - a stale LAST_5 is withheld
  - a smallSample SEASON window is withheld
  - a `Rate` with `reason` is unmarked
  - a `dataAsOf` mismatch withholds everything
  - a missing `windowQualification` withholds LAST_N only
- [x] T011 [US1] Write `web/src/pages/PlayerCompare.tsx`.
  - Read `a`/`b` from the query, and load each column with its own `useBlock(() => getPlayerStats(leagueId, id), [leagueId, id, dataVersion])`, where `dataVersion` is `useLeagueDataVersion(leagueId)` (A15).
  - **Columns**: `PlayerFace`, name, positions, team, and `OwnershipMark` (verbose) from the response's `ownership`.
  - **Rows**: in contracts/ui.md's order. The window switch (Season / Last 10 / Last 5) applies to the box-score and turnover-rate rows only. Shooting shows made-attempted beneath.
  - **Marks**: a tinted cell plus visually hidden "higher"/"lower" text. The intro and this-week note use contracts/ui.md's copy, word for word.
  - **Season line**: the player page's own `fallbackNote` on a fallback.
  - **Mismatch**: if `season` or `dataAsOf` differ, refetch the older column once. If they still differ, withhold all marks and print both as-ofs (A14).
  - **This week**: "1 played · 3 left", or "No game this week", with `scheduleAsOf`. If `thisWeek` is undefined or null, show no line.
  - **One column fails** (`notFound` / error): it says so, the other column renders, and nothing is marked (FR-012).
  - **Same player twice** (`a == b`): one column and the chooser. Before T015 the chooser is a plain "choose a player" placeholder.
  - **Football** *(amended at analyze, I1)*: decide it from the **first response's `sport`** (a player page, or, with neither `a` nor `b`, the chooser's SEASON leaderboard) through a new `comparePagesFor(sport)` in `web/src/destinations.ts`, written beside `playerPagesFor` and derived from the `compare` row's `sports`. When it is false, render the basketball-only line from contracts/ui.md and make no further request. The page has no league sport before its first fetch (`LeagueHome` fetches its `DraftSummary` too), so "no request at all" was not buildable.
  - Use `PageHeader` as the player page does.
- [x] T012 [US1] Add CSS for the comparison in the stylesheet that `PlayerPage` uses (find its `pp-` classes). Use a new `pc-` prefix and the existing tokens. Two value columns side by side at 375 px with no horizontal page scroll (FR-015). Long names wrap; they are never truncated to nothing.
- [x] T013 [US1] Add the route `/leagues/:sleeperLeagueId/compare` in `web/src/App.tsx` with `KeyedByLeague` and `gate="stats"`, next to the player route at `App.tsx:156-158`. Run `destinations.test.ts` again: it must pass with T008's row.
- [x] T014 [US1] Write `web/src/pages/PlayerCompare.test.tsx`, with fixtures that mirror real `PlayerStatsPage` responses. It covers:
  - (a) every displayed figure equals `statFormat`'s output on the fixture
  - (b) only ✓ rows can carry a mark
  - (c) **SC-006**: contracts/ui.md's three regexes over `container.textContent`, plus the two positive controls (the full page including the this-week note passes; a planted "Start Jokić" fails)
  - (d) one column 404s: the other renders and nothing is marked
  - (e) `a == b`
  - (f) football: the line shows after exactly one fetch, and no second column or chooser fetch follows (access is the reused player endpoint's own check, FR-014)
  - (g) "No game this week"
  - (h) a `dataAsOf` mismatch triggers exactly one refetch

**Checkpoint**: MVP. Run vitest for these files and `tsc -b`.

---

## Phase 4: User Story 3 — entry point, chooser, shareable address (P2)

**Independent test**: from a player page, Compare → type 3 letters → pick. The address carries both ids, and a new tab
shows the same comparison.

- [x] T015 [P] [US3] Write `web/src/components/PlayerChooser.tsx`.
  - Rows come from `fetchDraftStats(leagueId, 'SEASON')` (`web/src/draftStatsCache.ts`, the module-level promise cache, A9), loaded **only when the chooser is opened**.
  - Filter with `matchesSearch` / `fold` from `web/src/targets.ts`, with no new fold, and respect `SEARCH_MIN_CHARS`.
  - Skip rows with `name == null` (A11), and exclude the id already in the other column.
  - Each result shows name, team and `OwnershipMark`.
  - On a fallback board (`requestedSeason` set), show the same fallback note the leaderboard page shows.
  - Make it keyboard-usable (arrow keys + Enter), like the existing search inputs.
- [x] T016 [P] [US3] Write `web/src/components/PlayerChooser.test.tsx`. It covers:
  - an accent-insensitive match ("jokic" finds "Jokić")
  - a null-name row skipped
  - the other column's player excluded
  - no fetch until opened
  - the fallback note
- [x] T017 [US3] Wire the chooser into `PlayerCompare.tsx`. Picking a player writes only that side's param with `setSearchParams(..., { replace: true })`, and the other column doesn't refetch. Replace T011's placeholder. Extend `PlayerCompare.test.tsx`:
  - a swap keeps the other column
  - the URL updates
  - reloading with the same URL renders the same pair
- [x] T018 [US3] Add a "Compare" link to `web/src/pages/PlayerPage.tsx`'s `Header` card, beside the owner (A15; `PageHeader` unchanged), pointing to `/leagues/{id}/compare?a={playerId}`.
  - Render it only when `available` and `comparePagesFor(d.sport)` (A8, I1).
  - Add tests to `PlayerPage.test.tsx`: the link is present on a basketball page with the right href, and absent when unavailable.

---

## Phase 5: Polish & verification prep

- [x] T019 [P] Run the full web checks: `cd web && npx tsc -b && npm run build && npx vitest run`. Report pass/fail counts.
- [x] T020 [P] Run the full backend suite: `cd backend && ./gradlew test`. Report tests, failures and **skipped**. If the skip count rose, find out why before calling it green.
- [x] T021 Write an "Amended during build" section at the end of this file for anything the build found that contradicts the plan, with the reason. Do not silently diverge.

(The bug-hunting code review → `code-review.md` and live verification → `verification.md`, following quickstart.md
scenarios 1–11, are separate pipeline stages run by the parent session, not build tasks.)

---

## Dependencies

- T001 first. Its result can change T015's data source (A9 threshold).
- T002, T003 and T008 are parallel. T004 → T005 (same file) → T006 → T007.
- US1 (T009–T014) needs T002, T006 and T008. T009 ∥ T010, then T011 → T012/T013 → T014.
- US3 (T015–T018) needs T011. T015 ∥ T016, then T017, then T018 (T018 needs only T008 + T013).
- Polish last.

## Parallel examples

- Foundational: T002 (web formatters) ∥ T003 (backend visibility) ∥ T008 (destinations).
- US1: T009 (`compare.ts`) ∥ T010 (`compare.test.ts`), both written against data-model.md's guard list.
- US3: T015 (chooser) ∥ T016 (its tests).

## Implementation strategy

MVP = Phases 1–3: a comparison reachable by URL, with honest marks. Phase 4 makes it reachable from the player page
and shareable. The build runs on Sonnet (AGENTS.md); the parent session reads the diff before reporting it done.

---

## Amended during build (2026-10-26)

Where the code or the run contradicted the plan, what was done, and why. Nothing below changes what the feature
is; each is a how.

1. **T003 needed no change.** `LockInService.liveWeek` is already package-private `static` in the same package as
   `PlayerStatsService`. Confirmed, not edited.
2. **Column B waits for column A's first answer** (T011). I1's football rule says the line shows "after exactly
   one fetch", and T014(f) tests that no second column request follows. With both `a` and `b` set, parallel fetches
   would fire both before either answers, so B starts once A has answered (or failed), or at once when there is no
   A. The cost is one serial player-page call on basketball (about 70 ms warm locally, T001). Once B has started it
   stays started, so swapping A never drops B's data. If you would rather have the parallel pair and accept a
   wasted football request, it is the `bOn` expression in `PlayerCompare.tsx`.
3. **With neither `a` nor `b`, the page itself fetches the SEASON leaderboard on mount** to learn the sport (T011's
   "the chooser's SEASON leaderboard"). The chooser *component* still loads nothing until it is opened (T016 tests
   that in isolation); both go through `fetchDraftStats`, so opening the chooser afterwards is a cache hit. On a
   football league that response is the small `NOT_BASKETBALL` one.
4. **The stale-window text has a second form** (T009, A7). The server's stale rule is measured against the season's
   newest game, which the player-page response does not carry. The cell prints "last game N days before the newest"
   only when N, measured against the newest game either player in the pair shows, already exceeds the response's own
   `recencyDays`. Otherwise it prints "last game more than 14 days before the season's newest" (14 read from
   `qualification.recencyDays`), because a smaller N printed beside "stale" would contradict the rule.
5. **The mismatch note waits for the refetched response** (T011, A14). The first version showed the "different
   refreshes" note for one render between "retry started" and "loading" (a transient flash that a vitest run
   caught). It now shows only once the retried column's new response object has arrived.
6. **`PlayerChooser`'s load effect must not depend on its own `load.status`** (T015). The first version did, and its
   cleanup cancelled the very request it had just made, so the list never filled. Fixed; covered by T016/T017 tests.
7. **`destinations.test.ts` had one assertion that encoded the old state** ("`players` is the only row with
   `inRail: false`"). It now expects `['players', 'compare']`; that is the plan's own `compare` row (T008), not a
   behaviour change.
8. **Backend tests (T007) went into the existing `PlayerStatsServiceTest.java` and `PlayerStatsControllerTest.java`**
   rather than a new file, because they reuse that file's private `Wiring`/`row`/`schedule` helpers. (f) is a
   standalone-MockMvc test with the app's Jackson (`findAndAddModules`), not a full-context IT. It asserts a
   null `thisWeek` is present-and-null on the wire and an empty `windowQualification` is `{}`.
9. **`thisWeek` with a schedule but no `leg`** is non-null (the week comes from `liveWeek`'s first counted game on or
   after today). It is null only when there is neither, which is the contract's wording. `played` reads the box
   cache for the URL league's own season, a second `boxCache.get` that is a cache hit unless the page fell back.
10. **New test-support file `web/src/testPlayerStats.ts`** (a full `PlayerStatsPage` fixture), shared by
    `compare.test.ts` and `PlayerCompare.test.tsx`. It builds on `testStatBoard.ts`.
11. **Copy uses the contract's straight apostrophes** ("aren't", "points'") word for word. The rest of the app
    prints curly ones in its own copy; say if you want these matched.
12. **`statFormat.ts` also exports `DASH` and `MINUS`**, because `fixed`/`num` need them and `PlayerPage.tsx` still
    uses `DASH` in many places. No output changed (`PlayerPage.test.tsx` passes unchanged apart from the three new
    link tests).
13. **`npm ci` was needed in the worktree** (no `node_modules`). Not a plan change; noted for the next session.

### Flip test (T010)

Each direction was flipped in `compare.ts` in turn, the suite run, and the file restored byte for byte:
fantasy-points row `HIGHER`→`LOWER` (2 tests failed), turnover-rate row `LOWER`→`HIGHER` (1 failed), and the
comparison in `markRow` reversed (5 failed). All three were caught.

### Pre-existing failure, not ours

`HallOfFameServiceIT.nameLastPlaceOnlyWhenTheRuleCouldReadIt` fails ("winners bracket stored ... but no losers
bracket") in the full backend suite. It fails identically on an unmodified checkout of `ec72031` (run in a
throwaway detached worktree, since removed). It is the 047 post-deploy bracket backfill noted in HANDOFF, in the
shared 5433 database.
