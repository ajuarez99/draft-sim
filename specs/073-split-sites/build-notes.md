# Build notes: spec 073, split sites

Written 2026-11-15 by the build pass. "Ran" means I ran it and saw the result; "assumed" means I did not.

## Test counts (T001, T028)

| | Files | Tests | `tsc -b` |
|---|---|---|---|
| Baseline (before any change, `npx vitest run`) | 183 | 3024 | clean |
| Final (`npx vitest run`) | 194 | 3158 | clean |

Baseline detail: my first run showed 184 files / 3025 tests because it included one throwaway snapshot test I added to capture the pre-refactor combined HTML (and deleted afterwards). Without it: 183 / 3024, which matches the reviewer's number. No skipped tests in either run. Final run: 194 passed, no failures, no unhandled errors.

Existing tests edited: `landingHtml.test.tsx` (passes the combined config to `transform`), `analytics.test.ts` (per-site tables, the `data-domains` assertion now lists three hosts, coverage check once per tree). Nothing else. `TopBar.test.tsx`, `SignIn.test.tsx`, `PhoneNav.test.tsx`, `ErrorBoundary.test.tsx`, `App.test.tsx` and the Historical tests passed untouched.

New test files: `site.test.ts`, `siteFiles.test.ts`, `siteCarry.test.ts`, `tmLandingCopy.test.ts`, `landingHtml.sites.test.tsx`, `App.site.test.tsx` (53 tests, every contract row per mode signed in and out with `?x=1#y`), `historicalPaths.test.ts`, `tmSite.wording.test.tsx`, `components/TimeMachineShell.test.tsx`, `components/LeaguesMovedNotice.test.tsx`, `components/TopBar.sites.test.tsx`.

## Combined output is unchanged (T004)

Before refactoring I captured `transform(index.html)` to a file. After the refactor `transform(index.html, makeConfig({}))` is byte-identical (checked with `cmp`: IDENTICAL). `robotsTxt`/`sitemapXml` for combined are asserted equal to the old `public/` files byte for byte in `siteFiles.test.ts`.

## Builds (T028), all ran

| Build | Exit | `<title>` | canonical / og:url | og:image | robots Sitemap | sitemap `<loc>` |
|---|---|---|---|---|---|---|
| unset | 0 | Ball Knowers: Fantasy Football & Basketball for Sleeper Leagues | `https://www.ballknowers.co/` | `www/og-image.png` | `www/sitemap.xml` | `https://www.ballknowers.co/` |
| `VITE_SITE=timemachine` | 0 | Ball Knowers Time Machine: Redraft a Real NBA or NFL Season | `https://www.ballknowers.co/` | `www/og-image-tm.png` | `www/sitemap.xml` | `https://www.ballknowers.co/` |
| `VITE_SITE=leagues` | 0 | (league title) | `https://leagues.ballknowers.co/` | `leagues/og-image.png` | `leagues/sitemap.xml` | `https://leagues.ballknowers.co/` |
| `VITE_SITE=leauges` | 1 | n/a | n/a | n/a | n/a | n/a |

The typo build failed with `Error: VITE_SITE: unknown value "leauges". Use "timemachine", "leagues", or leave it unset (combined).` The `timemachine` `index.html` contains zero occurrences of league, leagues, mock or managers (grep). The unset and leagues files do (league landing copy).

Every `dist/` contains both `og-image.png` and `og-image-tm.png` because both live in `public/`; only the tags differ per site.

## Share image (T014)

Rendered with `@resvg/resvg-js` installed in the scratchpad (not in `web/`) via `scripts/og-image/render.mjs`. As a check on the pipeline, re-rendering the existing `og-image.svg` reproduced the committed `og-image.png` at the same size (41057 bytes). `og-image-tm.png` is 1200x630 (viewed), Time Machine wording, same style. Fonts come from this Windows machine's system (Bahnschrift).

## Departures from the plan, and why

1. **Redirect routes use a nested `<Routes>`.** Each tree is an outer `<Routes>` holding the redirect routes plus `path="*"`, whose element is the shell + gate + an inner `<Routes>`. That is the way to put redirects outside the gate and outside the shell's page-view effect with literal `<Route path>` lines. It also means the combined tree is wrapped one level deeper than before (to host `/game/:gameId`). All existing App/AppShell tests pass; behaviour is believed identical, but this is the least trivial edit to combined and a browser run should confirm remount and scroll behaviour.
2. **League pages are one shared fragment** (`leaguePages`) used by the combined and leagues trees, to avoid duplicating ~40 routes. `analytics.test.ts` reads regions between `// @routes:... begin/end` markers in `App.tsx` to check each tree against its table; the markers are load-bearing.
3. **`SignInForm` takes `notes: string[]`, not one note**, and its `onSignedIn` runs immediately before `setUser`, not after. Reason: `setUser` mounts the signed-in screen, which reads the `bk.tm.signedInHere.v1` marker for FR-019; written after, the notice would flash for people who just signed in here.
4. **Wording change on the combined build too:** `HistoricalDraft`'s NBA how-it-works line said "Only stats the league tracked..." (meaning the NBA). The wording test caught it on the Time Machine home, so it now says "the NBA tracked" in all modes. Tiny copy change to combined.
5. **`signInNote` added to `landingCopy.ts`** (the league sign-in note was a literal in both `SignIn.tsx` and `staticBody()`); both read it now. Output unchanged.
6. **`siteFiles.ts`, `goTo.ts`, `InAppRedirect.tsx`, `leagueWords.ts`, `tmSignedInHere.ts`** are small files not named in the plan: pure robots/sitemap text; a mockable `window.location.replace` wrapper; the in-app redirect; the banned-word regex shared by tests; the two storage keys. `siteEnv.ts` also exports `otherOriginUnsetInDev` so `CrossSiteRedirect` does not read `import.meta.env` itself. `statCalcSaved.parseStore` is now exported (no behaviour change).
7. **`routeTemplate` reads `currentSite()` at call time** (no site parameter), and `ROUTE_TEMPLATE_PATHS` was replaced by `routesFor(site)`.
8. **JSON-LD for the Time Machine** uses `applicationCategory: "GameApplication"` and name "Ball Knowers Time Machine". My choice; not in the plan.
9. **New CSS** (end of `styles.css`): `.tm-door` (front door scrolls itself, its children make the 760px column), `.tm-title-sm`, small `.tm .signin-*` rounding, `.tm-moved` with a 640px rule. A `tm-door` class was added to both the React front door and `staticTmBody()`.
10. Files I wrote are LF; the checked-out originals are CRLF (`core.autocrlf=true`). Appended CSS lines are LF in a CRLF file; git normalises on commit.
11. `git rm --cached` was used once by mistake on `public/robots.txt` and `sitemap.xml` and immediately undone with `git reset -q HEAD -- <files>`; they show as unstaged deletions. Nothing is staged or committed.

## Could not do / not run

- No browser or dev-server run (instructed). Still to confirm live: the Time Machine room's height and scroll inside `TimeMachineShell`; the front door scrolling on a phone; the nested-`<Routes>` combined tree; the redirects and carried formulas across two real origins (5184/5185); the CORS 403 the plan review predicted for ports 5184/5185 (`quickstart.md` says to run the curl); real analytics hostname handling; link-preview rendering of the new image.
- Backend suite not run (no backend file changed).
- Railway custom-domain limit unconfirmed (DEPLOY.md names it as the first check).

## Noticed, out of scope

- Shared `DraftBoard.tsx` has hover/title strings with league/manager wording ("drafts like the league average", "Sleeper hasn't mapped a manager to it yet"). I did not check whether the Time Machine room can show them; the wording test covers the home, front door, not-found and error boundary, not the room or results.
- Backend `settingsSource` text (e.g. "Settings: the Legacy Ball Knowers league.") is rendered on the Time Machine home when seasons exist; it contains "league". It comes from the API and the test fixture returned no seasons. Reading it on the Time Machine site would break FR-004 wording; a backend or copy decision.
- The `.landing-prerender` boot hide rule and `boot.js` are shared by both sites unchanged.
- The old `/history` bookmark of a signed-out visitor on the combined build still lands on the league landing at `/history` (unchanged behaviour).

## Fix pass after code review (2026-11-15)

Each finding in `code-review.md`, what changed, and the test that covers it.

- **CR-01 (done).** `siteCarry.ts`: `CARRY_MAX_CHARS` 6,000 -> **24,000**, labelled hand-set, with the measured figures (about 320 encoded characters per formula; 15,952 for 50 short-named ones). Over the cap the newest formulas by `savedAt` that fit are carried (binary search on count), kept oldest first; `acceptCarry`'s length check moved with the constant. **Deviation from the brief:** the brief said 16,000; 50 real entries measured 15,952, so a few extra characters (longer names, a longer random id) pushed a full store over and the "50 arrive" test failed. 24,000 leaves about 50% headroom. Tests (`siteCarry.test.ts`): 20, 30 and 50 `saveFormula`-made entries all arrive in order; an oversized hand-built set arrives as the newest subset, ending at the newest; one formula alone over the cap carries nothing.
- **CR-02 (done).** `site.ts` `makeConfig` parses both origins with `new URL`, requires `http:`/`https:`, stores `.origin`, and throws naming the variable and value otherwise; empty after trim is the default. Tests (`site.test.ts`): `http://LOCALHOST:3000` lowercased; trailing slash and path dropped; `leagues.ballknowers.co`, `ftp://`, `javascript:` and garbage throw naming the variable; empty -> default. New `components/CrossSiteRedirect.test.tsx`: the mixed-case reproduction now hits the same-origin guard (not-found, no `goTo`); a different origin still forwards. The `NotFound` rendered outside `.app` on that path (cosmetic note in CR-02) is not changed.
- **CR-03 (done).** `acceptCarry` writes only when `getItem(SAVED_KEY)` is null, or holds a value that does not parse as JSON. Tests: a store holding the real empty shape `{"v":1,"formulas":[]}` (made by `saveFormula` then `removeSaved`) is left unchanged; a garbage value still counts as never saved.
- **CR-04 (done, display only).** `historicalCopy.ts` `notCountedReason()` rewrites "not tracked by the league that season" to "...by the NBA that season"; applied in `HistoricalRoom.tsx` where the Not counted reason prints (the only render site; `HistoricalResults` does not print reasons). Active in every site mode. Test: `historicalCopy.notCounted.test.ts`. **Not fixed:** the Java string at `EraScoring.java:64` is untouched (no backend edits in this pass); stored seasons and game snapshots keep the old wording, so the fix is display-side and a follow-up remains for future imports. `settingsSource` ("Legacy Ball Knowers scoring") is left for Allan.
- **CR-05 (done).** `tmSite.wording.test.tsx` rewritten around real-shaped data: both sports' seasons with the backend's own `settingsSource` and not-counted strings, a daily for each sport, an in-progress and a finished game in the list, a profile with a badge; plus cases for the room while drafting (NBA and NFL) and a finished game's results (`historical-result-062.json`, award panel mocked). Banned words unchanged (league, leagues, mock, manager; FR-019 notice excluded). **New wording found:** only the CR-04 string; the room and results cases assert the rewritten phrase is on screen. Nothing else on these screens. Left for Allan: "Legacy Ball Knowers scoring" in the NBA `settingsSource`, a proper name, not changed.
- **CR-06 (done).** New `pages/TimeMachineSignIn.test.tsx`: the real form, only `getSleeperUser` mocked. The marker is already present at the moment the user first appears, and the notice does not render afterward; an unknown name and an unreachable Sleeper write no marker. Mutation check run: removing `onSignedIn={markSignedInHere}` fails the first test (file restored). The "signed in before the switch sees the notice" half was already covered by `LeaguesMovedNotice.test.tsx`.
- **CR-07 (done).** `analytics.test.ts`: the count of `<Route` elements in `App.tsx` (comment lines excluded) must equal the sum over the four marked regions plus an explicit `OUTSIDE_REGIONS` list (empty today).
- **CR-08 (accepted, not fixed).** A visitor forwarded between the sites is recorded with no outside referrer. Noted in DEPLOY.md's analytics paragraph and quickstart.
- **CR-09 (done).** DEPLOY.md "What carries across", quickstart, and a dated "Amended after code review" bullet in plan.md under PR-04 (original sentences untouched).
- **CR-10 (done, first bullet).** `LeaguesMovedNotice.tsx`: `onAuxClick` also dismisses. Test: auxclick and a Ctrl-click both set the dismissed key. **Not changed:** the notice has no dev guard, so a local dev server with `VITE_LEAGUES_ORIGIN` unset links to production (reviewer: harmless).
