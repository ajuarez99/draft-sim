# Adversarial plan review: 073 Split the website into a Time Machine site and a league site

**Date**: 2026-11-15 · **Reviewer**: adversarial plan-review pass (read cold, no 073 code exists)

**Inputs read**: `AGENTS.md`; `specs/073-split-sites/` `spec.md`, `plan.md`, `research.md`, `contracts/site-routes.md`, `data-model.md`, `quickstart.md`, `checklists/requirements.md`; in `web/`: `vite.config.ts`, `index.html`, `Dockerfile`, `serve.json`, `railway.toml`, `package.json`, `tsconfig.json`, `.env.example`, `public/boot.js`, `public/robots.txt`, `public/sitemap.xml`; in `web/src/`: `App.tsx`, `main.tsx`, `user.ts`, `analytics.ts`, `analytics.test.ts`, `landingCopy.ts`, `landingCopy.test.ts`, `landingHtml.ts`, `landingHtml.test.tsx`, `appSlots.tsx`, `useNarrow.ts`, `useFailure.ts`, `styles.css` (shell, landing, `.hist-*`, `.tm*` blocks), `components/AppShell.tsx`, `TopBar.tsx`, `NavSheet.tsx`, `NotFound.tsx`, `ErrorBoundary.tsx`, `HowThisWorks.tsx`, `pages/SignIn.tsx`, `pages/HistoricalDraft.tsx`, `pages/HistoricalRoom.tsx`; `backend/.../WebConfig.java`, `application.yml`; `.claude/launch.json`; `DEPLOY.md`; `claude/lessons.md` #14.

**Checks I ran** (read-only against the project; experiments in the scratchpad only):

- `npm ci` in `web/` (no `node_modules` was present), then the baseline on untouched `d3a29f0`: `npx vitest run` = 183 files, 3024 tests, all pass; `npx tsc -b` exit 0. `git status` afterwards shows only `specs/073-split-sites/`.
- A scratch Vite 6.4.3 project (the worktree's own `node_modules` through a junction) whose `vite.config.ts` imports a `site.ts` shaped like the plan's: what `import.meta.env` is inside the config, what `loadEnv` returns for a process env var, whether a thrown `parseSite` fails the build, whether `generateBundle` + `emitFile` writes `dist/robots.txt`.
- `matchRoutes` from the worktree's `react-router-dom` against the planned Time Machine route list, 14 paths.
- `cmd /c` with `set VITE_SITE=timemachine && node -e ...` to see the value a launch entry of the existing shape would deliver.
- greps: every `/history` literal in `web/src`, every `localStorage`/`sessionStorage` key, every `window.location`/`location.origin`/`EventSource`/`import.meta.env`, every `createPortal`, every use of AppShell's contexts inside Time Machine files, `ballknowers.co` across backend, config and scripts.

Not run: the backend (8080 was not listening; starting it would run Flyway against the shared 5433 database), any browser, any real DNS or Railway step.

**VERIFIED** = I ran or read something that shows it. **REASONED** = argument only.

## Summary

| ID | Sev | Area | One line |
|---|---|---|---|
| PR-01 | HIGH | Build | `site.ts` reading `import.meta.env` throws when `vite.config.ts` evaluates it; the plan routes `SITE_ORIGIN` through it and gives `VITE_TM_ORIGIN`/`VITE_LEAGUES_ORIGIN` no Node-side source |
| PR-02 | HIGH | Time Machine site wording | Shared pieces the plan does not list put league wording and a "Back to your leagues" link on the Time Machine site: `NotFound`, `ErrorBoundary`, the sign-in note, the analytics disclosure |
| PR-03 | MEDIUM | Local runs | "Both proxy `/api`, so no CORS change" is very likely false for POSTs from ports 5184/5185; the default allow-list is 5173 only |
| PR-04 | MEDIUM | Browser storage | League-side data kept per origin stays behind on `www`: saved stat-calculator formulas, six preferences, and the owner's analytics opt-out |
| PR-05 | MEDIUM | Analytics | One route table for three trees: redirect paths get counted as page views on the wrong site, and two existing assertions break in ways the plan does not name |
| PR-06 | MEDIUM | Rollout / rollback | `/game/:id` does not exist in `combined`, so the rollout window and any rollback strand links; pointing the league site at `/history...` instead removes both problems |
| PR-07 | MEDIUM | Launch entries | The repo's existing `set X=value && npm run dev` shape delivers `"timemachine "` with a trailing space, which `parseSite` is designed to reject |
| PR-08 | MEDIUM | Scope (Allan) | After the flip a signed-in league-mate opening the bare root lands signed in on the Time Machine with no pointer to their leagues; the root is the commonest bookmark and User Story 3 does not cover it |
| PR-09 | MEDIUM | TimeMachineShell | The shell's contract is underspecified: required class names, the account menu is not a reusable component, error boundary, sign-out navigation |
| PR-10 | MEDIUM | Spec coverage | The "backend not told about the league address must be a visible error" edge case has no design and no check; today it reads as "Couldn't reach Sleeper" |
| PR-11 | LOW | Landing copy | No parity or banned-words test planned for the Time Machine landing; its steps duplicate `HistoricalDraft`'s; `transform()`'s new parameter breaks the existing test's call |
| PR-12 | LOW | Tests | SC-006 says the suite passes "for both sites"; the suite reads `VITE_SITE` from the process, so running it under a split value fails existing tests |
| PR-13 | LOW | Hygiene | Vite version, `vite-env.d.ts`, self-redirect guard, default origins pointing at production in local builds, query string on in-app redirects, Railway domain limit |

Checked and **OK** (no finding):

- **The `/history` list in plan §3 is complete.** VERIFIED by grep: `HistoricalDraft.tsx` 145, 158, 243, 247, 351, 357; `HistoricalRoom.tsx` 257, 324, 343; `HistoricalProfileCard.tsx:34`; `HistoricalResults.tsx:82`. The only other hits are `TopBar.tsx:120`, `NavSheet.tsx:104`, `analytics.ts:51-52`, `App.tsx:142-143` (all in the plan) and the league pages `/leagues/:id/history`, `/managers/:id/history`.
- **Time Machine pages do not depend on AppShell's providers.** VERIFIED by grep: no `Historical*` file, nor `DraftBoard`, `Skeleton`, `HowThisWorks`, `AchievementToasts`, `usePickReveal`, `useDraftQueue`, uses `PageHeader`, `usePageActionSlot`, `useRailContextSlot`, `useRailLeagueHint`, `useInsideLeague`, the league data version, or `createPortal`. `AchievementToasts` is mounted by `HistoricalAwardPanel.tsx:99`, not by the shell. `HistoricalRoomLayout.tsx:41` calls `useNarrow` itself.
- **`DraftBoard` and the other shared components the room imports carry no links** (VERIFIED by grep for `<Link`, `navigate(`, `PlayerLink`, `href=`). `HistoricalProfileCard` and `HistoricalResults` are imported only by the two Time Machine pages, so `tmHome`/`tmGame` throwing for `leagues` cannot be reached from a league page.
- **Route patterns match as the contract says.** VERIFIED with `matchRoutes`: `/managers`, `/managers/`, `/managers/12/history` → `/managers/*`; `/leagues/9/history` → `/leagues/*`; `/history/5` → `/history/:gameId`; `/game/5` → `/game/:gameId`; `/admin/digest`, `/leaguesX`, `/history/5/x`, `/game` → `*`. No redirect loop exists between the two split modes as specified.
- **`loadEnv` is the right call for Railway/Docker.** VERIFIED: with no `.env` file and `VITE_SITE=leagues` in the process environment, `loadEnv(mode, cwd)` returned `{"VITE_SITE":"leagues"}`. An unset `ARG` gives an empty string, which the plan maps to `combined`.
- **A throwing `parseSite` fails the build.** VERIFIED: `VITE_SITE=leauges` → `Error: bad VITE_SITE "leauges"`, exit 1.
- **Emitting `robots.txt` from `generateBundle` works.** VERIFIED: `emitFile({ type: 'asset', fileName: 'robots.txt', ... })` wrote `dist/robots.txt` beside `index.html`; `serve -s` serves a real file before its fallback. No test reads `public/robots.txt` or `public/sitemap.xml` (grep). The dev middleware the plan adds is needed, since Vite's fallback would otherwise answer `/robots.txt` with `index.html`.
- **`tsc -b` does not check `vite.config.ts`** (`tsconfig.json` includes `src` only), so the config's imports are checked only by running a build.
- **Styling of a Time Machine landing.** VERIFIED by reading: Nunito is loaded by `index.html` in every build; `.tm` (styles.css:7182) only re-points font tokens and works on any element; the prerender rules (5476-5478) key on `.landing-prerender`, not on `.landing`; `boot.js` reads the same `bk.user.v1` key. A static body shaped `.app > .landing-prerender > .content.tm.tm-page` will be styled and hidden for a signed-in visitor.
- **No Time Machine entry in jump-to search.** VERIFIED: `searchIndex.ts:160` is the only `/history` hit and it is the manager page.
- **Backend names no site address.** VERIFIED: the only `ballknowers.co` outside `web/` and docs is a comment in `scripts/repair-missing-pairings.sh:16` (the API host). `WebConfig.java:47-60` takes origins from `CORS_ORIGINS`.
- **Time Machine progress and its browser state carry over.** `bk.tm.revealSpeed` and the per-game queue keys (`useDraftQueue.ts`) live on `www`, which stays the Time Machine origin; a signed-in `www` visitor stays signed in.
- **`serve.json`**: `connect-src` names `api.ballknowers.co` and serves both sites; nothing in it is origin-specific to `www`.
- **Only two places build an absolute URL from the page's origin**: `HistoricalResults.tsx:82` (in the plan) and `StatCalculator.tsx:252` (league site, correct after the move).
- **Existing tests under the default mode.** `App.test.tsx:194` and `TopBar.test.tsx:21-25` expect the Time Machine link; they keep passing because vitest runs with `VITE_SITE` unset (but see PR-12).

## Findings

### PR-01 (HIGH, VERIFIED) - `site.ts` cannot read `import.meta.env` where `vite.config.ts` evaluates it

**What.** Plan §1 describes `site.ts` as "importable from Node (`vite.config.ts`) and from the app", with `site()`, `tmOrigin()` and `leaguesOrigin()` reading `import.meta.env`. Plan §5 then makes `landingCopy.ts`'s `SITE_ORIGIN` "become `siteOrigin(...)`", and `siteOrigin` calls `tmOrigin()` / `leaguesOrigin()`. `vite.config.ts:4` imports `landingHtml.ts`, which imports `landingCopy.ts`, so those calls run in Node at build time.

**Measured.** In a scratch project with the same shape, inside the config:

```
typeof import.meta.env = undefined
site()     THREW TypeError: Cannot read properties of undefined (reading 'VITE_SITE')
tmOrigin() THREW TypeError: Cannot read properties of undefined (reading 'VITE_TM_ORIGIN')
```

**Why it fails.** The plan handles `VITE_SITE` on the Node side (`parseSite(loadEnv(...).VITE_SITE)`) but says nothing about the two origin variables there. Either the build throws the first time `siteOrigin` runs, or a builder guards the read and the canonical tag, `og:url`, `robots.txt` and `sitemap.xml` silently fall back to the production defaults regardless of the variables. Two further traps in the existing code: `SITE_ORIGIN` (`landingCopy.ts:15`) and `IMAGE_URL` (`landingHtml.ts:12`) are module-level constants, evaluated at import, so they cannot depend on a site chosen later and cannot be changed by `vi.stubEnv` in a test.

**Change in the plan.** Make every function in `site.ts` pure over an explicit argument: `parseSite(raw)`, `resolveOrigins({ VITE_TM_ORIGIN, VITE_LEAGUES_ORIGIN })`, `siteOrigin(site, origins)`. Give `import.meta.env` exactly one reader, a browser-only wrapper (`site()`, `origins()`), and state that nothing reachable from `vite.config.ts` may call it. The config builds the same values from `loadEnv` and passes them down: `transform(html, { site, origins })`. `SITE_ORIGIN` and `IMAGE_URL` stop being constants and become parameters of `headTags` / `jsonLd`. Add acceptance check: build with `VITE_SITE=leagues VITE_LEAGUES_ORIGIN=http://localhost:5185` and read the canonical tag in `dist/index.html`.

### PR-02 (HIGH, VERIFIED by reading; the outcome is REASONED) - League wording reaches the Time Machine site through shared pieces

FR-004 and SC-001 require zero league wording and zero links to league pages on the Time Machine site. The plan lists the `/history` literals but not these:

- `components/NotFound.tsx:17`: every variant ends with `<Link to="/">Back to your leagues</Link>`, and the `page` copy (line 7) is "No draft, mock or page at this address." The plan's Time Machine tree renders `NotFound` at `*`, and `HistoricalRoom.tsx:216` renders `<NotFound what="page" />` for a game id that does not exist or belongs to someone else, which is a spec edge case.
- `components/ErrorBoundary.tsx:50-51`: "The rest of the app still works — back to your leagues", linking to `/`. The plan does not say whether the Time Machine tree keeps the boundary at all (see PR-09).
- `pages/SignIn.tsx:96-100`: the note under the form reads "to see your own leagues and get your seat highlighted at the table". The plan lifts "the username form" into `SignInForm.tsx`; if the note travels with it, the Time Machine front door has league wording.
- `landingCopy.ts:42` `analyticsLine`: "signing in, adding a league, starting a mock ... which pages and which league". Spec 052 FR-012 requires the disclosure where people sign in. The plan's `tmLandingCopy.ts` field list (title, description, headline, steps, image alt) has no disclosure line, so the builder either drops it (a 052 regression and an honesty break) or reuses the league-worded one.

**Change in the plan.** Add to §3/§5: `NotFound` gains a `game` variant and takes its back-link label and target from the site (`Back to Time Machine` → `tmHome`); `ErrorBoundary`'s sentence does the same; `SignInForm` owns only the input, button and the three failure states, with the note supplied by each page; `tmLandingCopy.ts` carries its own analytics line (what the Time Machine site actually sends: page visits and sign-in) and the static body and `TimeMachineSignIn` both render it. Extend the planned "no `/history` literal" test with one that renders the Time Machine tree at `/nope` and at an unknown game and asserts no text matching `/league|mock|manager/i` and no link outside `/` and `/game/`.

### PR-03 (MEDIUM, REASONED) - Local side-by-side runs will likely fail on every POST

**Claim under test.** Plan §7 and quickstart: "Both proxy `/api` to the local backend, so local runs need no CORS change."

**Why I think it is false.** `application.yml:50` defaults `CORS_ORIGINS` to `http://localhost:5173,http://127.0.0.1:5173`. The Vite proxy sets `changeOrigin: true` (`vite.config.ts:38`), which rewrites `Host` but not `Origin`. Browsers send `Origin` on a same-origin POST. Spring decides "is this a CORS request" by comparing `Origin` with the request's own scheme, host and port, so a POST arriving with `Origin: http://localhost:5184` at `localhost:8080` is treated as cross-origin and refused with 403 `Invalid CORS request` when 5184 is not in the list (the same response as `claude/lessons.md` #14). GETs carry no `Origin`, so sign-in and page loads work, which is what makes this easy to miss: starting a Time Machine game, making a pick and abandoning a game are POSTs. Supporting evidence in the repo: `.claude/launch.json` already pairs `CORS_ORIGINS=http://localhost:5182` with the 5182 web entry and `CORS_ORIGINS=http://localhost:5183` with 5183.

**Not run**: no backend was listening. One command settles it once one is: `curl -i -X POST -H "Origin: http://localhost:5184" -H "X-Sleeper-User: <id>" localhost:8080/api/history/games` (403 with `Invalid CORS request` confirms; anything else refutes).

**Change in the plan.** Either add a backend launch entry with `CORS_ORIGINS=http://localhost:5173,http://localhost:5184,http://localhost:5185`, or state the measured result of the curl above in quickstart. Do not leave "nothing about CORS changes locally" as an unmeasured sentence.

### PR-04 (MEDIUM, VERIFIED by reading) - League-side browser data stays behind on `www`

Plan "Technical Context" says "Storage: none", and research R5 concludes "Only the league site starts with empty storage, and it has no Time Machine state to lose." R5 checked Time Machine state only. The league pages move to a new origin and leave these on `www`:

| Key | File | What is lost |
|---|---|---|
| `bk.statCalc.saved.v1` | `statCalcSaved.ts:17` | The user's saved stat-calculator formulas (spec 032). User-authored content, not a preference; nothing else stores it |
| `bk.draftStats.v1` | `statChoice.ts:3` | Chosen draft-room stat columns |
| `bk-sport-filter` | `sportFilter.ts:6` | Sport filter |
| `bk.sound.v1`, `bk.pickCards.v1` | `sound.ts:27`, `pickCardsPref.ts:1` | Draft-room sound and pick-card settings |
| `draftRoom.split`, `lh-spotlight-tab` | `SplitDivider.tsx:7`, `PlayerSpotlight.tsx:61` | Layout split, spotlight tab |
| `umami.disabled` | `analytics.ts:128` | Allan's own analytics opt-out (DEPLOY.md "Exclude your own browser"). On `leagues.ballknowers.co` his visits are counted until he sets it again |

The spec's assumptions accept a second sign-in and nothing else. **Change**: add an edge case to the spec naming the loss, with the saved formulas called out; add "set `umami.disabled` on the league address" to the deploy steps. Whether saved formulas are worth carrying (they could ride the cross-site redirect once) or are accepted as lost is Allan's call.

### PR-05 (MEDIUM, VERIFIED by reading) - The analytics edits are not sufficient for three route trees

How it works today: `ROUTES` (`analytics.ts:44-77`) is one flat table that `routeTemplate` matches every pathname against; `AppShell.tsx:98-100` sends one page view per path or signed-in change; `enabled()` (`analytics.ts:134-138`) requires `location.hostname` in `OWN_HOSTS`; the coverage test (`analytics.test.ts:196-206`) regex-reads `App.tsx` and requires every `<Route` to carry a string-literal `path` that is in `ROUTES`.

What the plan's three edits (add the league host, add `/game/:gameId`, retitle `/` on the Time Machine site) leave wrong:

1. **Redirect paths are counted as pages.** On the Time Machine site `/leagues/9/power` still matches the league template, and `TimeMachineShell` "calls `trackPageView` on path change exactly as `AppShell` does", so a "League power rankings" view is sent under `www` just before `location.replace`. On the league site `/history` is sent as "Time Machine" under the league host. `/history` → `/` in-app sends two views. FR-016 says a view on one site must not be recorded as a view on the other; these are views of pages the site does not serve.
2. **The coverage test will fail on the planned code.** The plan's redirect routes come from `LEAGUE_PREFIXES` with `/*`; a path built in a `.map` or a template literal is exactly what that test's count check rejects (its own comment says so). The plan says the test "is extended to cover the union of the three trees" without saying how the table and the routes stay one list.
3. **`analytics.test.ts:119`** asserts `data-domains` is exactly `www.ballknowers.co,ballknowers.co`; adding a host to `OWN_HOSTS` breaks it. `DEPLOY.md:321` states the same two hosts.
4. `trackAction('sign-in')` (`analytics.ts:262-271`) labels the page through the same table, so a sign-in on a Time Machine deep link is labelled by whatever league template the path happens to match.

**Change in the plan.** Give `routeTemplate` the site: one table per tree, or a `sites` column on each row, with redirect paths mapped to a single `/(redirect)` template or not sent at all (state which). Write the redirect routes as literal `<Route path="/leagues/*">` lines so the existing test keeps reading them, and have the test check each tree against its own table. List `analytics.test.ts:119` and `DEPLOY.md:318-323` among the edits.

### PR-06 (MEDIUM, REASONED) - The rollout window and the rollback both strand links, and a small change removes both

Contract: `leagues` forwards `/history/:gameId` to `TM/game/:gameId` and `/history` to `TM/`. In `combined`, `/game/:gameId` is "not found" and `/` is the league picker.

- **Window.** From the moment DNS resolves (quickstart step 3, not step 4) until step 5, `leagues.ballknowers.co/history/5` lands on a not-found page at `www`, and `leagues.ballknowers.co/history` lands on the league picker. The quickstart note covers the first and not the second, and relies on "nobody has the league address".
- **Rollback.** "Remove `VITE_SITE` from the existing web service" returns `www` to `combined` while the league service stays `leagues`. Every `/game/<id>` bookmark made during the split, and every league-site `/history` forward, then breaks for as long as the rollback lasts. The quickstart calls the rollback clean.

**Change in the plan.** (a) Have `leagues` forward to `TM/history` and `TM/history/:gameId`. Both `combined` and `timemachine` understand those paths; on `timemachine` the second step is an in-app `<Navigate>` with no network request, so the contract's "one hop, not two" argument costs nothing. (b) Have `combined` serve `/game/:gameId` as `<Navigate to="/history/:gameId" replace>`, so links minted during the split survive a rollback. Update the contract table and its second note, and delete the quickstart's "do steps 4 and 5 in one sitting" caveat.

### PR-07 (MEDIUM, VERIFIED) - The existing launch-entry shape delivers a value `parseSite` rejects

`.claude/launch.json`'s web entries are written `cd web && set DRAFTSIM_PROXY_TARGET=http://localhost:8081 && npm run dev ...`. In `cmd`, the space before `&&` is part of the value. Measured:

```
set VITE_SITE=timemachine && node -e "console.log(JSON.stringify(process.env.VITE_SITE))"
"timemachine "
```

Plan §1: anything other than the two names throws. An entry copied from its neighbours therefore fails at startup, and `VITE_LEAGUES_ORIGIN=http://localhost:5185 ` would give `crossSiteUrl` an origin with a space in it (the plan strips a trailing slash, not whitespace). The backend entries in the same file avoid this by writing `set X=value&& `.

**Change in the plan.** `parseSite` and the origin readers trim before validating (a typo still throws), and §7 spells the entries as `set VITE_SITE=timemachine&& set VITE_LEAGUES_ORIGIN=http://localhost:5185&& npm run dev -- --port 5184 --strictPort`. Add a `site.test.ts` case for `'timemachine '`.

### PR-08 (MEDIUM, REASONED) - The bare root is the one old link that cannot land "at its new home"; needs Allan

After step 5 a league-mate who was signed in at `www.ballknowers.co` still has `bk.user.v1` there (same origin), so opening `www.ballknowers.co/` shows the Time Machine home, signed in, with no rail and by FR-015 no link to the league site. User Story 3 says "Each old link takes them to the same thing at its new home" and covers the four prefixes; the root, which is the address most people bookmarked or type, is not a prefix and now means something else. The spec's assumption ("No visible cross-links ... not even a footer link") was written about steady state; I do not think it was weighed against existing league users on the day of the switch. Also outside the prefixes: the operator's `/admin/digest` bookmark on `www` becomes not-found (the contract says so deliberately).

This is a product choice, not a build detail. Options: accept it and tell the leagues the new address out of band; or a one-time, dismissible notice on the Time Machine home for a browser that holds league-era storage. The plan should record which, with Allan's name on it.

### PR-09 (MEDIUM, VERIFIED by reading) - `TimeMachineShell`'s contract is underspecified

Plan §4: "skip link, a slim bar ..., `<main>`. It reuses the existing top-bar and account-menu classes." What the code requires and the plan does not say:

- **Class names are load-bearing.** `.app` is a fixed `100vh` column (styles.css:268-281) and `.app-main` is its only scroller (2080-2083); on a phone `.app`, `.app-shell` and `.app-main` switch together to document scrolling (2255-2263). The room's wide layout is `height: max(460px, calc(100vh - 290px))` (6989-6991), a number its own comment calls arbitrary and sized against today's chrome, and `.hist-banner` is `position: sticky; top: 0` inside that scroller (6961). A shell that is not `.app-shell > .topbar + main.app-main#main` changes how the draft room scrolls. Name the three classes in the plan and re-check the room at 1366x768 and at phone width, since the league header and context strip are gone.
- **"The same sign-out menu the top bar has" is not a component.** It is inline in `TopBar.tsx:154-183` with its own state and `usePopup`. Either extract an `AccountMenu` used by both bars (touches `TopBar.tsx` beyond lines 120-122, which the plan's file list does not show) or accept a second copy and say so.
- **Error boundary.** `App.tsx:134` wraps the routes so a throwing page keeps the chrome. The Time Machine tree in plan §2 does not mention it.
- **Sign-out.** `AppShell.tsx:163-166` clears the user and navigates to `/`. If the Time Machine shell only clears the user, the URL stays `/game/5`, and the next person to sign in on that browser lands on someone else's game.

### PR-10 (MEDIUM, REASONED) - A spec edge case has no design and no check

Spec edge case: "The backend is not told about the league site's address: league pages fail to load data there. ... the failure must be a visible error, not a blank page." The plan has no section for it and the acceptance list has no item. Reading the code, the first thing a visitor does on the league site is sign in, and `SignIn.tsx:48-53, 108-112` turns any fetch failure into "Couldn't reach Sleeper just now -- that's not on you." So a missing `CORS_ORIGINS` entry is visible, but as a message that blames Sleeper, and quickstart step 4's diagnosis ("If data does not load, step 1 was missed") starts one screen too late.

**Change.** Add an acceptance item that reproduces it: serve a `leagues` build on a port the backend does not allow, with `VITE_API_BASE` pointing at the backend, and record what the sign-in screen and a league page show. Add a pre-check to quickstart step 4 that needs no browser: `curl -i -X OPTIONS -H "Origin: https://leagues.ballknowers.co" -H "Access-Control-Request-Method: GET" https://api.ballknowers.co/api/health` must answer with `Access-Control-Allow-Origin`.

### PR-11 (LOW, VERIFIED by reading) - The Time Machine landing has no guard tests and duplicates words

- The league landing is held in place by `landingHtml.test.tsx:57-70` (static body and React page say the same thing) and `landingCopy.test.ts:33-46` (banned claims, length limits). The plan adds `tmLandingCopy.ts`, a static body and `TimeMachineSignIn.tsx` with no equivalent, so the two copies can drift and the copy is unguarded.
- The "how to play" steps already exist in `HistoricalDraft.tsx` (the `howToPlay` block and `COPY[sport]`). A second hand-written set in `tmLandingCopy.ts` is a second copy of the same words; move the sport-neutral steps into one module both read.
- Honesty: `HistoricalDraft.tsx` offers a sport only when the server lists a season for it. Static landing copy must not state season ranges or counts as facts ("1970-71 to today", "56 seasons"); keep it to what is true regardless of what is imported.
- `transform(html)` gaining a parameter breaks `landingHtml.test.tsx:14` at type-check. Trivial, but it is an existing test the plan changes without listing.

### PR-12 (LOW, REASONED) - "The suite passes for both sites" has two readings

SC-006: "The existing web test suite, type check and production build pass for both sites." Plan acceptance 8 runs `npm test` once. Vitest exposes `VITE_*` process variables through `import.meta.env`, so a run under `VITE_SITE=leagues` would fail `App.test.tsx:194` and `TopBar.test.tsx:21-25`, which expect the Time Machine link. State the meaning: one run with `VITE_SITE` unset, the three modes covered by `vi.stubEnv` inside `App.site.test.tsx` and `site.test.ts`, each with `vi.unstubAllEnvs()` in `afterEach`; the build is what runs once per mode.

### PR-13 (LOW) - Hygiene

- Plan "Technical Context" says Vite 5; `package.json` pins `^6.0.5` and 6.4.3 is installed (VERIFIED).
- `web/src/vite-env.d.ts` declares each `VITE_*` variable; the three new ones are not in the plan's file list (VERIFIED).
- `CrossSiteRedirect` should refuse to run when the target origin equals `window.location.origin`: a mis-set variable would otherwise reload the same URL forever. `resolveOrigins` should throw when the two origins are equal in a split mode (REASONED).
- The quickstart's "build each mode" commands set no origin variables, so a locally previewed `timemachine` build forwards `/leagues/...` to the real `leagues.ballknowers.co`. Say so beside the commands (REASONED).
- Contract: "In-app redirects preserve the query string." Plan §2 writes `<Navigate to="/" replace>`, which drops it. Pick one (VERIFIED by reading both).
- `DEPLOY.md:35-37` records that a Railway custom-domain limit once blocked a second domain. Whether the Hobby plan allows a third custom domain in this project is not something I could check; name it in the deploy steps as a thing to confirm before step 3.
- The plan's own og-image note (the render script needs `@resvg/resvg-js` installed outside `web/`) is correct as far as `web/scripts/og-image/` shows; nothing in `web/package.json` provides it.

## For the parent session

Count: 0 CRITICAL, 2 HIGH, 8 MEDIUM, 3 LOW.

Findings that change what the feature is, and need Allan's decision:

- **PR-08**: what an existing league-mate sees at the bare root after the flip.
- **PR-04**: whether saved stat-calculator formulas (and the other per-origin league settings) are accepted as lost on the move.

Everything else is how it is built and can be folded into the plan with a dated "amended after review" note. PR-01 and PR-02 should be settled before tasks are written, PR-03 needs one curl against a running backend before the quickstart is trusted, and PR-06's two changes alter the route contract table.
