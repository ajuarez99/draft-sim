# Tasks: Split the website into a Time Machine site and a league site

**Input**: `specs/073-split-sites/` — spec.md, plan.md (its "Amended after review" section wins over earlier sections), research.md, contracts/site-routes.md, quickstart.md, plan-review.md

**Tests**: included. This repo's bar is tests plus live verification, and the plan review asked for specific ones.

**Rule for every task**: with `VITE_SITE` unset the site must behave exactly as it does on `origin/main` d3a29f0. The existing 183 test files / 3024 tests (reviewer's measured baseline) must stay green; an existing test is changed only where a task says so.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [x] T001 Run `npm ci` (if needed), `npx vitest run` and `npx tsc -b` in `web/` and record the baseline counts in `specs/073-split-sites/build-notes.md`.

## Phase 2: Foundational (blocks every story)

- [x] T002 Create `web/src/site.ts` (pure: `Site`, `SiteConfig`, `parseSite` with trim and throw, `makeConfig`, `siteOrigin`, `tmHome`, `tmGame`, `LEAGUE_PREFIXES` with `/admin`, `crossSiteUrl`) and `web/src/site.test.ts` covering every row of the "Time Machine path helpers" and "Build variable" tables in contracts/site-routes.md, including `"timemachine "` (trailing space) and `"leauges"` (throws).
- [x] T003 Create `web/src/siteEnv.ts` (`currentSite()` from `import.meta.env`) and declare `VITE_SITE`, `VITE_TM_ORIGIN`, `VITE_LEAGUES_ORIGIN` in `web/src/vite-env.d.ts`.
- [x] T004 Make the landing modules config-driven: remove module-level `SITE_ORIGIN` from `web/src/landingCopy.ts` and `IMAGE_URL` from `web/src/landingHtml.ts`; `transform(html, cfg)`, `headTags(cfg)`, `jsonLd(cfg)` take a `SiteConfig`; update `web/vite.config.ts` to build the config with `makeConfig(loadEnv(mode, process.cwd(), 'VITE_'))`; update `landingHtml.test.tsx`, `landingCopy.test.ts` and any other caller. Output for `combined` must be byte-identical to today's (assert it against a snapshot taken before the change).
- [x] T005 Emit `robots.txt` and `sitemap.xml` from the Vite plugin (`generateBundle` + a dev middleware) using `siteOrigin(cfg)`; delete `web/public/robots.txt` and `web/public/sitemap.xml`; test the generated text for all three modes.
- [x] T006 [P] Lift the account button and menu out of `web/src/components/TopBar.tsx:154-183` into `web/src/components/AccountMenu.tsx`; `TopBar` uses it; `TopBar.test.tsx` stays green unchanged.
- [x] T007 [P] Lift the username form out of `web/src/pages/SignIn.tsx` into `web/src/components/SignInForm.tsx` (takes the note text and an `onSignedIn` callback); `SignIn.test.tsx` stays green unchanged.
- [x] T008 Create `web/src/siteCopy.ts`: per-site wording for the not-found link, the error-boundary line, the sign-in note and the analytics disclosure; wire `NotFound.tsx:17`, `ErrorBoundary.tsx:50-51`, `SignIn.tsx:96-100` and `analyticsLine` to it. `combined` and `leagues` wording identical to today's.

**Checkpoint**: suite green, `combined` build output unchanged.

## Phase 3: User Story 1 — ballknowers.co is the Time Machine (P1)

**Independent test**: `VITE_SITE=timemachine` dev server shows only the Time Machine, signed out and in.

- [x] T009 [US1] Replace every `/history` literal with `tmHome(currentSite())` / `tmGame(currentSite(), id)` in `web/src/pages/HistoricalDraft.tsx` (145, 158, 243, 247, 351, 357), `web/src/pages/HistoricalRoom.tsx` (257, 324, 343), `web/src/components/HistoricalProfileCard.tsx` (34) and the share link in `web/src/components/HistoricalResults.tsx` (82). Add a test that fails if a `/history` string literal remains in any `web/src/**/Historical*.tsx` non-test file.
- [x] T010 [US1] Move the "how to play" steps to one exported list (in `web/src/historicalCopy.ts`) used by `HistoricalDraft.tsx`; create `web/src/tmLandingCopy.ts` (title, description, headline, sub, image alt, footer, its own analytics disclosure; pure data, no league wording).
- [x] T011 [US1] Create `web/src/pages/TimeMachineSignIn.tsx` (front door inside the `.tm` scope, uses `SignInForm`, writes `bk.tm.signedInHere.v1` on sign-in) and the Time Machine branch of `landingHtml.ts` (head tags, JSON-LD, static body from `tmLandingCopy.ts`, image `og-image-tm.png`).
- [x] T012 [US1] Create `web/src/components/TimeMachineShell.tsx`: `.app-shell > header.topbar + main.app-main#main`, skip link, wordmark to `/`, `AccountMenu`, `ErrorBoundary` around children, sign-out clears the user and navigates to `/`, `trackPageView` on path / signed-in change; renders only children when signed out.
- [x] T013 [US1] `web/src/App.tsx`: the `timemachine` tree per contracts/site-routes.md (routes written as literal `<Route path>` lines): gated `/` → `HistoricalDraft`, `/game/:gameId` → keyed `HistoricalRoom`, `*` → `NotFound`; signed out → `TimeMachineSignIn` at the same URL; `document.title` per plan Design 2. The `combined` tree is not edited beyond T016.
- [x] T014 [US1] Add `web/scripts/og-image/og-image-tm.svg` and render `web/public/og-image-tm.png` (1200x630) with `scripts/og-image/render.mjs` (install `@resvg/resvg-js` in the scratch directory, not in `web/`). If rendering cannot be done, say so in build-notes.md and leave the head tag pointing at the existing image; do not commit a placeholder that claims to be the real one.
- [x] T015 [P] [US1] Tests: `App.site.test.tsx` (timemachine: gated routes, sign-in keeps the URL, not-found), `TimeMachineShell.test.tsx` (no league nav, sign-out goes to `/`, page view sent), a wording test that renders the front door, home, `NotFound` and `ErrorBoundary` in `timemachine` and fails on `league`, `leagues`, `mock`, `manager` (case-insensitive; the FR-019 notice is excluded), landing parity (rendered text equals static body text) and banned-words for `tmLandingCopy`.

## Phase 4: User Story 2 — the league tools at leagues.ballknowers.co (P1)

**Independent test**: `VITE_SITE=leagues` dev server serves every league page and offers no Time Machine link.

- [x] T016 [US2] `TopBar.tsx:120-122` and `NavSheet.tsx:104-106`: render the Time Machine link only when `currentSite().site === 'combined'`. `App.tsx`: the `leagues` tree is the `combined` tree without the two gated `/history` routes.
- [x] T017 [US2] `web/src/analytics.ts`: `OWN_HOSTS` gains `leagues.ballknowers.co`; `routesFor(site)` returns each site's table (`/game/:gameId`, and `/` titled "Time Machine", for `timemachine`); redirect paths are in no table and send nothing. Update `analytics.test.ts` (coverage check once per tree, the `data-domains` assertion at line 119).
- [x] T018 [P] [US2] Tests: `TopBar`/`NavSheet` per mode; `leagues` tree has no gated `/history` route; jump-to search index has no Time Machine entry; `leagues` head tags, canonical, robots and sitemap name `leaguesOrigin`.

## Phase 5: User Story 3 — old links still land (P2)

- [x] T019 [US3] Create `web/src/components/CrossSiteRedirect.tsx`: `window.location.replace(target)` on mount plus a one-line fallback with a plain link; renders `NotFound` when the target origin equals the current origin; under `import.meta.env.DEV` with the other origin's variable unset it shows the target as text and does not navigate.
- [x] T020 [US3] `App.tsx` redirect routes, all outside the signed-in gate and outside the shells' page-view effect, written as literal `<Route>` lines: `timemachine` — `/history` → `/`, `/history/:gameId` → `/game/:gameId` (in-app, query and hash kept), and `/leagues/*`, `/drafts/*`, `/mock/*`, `/managers/*`, `/admin/*` → league origin with path, query, hash; `leagues` — `/history`, `/history/:gameId` → `TM/history[/id]`; `combined` — `/game/:gameId` → `/history/:gameId` (in-app).
- [x] T021 [P] [US3] Tests for every row of the contract route table in each mode, signed in and signed out, with `?x=1#y`; `/leagues/9/history` and `/managers/3/history` are never treated as Time Machine links.

## Phase 6: Allan's decisions — moved notice and carried formulas

- [x] T022 Create `web/src/siteCarry.ts` per plan ("Decisions put to Allan", PR-04): `withCarry(url, storage)` and `acceptCarry(location, history, storage)`, reusing `statCalcSaved`'s validation (export what is needed from it without changing its behaviour); 6,000-character cap; never overwrites existing league-site formulas; never deletes from the source; always strips the carry from the address bar. `siteCarry.test.ts`: round trip, existing fragment preserved, destination not empty → no write, garbage → dropped and stripped, oversized → not attached.
- [x] T023 Wire it: `CrossSiteRedirect` (Time Machine → league direction only) uses `withCarry`; `web/src/main.tsx` calls `acceptCarry` before render when the site is `leagues`.
- [x] T024 Create `web/src/components/LeaguesMovedNotice.tsx` (FR-019) and render it at the top of `HistoricalDraft` only when the site is `timemachine`, the user is signed in, `bk.tm.signedInHere.v1` is absent and `bk.tm.movedNotice.dismissed.v1` is absent; dismiss and link-click both set the dismissed key; the link uses `withCarry`. Tests for each condition, including blocked storage.

## Phase 7: Build and deploy configuration

- [x] T025 [P] `web/Dockerfile` (three `ARG`/`ENV` pairs), `web/.env.example`.
- [x] T026 [P] `.claude/launch.json`: `draft-sim-web-tm` (5184), `draft-sim-web-leagues` (5185), `draft-sim-api-split` (`CORS_ORIGINS` = 5173, 5184, 5185), written in the `set X=value&& ` form. The existing entries are not changed.
- [x] T027 `DEPLOY.md` "Two sites" section and `specs/073-split-sites/quickstart.md` brought in line with the amended plan: rollout order, the unconfirmed Railway custom-domain limit as the first check, the "Couldn't reach Sleeper" symptom for a missing allowed origin, formulas carry only on a forward, Allan re-sets his analytics opt-out on the league address, rollback.

## Phase 8: Polish

- [x] T028 Run `npx vitest run`, `npx tsc -b`, then `npm run build` three times (unset, `timemachine`, `leagues`); for each, read `dist/index.html`, `dist/robots.txt`, `dist/sitemap.xml` and record in build-notes.md what each names (SC-007). Confirm `VITE_SITE=leauges npm run build` exits non-zero.
- [x] T029 Record in build-notes.md every place the build departed from the plan and why.

## Dependencies

- T002 → T003 → T004 → T005; T006, T007 parallel with those; T008 after T003.
- US1 (T009-T015) needs Phase 2. US2 (T016-T018) needs Phase 2 and shares `App.tsx` with T013 (do T013 first). US3 (T019-T021) needs T013 and T016.
- Phase 6 needs T019. Phases 7 and 8 last.

## Requirement coverage

| Requirement | Tasks |
|---|---|
| FR-001, FR-017 | T002, T003, T025, T026 |
| FR-002, FR-006 | T009, T013 |
| FR-003 | T012, T015 |
| FR-004 | T008, T010, T011, T015 |
| FR-005, FR-009 | T004, T005, T011, T014, T018 |
| FR-007 | T016, baseline suite |
| FR-008 | T016, T018 |
| FR-010, FR-011, FR-012 | T019, T020, T021 |
| FR-013 | T013, T015, T021 |
| FR-014 | no code; verification SC-004 |
| FR-015 | T015, T018 (wording and link tests) |
| FR-016 | T017 |
| FR-018 | T027 |
| FR-019 | T024 |
| FR-020 | T022, T023 |
| SC-001..SC-007 | T015, T018, T021, T028, live verification |
