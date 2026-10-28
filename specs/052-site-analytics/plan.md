# Implementation Plan: Site analytics

**Branch**: `052-site-analytics` | **Date**: 2026-10-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/052-site-analytics/spec.md`

## Summary

Add cookie-free usage measurement to the web front end using Umami Cloud's free plan
(clarified: hosted service; per-league, never per-person). One module, `web/src/analytics.ts`,
loads the tracker only when a build-time website id is set, sends a page view per route
change with the URL reduced to a route template, a `league-view` event carrying league id
and sport, and four named key actions. The owner reads the numbers on Umami's own
dashboard. The front-end CSP gains the tracker's origin, the sign-in screen gains one
plain-words line saying what is counted, and DEPLOY.md gains the setup steps. No backend,
schema or `api.ts` change.

## Technical Context

**Language/Version**: TypeScript (strict), React 18, react-router-dom, Vite

**Primary Dependencies**: Umami tracker script loaded from `https://cloud.umami.is` at
runtime; **no new npm dependency**

**Storage**: N/A in this repo; data held by Umami Cloud

**Testing**: Vitest + Testing Library (`cd web && npm test`), `npx tsc -b`, `npm run build`

**Target Platform**: browsers hitting `www.ballknowers.co` (served by `serve` on Railway)

**Project Type**: web application, front-end-only change

**Performance Goals**: no visible change to page load (FR-007); tracker loads `async`,
after the app's own bundle, and never blocks rendering

**Constraints**: no cookies (FR-005); no personal identifier in any payload (FR-006);
CSP `script-src 'self'` means no inline script; off by default (FR-014)

**Scale/Scope**: ~30 routes in `App.tsx`; 4 key actions; traffic unmeasured (assumed
well under the free plan's reported ~100K events/month)

## Constitution Check

| Rule | Applies? | Status |
|---|---|---|
| Migrations append-only | No migration | Pass |
| `api.ts` mirrors Java records | No backend record touched | Pass |
| Don't retune constants to match guesses | None touched | Pass |
| `Map.of` nulls in JSON paths | No backend | Pass |
| Ask before committing | Commit/push/PR wait for Allan | Pass |
| Planning doc scope ≠ verified spec | Call sites read in the code (R5), not taken from the spec's examples | Pass — and it changed US2's examples (amendment below) |

Post-design re-check: unchanged, pass.

## Design

1. **`web/src/analytics.ts`** — per [contracts/analytics-module.md](contracts/analytics-module.md).
   Route-template table keyed on `App.tsx`'s paths with `matchPath`, parameter segments
   renamed to neutral names (`:league`, `:draft`, `:manager`, `:player`, `:session`).
2. **`main.tsx`** — `initAnalytics()` before render.
3. **`AppShell.tsx`** — AppShell lives outside `<Routes>` and survives Keyed* remounts, so it
   is the one place that sees every route change exactly once. Effect on
   `location.pathname` → `trackPageView`. A second effect on `railLeague` (already resolved
   there, `railLeague.ts:209`) → `trackLeagueView` once per pathname, using
   `railLeague.lineage.current.sleeperLeagueId` and `.sport`.
4. **Key-action call sites** — `SignIn.tsx:43`, `DraftPicker.tsx:196`, `MockSetup.tsx:120`,
   `LiveDraftView.tsx:359`, `LiveStatusBar.tsx:65`; one `trackAction` line after each
   awaited success.
5. **Disclosure (FR-012)** — one sentence on the sign-in screen beside the existing
   "No password" line (`SignIn.tsx:95`): anonymous page counts and league, never your
   username, no cookies. Update `SignIn.test.tsx`.
6. **CSP (FR-011)** — `web/serve.json`: add `https://cloud.umami.is` to `script-src` and
   `connect-src`. DEPLOY.md "Security headers" list updated.
7. **Build config** — `web/Dockerfile`: `ARG VITE_UMAMI_WEBSITE_ID` / `ENV`, beside
   `VITE_API_BASE`. DEPLOY.md: env-var table row and an "Analytics" section (create the
   Umami site, set the build variable, exclude your own browser, numbers are a floor and
   start on deploy day — FR-010).
8. **Tests** — `analytics.test.ts`: off without id (no script element, no throw); template
   mapping for every route incl. not-found; no id-like segment survives (`/\d{6,}/` never in
   `url`); referrer reduced to outside origin, first view only; de-dupe of repeated pathname;
   queue-then-flush; a throwing `umami.track` doesn't propagate; the App.tsx-paths coverage
   test. AppShell test: a navigation produces exactly one page view.

## Spec amendments forced by planning (2026-10-28, amended at plan time)

- **US2 examples**: "running a draft simulation", "comparing two players" and "opening power
  rankings" are not discrete user actions in this code — the sim runs itself on page open and
  re-runs per pick; the others are page views. The v1 key-action list is the four in R5.
- **FR-009 / FR-010**: with a hosted dashboard (FR-013), "visible only to the owner" is the
  owner's login at Umami, and the "this is a floor / measurement began on" label can't be
  put on Umami's screens. It goes in DEPLOY.md's Analytics section and HANDOFF.md instead.

## Not built, and why

- No owner page inside the site — the hosted dashboard is the owner view (FR-013).
- No per-person attribution, no `identify()`, no `data-distinct-id` (clarified FR-006).
- No Web Vitals (`data-performance`) — out of scope per spec Assumptions.
- No backend events (simulations served, ingests) — server-side metrics are out of scope.
- The Umami account itself is **not** created by the build: creating an account and the
  website id is Allan's step.

## Project Structure

### Documentation (this feature)

```text
specs/052-site-analytics/
├── spec.md, research-notes.md, research.md, data-model.md, quickstart.md, plan.md
├── contracts/analytics-module.md
└── tasks.md   (next)
```

### Source Code

```text
web/
├── src/analytics.ts               (new)
├── src/analytics.test.ts          (new)
├── src/main.tsx                   (init)
├── src/components/AppShell.tsx    (page + league views)
├── src/components/LiveStatusBar.tsx, src/pages/{SignIn,DraftPicker,MockSetup,LiveDraftView}.tsx
├── serve.json                     (CSP)
└── Dockerfile                     (build arg)
DEPLOY.md, HANDOFF.md
```

**Structure Decision**: front-end only, inside the existing `web/` app.

## Complexity Tracking

None.

## Amended after review (2026-10-28)

`plan-review.md` found 13 issues. Every one is taken, as follows. Where a finding rested
on how Umami behaves, I checked it by reading the live `https://cloud.umami.is/script.js`
(8,575 bytes, fetched 2026-10-28). That is reading the source, not running it.

- **F1 (critical): signed-out visitors see SignIn at every path** (`App.tsx:121-204`).
  When signed out, every page view goes to `/(landing)` plus the route template. A shared
  link opened signed out records `/(landing)/leagues/:league/power`, titled "Landing (from
  League power rankings)". That keeps spec 050's question ("do shared links bring people in")
  answerable without counting it as a league page view.
- **F2: signing in or out changes the page but not the pathname.** The page-view effect
  and the de-dupe both key on `signedIn + pathname`.
- **F3: the rail league is stale across navigation** (`railLeague.ts:248`). `league-view`
  is sent only when the resolved `railLeague.season` matches the league ref expected for
  the *current* pathname: the path's league or draft id, or the hint on hinted routes. It
  is latched per `signedIn + pathname`, after that match.
- **F4: the shorthand form leaks.** Confirmed in the script source: `track(name, data)`
  builds `{...defaults(), name, data}`, where the defaults include the raw URL and
  `document.title`. Object form sends `{...e}` and nothing else. So `analytics.ts` builds
  every payload itself (`website`, `url`, `title`, `hostname`, `language`, `screen`,
  optional `referrer`, and `name` + `data` for events), and calls only `umami.track(object)`.
  A test asserts that no call ever passes a string first argument.
- **F5: `LiveStatusBar.tsx:65` is the manual Refresh button, not following a draft.**
  `live-draft-followed` is dropped. Live-room page views (`/drafts/:draft/live`) answer the
  question. The v1 key-action list is now three: `sign-in`, `league-added`, `mock-started`.
- **F6: `league-added` was missing two add paths.** Fire it at all three: the one-click
  Sleeper setup (`DraftPicker.tsx:152-177`), the pasted id (`:196`) and ESPN
  (`EspnLeagueSetup.tsx:68-74`). Props: `via: 'sleeper' | 'id' | 'espn'`.
- **F7: two copies of the landing copy.** The disclosure sentence goes in `landingCopy.ts`
  and renders in both `SignIn.tsx` and the static `landingHtml.ts`. Update both tests.
- **F8: don't rely on the service to exclude dev and owner visits.** `analytics.ts`
  itself refuses to load unless `location.hostname` is `www.ballknowers.co` or
  `ballknowers.co`. It also refuses if `localStorage['umami.disabled']` is set, the same
  flag the script checks itself (confirmed in source), so the owner opt-out works in both
  layers. `data-domains` stays as a second guard.
- **F9: collection host.** It is `https://gateway.umami.is/api/send` when `data-host-url`
  is unset (confirmed in source). The CSP gets `https://cloud.umami.is` in `script-src`
  and `https://gateway.umami.is` in `connect-src`. For payload checks off production, a
  dev-only `VITE_ANALYTICS_DEBUG=1` logs the would-be payloads to the console instead of
  loading the script. The real capture in production stays a verification step.
- **F10:** SC-002 needs two different browsers *on different networks* (or a phone on
  cellular), because the cookie-free visitor hash uses IP plus user agent (inferred).
  Bots: crawlers that don't run JS never load the tracker, and the service filters known
  bot user agents (inferred, not checked).
- **F11:** the route table is a `RouteObject[]` matched with `matchRoutes`, which ranks
  matches. Tests cover `/mock/new` vs `/mock/:sessionId` and `/stats/calculator` vs `/stats`.
- **F12:** accepted. The auction-draft redirect counts two views; documented in DEPLOY.md.
- **F13:** type `VITE_UMAMI_WEBSITE_ID` and `VITE_ANALYTICS_DEBUG` in `vite-env.d.ts`.
  Read env lazily, behind a test-only reset. Treat the apex and any `*.ballknowers.co`
  origin as our own for referrers. Document that the league id changes at season rollover,
  because Sleeper issues a new id each season.
- **Also found while reading the script:** it has an error reporter that sends stack traces
  and the raw URL. It is off unless `data-errors="true"`. We never set it, and a test
  asserts the attribute is absent.
