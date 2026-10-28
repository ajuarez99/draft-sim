---
description: "Task list for spec 052, site analytics"
---

# Tasks: Site analytics

**Input**: `specs/052-site-analytics/` (plan.md including "Amended after review", spec.md,
research.md, data-model.md, contracts/analytics-module.md, quickstart.md)

**Tests**: included. The plan's Design §8 asks for them, and this repo doesn't call
anything verified without them.

All paths are relative to the worktree root. Front-end only: no backend, migration or
`api.ts` change.

## Phase 1: Setup

- [x] T001 [P] Type `VITE_UMAMI_WEBSITE_ID?: string` and `VITE_ANALYTICS_DEBUG?: string` on `ImportMetaEnv` in `web/src/vite-env.d.ts`
- [x] T002 [P] Add `ARG VITE_UMAMI_WEBSITE_ID` / `ENV VITE_UMAMI_WEBSITE_ID=$VITE_UMAMI_WEBSITE_ID` beside `VITE_API_BASE` in the build stage of `web/Dockerfile`, with a comment matching the existing one (build variable, optional, absent = analytics off)
- [x] T003 [P] In `web/serve.json`, add `https://cloud.umami.is` to `script-src` and `https://gateway.umami.is` to `connect-src`, changing nothing else in the policy

## Phase 2: Foundational (blocks every story)

- [x] T004 Create `web/src/analytics.ts` per `contracts/analytics-module.md` (including its "Amended after review" section):
  - Env is read lazily, with an exported test-only `__resetAnalyticsForTests()`.
  - Active only when: the website id is non-empty, AND `location.hostname` is `www.ballknowers.co` or `ballknowers.co`, AND `localStorage['umami.disabled']` is unset. The storage read goes in a try/catch, and blocked storage counts as unset.
  - When `import.meta.env.DEV && VITE_ANALYTICS_DEBUG === '1'`: never load the script; `console.debug('[analytics]', payload)` instead.
  - `initAnalytics()` appends `<script async src="https://cloud.umami.is/script.js" data-website-id data-auto-track="false" data-domains="www.ballknowers.co,ballknowers.co">`. It never sets `data-errors`, `data-performance`, `data-distinct-id` or `data-host-url`.
  - A queue of at most 20 payloads, flushed on script `load` and dropped on `error`. After an error, every call is a no-op.
  - The only send path is `window.umami.track(payload)` with a full object built here, never a string first argument. Each payload is `{ website, url, title, hostname, language, screen, referrer? }`, plus `name` and `data` for events. Every call is in a try/catch.
  - `routeTemplate(pathname, signedIn)` uses a `RouteObject[]` mirroring every `<Route path>` in `web/src/App.tsx` and `matchRoutes`. Params are renamed to `:league`, `:draft`, `:manager`, `:player`, `:session`, `:a`, `:b`, keeping each title label. No match → `/(not-found)`. Signed out → `/(landing)` + template, titled `Landing (from <label>)`.
  - Referrer goes only on the first page view of the document. It is `new URL(document.referrer).origin`, and only when that origin is not `ballknowers.co` or a `*.ballknowers.co` host.
  - `trackPageView(pathname, signedIn)` de-dupes on the last `signedIn + pathname` it sent.
  - `trackLeagueView(pathname, signedIn, sport, leagueId)` sends named event `league-view` with data `{ route, sport, league }`, latched per `signedIn + pathname`.
  - `trackAction(name: 'sign-in' | 'league-added' | 'mock-started', data?)`.
- [x] T005 Call `initAnalytics()` before `createRoot(...)` in `web/src/main.tsx`
- [x] T006 Write `web/src/analytics.test.ts`:
  - Off without an id: no script element, `window.umami` untouched, no throw.
  - Off on `localhost`, and off with `umami.disabled` set.
  - The script element has `data-auto-track="false"` and no `data-errors`.
  - The template for every route, including `/mock/new` vs `/mock/:sessionId`, `/leagues/x/stats/calculator` vs `/leagues/x/stats`, an unmatched path, and signed out.
  - No `url` or `title` ever contains a run of 6 or more digits, or the test username.
  - Referrer: outside origin on the first view only; dropped for our own origin; reduced to the origin.
  - The same pathname twice → one send; the same pathname with `signedIn` flipped → two sends.
  - Queue then flush on load; dropped on error.
  - A throwing `umami.track` doesn't propagate.
  - `document.cookie` is unchanged after init and several sends (FR-005).
  - A spy asserts every `track` call's first argument is an object.
- [x] T007 Add a coverage test in `web/src/analytics.test.ts` that reads `web/src/App.tsx` as text (as `boot.test.ts` reads `boot.js`), extracts every `path="..."` value, and fails if any one has no entry in the route-template table

**Checkpoint**: `cd web && npx vitest run src/analytics.test.ts` is green.

## Phase 3: User Story 1, page views and referrers (P1) 🎯 MVP

**Goal**: one page view per route change, signed in or out, with an outside referrer on landing.
**Independent test**: an AppShell test where navigating A → B → B?x=1 → C records exactly 3 page views with the right templates, and signing in on the same path records a second view.

- [x] T008 [US1] In `web/src/components/AppShell.tsx`, add an effect on `[signedIn, location.pathname]` that calls `trackPageView(location.pathname, signedIn)`, where `signedIn` comes from the existing `useUser()`. It must sit **above** the component's signed-out early return, so it also runs signed out (plan F1/F2).
- [x] T009 [US1] Add a test (new `web/src/components/AppShell.analytics.test.tsx`, mocking `../analytics`) for the independent test above. Include a signed-out visit to `/leagues/123/power`, which must record `/(landing)/leagues/:league/power`.
- [x] T010 [P] [US1] Put the disclosure in `web/src/landingCopy.ts` (new export `analyticsLine`): "We count page visits anonymously: which pages and which league, never your username, and no cookies." Render it beside the "No password" line in `web/src/pages/SignIn.tsx:95` and in the static landing in `web/src/landingHtml.ts:110`.
- [x] T011 [US1] Update `web/src/pages/SignIn.test.tsx` and `web/src/landingHtml.test.tsx` so both assert the new line, and keep their existing assertions green.

## Phase 4: User Story 2, key actions (P2)

**Goal**: three named actions, each counted once and only after success.
**Independent test**: the unit tests below, with each call site's API mocked to succeed once and fail once.

- [x] T012 [P] [US2] `web/src/pages/SignIn.tsx`: `trackAction('sign-in')` immediately after `setUser(found)`, in the success branch only
- [x] T013 [P] [US2] `web/src/pages/DraftPicker.tsx`: `trackAction('league-added', { via: 'sleeper' })` after the one-click Sleeper setup succeeds (`:152-177`), and `{ via: 'id' }` after the pasted-id ingest succeeds (`:196`). Fire once per user action, not once per setup step.
- [x] T014 [P] [US2] `web/src/components/EspnLeagueSetup.tsx`: `trackAction('league-added', { via: 'espn' })` after the add succeeds (`:68-74`)
- [x] T015 [P] [US2] `web/src/pages/MockSetup.tsx:120`: `trackAction('mock-started', { from: 'setup' })` after `createMockSession` resolves. `web/src/pages/LiveDraftView.tsx:359`: `{ from: 'live' }` after `createMockSessionFromDraft` resolves.
- [x] T016 [US2] Add tests in the existing test files for SignIn, DraftPicker, EspnLeagueSetup and MockSetup, or new `*.analytics.test.tsx` beside them, mocking `analytics`. Each checks: success → exactly one call with the right data; failure → none.

## Phase 5: User Story 3, per league and sport (P3)

**Goal**: a `league-view` event for league-scoped pages, never attributed to the wrong route.
**Independent test**: an AppShell test where the rail resolves late for page A after navigating to page B (another league) sends a league view for B only, with B's league.

- [x] T017 [US3] In `web/src/components/AppShell.tsx`, add an effect on `[railLeague, signedIn, location.pathname]`. It works out the league ref the current path expects (`leagueRefFromPath(location.pathname)`, else the active hint on hint-accepting routes). It calls, only when `signedIn` (data-model: signed-out pages send no league view), `trackLeagueView(location.pathname, signedIn, railLeague.lineage.current.sport, railLeague.lineage.current.sleeperLeagueId)` only when `railLeague.season` matches that ref, by `sleeperLeagueId` or `sleeperDraftId` (plan F3).
- [x] T018 [US3] Extend `web/src/components/AppShell.analytics.test.tsx` with the stale-rail case above, plus a page with no league → no `league-view`.

## Phase 6: Polish

- [x] T019 [P] `DEPLOY.md`:
  - Add an env-var table row for `VITE_UMAMI_WEBSITE_ID` (a build variable; absent = off).
  - Add the two Umami origins to the "Security headers" origin list.
  - Add an "Analytics (spec 052)" section covering:
    - Create the site in Umami Cloud and set the build variable on the frontend service.
    - Exclude your own browser with `localStorage.setItem('umami.disabled','1')`.
    - Numbers are a floor (blocked visitors aren't counted) and start on deploy day (FR-010).
    - The league id changes at season rollover.
    - An auction-draft redirect counts two views.
    - What is sent (link data-model.md).
- [x] T020 [P] `HANDOFF.md`: one short entry for spec 052 (state, what's owed: the Umami account plus the production checks in quickstart.md)
- [x] T021 Run `cd web && npx tsc -b && npm test && npm run build`; all green, with no new warnings

## Dependencies

- Setup T001–T003 → Foundational T004–T007 → US1 (T008–T011) → US2 and US3 can run in either order.
- T012–T015 touch different files, so they can run in parallel. T017 and T008 are both in AppShell, so they run in sequence.

## Implementation strategy

MVP = Phase 1–3 (page views, referrers, disclosure). US2 and US3 are small additions to
one module that already exists. Production verification needs Allan's Umami website id;
everything before that is verified locally with `VITE_ANALYTICS_DEBUG=1` and unit tests.

## Implementation notes (2026-10-28)

- **Route table exports `ROUTE_TEMPLATE_PATHS`.** The T007 coverage test reads `App.tsx?raw` and checks every `path="..."` (including the catch-all `*`) against it. `*` maps to `/(not-found)`, so "no match" and the real catch-all are one rule.
- **`trackAction` derives its page from `location.pathname`.** Its callers don't know the signed-in state, so `sign-in` is filed as a landing-form action (`/(landing)...`) and the other two as signed-in templates. The action payload carries a template `url`/`title` like every other send; the contract didn't say what `url` an event carries.
- **Debug mode ignores the hostname/id gate** (dev only, `import.meta.env.DEV`); `umami.disabled` still wins.
- **T010:** the disclosure is its own `<p class="signin-note muted small">` after the "No password" paragraph, in both `SignIn.tsx` and `landingHtml.ts`, so `landingHtml.test.tsx`'s per-selector text comparison still holds. `analyticsLine` is also in `allCopyStrings()`, so the banned-words guard covers it.
- **T013:** `league-added` (sleeper) fires after the whole `setupLeague` stage loop succeeds, so a retry after a failed stage counts once.
- **T014:** fires on the write (`result.written`), not on the preview lookup.
- **T016:** tests were added to the existing DraftPicker, EspnLeagueSetup, MockSetup and SignIn test files, plus new `LiveDraftView.analytics.test.tsx`.
- **T018:** the stale-rail test was checked by mutation: with the `matches` guard disabled it fails; with it, it passes.
- **Not verified:** anything in a real browser, the Umami script loading, real payloads, the CSP in a browser, the Docker build arg (Dockerfile not built).
