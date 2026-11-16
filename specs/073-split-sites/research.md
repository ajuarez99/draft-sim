# Research: Split the website into a Time Machine site and a league site

All findings are from reading `origin/main` d3a29f0 on 2026-11-15. Nothing here was run; the plan review and the build re-check each one.

## R1. Build-time variable, or decide by hostname at run time?

- **Decision**: build-time `VITE_SITE`.
- **Rationale**: the title, link-preview tags and the pre-rendered landing text are written into `index.html` at build time (`web/vite.config.ts`, `web/src/landingHtml.ts`, spec 050) because crawlers and preview bots do not run scripts. A hostname check happens after that HTML is already delivered, so it cannot give the two addresses different titles or previews. `sitemap.xml` and `robots.txt` are static files with the same problem.
- **Alternatives considered**: one service with both domains attached and a `window.location.hostname` switch. Fewer moving parts on Railway, but fails FR-005, FR-009 and SC-007.

## R2. What does merging do to production before the split is set up?

- **Decision**: unset `VITE_SITE` means `combined`, which is today's behaviour.
- **Rationale**: pushes to `main` deploy the web service (DEPLOY.md). The split needs a DNS record and a second Railway service that only Allan can create. Any default other than "unchanged" alters the live site at merge time.
- **Alternatives considered**: hold the branch unmerged until the infrastructure exists (blocks on a manual step and invites drift against a busy `main`); default to `leagues` (the main address would lose Time Machine until the flip).

## R3. Where do redirects happen?

- **Decision**: in the client, by route.
- **Rationale**: both sites are static directories served by `serve -s dist`, which answers every path with `index.html`. The redirected paths are signed-in app pages; no crawler depends on a 301. Client routes are testable in vitest and behave the same locally and deployed.
- **Alternatives considered**: `redirects` in `serve.json`. Needs one config per site, cannot be exercised by the dev server, and the game-id rewrite is the only non-trivial rule.

## R4. Does sign-in survive the hop?

- **Finding**: no. `web/src/user.ts` keeps the user under `bk.user.v1` in `localStorage`, which the browser scopes to the origin. `www.ballknowers.co` and `leagues.ballknowers.co` are different origins.
- **Decision**: accept one sign-in per site (spec assumption). FR-013 is met because `App` renders the sign-in screen without changing the URL, so the route named by the link renders once `setUser` fires. That is existing behaviour, re-used.

## R5. Does Time Machine progress follow the player?

- **Finding**: yes. Every `/api/history` route keys on `X-Sleeper-User` (`HistoricalGameController`), and games, awards, achievements and profiles live in `historical_*` tables keyed by that user. Nothing is stored per browser except the sign-in itself.
- **To confirm in the build**: grep Time Machine web files for `localStorage` / `sessionStorage` keys holding progress or preferences (reveal speed, queue). Anything found is per-origin and would not carry from the old `/history` on the same host... which is the same origin as the new Time Machine site (`www`), so it carries anyway. Only the league site starts with empty storage, and it has no Time Machine state to lose.

> **Amended after review, 2026-11-15 (PR-04).** R5 only looked at Time Machine state and concluded nothing is lost. The league side does keep data in the browser, and it stays behind on `www` when the league pages move: the saved stat-calculator formulas (`bk.statCalc.saved.v1`, `statCalcSaved.ts:17`), six display preferences, and the analytics opt-out (`umami.disabled`). Whether to carry the formulas across is Allan's decision, recorded in plan.md.

## R6. Backend changes

- **Finding**: CORS is `allowedOrigins(cors.allowedOrigins())` over `/api/**` from the `CORS_ORIGINS` variable (`WebConfig.java:48-50`, `application.yml:50`), a comma-separated list. No backend source names the site's address (grep for `ballknowers.co` under `backend/src/main` finds nothing).
- **Decision**: configuration only. Add `https://leagues.ballknowers.co` to `CORS_ORIGINS` on the backend service before the league site is opened to anyone.

## R7. Is there a Time Machine entry in league search or the destinations registry?

- **Finding**: `grep -i "time machine"` over `web/src` outside Time Machine files hits only `TopBar.tsx`, `NavSheet.tsx`, `analytics.ts`, and comments in `PageHeader.tsx`, `api.ts`, `revealSpeed.ts`, `rookie.ts`, `useDraftQueue.ts`, `usePickReveal.ts`. `searchIndex.ts` and `destinations.ts` match `/history` only for `/managers/:id/history` and `/leagues/:id/history`, which are league pages.
- **Decision**: two links to remove, nothing else. A test pins it.

## R8. Local side-by-side runs

- **Decision**: two launch entries on ports 5184 and 5185, each given the other's localhost origin through `VITE_TM_ORIGIN` / `VITE_LEAGUES_ORIGIN`, both proxying `/api` to one backend. **Amended after review, 2026-11-15 (PR-03):** the proxy does not rewrite `Origin`, so the backend must also allow the two ports; a launch entry sets `CORS_ORIGINS` for that. Reasoned from `application.yml:50`, to be measured in verification.
- **Known trap** (project memory, 2026-09): `preview_start` from a worktree can run the main checkout's `launch.json`. The verification pass must confirm which directory each dev server is serving before trusting what it shows.
