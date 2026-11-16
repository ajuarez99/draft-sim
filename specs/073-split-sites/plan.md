# Implementation Plan: Split the website into a Time Machine site and a league site

**Branch**: `073-split-sites` | **Date**: 2026-11-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/073-split-sites/spec.md`

## Summary

One `web/` codebase is built in one of three modes chosen by a build variable, `VITE_SITE`:

| `VITE_SITE` | What it serves | Where |
|---|---|---|
| unset (`combined`) | Exactly today's single site, Time Machine at `/history` | Today's production service until Allan flips it; local default |
| `timemachine` | Only the Time Machine, at `/` and `/game/:gameId`, in its own shell | `www.ballknowers.co` |
| `leagues` | Everything except the Time Machine | `leagues.ballknowers.co` |

The third mode exists for one reason: pushes to `main` deploy the web service. If the code changed behaviour the moment it merged, the main address would lose or duplicate something before the second service and DNS exist. With `combined` as the default, merging changes nothing visible; the split happens when Allan sets variables (rollout order in [quickstart.md](quickstart.md)).

The backend is not edited. Its `CORS_ORIGINS` variable gains the league origin. No migration.

**Stated plainly, not hidden**: both split builds still contain all the page code. The mode decides which routes exist, which shell wraps them, what the delivered HTML says and where old paths redirect. A visitor cannot reach the other site's pages, but the JavaScript file is not smaller. Trimming the bundle per site is not built (see "Not being built").

## Technical Context

**Language/Version**: TypeScript 5 (strict), React 18, react-router-dom 6, Vite 6.4.3 (corrected after review, PR-13: the draft said Vite 5); Node 20 for the build

**Primary Dependencies**: none added

**Storage**: none. Browser `localStorage` key `bk.user.v1` is per origin and stays as it is

**Testing**: vitest + Testing Library (`cd web && npm test`), `npx tsc -b`, `npm run build` once per mode, then live checks in the browser against a real backend

**Target Platform**: two static sites served by `serve` on Railway; one Spring Boot backend, unchanged

**Project Type**: web application (frontend-only change plus deploy configuration)

**Performance Goals**: no regression. No new requests on either site; the Time Machine shell makes fewer (no league list fetch)

**Constraints**: merging to `main` must not change production behaviour until variables are set; both split sites must run locally side by side against one backend

**Scale/Scope**: about 12 source files edited, about 8 added, the web test suite extended; `DEPLOY.md` and `.claude/launch.json` updated

## Constitution Check

| Hard rule | Applies? | How this plan meets it |
|---|---|---|
| Migrations append-only | No | No schema change |
| `api.ts` mirrors Java records | No | No record or endpoint changes |
| Never retune a hand-set constant | No | None touched |
| `Map.of` null trap | No | No backend code |
| Ask before committing | Yes | Nothing is committed, pushed or deployed without Allan's go-ahead |
| Planning doc scope is not a verified spec | Yes | Every "what is there now" claim cites a file read on `origin/main` d3a29f0; the plan review checks them cold |

No violations. Re-checked after design: unchanged.

## Design

### 1. One module owns "which site is this" — `web/src/site.ts` (new)

> **Amended after review, 2026-11-15 (PR-01 HIGH, measured; PR-07, measured).** The first draft had `site.ts` read `import.meta.env` itself. The reviewer ran it: inside `vite.config.ts` `import.meta.env` is `undefined` and `site()` / `tmOrigin()` throw. The draft also left `VITE_TM_ORIGIN` / `VITE_LEAGUES_ORIGIN` with no Node-side source, and kept `SITE_ORIGIN` (`landingCopy.ts:15`) and `IMAGE_URL` (`landingHtml.ts:12`) as module constants that cannot follow a site chosen later. Rewritten below: pure functions over an explicit config, one browser-only reader, the build passes its own config in.

`site.ts` is pure: no `import.meta`, no `process`, no browser globals. Everything takes a `SiteConfig`.

- `type Site = 'combined' | 'timemachine' | 'leagues'`
- `type SiteConfig = { site: Site; tmOrigin: string; leaguesOrigin: string }`
- `parseSite(raw)` — **trims first** (a Windows `set X=value && cmd` delivers `"value "`; measured), then: `undefined`/empty is `combined`; the two names map to themselves; **anything else throws**, naming the value and the three accepted ones. A typo in a Railway variable must fail the build.
- `makeConfig({ VITE_SITE, VITE_TM_ORIGIN, VITE_LEAGUES_ORIGIN })` — parses the site, defaults the origins to `https://www.ballknowers.co` / `https://leagues.ballknowers.co`, strips trailing slashes.
- `siteOrigin(cfg)` — canonical origin of the site being built: `tmOrigin` for `combined` and `timemachine`, `leaguesOrigin` for `leagues`.
- `tmHome(cfg)`, `tmGame(cfg, id)` — `/history`, `/history/<id>` in `combined`; `/`, `/game/<id>` in `timemachine`; throw for `leagues`.
- `LEAGUE_PREFIXES = ['/leagues', '/drafts', '/mock', '/managers', '/admin']` — old league-site paths the Time Machine site forwards (`/admin` added after review, PR-08: the operator's `/admin/digest` bookmark).
- `crossSiteUrl(targetOrigin, pathname, search, hash)`.

Two readers build the config, one per runtime:

- `web/src/siteEnv.ts` (new, browser/vitest only): `currentSite(): SiteConfig` = `makeConfig(import.meta.env)`. A function, so tests can `vi.stubEnv`. `vite-env.d.ts` declares the three variables.
- `vite.config.ts`: `makeConfig(loadEnv(mode, process.cwd(), 'VITE_'))`. The reviewer confirmed `loadEnv` sees a plain process variable (Docker `ENV`) with no `.env` file, and that a throw here fails the build with exit 1.

`landingCopy.ts` and `landingHtml.ts` lose their module-level `SITE_ORIGIN` / `IMAGE_URL`; `transform(html, cfg)`, `headTags(cfg)` and `jsonLd(cfg)` take the config. Anything in the app that needs the origin calls `siteOrigin(currentSite())`.

In the sections below, read `site()` as `currentSite().site`, and `tmHome(site())` as `tmHome(currentSite())`.

### 2. Routes — `web/src/App.tsx`

`App` reads `site()` once and renders one of three trees.

- **`combined`**: today's tree, untouched (AppShell, the user gate, every route including `/history`).
- **`leagues`**: today's tree minus the two `/history` routes. In their place, **outside the signed-in gate**, `/history` and `/history/:gameId` render `<CrossSiteRedirect>` to `tmOrigin()` + `/` or `/game/<id>`. Outside the gate so a signed-out visitor with an old Time Machine link is not asked to sign in to the league site just to be sent away.
- **`timemachine`**: `<TimeMachineShell>` wrapping:
  - outside the gate: `/history` → `<Navigate to="/" replace>`; `/history/:gameId` → `<Navigate to="/game/<id>" replace>`; each `LEAGUE_PREFIXES` entry with `/*` → `<CrossSiteRedirect>` to `leaguesOrigin()` + same path, query and hash.
  - signed out, any other path: `<TimeMachineSignIn>` (the front door). The URL is left alone, so after sign-in the route the link named renders (FR-013; this is how the single site behaves today, `App.tsx:128`).
  - signed in: `/` → `HistoricalDraft`, `/game/:gameId` → keyed `HistoricalRoom`, `*` → `NotFound`.

`CrossSiteRedirect` (new, small): on mount calls `window.location.replace(url)` and renders a one-line "Taking you to …" with a plain `<a href>` to the same URL as the fallback. That anchor is the only cross-site link either site can render and only appears on a redirect path (FR-015 allows exactly this).

`document.title`: `combined`/`leagues` keep today's rule; `timemachine` uses the Time Machine landing title signed out and `Time Machine` signed in.

Redirects are client-side on purpose. The sites are static files behind `serve -s` (every path answers with `index.html`), and the paths being redirected are app pages no crawler indexes. Server-side redirect rules would need a second `serve.json` per site for no reader's benefit.

### 3. Time Machine links stop hard-coding `/history`

Every `'/history'` and `` `/history/${id}` `` in Time Machine files becomes `tmHome(site())` / `tmGame(site(), id)`:
`pages/HistoricalDraft.tsx` (lines 145, 158, 243, 247, 351, 357), `pages/HistoricalRoom.tsx` (257, 324, 343), `components/HistoricalProfileCard.tsx` (34), and the share link `components/HistoricalResults.tsx:82` (`window.location.origin + tmHome(site())`, so in `timemachine` it is the bare origin). The builder greps for any the list above missed; a test fails if a `/history` literal remains in a `Historical*` file.

### 4. Shells and navigation

- **`components/TimeMachineShell.tsx`** (new): skip link, a slim bar (wordmark "Ball Knowers" linking to `/`, account button with the same sign-out menu the top bar has), `<main>`. It reuses the existing top-bar and account-menu classes; no league header, no jump-to search, no Ctrl+K binding, no league list fetch. Signed out it renders only its children, like `AppShell`. It calls `trackPageView` on path / signed-in change exactly as `AppShell` does, so the Time Machine site is still counted.
- **`TopBar.tsx:120-122`, `NavSheet.tsx:104-106`**: the Time Machine link renders only when `site() === 'combined'`.
- Jump-to search (`searchIndex.ts`) has no Time Machine entry today (checked by grep; the plan review re-checks). A test pins that for `leagues`.

### 5. Front doors and delivered HTML

- **League landing** (`landingCopy.ts`, `SignIn.tsx`): words unchanged. `SITE_ORIGIN` becomes `siteOrigin(...)` so the canonical link, `og:url` and image URL name `leagues.ballknowers.co` in the `leagues` build and stay `www` in `combined`.
- **Time Machine landing** — `tmLandingCopy.ts` (new: title, description, headline, steps, image alt; pure data) and `pages/TimeMachineSignIn.tsx` (new). The username form is lifted out of `SignIn.tsx` into `components/SignInForm.tsx` and used by both pages, so the "Sleeper recognises the name" logic exists once. Styled inside the existing `.tm` scope.
- **`landingHtml.ts`**: `transform(html, site)` — for `timemachine` it writes the Time Machine title, description, canonical, og/twitter tags, JSON-LD and a static body from `tmLandingCopy.ts`; otherwise today's output with the per-site origin. `boot.js` and the `.landing-prerender` hide rule already cover a signed-in visitor and apply to both.
- **robots.txt and sitemap.xml** leave `web/public/` and are emitted by the Vite plugin (`generateBundle`) from `siteOrigin(site)`, so each site's files name its own address. In dev the plugin serves the same text from a small middleware.
- **Share image**: `public/og-image.png` stays the league image. A Time Machine image is added as `scripts/og-image/og-image-tm.svg` → `public/og-image-tm.png` using the existing one-off render script (`scripts/og-image/render.mjs`, which needs `@resvg/resvg-js` installed outside `web/`). The `timemachine` head tags point at `og-image-tm.png`.

### 6. Analytics — `web/src/analytics.ts`

- `OWN_HOSTS` gains `leagues.ballknowers.co`, so the league site reports in production. Each site sends its own hostname, which is what keeps the streams apart (FR-016); the website id is whatever each service's `VITE_UMAMI_WEBSITE_ID` is.
- `ROUTES` gains `/game/:gameId` ("Time Machine game"); `/` is titled "Time Machine" when `site() === 'timemachine'`. `analytics.test.ts` compares `ROUTES` with `App.tsx`'s path strings; it is extended to cover the union of the three trees.
- `outsideReferrer()` already treats every `*.ballknowers.co` host as inside; unchanged, so a redirect between the sites is not counted as an outside referral.

### 7. Build and deploy configuration

- `web/Dockerfile`: `ARG`/`ENV` for `VITE_SITE`, `VITE_TM_ORIGIN`, `VITE_LEAGUES_ORIGIN` beside the two existing ones.
- `web/.env.example`: the three variables, with the local pairing.
- `web/serve.json`: unchanged. `connect-src` already names `api.ballknowers.co`; the policy is report-only and does not govern navigation.
- `.claude/launch.json`: `draft-sim-web-tm` (port 5184, `VITE_SITE=timemachine`, `VITE_LEAGUES_ORIGIN=http://localhost:5185`) and `draft-sim-web-leagues` (port 5185, `VITE_SITE=leagues`, `VITE_TM_ORIGIN=http://localhost:5184`). Both proxy `/api` to the local backend, so local runs need no CORS change.
- `DEPLOY.md`: a "Two sites" section with the variables per service and the rollout order.

## Project Structure

### Documentation (this feature)

```text
specs/073-split-sites/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/site-routes.md
├── checklists/requirements.md
└── tasks.md            (from /speckit-tasks)
```

### Source Code

```text
web/
├── vite.config.ts                    edit: parse VITE_SITE, pass to HTML plugin, emit robots/sitemap
├── Dockerfile, .env.example          edit: three build variables
├── public/robots.txt, sitemap.xml    delete (now emitted)
├── public/og-image-tm.png            new
├── scripts/og-image/og-image-tm.svg  new
└── src/
    ├── site.ts (+ site.test.ts)                  new
    ├── App.tsx (+ App.site.test.tsx)             edit: three route trees
    ├── tmLandingCopy.ts                          new
    ├── landingCopy.ts, landingHtml.ts            edit: per-site origin, Time Machine variant
    ├── analytics.ts                              edit: hosts, routes
    ├── components/TimeMachineShell.tsx           new
    ├── components/CrossSiteRedirect.tsx          new
    ├── components/SignInForm.tsx                 new (lifted from SignIn.tsx)
    ├── components/TopBar.tsx, NavSheet.tsx       edit: link only in combined
    ├── components/HistoricalResults.tsx, HistoricalProfileCard.tsx   edit: path helpers
    ├── pages/TimeMachineSignIn.tsx               new
    ├── pages/SignIn.tsx                          edit: use SignInForm
    └── pages/HistoricalDraft.tsx, HistoricalRoom.tsx                 edit: path helpers
.claude/launch.json, DEPLOY.md        edit
backend/                              not touched
```

**Structure Decision**: frontend-only, inside the existing `web/` layout. No new package, no new service code.

## Not being built, and why

- **Per-site bundle trimming** (lazy-loaded route sets so the Time Machine file omits league pages). Not asked for, and it would put `Suspense` under every existing route test. A later change if load time ever matters.
- **Backend, database or shared-module separation.** Allan confirmed the website-only reading.
- **Anonymous Time Machine play.** Still needs a Sleeper username.
- **Carrying sign-in across the two addresses.** Would need a shared cookie or a hand-off page; a second sign-in is accepted.
- **Server-side redirects.** See Design 2.
- **Any visible link between the sites.**
- **Deploying, DNS, Railway variables.** Allan's steps, written up in `DEPLOY.md` and `quickstart.md`.

## Acceptance criteria for the verification pass

Run for real, in the browser, against a local backend with data:

1. `combined` (no variable): the site behaves as `origin/main` does, Time Machine link present, `/history` works. This is what merging ships.
2. `timemachine`: spec User Story 1 scenarios 1-5 at phone and desktop width; SC-001 by reading the page tree for league links.
3. `leagues`: User Story 2 scenarios 1-4; SC-005's page list for one NFL and one NBA league.
4. All five User Story 3 redirects, each on the site it names, signed in and signed out, with a query string.
5. SC-004: a user with existing Time Machine games sees them on the `timemachine` site.
6. SC-007: `npm run build` in each mode; `dist/index.html`, `dist/robots.txt`, `dist/sitemap.xml` read as text name only that site.
7. `parseSite('leauges')` fails the build.
8. `npm test`, `npx tsc -b` pass. Backend suite not run unless a backend file changed (none planned); say so in the write-up.

Not verifiable locally, to be named as such: real DNS, the Railway services, production CORS, Umami receiving the league host, how link-preview bots render the new tags.

## Amended after review (2026-11-15)

[plan-review.md](plan-review.md) read this plan cold against the code: 0 critical, 2 high, 8 medium, 3 low. Its baseline on untouched d3a29f0 was measured (183 test files / 3024 tests pass, `tsc -b` clean). Each finding and what changed follows. Where this section and an earlier section disagree, this section wins; Design 1 and the contract table were rewritten in place.

| Finding | What was wrong | Change |
|---|---|---|
| PR-01 HIGH (measured) | `site.ts` reading `import.meta.env` throws inside `vite.config.ts`; origins had no Node-side source; module-level origin constants | Design 1 rewritten: pure `site.ts`, `siteEnv.ts` for the browser, config passed into `transform` |
| PR-02 HIGH (read) | League wording would appear on the Time Machine site through shared pieces the plan did not list | `NotFound.tsx:17` ("Back to your leagues"), `ErrorBoundary.tsx:50-51`, the sign-in note at `SignIn.tsx:96-100` and `analyticsLine` each take per-site wording from one small `siteCopy(cfg)` table; `tmLandingCopy.ts` carries its own analytics disclosure so spec 052 FR-012 still holds. A test renders each on `timemachine` and fails on the words league, leagues, mock, manager |
| PR-03 (reasoned, not run) | "Both proxy `/api`, so no CORS change" is probably false: the proxy keeps the browser's `Origin`, and `application.yml:50` allows only 5173 | New launch entry `draft-sim-api-split` sets `CORS_ORIGINS` to 5173, 5184 and 5185. `application.yml` is not edited. The verification pass runs the reviewer's curl first and records whether the 403 is real |
| PR-04 (read) | League-side browser data stays on `www`: saved stat-calculator formulas (`statCalcSaved.ts:17`), six preferences, the analytics opt-out. Research R5 only looked at Time Machine state | **Allan's decision**, below. Research R5 corrected |
| PR-05 (read) | One flat `ROUTES` table would report redirect paths as page views on the wrong site; the coverage test rejects a non-literal `<Route path>`; `analytics.test.ts:119` pins the exact host list | `routesFor(site)` returns that site's table. Redirect routes are written as literal `<Route>` lines (no `.map`), sit outside the shell's page-view effect and send nothing. The coverage test runs once per tree; the host-list assertion is updated |
| PR-06 (reasoned) | `/game/:id` did not exist in `combined`, so links forwarded during the rollout window, or after a rollback, were stranded | `leagues` forwards `/history[/id]` to `TM/history[/id]`, which both `combined` and `timemachine` understand (a second, in-app hop after the flip; accepted). `combined` also serves `/game/:gameId` as an in-app redirect to `/history/:gameId`. Contract table rewritten |
| PR-07 (measured) | `set X=value && npm run dev` passes a trailing space | `parseSite` trims; launch entries are written `value&& ` |
| PR-08 (reasoned) | After the flip a signed-in league-mate opening the bare main address lands in the Time Machine with no pointer to their leagues; `/admin/digest` on `www` becomes not-found | `/admin` added to the forwarded prefixes. The pointer is **Allan's decision**, below |
| PR-09 (read) | `TimeMachineShell` underspecified | It renders `.app-shell > header.topbar + main.app-main#main` (the room's scroll and its `calc(100vh - 290px)` height at `styles.css:6989-6991` depend on those classes), wraps its routes in `ErrorBoundary`, and signs out to `/`. The account button and menu are lifted out of `TopBar.tsx:154-183` into `components/AccountMenu.tsx`, used by both bars |
| PR-10 (reasoned) | The spec's "missing allowed origin must be a visible error" had no design or check | Today that failure surfaces on sign-in as "Couldn't reach Sleeper just now" (`SignIn.tsx:108-112`): visible, not blank. Kept. The verification pass reproduces it (league dev server against a backend that does not allow its origin) and records what the screen says; `quickstart.md` names the symptom |
| PR-11 (low) | No parity tests for the Time Machine landing; its steps would duplicate `HistoricalDraft`'s; `transform()`'s new parameter breaks `landingHtml.test.tsx:14` | The "how to play" steps move to one exported list used by `HistoricalDraft` and the landing. A parity test (rendered page text equals static body text) and the existing banned-words test are extended to the Time Machine copy. Existing landing tests pass a config |
| PR-12 (low) | SC-006 "passes for both sites" was ambiguous | The suite runs once, in the default mode; per-mode behaviour is covered by tests that stub the variable. `tsc -b` once. `npm run build` three times. Spec SC-006 reworded |
| PR-13 (low) | Vite version; `vite-env.d.ts`; no self-redirect guard; local split builds would forward to production by default; query string on in-app redirects; Railway domain limit | Version corrected; file added to the list; `CrossSiteRedirect` renders `NotFound` instead of navigating when the target origin equals the current one; under `import.meta.env.DEV` with the other origin unset it shows the target URL as text and does not navigate; every redirect keeps query and hash; the Railway custom-domain limit on the Hobby plan is **unconfirmed** and is the first thing `quickstart.md` says to check |

Confirmed correct by the review, unchanged: the `/history` file:line list is complete; no Time Machine file depends on `AppShell`'s contexts or portals; route patterns match as the contract says with no loop between the split modes; emitting `robots.txt` from `generateBundle` works; the `.tm` scope, Nunito, `.landing-prerender` and `boot.js` cover a Time Machine landing; jump-to search has no Time Machine entry; the backend names no site address.

### Decisions put to Allan (both change what the feature is)

Answered by Allan, 2026-11-15.

**PR-08 → a dismissible "your leagues moved" notice on the Time Machine home.** Spec FR-015 amended, FR-019 added.

- Who sees it: a browser that arrives on the `timemachine` site already signed in from before the switch. Detection is a marker, not a guess at league-era keys: `TimeMachineSignIn` writes `bk.tm.signedInHere.v1` when someone signs in on the Time Machine site. A signed-in browser with no marker signed in when this address was the combined site. (Many league-mates have no preference keys at all, so "has league-era storage" would miss them.) A pre-switch Time Machine-only player also sees it once; harmless.
- What it is: one line above the Time Machine home, "Looking for your leagues? They moved to leagues.ballknowers.co." with a link and a dismiss button. `components/LeaguesMovedNotice.tsx`, rendered by `HistoricalDraft` only when `site === 'timemachine'`.
- Gone for good after dismiss or click: `bk.tm.movedNotice.dismissed.v1`. Storage blocked: the notice shows and dismiss lasts for the page load.
- The link goes to `leaguesOrigin + '/'` through the same forwarding helper as the redirects, so it carries formulas (next item).
- Not shown in `combined` or `leagues`, and never on the signed-out front door.

**PR-04 → carry saved stat-calculator formulas across; preferences and the analytics opt-out reset.** Spec FR-020 added.

- New `web/src/siteCarry.ts`, pure apart from the storage and location it is handed:
  - `withCarry(url, storage)` — used by every Time Machine → league forward (`CrossSiteRedirect` and the notice link). Reads `bk.statCalc.saved.v1`; if it holds at least one formula, appends it to the URL fragment as `bk-carry=<base64url JSON>`, placed before any fragment the link already had (`#bk-carry=<data>;<original>`). The fragment is used because it is never sent to a server or written to an access log. Nothing is deleted on the old address.
  - `acceptCarry(location, history, storage)` — called once in `main.tsx` before the app renders, in the `leagues` build only. If the fragment has a carry: decode, pass every entry through `statCalcSaved`'s own reader/validator, and write **only if this site has no saved formulas**; then strip the carry from the address bar with `history.replaceState`, restoring any original fragment. Bad or oversized data is dropped silently and still stripped.
- Bounds: `SAVED_MAX` is 50 and names are capped (`statCalcUrl.NAME_MAX`), so the fragment stays in the low kilobytes. If the encoded carry exceeds 6,000 characters (a hand-set limit, not a measured browser one) it is not attached.
- It only happens when someone is forwarded. A league-mate who types the league address directly starts with no saved formulas there; their old ones stay readable on the main address's storage and will be carried the first time any forward happens. Stated in `DEPLOY.md`.
- **Amended after code review, 2026-11-15 (CR-01, CR-03).** The "low kilobytes" bound above was a guess; measured, it was 3-4x larger: about 320 encoded characters per formula, so 50 formulas are about 16,000 (15,952 for short names), not low kilobytes. The 6,000-character cap therefore dropped the whole carry from 20 formulas up. The cap is now 24,000 (hand-set, with headroom over 16,000 for longer names), and when the list is still over it the newest formulas (by `savedAt`) that fit are carried instead of none. "Has none of its own" now means "has never saved any": the carry is written only when `bk.statCalc.saved.v1` is absent (or unparseable), so formulas deleted on the league site do not come back on a later forward. The original sentences above are left as written.
- Not carried, by decision: the six display preferences (they default), and `umami.disabled`. **Allan's own analytics opt-out must be set again on `leagues.ballknowers.co`** — in the rollout steps.

## Complexity Tracking

| Addition | Why needed | Simpler alternative rejected because |
|---|---|---|
| A third mode, `combined` | Merge to `main` auto-deploys; the split must wait for DNS and the second service | Two modes with a default of either split site changes production the moment the code lands |
