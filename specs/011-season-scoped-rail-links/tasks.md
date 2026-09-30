---

description: "Task list for season-scoped rail links"
---

# Tasks: Season links keep you on the page you were on

**Input**: `claude/season-scoped-rail-links.md` (design doc, planned 2026-09-29). The
design doc is both the spec and the plan, following the repo convention for
`claude/<topic>.md` features (AGENTS.md, "building anything feature-sized"). The
`spec.md` and `plan.md` beside this file only point to it, so the speckit tools can run.
User stories below are taken from the doc's reported bug and its Design sections A–C.

**Amended after `/speckit-analyze` (2026-09-30)**: doc amendments A1–A5 are applied
here. New tasks are T011a, T011b, T020b and T013's fallback rule. Changed tasks are T008,
T009 and T031. The new tasks use letter suffixes rather than renumbering, so IDs already
quoted elsewhere stay valid.

**Branch / worktree**: `011-season-scoped-rail-links`, cut from `origin/main` at `587fbc3`.
Local `main` was behind `origin/main` and did not contain the design doc.

**Reference check (2026-09-30, against `587fbc3`)**: the doc's file:line references were
re-read, **not run**:
- `web/src/destinations.ts`: header comment `:92-97`. `lineage.current.sleeperLeagueId`
  hrefs at `:128` history, `:139` power, `:158` analysis, `:174` rosterManagement,
  `:188` expectedWins, `:202` forecast, `:215` weeklyReport, `:228` superlatives. All
  match the doc.
- `web/src/components/LeagueRailSection.tsx`: `seasonHref` `:133`, `switchTarget` `:149`,
  `currentKey` `:297`, year row `seasonHref(s)` `:399`, flyout Seasons `seasonHref(s)`
  `:440`, Leagues `switchTarget(...)` `:457`, refresh bump loop `:71`.
- `web/src/searchIndex.ts:85` already builds `{ lineage, season: current }`. So the doc's
  palette risk is expected to be a no-op, but it still needs a pinning test.
- `web/src/pages/PowerRankings.tsx`: `sleeperLeagueId` from params `:387`. The season is
  the max of `data.entries[].season` (`:470-473`), and the history lookup picks by it at
  `:482`.
- **Not in the doc:** `web/src/destinations.test.ts:139` ("points league pages at the
  current season and the board at the viewed one") asserts today's behavior for
  History, which stays chain-wide. It must be kept for History and extended, not deleted.
- The backend forecast (`PlayoffOddsService.Unavailable`) has `UNMODELLED_SEEDING`,
  `NO_SCORED_WEEKS` and `NOT_COMPUTED`, and no "season complete" reason. Design C is
  still open.

**Tests**: Requested. The doc's acceptance criterion 1 names `destinations.test.ts` and
`LeagueRailSection.test.tsx`. Per AGENTS.md, a passing suite is not "verified" here: the
live browser pass (acceptance criterion 2) is required.

**Pipeline** (AGENTS.md): the plan is the doc. Then an adversarial review (T001), build
on **Sonnet** subagents (coding tasks), a bug-hunting review (T030) and live verification
(T031–T034). **Ask Allan before committing** at every checkpoint.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: US1 = year links keep the page; US2 = Power rankings honours the season;
  US3 = Forecast on a finished season

---

## Phase 1: Setup

- [X] T001 Adversarial review of `claude/season-scoped-rail-links.md`: read it cold
  against `587fbc3` and look for gaps before any code. Check at least these:
  (a) every `/leagues/:id/*` page's fetch honours the URL id, not only the five the doc
  lists (Analysis goes through `LeagueAnalysisService:209` `bySleeperId`, which has no
  fallback);
  (b) the Leagues flyout (`LeagueRailSection.tsx:457`) passes `season: l.current`, which
  is still correct;
  (c) `switchTarget`'s History fallback for a `requiresStatus` mismatch (`live` on a
  finished season).
  Append findings to the doc as an "Amended after review" note. Don't rewrite text
  silently.
- [X] T002 Confirm the dev environment before trusting any live check. Postgres on
  `localhost:5433`, not 5432. Run `curl localhost:8080/api/health` and confirm
  `weightsLoaded:true`. Run `POST /api/ingest/all/{sleeperLeagueId}` for "(Foot) Ball
  Knowers" and "Ball Knowers" (NBA) so both have 2025 + 2026 in their lineage. Per
  memory, `preview_start` from a worktree runs **main's** `launch.json`: point
  `bootRun`/`vite` at this worktree and confirm the classpath before trusting a live
  result.

---

## Phase 2: Foundational (confirm the "read, not run" claims)

**Purpose**: The doc says its "already works" section is read, not run, and that the
first build step is to confirm it. Nothing in US1 is safe to build until these hold.

- [X] T003 [P] Run each one-season endpoint with a 2025 league id and record the
  `season` / `requestedSeason` each returns in the doc's amendment section:
  `curl -H "X-Sleeper-User: <member>" localhost:8080/api/leagues/<2025 id>/{superlatives,weekly-report,expected-wins,roster-management,forecast,analysis}`.
  The answer must be about 2025, or name its fallback (`SeasonFallbackNote`). It must
  never be silently 2026.
- [X] T004 [P] Add a test in `web/src/railLeague.test.ts`: `useRailLeague` on
  `/leagues/<older id>/superlatives` resolves `season` to the older lineage entry, not
  `lineage.current`. This pins the claim at `web/src/railLeague.ts:210-214`.
- [X] T005 [P] Add a test in `web/src/components/LeagueRailSection.test.tsx`: a FRESH
  refresh bumps the data version for an **older** season id too. It extends the existing
  "polls while RUNNING, bumps the data version on FRESH" test at `:205`, using
  `useLeagueDataVersion('<older id>')`. This pins `LeagueRailSection.tsx:71`.

**Checkpoint**: If T003 shows any page ignoring the URL id, stop. Fix that page first,
or drop it from US1's one-season list and record why in the doc.

---

## Phase 3: User Story 1 – pick a year, every tab is that year (Priority: P1) 🎯 MVP

**Goal**: On "(Foot) Ball Knowers", clicking **2025** then **Superlatives** lands on 2025
Superlatives, not the 2025 draft board. From any one-season page, the year links keep
you on that page for the other year. Design A and Design B both ship here: the doc says
fixing only one does not fix the bug.

**Independent Test**: On `/leagues/<2026 id>/superlatives`, the 2025 year link's href is
`/leagues/<2025 id>/superlatives`. Clicking it and then Weekly report stays on 2025.

### Tests for User Story 1 (write first, confirm they fail)

- [X] T006 [P] [US1] In `web/src/destinations.test.ts`, rewrite the test at `:139` to
  cover the new split and keep its History assertion. For a two-season lineage viewing
  the older season: `history.href` is still `/leagues/L_NEW/history`, the board is still
  `/drafts/D_OLD/board`, and each of `analysis`, `rosterManagement`, `expectedWins`,
  `forecast`, `weeklyReport` and `superlatives` gives `/leagues/L_OLD/<path>`. Retitle it
  so the title says the new rule.
- [X] T007 [P] [US1] In `web/src/destinations.test.ts`, add a test that iterates
  `LEAGUE_DESTINATIONS` and asserts each non-action `/leagues/` row's href carries
  `ctx.season.sleeperLeagueId`, except the explicit chain-wide set
  `{ history }` (plus `power` if T019 decides so). A new row then has to choose a side.
  It can't inherit one silently.
- [X] T008 [P] [US1] In `web/src/components/LeagueRailSection.test.tsx`, add a
  `describe('year links keep the page')` block. For an NFL two-season lineage it covers
  these cases:
  (a) on `/leagues/<current>/superlatives`, the 2025 year-row link href is
  `/leagues/<2025 id>/superlatives`;
  (b) on the 2026 board, the 2025 link is the 2025 board (`/drafts/<2025 draft>/board`);
  (c) on History, both year links are the same History href;
  (d) on `/leagues/<2025 id>/weekly-report`, the 2025 link has class `on` and 2026 does
  not. The per-row href rule is T007's job, so don't repeat it here;
  (g) on `/leagues/<2026 id>/weekly-report?week=5`, the 2025 year link's href is exactly
  `/leagues/<2025 id>/weekly-report`, with no query string. Week 5 of another season is a
  different week (doc A5).
- [X] T009 [P] [US1] In the same file, add these cases:
  (e) the flyout "Seasons" items (open the Switch menu, collapsed rail) give the same
  hrefs as (a) and (b);
  (f1) on `/drafts/<2026 draft>/live` (2026 is `pre_draft`), the 2025 year link (complete)
  is `/drafts/<2025 draft>/board`, not History. Today's `seasonHref` behavior is
  preserved through the draft-page fallback (doc A3);
  (f2) a direct `switchTarget('analysis', nbaCtx)` returns the NBA lineage's History
  href. The test comment should say this is **Leagues flyout** coverage: year links
  can't cross sports, because a lineage is one sport (doc A2).
- [X] T010 [P] [US1] In `web/src/searchIndex.test.ts`, add a test that pins the
  palette's "this league, now" meaning. Build the index for a two-season lineage and
  assert every league-page result uses `lineage.current`'s id (`searchIndex.ts:85`).

### Implementation for User Story 1 (Sonnet subagent)

- [X] T011 [US1] In `web/src/destinations.ts`, change the `href` of `analysis` (`:158`),
  `rosterManagement` (`:174`), `expectedWins` (`:188`), `forecast` (`:202`),
  `weeklyReport` (`:215`) and `superlatives` (`:228`) from
  `ctx.lineage.current.sleeperLeagueId` to `ctx.season.sleeperLeagueId`. Leave `history`
  (`:128`) as is. Leave `power` (`:139`) until T019.
- [X] T012 [US1] In `web/src/destinations.ts:92-97`, rewrite the header comment to name
  both rules and each row's rule. History is chain-wide and uses `lineage.current`. The
  one-season rows and the board use the viewed season. State power's rule once T019
  decides it. Add a one-line `// one season` or `// whole chain` note on each `/leagues/`
  row so the rule is visible where it's applied.
- [X] T011a [P] [US1] Write a failing test for each one-season page (doc A1). The
  `PageHeader` eyebrow names the season from the **response payload**, not the URL. When
  the payload's `season` differs from `requestedSeason` (resolver fallback), the header
  shows the resolved season and `SeasonFallbackNote` still shows. Put one test per page
  test file, beside the page (create `web/src/pages/<Page>.test.tsx` where it's missing,
  modelled on `web/src/pages/SeasonForecast.test.tsx`). The pages are `Superlatives.tsx`,
  `WeeklyReport.tsx`, `ExpectedWins.tsx`, `RosterManagement.tsx`, `SeasonForecast.tsx` and
  `LeagueAnalysis.tsx`.
- [X] T011b [US1] Make T011a pass. In each of those six pages, set the `PageHeader`
  eyebrow to `League · <season>` using the payload's resolved season (Superlatives
  already has `data.season`, `Superlatives.tsx:126`). **Check each payload type in
  `web/src/api.ts` first.** If a page's response has no season field, don't read the
  year from the URL or the lineage. Record the gap in the doc and ask before adding a
  field to the backend record, which would need a matching edit in `api.ts` in the same
  change. Keep the eyebrow unchanged while the page is loading or shows an error. This is
  a label, not the in-page picker ruled out under "Not building". Sonnet subagent.
- [X] T013 [US1] In `web/src/components/LeagueRailSection.tsx`, replace `seasonHref(s)`
  at the year row (`:399`) and at the flyout Seasons group (`:440`) with
  `switchTarget(currentKey, { lineage, season: s })`. In the same task, change the
  fallback in `switchTarget` (`LeagueRailSection.tsx:149-155`, doc A3). When the current
  page isn't offered by the target, fall back to the target's `board` row if the current
  destination's `idKind === 'draft'`, and to `history` otherwise. Read `idKind` from
  `LEAGUE_DESTINATIONS`, don't list keys, and update `switchTarget`'s doc comment to match.
  The Leagues flyout (`:457`) gets the same rule, which is intended: going from Follow
  live on one league to a league whose draft is done lands on its board. Update the year-row comment
  (`:387-393`), which currently says years are links because "every season is its own
  Sleeper league with its own board", so that it describes keeping the page.
- [X] T014 [US1] Delete `seasonHref` and its doc comment
  (`web/src/components/LeagueRailSection.tsx:131-137`). It has no callers after T013 and
  duplicates `draftRoute`. Confirm with a grep for `seasonHref` across `web/src`.
- [X] T015 [US1] Run `cd web && npx vitest run`. T006–T010, T011a and T004–T005 must
  now pass, and nothing else may regress. T004 and T005 pin behavior that already
  exists, so they should have passed when written. Here they're only a regression check. Then run `npx tsc -b && npm run build`. Record the actual
  test count, pass or fail, in the doc.

**Checkpoint**: The US1 unit criteria (doc acceptance 1) are green. Ask Allan before
committing.

---

## Phase 4: User Story 2 – Power rankings honours the season it's opened for (Priority: P2)

**Goal**: Decide whether `power` stays chain-wide or becomes one-season, based on what
the page actually does with a non-current id (doc Design A, "`power` needs a decision at
build time").

**Independent Test**: On a 2025 page, click Power rankings. The page either opens on
2025 (one-season) or visibly shows the current season, with the rail highlighting the
year it shows (chain-wide). It must never highlight 2025 while showing 2026.

- [X] T016 [US2] Investigate first. Run
  `curl localhost:8080/api/leagues/<2025 id>/power` and the history endpoint with the 2025
  id. Record what `data.entries[].season` and `sportState.season` contain. The page
  derives `season` as `max(entries.season)` (`web/src/pages/PowerRankings.tsx:470-473`),
  so a 2025 id may still render 2026. Check the ballot/week logic (`:490-493`, uses
  `sportState.week`) for what a past season means.
- [X] T017 (SKIPPED: T019 kept Power chain-wide) [P] [US2] Only if T016 shows the endpoint can scope to one season: write a
  failing test in `web/src/pages/PowerRankings.test.tsx` (create it if absent) asserting
  that the page opened on an older season's id shows that season's standings. Model it
  on an existing page test such as `web/src/pages/SeasonForecast.test.tsx`.
- [X] T018 (SKIPPED: T019 kept Power chain-wide) [US2] Only if T016 supports it: make `web/src/pages/PowerRankings.tsx` start
  on the URL id's own season instead of `max(entries.season)`. Keep ballot voting on the
  current week only. A past season is read-only (doc's "Recommended" option).
- [X] T019 [US2] Record the power decision in `claude/season-scoped-rail-links.md` as an
  amendment: what T016 found, and chain-wide or one-season. Apply it to
  `destinations.ts:139`, the header comment (T012) and the T007 exception set.
  If power stays chain-wide, confirm the rail on `/leagues/<current>/power` highlights
  the current year, and note that a year click from Power goes to Power at the same URL.

**Checkpoint**: Run the web suite again. Ask before committing.

---

## Phase 5: User Story 3 – Forecast on a finished season (Priority: P3)

**Goal**: A 2025 Forecast link now exists, which it didn't before US1. It must not
present odds for a finished season as if they were live (doc Design C).

**Independent Test**: `/leagues/<2025 id>/forecast` either refuses with a named reason,
or says "this season is over, here's how it ended". It never shows a live-looking odds
table.

- [X] T020 [US3] Investigate. Run
  `curl -H "X-Sleeper-User: <member>" localhost:8080/api/leagues/<2025 id>/forecast` for
  both leagues and record `available`, `reason`, `season`, `requestedSeason` and `week`.
  `PlayoffOddsService.Unavailable` currently has no "season complete" reason
  (`backend/src/main/java/com/ballknowers/draftsim/engine/PlayoffOddsService.java:277`).
  Check whether a stored snapshot for a finished season comes back `available:true`.
- [X] T020b [P] [US3] Investigate Analysis on a finished season (doc A4). Run
  `curl -H "X-Sleeper-User: <member>" localhost:8080/api/leagues/<2025 NFL id>/analysis`
  and open `/leagues/<2025 NFL id>/analysis` in the browser. Record what the two
  rest-of-season projection blocks show for a completed season: whether they refuse,
  explain themselves, or show projections as if they were live.
- [X] T021 [US3] Decide and record the Design C amendment in
  `claude/season-scoped-rail-links.md`, for both Forecast (T020) and Analysis (T020b).
  For each page, if it already refuses or explains itself, record that and stop there.
  If Forecast needs work, do T022–T025. If Analysis needs work, apply the same
  "season is over" treatment in `web/src/pages/LeagueAnalysis.tsx`, with a failing test
  first, and add those tasks here as T024a/T024b. Mark any skipped task with the reason.
- [X] T022 [P] [US3] (Only if needed) Write a failing test in
  `web/src/pages/SeasonForecast.test.tsx`: when the forecast payload is for a season
  whose league is complete, the page renders a "this season is over" line with the final
  placements instead of the odds table.
- [X] T023 [US3] (Only if needed) Backend: expose "season complete" on the forecast
  payload from the V21 `league.status` (the `complete()` flag). Do it by adding a new
  `Unavailable.SEASON_COMPLETE` reason, or a `complete` boolean, in
  `PlayoffOddsService.java` and `SeasonForecastController.body()`
  (`backend/src/main/java/com/ballknowers/draftsim/api/SeasonForecastController.java`).
  Update `SeasonForecastShapeTest.java` to pin the new field. No schema migration should
  be needed. If one is, it's the next `V<n+1>` and existing migrations must not be edited.
- [X] T024 [US3] (Only if needed) Mirror the new field in `web/src/api.ts` field-for-field
  in the same change (AGENTS.md hard rule). Render it in `web/src/pages/SeasonForecast.tsx`.
  Keep the tab offered, as the doc says: don't add a league-status gate to
  `destinations.ts`.
- [X] T025 (backend 880 tests, 0 skipped, 1 failure: RefreshControllerIT.aChainRunShowsRunning…, which fails identically on base e140fc1, so it was there before this branch) [US3] (Only if T023 ran) Run `cd backend && ./gradlew test`, and check the
  **skipped** count: ITs skip silently when Postgres is down (memory). Restart `bootRun`
  so the running server isn't serving stale bytecode.

**Checkpoint**: Ask before committing.

---

## Phase 6: Polish, review & live verification

- [X] T026 [P] Check the doc's "Resolver fallback on a new season" risk. Open
  `/leagues/<2026 id>/superlatives` before 2026 week 1 is scored (NBA is the likely
  case). The rail must highlight **2026**, and `SeasonFallbackNote` must name 2025.
- [X] T027 [P] Check the "Commissioner conduct list" risk. On
  `/leagues/<2025 id>/superlatives` as commissioner, confirm the list edits 2025's row
  (`web/src/pages/Superlatives.tsx:557-579`). Record in the doc that this is intended.
- [X] T028 (checked live: backdated the 2026 league_refresh row 2h in the local DB, then opened 2025 Weekly report. The refresh ran (log 11:11:57, "1 season(s) refreshed, 1 skipped as complete") and the page made a third weekly-report request at +6.2s after the two initial ones. Refresh and refetch are linked by timing, not a trace.) [P] Check that an older-season page refetches after a refresh. Trigger a
  refresh while on `/leagues/<2025 id>/weekly-report` and confirm a re-request in
  `read_network_requests`. This is the live counterpart of T005.
- [X] T029 Update `HANDOFF.md` and `claude/season-scoped-rail-links.md`: set the status
  line to built, fill in the amendments from T001, T003, T019 and T021, and keep
  "verified" and "assumed" separate.
- [X] T030 (4 findings: 3 fixed test-first, 1 left with reason; see the doc's T030 amendment) Run a bug-hunting code review (not a style pass) over the branch diff, as a
  separate pass. Fix findings on a Sonnet subagent and re-run T015.
- [X] T031 Live check, "(Foot) Ball Knowers": click 2025, then Superlatives. The header
  eyebrow must read "League · 2025" (T011b) and the cards must be 2025's. Also from
  Follow live, if a pre-draft season exists, click a completed year: it must land on
  that year's board (A3). Then go to Weekly report, which must still
  be 2025. Then click 2026, which must land on 2026's Weekly report. The year row must
  highlight the shown year each time. Screenshot the 2025 Superlatives.
- [X] T032 Run the same sequence as T031 on "Ball Knowers" (NBA), and also confirm
  Analysis isn't offered.
- [X] T033 Live check of the flyout path: with the rail collapsed (draft room), use
  Switch → Seasons from Superlatives and confirm it keeps the page. Then use Switch →
  Leagues and confirm it still keeps the page across leagues, as it did before.
- [X] T034 Hard-refresh (Ctrl+Shift+R) any tab open from before a vite restart before
  concluding a live check failed. See AGENTS.md on stale HMR tabs.

---

## Dependencies & Execution Order

- **T001–T002** first. **T003–T005** (Foundational) block US1: T003's result can shrink
  US1's one-season list.
- **US1 (T006–T015)**: tests T006–T010 are [P], each in a different file except
  T008/T009, which share `LeagueRailSection.test.tsx` and should be done by one agent.
  Then T011 → T012 (same file), T013 → T014 (same file), and T015 last. T011a/T011b
  (the six page files and their tests) touch no file the rest of US1 does, so they can
  run on a separate agent in parallel with T011–T014.
- **T013's draft-page fallback** is what makes T009(f1) pass, so T009 must fail before
  T013 and pass after it.
- **US2 (T016–T019)** depends on US1's `destinations.ts` changes (T011/T012), because
  T019 edits the same file and the same header comment.
- **US3 (T020–T025)** can start its investigation (T020) any time after T002. Its code
  depends only on US1 existing, since US1 is what makes the 2025 Forecast link reachable.
- **Polish (T026–T034)** comes after the stories that are built. T030 runs before
  T031–T033.

## Parallel Example: User Story 1

```text
Agent A (sonnet): T006 + T007  -> web/src/destinations.test.ts
Agent B (sonnet): T008 + T009  -> web/src/components/LeagueRailSection.test.tsx
Agent C (sonnet): T010         -> web/src/searchIndex.test.ts
Agent D (sonnet): T011a -> T011b  -> web/src/pages/{Superlatives,WeeklyReport,ExpectedWins,
                                     RosterManagement,SeasonForecast,LeagueAnalysis}.tsx + tests
then one agent: T011-T014 (destinations.ts, LeagueRailSection.tsx), then T015
```

## Implementation Strategy

1. **MVP = US1.** It alone fixes the reported bug: year → Superlatives lands on that
   year's Superlatives, and every one-season tab keeps the year.
2. US2 is a correctness follow-up for the one page with its own season logic.
3. US3 exists because US1 creates the 2025 Forecast link. It may turn out to be a
   no-op, which is a legitimate outcome if T020 shows the endpoint already explains
   itself.
4. Out of scope (doc "Not building"): an in-page year picker, a `?season=` param,
   and changes to History.
