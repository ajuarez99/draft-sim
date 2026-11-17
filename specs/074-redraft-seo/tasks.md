# Tasks: 074 Rename Time Machine to Redraft and make it findable in search

**Input**: `specs/074-redraft-seo/` — spec.md, plan.md (its "Amended after review" section wins over anything above it), design.md (exact copy and classes), data-model.md, contracts/, research.md, plan-review.md.

**Tests**: required. This repo's bar: scoring/ranking changes need a preference-ordering test; SQL and JDBC binds are proven by running them against Postgres; a green suite with skipped ITs is not a pass (report the skip count).

**Format**: `[ID] [P?] [Story] Description`. [P] = different files from every other open task in its phase.

**Build waves** (who may run at once without touching the same files): Wave 1 = Phase 2 backend ∥ Phase 3 web serving and static pages. Wave 2 = Phase 4 (rename) then Phase 5 (guest and app pages). Wave 3 = Phase 6 (shared result).

---

## Phase 1: Setup and measurements

- [x] T001 Start Postgres 5433 if it is down (memory note "Restart local Postgres 5433"); record which historical seasons are imported locally for each sport (`select sport, count(*) from historical_season group by 1`) in `specs/074-redraft-seo/t001-local-data.md`. Do not touch the Postgres on 5432.
- [x] T002 Run the backend suite and the web suite on the untouched branch; record pass/fail/skip counts as the baseline in the same file.

---

## Phase 2: Backend (blocks every story that reads data)

**Identity, migration, guests, chosen seasons**

- [x] T003 `backend/src/main/resources/db/migration/V50__redraft_guest_and_chosen_season.sql`: add `chosen_season boolean not null default false` to `historical_game`; comment that `sleeper_user_id` now also holds `guest:<uuid>`. Re-check no branch holds a V50 (`git branch -a`, sibling worktrees) before writing.
- [x] T004 `historical/Identity.java` + `IdentityTest`: resolve(sleeperUser, guestId) → owner key; Sleeper id wins; `guest:`-prefixed Sleeper header is 400; malformed UUID is 401; `isGuest(ownerKey)`.
- [x] T005 `config/WebConfig.java:72-73`: add `X-Guest-Id` to the CORS allowed headers; extend the preflight test at `HistoricalGameMvcIT:130` to send it (PR-03).
- [x] T006 `HistoricalGameRepository`: `chosen_season` in create/read/summary rows; `countNonDaily(ownerKey)` (any status); claim statements (lock guest rows `for update`, move non-colliding, count the rest). Run the SQL against Postgres in an IT (bug classes 2 and 3).
- [x] T007 `HistoricalGameService`: explicit `chosenSeason` parameter on the private `create(...)` (false from `createDaily` and test overrides, PR-16); public create with `seasonStart` checks `findSeason` AND `firstOffered..lastOffered`; guest allowance (daily yes; chosen season only while `countNonDaily == 0`; random season refused) throwing a typed `GuestLimitException`; `claim(guestKey, sleeperId)` in one transaction, a unique-index race answered as a normal result (PR-18); `publicGame(id)` returning COMPLETE games only, reduced while `daily_date` is today in `HistoricalDailyService.ZONE` (PR-07).
- [x] T008 `HistoricalGameState`, `GameSummary`: add `chosenSeason`. `HistoricalGameController`: use `Identity`; `CreateRequest.seasonStart`; `POST /api/history/claim`; map `GuestLimitException` to 403 with `code: GUEST_LIMIT` in the project's existing error body (read which of the two shapes this controller uses, PR-25).
- [x] T009 `HistoricalAchievementService` + controller: `settle` skips chosen-season games; `award` answers a chosen-season game with `notCounted: true` (new `AwardView` field); guests get 403 `GUEST_LIMIT` (PR-17). Check every other aggregate of a user's games (profile totals, draft-score records) and exclude chosen-season games; list each place checked in the task's completion note.
  - *Completion note (aggregates of a user's games checked, all server-side; the web's own aggregates are the web builder's):* (1) `HistoricalAchievementRepository.unsettled` now has `and not g.chosen_season`: this is the only road into settle, so no award row, XP, badge or `totals` row can exist for a chosen-season game. (2) `HistoricalAchievementRepository.totals` and `totalXp`: read award rows and badge rows only, so chosen games are out by construction (checked, unchanged). (3) `AchievementRules.cumulative(CareerTotals)` is fed from `totals` (same). (4) `HistoricalGameRepository.completedDailyDates` and `DailyStreak`: filter `daily_date is not null`, and V50 forbids chosen with daily, so a chosen game can never count toward a streak (checked, unchanged). (5) `HistoricalGameRepository.draftScore` and `summaries`: per game, not an aggregate; `GameSummary.chosenSeason` is the flag the web's landing stats, best score and average score must read to leave these out (web work, T038). (6) `HistoricalDailyService.view`: one daily game, not an aggregate. (7) `HistoricalAchievementService.profile/award`: `award` answers `notCounted: true`; a guest gets 403 `GUEST_LIMIT` on all three achievements routes. No other query reads `historical_game` (grep of `src/main`).
- [x] T010 ITs in `HistoricalGameMvcIT` (or a new `RedraftGuestMvcIT`): the five steps of quickstart §2; a guest cannot read another owner's drafting game; an abandoned chosen game still spends the allowance; claim when the Sleeper id already holds that day's daily leaves it behind and counts it; two concurrent claims never 500.

**Season summaries**

- [x] T011 `HistoricalBoardBuilder`: return the ranked count with the cards (small result record; existing callers use `.cards()`), PR-12. Existing board tests stay green unchanged.
- [x] T012 `historical/SeasonPage.java` (records per plan "Data model, amended after review") and `historical/SeasonSummaryService.java`: board at seed 0, `scorePlayerWeeks`, finish rank (1 + strictly more), going-in rank among ranked, busts, steals, `unrankedLeftOut` (unranked finishing in top teams × 5), `topByPosition` by base position, dream roster via `Comparisons.greedy` with nothing taken (make it package-visible; no fallback algorithm, PR-14), `pointsNote` per sport, `excludedStats`/`statCoverage` as the game reads them. Maps for JSON are `LinkedHashMap`; `team` and `attribution` may be null.
  - *Completion note:* `dreamRosterPoints` is the best-ball team total from `TeamScorer.season` of the roster `Comparisons.greedy` found (not the sum of its players' points). `teams x 5`, top 12, top 5 per position and 5 movers are hand-set and labelled in `SeasonSummaryService`; the guest allowance (1) is `historical.guest-chosen-seasons`, labelled a product rule in `HistoricalProperties` and `application.yml`.
- [x] T013 `SeasonSummaryServiceTest` (pure, no Spring): **ordering tests** — a player ranked 2nd going in who finished 150th is in busts and never in steals; a player ranked 200th who finished 3rd is in steals and never in busts; an unranked top finisher is in neither and is counted in `unrankedLeftOut`; equal points share a rank; lists are never padded; ties order by player id so two runs agree.
- [x] T014 Cache in `SeasonSummaryService`: per-key lock, single flight, no JDBC inside `computeIfAbsent` (PR-19); cleared when a historical import completes (find the import service's completion point).
- [x] T015 `api/RedraftPublicController` at `/api/history/public`: `GET /seasons?sport=` (wrapped `SeasonList` with `schema: 1`), `GET /seasons/{sport}/{slug}`, `GET /daily/yesterday` (read-only: never deals), `GET /games/{id}`. Reads no identity header. `Cache-Control` per contracts/public-api.md.
- [x] T016 `LeagueLockMappingsIT`: each new public route answers without a session when the lock is ON (PR-01). MVC ITs for 400/404 cases in contracts/public-api.md.
- [x] T017 Measure (not estimate): with the backend running against local data, time a cold `GET /seasons/NBA/<largest local season>` and a cold fetch of every local season in sequence; record wall time and heap before/after (`jcmd <pid> GC.heap_info`) in `specs/074-redraft-seo/t017-measurements.md`. Say which seasons existed locally.
  - *Completion note:* numbers in `t017-measurements.md`. NBA only (47 local seasons); no NFL data locally, so football was not measured.
- [x] T018 Check three seasons per sport that exist locally: each listed player's points on the summary equal the points a real game of that season reports for him (play or auto-finish a game; compare), SC-005 as amended. Record in the same file.
  - *Completion note:* three NBA seasons checked, 0 mismatches (`t017-measurements.md`). Football not run (no local NFL seasons).

**Checkpoint**: backend suite green with the skip count stated; quickstart §1 and §2 pass by curl.

---

## Phase 3: Web serving and static pages (US1, US4) — parallel with Phase 2

- [x] T019 [US4] `web/vite.config.ts` + `web/Dockerfile` + `web/.gitignore`: emit `serve.site.json` beside `dist` per site (timemachine: `cleanUrls`, `trailingSlash: false`, `directoryListing: false`, rewrites of `/game/**`, `/history`, `/history/**`, `/leagues/**`, `/drafts/**`, `/mock/**`, `/managers/**`, `/admin/**` to `/app-shell.html`; leagues and combined: one `**` rewrite to `/index.html`), carrying `serve.json`'s headers; Dockerfile runs `serve dist --config ../serve.site.json` with no `-s` and copies the file from the build stage; gitignore it (PR-11, PR-26).
- [x] T020 [P] [US1] `web/src/api.ts`: TS twins of `SeasonPage`, `Finisher`, `Mover`, `DreamPlayer`, `SeasonRef`, `SeasonList`, `YesterdayDaily`, `PublicGame`, `ClaimResult`; `chosenSeason` on game state and summary; `notCounted` on the award view; `code` on `ApiError`. Field-for-field with the Java records (constitution).
- [x] T021 [US1] `web/src/redraftCopy.ts`: every string in design.md, as functions of data where they take data. Add it to the banned-words guard (`leagueWords.ts` test) and the new "Time Machine" guard.
- [x] T022 [US1] `web/src/redraftPages.ts` + test: the indexable page list (`/`, `/daily`, `/nba`, `/nfl`, each season) with path, file, title, description, canonical, JSON-LD; titles and descriptions unique across the list (test); slashless canonicals.
- [x] T023 [US1] `web/src/seasonPageHtml.ts` (+ sport index, daily, home, 404 static bodies) rendering the trees in design.md from `redraftCopy`; tests that the static season body contains every finisher, bust, steal and dream-roster name from a fixture, the caveats, and the prev/next/index links as real `<a href>`.
- [x] T024 [US1] `web/scripts/fetchSeasons.ts` (in a tsconfig, PR-26): sequential fetch from `VITE_API_BASE`, 3-minute retry budget per request, `schema` check, field validation, fails naming URL and reason; `REDRAFT_SEASON_DATA=<file>` reads a fixture; logs which source was used. Fixture at `web/src/__fixtures__/redraft-seasons.json` (two seasons per sport, shaped from the real endpoint once Phase 2 can serve it; hand-shaped until then and replaced).
- [x] T025 [US1] `web/src/landingHtml.ts` + `vite.config.ts`: `transform(html, cfg, page)`; capture the built template in a `transformIndexHtml` `order: 'post'` hook; emit every page, `app-shell.html` (index copy with a static noindex meta) and `404.html` (noindex) from `closeBundle`; embed each season's JSON with `<` escaped; gate the fetch on `command === 'build'` and the timemachine site (PR-02, PR-20). Leagues and combined builds emit exactly what they do today.
- [x] T026 [US4] `web/src/siteFiles.ts` + tests: sitemap from the page list only; leagues still none; existing three-mode tests updated, not deleted.
- [x] T027 [US4] Build with the fixture and serve with the real command (`npx serve@14.2.6 dist --config ../serve.site.json`); curl every row of contracts/pages.md for the timemachine build and the rows in "League site" for a leagues build; record status and `Location` in `specs/074-redraft-seo/t027-serve.md`. Kill only the port you started.

**Checkpoint**: static site answers per contracts/pages.md from a fixture build.

---

## Phase 4: Rename (US2)

- [x] T028 [US2] Replace every reader-facing "Time Machine" with the Redraft wording in design.md: `tmLandingCopy.ts`, `historicalCopy.ts`, `historicalShare.ts`, `siteCopy.ts`, `App.tsx` titles, `TimeMachineShell.tsx`, `AccountMenu.tsx`, `NotFound.tsx`, `PageHeader.tsx`, `HistoricalRoomLayout.tsx`, `HistoricalProfileCard.tsx`, `SignInForm.tsx`, `analytics.ts` labels, and the rest of the 28 files `grep -ril "time machine" web/src` lists. File, class and CSS names stay.
- [x] T029 [US2] `historicalShare.ts` + test: title "Ball Knowers Redraft"; season and sport; "Chosen season" when set; link is the game's own address through `tmGame` (never a path literal).
- [x] T030 [US2] `web/scripts/og-image/og-image-tm.svg` reworded and re-rendered to `web/public/og-image-tm.png` with the existing `render.mjs`; look at the PNG before calling it done.
- [x] T031 [US2] New test `web/src/redraft.naming.test.ts`: no "Time Machine" (any case) in any string exported by the copy modules or in the static HTML of every page built from the fixture; every indexable title contains "Redraft" and a year or "past"/"Past". Update the tests that pinned the old name.

---

## Phase 5: Guests and app pages (US1, US3, US4)

- [x] T032 [US3] `web/src/guest.ts` + test (`bk.guest.v1`, made on first need, storage failures tolerated) and `api.ts` header funnel: `X-Sleeper-User` when signed in, else `X-Guest-Id`; `claimGuestGames()`; sign-in flow calls claim **before** any refetch, then drops the guest id (PR-18).
- [x] T033 [US3] `TimeMachineShell.tsx`: bar and `main` always render; guest sees `a.topbar-signin`; guest page views recorded under the route (PR-09). `SignInForm`: `autoFocus` prop, default true.
- [x] T034 [US3] `App.tsx` `TimeMachineTree`: routes `/`, `/daily`, `/nba`, `/nfl`, `/nba/:season`, `/nfl/:season`, `/game/:gameId` render with or without a user; per-page `document.title` from `redraftPages` (PR-08); `analytics.ts` route table and its test updated.
- [x] T035 [US1] `pages/SeasonPage.tsx` + test: embedded `#redraft-data` first, public endpoint on client navigation; spoiler `<details>`; "Draft this season" → create with `seasonStart`; guest states and the `.tm-limit` panel per design.md; unoffered season → not-found body.
- [x] T036 [P] [US1] `pages/SportIndex.tsx` + test: decade groups, newest tile.
- [x] T037 [US4] `pages/DailyPage.tsx` + test: reuses the home's daily cards (extract the component from `HistoricalDraft.tsx`), yesterday rows from the public endpoint, rollover copy.
- [x] T038 [US3] `pages/HistoricalDraft.tsx` (home) and `TimeMachineSignIn.tsx`: one home for guest and signed in per design.md §4; no "Draft a random year" for guests; `#sign-in` panel and hash scroll; claim line; chosen pill; `landingStats` ignores chosen-season games (+ test).
- [x] T039 [US3] Results page for the owner: award panel not mounted for a guest or a chosen-season game, `notCounted` copy; "Keep this result" panel for a guest.
- [x] T040 [US3] `web/src/styles.css`: the new classes named in design.md, inside the `.tm` scope, using existing tokens; reduced-motion rule for the fold chevron.
- [x] T041 [US3] Rewrite the pinned tests that encode "signed out means the front door at every URL" to the new rule, one by one, naming each in the completion note: at least `App.site.test.tsx`, `App.test.tsx`, `TimeMachineShell.test.tsx`, `tmSite.wording.test.tsx`, `landingHtml.sites.test.tsx`, `landingHtml.test.tsx`, `analytics.test.ts` (PR-21). The static-versus-React parity test excludes data-driven regions by an explicit marker attribute, not by loosening the comparison.

---

## Phase 6: Shared result (US3)

- [x] T042 [US3] `HistoricalResults.tsx`, `HistoricalBracket.tsx`, `HistoricalPickRail.tsx`: `voice: 'you' | 'shared'`; every second-person string gets its shared form in `redraftCopy`/`historicalCopy`; `.tm-drafter` instead of `--you` tints; no Share button; reduced view when `reducedUntil` is set.
- [x] T043 [US3] `pages/SharedResult.tsx` (or the room page's non-owner branch): owner endpoint first, public on 404/403, loading held until settled; noindex meta on mount; "No finished game here" for anything else; primary action starts today's daily.
- [x] T044 [US3] Test: render a fixture result in shared voice and assert the text matches no `\byou(r|rs)?\b`; assert the owner voice is unchanged against the existing fixture test.

---

## Phase 7: Docs and whole-thing checks

- [x] T045 `DEPLOY.md`: deploy order (backend, then web), the build's dependency on the backend and that a failed web build is safe to re-run, redeploy web after a historical import, the per-site serve config, V50. `HANDOFF.md`: state, what is verified and what is assumed, owed manual steps (research R8), open decisions for Allan, unclaimed guest games have no retention rule.
- [x] T046 `README.md` naming section and any reader-facing doc that calls the game Time Machine.
- [ ] T047 Full suites: `cd backend && ./gradlew test` (state the skip count), `cd web && npx tsc -b && npm run build && npm test`, and a timemachine build against the local backend (not the fixture).
- [x] T048 `claude/lessons.md`: the lessons this feature found (a `headers` rule in serve matches the rewritten file; a route added outside the lock's public list passes locally and 401s in production; a Vite plugin's `generateBundle` does not see `index.html`).

---

## Dependencies

- T003–T010 in order within their group; T011–T018 in order; the two groups share `HistoricalGameService` only at T007, so one builder owns Phase 2.
- Phase 3 needs only the contracts, not a running backend, until T024's fixture is replaced with real output.
- Phase 4 before Phase 5 (both edit `App.tsx`, the copy modules and the shell).
- Phase 6 after Phase 5.
- T027 and T047 are run by the session or the live-verifier, not assumed from a builder's report.

## Story coverage

- US1 (season pages): T011–T018, T020–T025, T035, T036.
- US2 (name): T028–T031.
- US3 (shared result, guests): T003–T010, T032–T034, T038–T044.
- US4 (daily, 404, sitemap): T015, T019, T026, T027, T037.

---

## Amended after analysis (2026-11-17)

A read-only cross-check of spec, plan and tasks found no critical issue and five high ones, all of one shape: the plan's amendment took a review finding, but a task pointed the builder at design.md, which did not carry it. **Each addition below is part of the task it names and is not optional.** Where design.md and this section differ, this section wins.

- **T009** also: tests — a finished chosen-season game that would win a title adds nothing to `ProfileView.totals`, XP, achievements or any best/average draft score; `award` for it answers `notCounted: true`; a guest gets 403 `GUEST_LIMIT`.
- **T010** also: a daily game is stored with `chosen_season = false` and still settles; `POST /games` with a season that is imported but outside `firstOffered..lastOffered` answers 400; a claimed chosen-season game still earns nothing after the claim.
- **T011** also: new test — for a football season with a rookie drafted after the configured rookie round, the ranked count excludes him and he sits in the tail (PR-12).
- **T012** also: `dreamRosterPoints` is the best-ball team total from `TeamScorer.season` (say so in the completion note). `Mover` and `Finisher` carry `weeksPlayed`. `teams × 5`, top 12 and top 5 are labelled hand-set in a comment; the guest allowance (1) lives in `HistoricalProperties`, labelled a product rule from Allan's answer.
- **T016** also: `GET /public/games/{id}` for a COMPLETE game whose `daily_date` is today returns `reducedUntil` and no picks, lineups, hindsight or callouts; the same game dated yesterday returns the full state and `reducedUntil: null`; no response carries an owner, Sleeper or guest id (FR-017a, PR-07).
- **T018** also: each `topByPosition` rank equals that player's `finishPositionRank` in the same game (SC-005).
- **T019**: not parallel with T025 (both edit `web/vite.config.ts`); the `serve.site.json` emission is done inside T025's plugin work, T019 keeps the Dockerfile and gitignore.
- **T021** copy amendments, which win over design.md:
  (a) dream sub-line: `A greedy pick of the best-scoring roster that fits the lineup, ignoring draft order. Nobody could have drafted all of it.` (PR-14); keep only the best-ball total line;
  (b) add to the going-in block: `Rookies are ranked by their draft pick, not by stats.` and `Only players who appeared in {label} are on the board, so a player who missed the whole season is not listed.`; each bust row gains `{n} weeks played` (FR-006b, PR-13);
  (c) reduced shared result: `The picks and lineups show tomorrow, once today's game is over.` (FR-017a);
  (d) landing: `No cookies. We keep a random id in this browser so your games stay yours.` (PR-27);
  (e) the sentence saying what "points" are comes from the server's `pointsNote`, not a second copy in TypeScript. The builder checks every scoring sentence against `EraScoring` and the sport classes, corrects the sentence if they differ, and names what was checked;
  (f) no string states a search volume, a ranking or a traffic gain (FR-022).
  The "Time Machine" guard is created in T031, not here.
- **T021a** (new, before T022): create `web/src/__fixtures__/redraft-seasons.json`: two seasons per sport, one an early NBA season with excluded stats, hand-shaped to the plan's "Data model, amended after review". T024 no longer creates it.
- **T023** tests also: each bust row shows weeks played; `pointsNote` appears outside any `<details>` and before the first points number; the rookie and missed-season sentences are present; the early-NBA fixture page has an excluded-stat line; static `/daily` and `/` contain no season label; no page says "hindsight".
- **T024** tests also: an empty season list, a missing field and a wrong `schema` each throw, naming the URL and the reason.
- **T027** expects: `/game/1` and the forwarded paths serve `app-shell.html` with `<meta name="robots" content="noindex">` (there is no `X-Robots-Tag`; contracts/pages.md is superseded on that row); `/assets` answers 404; `404.html` carries the noindex meta.
- **T031** also: `redraftCopy.ts` is in the guard; every indexable description, H1 and JSON-LD name carries a year or "past" (FR-002; the noindex 404 page is exempt, by name); "hindsight" appears in no season-page string (FR-006a).
- **T032**: the guest id is dropped only after claim answers 200; the guest header is sent only on `/api/history/**` requests.
- **T034** also: test — for a season page, a sport index and `/daily`, the mounted `document.title` equals the `redraftPages` title (PR-08).
- **T037**: not parallel with T038; the daily-cards component is extracted in T038 and T037 follows it.
- **T041** also: extend `tmSite.wording.test.tsx` to render the season page, sport index, daily page, shared result and not-found, static and React (FR-004).
- **T043** also: the noindex meta is removed on unmount.
- **T045**: the owed visit check is "visits to the season-page route as a whole" (PR-28).
- **T047** also: `grep -ril "time machine" web/dist` on the timemachine build is empty (SC-004); rebuild the fixture from the real `/api/history/public` output and re-run the web suite; diff every new or changed Java record against its `api.ts` twin field-for-field and list the pairs checked.
- **Phase 3 checkpoint**: the landing parity and `landingHtml*` tests may be red until T041. Say so; do not skip or delete them.
