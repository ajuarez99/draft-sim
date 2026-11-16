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

## Amended 2026-11-16: the league site is locked to Allan

Allan's decision: the league site (the `leagues` build only) is now for him alone. It asks for the admin token before it shows anything. Spec FR-021 (new), FR-019 (withdrawn), FR-015 (no exception again), FR-009 (amended). The sections above are left as written; where they disagree, this section wins.

**What is built.**

- `leaguesLock.ts`: the unlocked state, `isUnlocked()`, `unlock(token)`, `lock()`, `useUnlocked()`, same subscribe shape as `user.ts`. Persists only a flag, `bk.leagues.unlocked.v1` = `"1"`, in `localStorage`. The token itself goes to `sessionStorage` under `bk.adminToken` (the key the digest page uses, now defined once in `adminToken.ts` and imported by both), so `/admin/digest` does not ask again in that tab. The token is never written to `localStorage`. Storage blocked: the unlock lasts for the page load.
- `components/LeaguesLock.tsx`: a centered card, "Ball Knowers", "This site is private.", a password input labelled "Admin token", an "Unlock" button ("Checking…" while asking). `role="alert"` messages: "That token wasn't accepted." and "Couldn't reach the server. Try again." No links, no league wording, no mention of Sleeper.
- `App.tsx`, `LeaguesTree` only: while locked, `LeaguesLock` replaces everything inside the sign-in gate (landing, sign-in, shell, every league route) at the requested URL, which is left alone. `/history` and `/history/:gameId` stay outside the lock, as they are outside the gate. `acceptCarry` in `main.tsx` still runs first. The tab title while locked is "Ball Knowers".
- `AppShell`'s sign-out also calls `lock()` when the site is `leagues`. Nothing else in the shell changed.
- Analytics: `trackPageView` is called from `AppShell`, which is not mounted while locked, so a locked visit sends no page view. (The Umami script itself still loads; it sends nothing without a view.)
- Delivered files, `leagues` only: `<title>Ball Knowers</title>`, `<meta name="robots" content="noindex, nofollow">`, the theme colour and `boot.js`; no description, canonical, og/twitter tags or JSON-LD; an empty `<div id="root">` (no pre-rendered landing). `robots.txt` is `User-agent: *` / `Disallow: /` with no Sitemap line. No `sitemap.xml` is emitted, and the dev server answers 404 for it. `combined` and `timemachine` output is unchanged (the snapshot and parity tests pass untouched).

**How the token is checked, and why that route.** `checkAdminToken(token)` in `api.ts` calls `GET /api/admin/digest?date=check` with `X-Admin-Token`, through `apiFetch`. No backend route was added. `/api/admin/**` goes through `AdminGateInterceptor` before any controller, so a wrong or missing token (or a server with no `ADMIN_TOKEN`) is refused `403 {code:"admin_token_required"}`. With the right token the request reaches `AdminDigestController.get`, which parses `date` first and answers `400 {code:"bad_date"}` for `"check"` before building a digest: nothing is read from the database or written. So 403 + `admin_token_required` is "wrong", 400 + `bad_date` is "ok", and anything else (network error, another status, an unparsable body, a proxy error page) is "unreachable", never "ok". Read from both Java files on 2026-11-16 and covered by `api.checkAdminToken.test.ts` with `fetch` mocked; not run against a live backend.

**What is deliberately NOT protected.** This is a front-door lock, by Allan's choice, not access control and not security.

- Every league API route (leagues, drafts, managers, mocks, Sleeper lookups) answers exactly as before, with or without the token. Anyone who knows or guesses an API address can read what those routes return.
- The unlock flag is a plain `localStorage` value. Setting `bk.leagues.unlocked.v1` to `1` by hand in devtools opens the door; no token is checked on a later visit.
- The admin routes were always gated by the token on the server; that has not changed.
- `noindex` and `Disallow: /` are requests that well-behaved crawlers honour, not enforcement.

**FR-019's notice was removed.** With the league site private, "your leagues moved" would send league-mates to a locked door. Deleted: `LeaguesMovedNotice.tsx` and its test, `tmSignedInHere.ts`, the `onSignedIn` wiring in `TimeMachineSignIn` (and `SignInForm`'s `onSignedIn` prop, which nothing else used), the render in `HistoricalDraft`, the `.tm-moved` CSS, `TimeMachineSignIn.test.tsx` (it tested only the marker), and the notice exclusions in `tmSite.wording.test.tsx`. Kept: the cross-site redirects (old league links on the Time Machine site forward to the league origin) and the formula carry (`siteCarry.ts`), which still serve Allan. PR-03's own text above describes the removed notice and is left as written.

**Operational requirement.** The check needs the backend's `ADMIN_TOKEN` set in production. DEPLOY.md notes it as not yet set. With it unset, every token is refused and nobody, Allan included, can open the league site. Set it before the flip.

## Amended 2026-11-16 (second): the league API is locked, 5-minute idle session

Allan reversed "front door only" the same day: "lock the api and once i entered it lets me browse for 5 min or something". He chose a session that stays alive while he is active and re-locks after 5 minutes idle, entered with the admin token, for him only. The Time Machine stays fully public. The "What is deliberately NOT protected" list in the section above is **superseded for the API** (its first, second and fourth bullets); the front-door description is kept as the history of the first pass. Spec: FR-021 amended, FR-022 to FR-025 new.

**Backend (no migration).**

- `config/LeagueLockProperties` (`draftsim.league-lock`: `enabled` from `LEAGUE_LOCK`, default **false**; `idle-seconds` from `LEAGUE_LOCK_IDLE_SECONDS`, default 300, labelled arbitrary where it is defined).
- `config/LeagueSession`: stateless signed tokens, `<expiryEpochSeconds>.<base64url(HMAC-SHA256(key, "league-session-v1:" + expiry))>`, key = HMAC-SHA256(adminToken, "league-session-key-v1"). Constant-time compare, takes a `Clock`, fails closed with no admin token. **Rotating `ADMIN_TOKEN` invalidates every session.** A token is never logged.
- `api/LeagueSessionController`: `POST /api/admin/session` (under the existing admin gate, so a wrong or missing token is already the 403 `admin_token_required`; works with the lock on or off) answers `{token, expiresInSeconds}`; `GET /api/league-session` is a locked route that answers 204 so the web can renew and confirm a stored session.
- `api/LeagueLockInterceptor` on `/api/**`, registered after the admin gate, with the public allowlist **excluded in the registration** so Spring's own PathPattern machinery (the same that picks the handler) decides it, not string compares on the URI. Lock off: passes everything. OPTIONS passes. A valid `X-Admin-Token` passes (operator curl). A valid `X-League-Session` passes and the response carries a freshly issued `X-League-Session` (sliding window). On the live-stream route only, a valid `session` query parameter is accepted too (EventSource cannot send headers); that route is recognised by the handler's matched pattern (`/api/drafts/{sleeperDraftId}/live-stream`), not the raw URI. Refusal: 401 `{"code":"league_session_required"}`, identical for missing, malformed, expired and badly signed.
- `WebConfig`: interceptor registration; `X-League-Session` added to the CORS allowed **and** exposed headers; one startup WARN when the lock is enabled with no admin token.

**Public allowlist** (`LeagueLockInterceptor.PUBLIC_PATHS`; everything else under `/api/**` is locked by default):

| Pattern | Why it stays open |
|---|---|
| `/api/health` | liveness and reachability |
| `/api/refresh/daily` | the external daily job, guarded by its own `X-Refresh-Secret` (`RefreshController` returns 404 with no secret configured, 401 on a wrong one). `/api/refresh/players` has **no** secret (only an `X-Sleeper-User` presence check), so it stays **locked** |
| `/api/history/**` | the Time Machine: seasons, games, pick/auto/abandon, daily, profile, profile/seen, games/{id}/award (both `/api/history` controllers read) |
| `/api/sleeper/user/**` | username lookup behind sign-in on both sites. `/api/sleeper/users/{id}/leagues` is league data and stays locked |
| `/api/admin/**`, `/api/ingest/**` | already behind the admin-token gate |

**Web.** `leaguesLock.ts` is now "holds a session whose expiry is in the future"; the record `{token, expiresAt, windowSeconds}` lives in `localStorage` under `bk.leagueSession.v1` (a 5-minute credential, so a new tab inside the window works). The 073 flag `bk.leagues.unlocked.v1` is gone: ignored and deleted on load. `api.unlockLeagueSite` does the POST (the brief placed `unlock` in `leaguesLock`; it lives in `api.ts` because `leaguesLock` cannot import `api` without a cycle). `apiFetch` sends `X-League-Session`, adopts a renewed one, and on a 401 `league_session_required` locks (the body is read from a clone). `useLiveDraft` adds `session=` to the EventSource URL. `useIdleLock` (mounted only while unlocked on `leagues`) listens for pointerdown, keydown, wheel, scroll and touchstart; every 60 s it locks if the expiry has passed, otherwise renews (`GET /api/league-session`) if the user was active and the tab is visible; it also checks expiry when a hidden tab becomes visible. The lock screen shows "Locked after N minutes without activity." after an idle lock (N from the last window) and nothing on a first visit or after sign-out. `checkAdminToken` and its digest-date trick are removed.

**A decision of mine, not in the brief.** A background poll (the rail's refresh-status poll, live-draft fetches) hits locked routes and would, through the sliding header, renew the session forever. So the client adopts a renewed token only if the user did something in the last 60 s (`ACTIVE_GRACE_MS`, arbitrary: one watcher tick). Cost: the worst case from last input to lock is about the window plus 60 s, not exactly the window. The server stays sliding; the client decides whether to take the new token.

**What this does and does not protect.**

- Protects: a stranger who finds an API address, or a league-mate with a Sleeper id, cannot read league routes without the admin token or a session minted from it.
- A stateless token **cannot be revoked** before it expires (at most one idle window). Rotating `ADMIN_TOKEN` kills all of them.
- The live-stream token is in a **URL**, so it appears in request logs (proxies, Railway). It is short-lived and accepted on that one route only.
- **Anyone holding the admin token has full access** (the same token already gates ingest and overrides the membership checks).
- Data already rendered stays in the DOM, memory and any screenshot or cache until the lock screen replaces it. The lock screen replaces the page at the same URL; it does not wipe the browser.
- The **Time Machine API stays public by design**, and `/api/health` and the username lookup with it.
- `API_TOKEN` / `VITE_API_TOKEN` (the shared bearer, off in production) is a separate, unrelated mechanism and is unchanged.
- The lock is checked at the interceptor. A handler that is not mapped under `/api/**` would not be covered; none exists today (every controller is mapped under `/api`).
- Nothing is verified in a browser; see build-notes.md.

**Enabling it** is a deploy-day step, after the league site is split off (DEPLOY.md "Two sites"). With `LEAGUE_LOCK` unset, nothing changes for the live combined site.

### Amended after lock review, 2026-11-16 (CR-01..CR-12)

The bug-hunting review of the lock (`code-review-lock.md`) found no bypass and twelve smaller problems. The text above is left as written; where it is now wrong, this section says so. What changed, in the review's order:

- **Idle handling (CR-01, CR-04, CR-05). The earlier claim that the worst case from last input to lock "is about the window plus 60 s" was wrong: the reviewer measured about 7 minutes (60 s grace + 300 s window + up to 60 s to the next tick), and an active user could be locked at the tick because input alone never renewed.** Now: the activity handler itself renews (past half the window, none in flight, at least 15 s since the last one); a `setTimeout` armed for the exact stored expiry locks at expiry, re-armed whenever the expiry moves; `visibilitychange`, `focus` and `pageshow` re-check at once; a 30 s tick is only a safety net. The adoption grace is 120 s, deliberately larger than every check interval (the earlier grace and tick were both exactly 60 s). A 401 `league_session_required` locks whenever a session record is held, even one already expired on the client. One failed renewal does not lock. A renewed token is adopted only if its embedded expiry is strictly greater than the held one's, and the client's expiry is the earliest of "held expiry moved by the token's own movement", "request send time + window" and "last input + window", never earlier than the one already held. **True worst case from last input to the lock screen is now the window itself (300 s by default) plus about a second of timer slop; it is shorter (between half the window and the whole window) when the last input came before the session was past half.** A throttled background tab can fire its timer up to about a minute late but its data is not on screen then, and the visibility check locks it when it is shown.
- **The stream credential (CR-02). The earlier sentence "short-lived and accepted on that one route only" was false for what it described: the value in the URL was the full session token, valid as a header on every locked route and renewable without end.** `?session=` is removed everywhere. The live stream now takes a separate **stream ticket**: `POST /api/league-session/stream-ticket?draftId=...` (a locked route) returns `{ticket, expiresInSeconds: 60}`; the ticket is `t.<expiry>.<HMAC>` under a different key label from sessions, bound to that draft id, 60 s (hand-set), never renewable, accepted only as `?ticket=` on `GET /api/drafts/{id}/live-stream` and only when its draft id equals the path's. It is never accepted as `X-League-Session`; a session is never accepted in any query parameter; a ticket-authorised request mints no session. The web fetches a ticket immediately before every `EventSource`, including its own reconnects, and only when a session record is held (no ticket call at all in the combined build or with the lock off). The admin token and a header-borne session still pass the stream route, for curl.
- **Absolute lifetime (CR-02).** The session token is now `<expiry>.<origin>.<sig>`: `origin` is the time of the original unlock and every renewal preserves it; validation refuses once now minus origin reaches `LEAGUE_LOCK_MAX_SESSION_SECONDS` (default 43200, 12 hours, hand-set, labelled where it is defined), and a renewal's expiry is clamped to `origin + cap`. A leaked or scripted token can no longer be kept alive indefinitely. Old (two-part) tokens fail validation: whoever held one unlocks again.
- **Admin token strength (CR-03).** One startup WARN if the configured admin token is shorter than 32 characters (never the token or its length). DEPLOY.md says to generate it with `openssl rand -base64 32`, that it is pasted into the lock screen, that rotating it ends every session, and that there is no rate limit on the unlock route. Session and ticket keys still derive from the admin token (no second secret), so the offline-guessing exposure of a short token is reduced to advice plus a warning, not removed.
- **Numbers (CR-09).** Every numeric token field must be canonical: digits, no leading zero, no sign, no whitespace.
- **Tests of deny-by-default (CR-10).** `LeagueLockMappingsIT` walks every handler mapping: every pattern is under `/api` (or the error page), the set of routes the allowlist makes public equals an explicit list written in the test, and every other route is called with no credentials and must answer 401 `league_session_required` (403 `admin_token_required` for the operator prefixes). The comment saying the lock is registered "after the admin gate" so ingest refuses first was misleading: the operator prefixes are excluded from the lock, so the order changes nothing. Corrected in `WebConfig`.
- **Browser leftovers (CR-11).** `lock()` now empties the rail league list, the jump-to index, the draft-stats and league-availability caches (each registers with `onLock`). The lock screen no longer writes the admin token to any storage (the `bk.adminToken` sessionStorage write is gone; `/admin/digest` keeps its own prompt and storage as on HEAD). Not fixed: the narrow two-tab race where tab B's in-flight response can write a session back after tab A locked.
- **Documents (CR-12).** Stale comments naming the removed notice are gone; DEPLOY.md now says the backend must be deployed before the league lock screen can work and that operator scripts reading league routes need `X-Admin-Token` once the lock is on.

**Still NOT protected, stated plainly.**

- A stream that is already open keeps delivering until the browser closes it or its own server-side timeout (4 hours) ends it; the lock is checked once, when the stream opens (CR-07, unchanged).
- A stateless token cannot be revoked before it expires; the only revocation is rotating `ADMIN_TOKEN`, which ends every session and ticket.
- A captured session or ticket still lets someone test admin-token guesses offline if the admin token is short (hence the WARN and the DEPLOY.md guidance).
- **CR-08, accepted:** with the lock OFF the backend is not byte-for-byte what it was. Cross-origin responses and preflights gain `Access-Control-Expose-Headers: X-League-Session` (and preflights accept that request header), and two routes exist that did not (`GET /api/league-session`, `POST /api/admin/session`; plus `POST /api/league-session/stream-ticket` from this pass). The "exactly as before" wording above means: no route's answer changes.
- A wrong-method request on a locked path answers 405 before the lock (no handler is chosen, so no interceptor runs). No data, but the refusal is not literally identical for every request.

**Open decision for Allan (CR-06, left UNCHANGED in code).** Stream events and heartbeats are not activity (otherwise a draft would hold the session open all night), so watching a live draft with no input for the idle window replaces the draft room with the lock screen, on draft night, when the tool matters most. Options: a longer `LEAGUE_LOCK_IDLE_SECONDS` on draft night, count `pointermove` as activity, or exempt the live room. FR-024 says what the code does today; nothing was changed.

**Decided by Allan, 2026-11-16: an open, visible live draft room counts as activity.** `useWatching()` (in `web/src/useIdleLock.ts`, mounted by `pages/LiveDraftView.tsx`) fires a `bk:watching` event every 20 s while the tab is visible, and the idle watcher treats it like input. A hidden tab does not count and locks on schedule; leaving the room stops it. The cost, accepted: a draft room left open and visible on an unattended screen stays unlocked until the 12-hour cap. Stream events themselves are still not activity. Tests: `useIdleLock.watching.test.tsx` (5, including the control that the same untouched page locks without it). Not checked in a browser.
