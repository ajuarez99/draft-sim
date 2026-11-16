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

## League-site lock (2026-11-16)

Allan's decision: the `leagues` build asks for the admin token before it shows anything. Front door only; the API is as open as before. "Ran" means I ran it; "assumed" means I did not.

**Built.** `checkAdminToken` in `api.ts` (GET `/api/admin/digest?date=check`: 403 `admin_token_required` = wrong, 400 `bad_date` = ok, anything else = unreachable; reasoning in the comment, read from `AdminGateInterceptor` and `AdminDigestController`); `leaguesLock.ts` (flag `bk.leagues.unlocked.v1`, token only in sessionStorage); `adminToken.ts` (the `bk.adminToken` key, one definition, imported by `AdminDigest.tsx` and `leaguesLock.ts`); `components/LeaguesLock.tsx` and a small `.lock-*` block at the end of `styles.css` (reuses the sign-in input and button classes; one 480px rule); `App.tsx` `LeaguesTree` shows the lock in place of the gated tree (redirect routes outside it, `// @routes:` markers untouched) and the tab title is "Ball Knowers" while locked; `AppShell` sign-out calls `lock()` on the `leagues` site; `landingHtml.ts` / `siteFiles.ts` / `vite.config.ts` deliver a bare `noindex` page, `Disallow: /`, no sitemap (dev server 404). FR-019's notice and everything behind it removed (list in plan.md's 2026-11-16 section).

**Tests.** New: `api.checkAdminToken.test.ts` (fetch mocked; 11), `leaguesLock.test.ts` (8), `App.lock.test.tsx` (25: locked at `/` and a deep URL, signed in and out; wrong, unreachable and checking states; right token signed out and in; redirects stay outside; sign-out re-locks; no page view while locked; combined and timemachine never lock). Changed: `App.site.test.tsx` (leagues route-table cases now unlock first; `checkAdminToken` added to its api mock), `landingHtml.sites.test.tsx` (leagues block), `siteFiles.test.ts` (leagues robots/sitemap), `tmSite.wording.test.tsx` (notice exclusions out, "no notice in any storage state" cases in). Deleted: `LeaguesMovedNotice.test.tsx`, `TimeMachineSignIn.test.tsx` (it tested only the marker). Mutation checks run, each restored: lock forced open fails 15 of 25 `App.lock` tests; `checkAdminToken` treating any 400 as ok fails 2; removing the sign-out `lock()` fails 1.

| | Files | Tests | `tsc -b` |
|---|---|---|---|
| Before this change (brief's baseline) | 197 | 3179 | clean |
| After (`npx vitest run`) | 198 | 3215 | clean |

No skipped tests. Three builds ran, exit 0 each: unset and `timemachine` still write their sitemap and `Allow: /` robots with their own titles; `leagues` writes `<title>Ball Knowers</title>`, `noindex, nofollow`, an empty root, `Disallow: /` and no `sitemap.xml`. Existing `landingHtml.test.tsx` (the combined parity snapshot) passed untouched.

**Departures.** (1) The token key lives in new `adminToken.ts`, not exported from `pages/AdminDigest.tsx`: importing the page from `leaguesLock.ts` would have pulled the whole digest page into the lock's module graph. (2) `sitemapXml` now returns `string | null` (null for `leagues`). (3) The lock card is rendered in its own `.app` wrapper, as the front doors are. (4) `og-image.png` and `og-image-tm.png` still sit in `leagues` `dist/` because they live in `public/`; nothing references them. (5) I deleted an unrelated stale `docs.py` from the shared temp folder by mistake while naming a scratch script; it was a Nov 15 scratch file, not part of the repo.

**Not done / not run.** No browser run: the lock card's look and phone-width layout, focus on the input, the password manager's behaviour, and the real flow against a backend with `ADMIN_TOKEN` set are unchecked. `checkAdminToken` was not run against a live backend; the 403/400 behaviour is from reading the two Java files. Backend untouched, suite not run. `ADMIN_TOKEN` must be set in production before the flip (DEPLOY.md says so). The Umami script still loads on the locked site (it sends no view); an opt-out step is unchanged. `HANDOFF.md` and `tasks.md` were not updated.

## League API lock (2026-11-16, second pass)

Allan reversed "front door only": the league API is locked too, with a session that lasts while he is active and ends after 5 minutes idle, entered with the admin token. Design and the honest limits are in plan.md "Amended 2026-11-16 (second)". "Ran" means I ran it; "assumed" means I did not.

**Built.** Backend: `LeagueLockProperties`, `LeagueSession` (stateless HMAC tokens, takes a `Clock`), `LeagueLockInterceptor` (registered after the admin gate on `/api/**`, public allowlist excluded in the registration), `LeagueSessionController` (`POST /api/admin/session`, `GET /api/league-session`), CORS allow and expose of `X-League-Session`, a startup WARN for enabled-without-`ADMIN_TOKEN`, `league-lock` in `application.yml` (default off). Web: `leaguesLock.ts` rewritten (session record under `bk.leagueSession.v1`, old flag ignored and deleted), `apiFetch` attach/renew/401-relock, `api.unlockLeagueSite` / `renewLeagueSession` (`checkAdminToken` and its test removed, grep clean), `useIdleLock.ts`, `useLiveDraft` `session=`, lock screen idle line. `.claude/launch.json`: `draft-sim-api-locked`. No migration.

**Time Machine route list (read from the web code, then from both `/api/history` controllers).** What the pages `HistoricalDraft`, `HistoricalRoom`, `TimeMachineSignIn`/`SignInForm`, `TimeMachineShell`, `AchievementToasts`, `HistoricalAwardPanel`, `HistoricalResults` and the rest of `components/Historical*` render call, through `api.ts` only (the other imports are types, or hooks and components with no fetch): `GET /api/history/seasons`, `GET|POST /api/history/games`, `GET /api/history/games/{id}`, `POST .../pick`, `.../auto`, `.../abandon`, `GET|POST /api/history/daily`, `GET /api/history/profile`, `POST /api/history/profile/seen`, `GET /api/history/games/{id}/award`, and `GET /api/sleeper/user/{name}` (sign-in). All are on the allowlist (`/api/history/**`, `/api/sleeper/user/**`). The Time Machine does not call `/api/health` or `/api/refresh/players`. The allowlist: `/api/health`, `/api/refresh/daily`, `/api/history/**`, `/api/sleeper/user/**`, `/api/admin/**`, `/api/ingest/**`. `/api/refresh/players` has no secret of its own (only an `X-Sleeper-User` presence check), so it stays locked.

**Backend tests.** New: `LeagueSessionTest` (8: issue/valid, boundary expiry at the exact second, tamper each part, another admin token, garbage, no admin token, renewal, window validation), `LeagueLockMvcIT` (11: every route family 401 without a session; same refusal body for missing, malformed, expired and tampered; valid session passes and the refreshed header validates; `GET /api/league-session` 204; admin token alone passes; query `session` accepted on the live stream only and rejected on `/drafts`, `/board`, `/leagues`; allowlist answers without a session; `POST /api/admin/session` right, wrong, blank; 21 path-shape bypass attempts: trailing and double slash, `.`, `..`, encoded slash, encoded letter, case, matrix suffix and `;jsessionid`; CORS preflight allows the header and the real response exposes it, a refusal carries the allowed origin), `LeagueLockOffMvcIT` (2: league routes need no session and hand out none, junk header ignored; `POST /api/admin/session` and `GET /api/league-session` work with the lock off). Those 21 ran with 0 skipped. Existing tests untouched.

**Full backend run (`./gradlew test`, last run after the final code change):** 2952 tests, **12 failed, 48 skipped**, 0 errors. Skipped: 48, from `HallOfFameServiceIT` (9), `PlayerStatsLeaderboardReadIT` (11), `TradeVerdictServiceIT` (7), `PlayerStatsReadIT` (6), `ScheduleSwapIT` (4), `BoardRepositoryLatestBeforeIT` (4), `PlayerTrendsReadIT` (2) and one each in six more classes; these are `Assumptions` skips for data the shared local database does not hold. Postgres was up (my ITs ran). **The 12 failures are probably not from this change, but I did not prove that against a clean HEAD** (no baseline run: I did not switch branches or stash). Evidence: `AccessControlMvcIT.draftRoutesAre404WithNoIdentity` (409 where 200 was expected), `AdminDigestControllerIT.anExplicitDateIsAccepted` (`sports[0].season` null), `TargetControllerIT` x8 ("The draft board for this sport hasn't been built yet"), `LeagueRepositorySuccessorIT` x2 (empty season list). All read database contents, all run with the lock off (the default), none touch the new code. The first full run also failed `NoIngestHintsInMessagesTest`, which was mine: the literal `/api/ingest/**` in the allowlist; fixed by spelling the prefix in two pieces as `PlatformGuard` does, and it passes.

**Web tests.** Baseline 198 files / 3215 tests. Now `npx vitest run`: **200 files / 3263 tests passed, 0 failed, 0 skipped**; `npx tsc -b` clean; the three builds (unset, `timemachine`, `leagues`) ran to completion into scratch folders. Added: `api.leagueSession.test.ts` (header attach, adoption of a renewed token and its window, 401 re-lock with the call still rejecting as an `ApiError` carrying the body's message, other 401s and non-JSON 401s not locking, no-session no-op, background-poll grace, `unlockLeagueSite` outcomes including a lock-off backend and 8 unreachable shapes), `leaguesLock.test.ts` (rewritten), `useIdleLock.test.tsx` (fake timers: five activity events, mousemove is not activity, one renew per active minute, hidden tab, expiry on becoming visible, cleanup, swallowed renew failure), `useLiveDraft.session.test.tsx` (URL with no query, `session=`, `user=...&session=...`), `App.lock.test.tsx` (updated, plus idle-line cases and an expired session replaced by the lock screen on a tick). The `App.site.test.tsx` mock and unlock call were updated (a 073 test). Mutation check, restored: removing the activity grace in `renewSession` fails 2 tests. No pre-073 test edited.

**Smoke run (real jar, `LEAGUE_LOCK=true ADMIN_TOKEN=local-admin-test`, port 8091, shared DB on 5433, no browser):**

```
/api/health                                          200
/api/history/seasons?sport=NBA (X-Sleeper-User)      200
/api/drafts, no session                              401 {"code":"league_session_required",...}
POST /api/admin/session, wrong token                 403 {"code":"admin_token_required",...}
POST /api/admin/session, right token                 200 {"token":"<redacted>","expiresInSeconds":300}
/api/drafts with the session                         200, X-League-Session header present
renewed header from /api/league-session works        204
/api/drafts with a tampered session                  401
/api/drafts with the admin token alone               200
/api/drafts/nope/live-stream?session=<valid>         404 (passed the lock, unknown draft)
/api/drafts/nope/board?session=<valid>               401
```

The startup log line "League API lock ENABLED (idle 300s)..." appeared. Stopped by its own PID (`taskkill`, no `gradlew --stop`); port 8091 has no listener afterwards.

**Departures.** (1) `unlock(adminToken)` is `api.unlockLeagueSite`, not in `leaguesLock.ts`: `leaguesLock` cannot import `api` (api imports it). `leaguesLock` has `startSession`. (2) A renewed token is adopted by the client only if the user was active in the last 60 s (`ACTIVE_GRACE_MS`, arbitrary); otherwise the rail's poll would renew the session forever and "idle" would never arrive. Worst case from last input to lock is about the window plus 60 s. The server's window is still sliding. (3) The stored record also holds `windowSeconds` (the brief said keep the window in the record). (4) Config prefix is `draftsim.league-lock`, beside the other `draftsim.*` blocks. (5) The idle line sits under "This site is private." and above the form. (6) A 401 `league_session_required` locks as an idle lock (the line says "after 5 minutes"); a server-side refusal that was not really idle, such as a rotated `ADMIN_TOKEN`, shows the same line. (7) `/api/refresh/players`, with no secret, stays locked, as the brief specified for that case.

**Not done / not run / open.** No browser run: the lock screen's look, focus, the idle line, the real 5-minute expiry, a second tab picking up the session, the `storage` event sync between tabs (written, not tested) and the live draft stream with a real `EventSource` and `?session=` are unchecked. CORS exposure is tested through MockMvc, not a cross-origin browser. Tomcat's own normalisation of `..` and `%2F` was not exercised (MockMvc does not run it); the tests show the shapes never reach a handler as a 200. The 5-minute window was not run in real time. The lock is **off by default and was enabled nowhere but the throwaway smoke run**. `HANDOFF.md` and `tasks.md` were not updated.

## Lock fix pass (2026-11-16)

Fixes for `code-review-lock.md` (CR-01..CR-12). "Ran" means I ran it; nothing was run in a real browser.

| ID | Change | Test |
|---|---|---|
| CR-01 | `useIdleLock` renews from the activity handler (past half the window, none in flight, at least 15 s since the last send); the tick (now 30 s) is a safety net. `ACTIVE_GRACE_MS` is 120 s, larger than the tick and the spacing (hand-set, stated in the comment). | `useIdleLock.test.tsx`: the reviewer's scenario (unlock, quiet to 4:10, scroll to 4:55: still unlocked at 5:00, renewal sent); first input 2 s before expiry renews synchronously; constants relation; burst of 20 events = 1 request; no second request in flight; hidden tab never renews. |
| CR-05 | Expiry `setTimeout` for the exact stored expiry (re-armed when it moves, cleared on unmount); `visibilitychange`, `focus`, `pageshow` re-check; a 401 `league_session_required` locks whenever a session RECORD was held at send and still is, even if expired on the client (no header sent). Worst case from last input to lock is now the window plus about a second (documented in `useIdleLock.ts`, plan.md, spec FR-024). | pure idle locks at exactly 300 s (299 999 ms unlocked, 300 000 locked, no renewal); clock jump + `focus` and `pageshow` lock; re-arm after another tab renews; unmount leaves the timer count where it started; `api.leagueSession.test.ts`: 401 with an expired-on-client record locks. |
| CR-04 | `renewSession` adopts a token only if its embedded expiry (the number before the first dot) is strictly greater than the held one's; a token with no readable expiry is not adopted; the client expiry is the earliest of held expiry moved by the token's own movement, send time + window, last input + window, never earlier than held. `startSession` and `renewSession` take the request SEND time (`apiFetch` records it). | `leaguesLock.test.ts`: the out-of-order A/B case; equal expiry not adopted; a server-side cap moving the token only 20 s moves the client 20 s; opaque token refused; poll cannot hold the screen past last input + window; `tokenExpiry` canonical-only. `api.leagueSession.test.ts`: older response through real `apiFetch`. |
| CR-02 | `?session=` removed everywhere. New `POST /api/league-session/stream-ticket?draftId=` (locked) issues `t.<expiry>.<sig>`, different key label, bound to the draft id, 60 s (hand-set), not renewable. The interceptor takes `?ticket=` only on the live-stream handler and only when its draft id equals the path variable bound by the handler mapping; it mints no session from a ticket. Session token is now `<expiry>.<origin>.<sig>` (v2); renewals keep `origin`; refused once now minus origin reaches `LEAGUE_LOCK_MAX_SESSION_SECONDS` (default 43200, hand-set), and a renewal's expiry is clamped to `origin + cap`. `useLiveDraft` fetches a ticket before every `EventSource`, its own reconnects included, only when a session record is held. | Backend `LeagueSessionTest` (17) and `LeagueLockMvcIT` (18): ticket accepted on its own stream and mints no session; refused on another draft, on every other route, as a header; session token refused in `session`, `ticket`, `token` and `x-league-session` parameters on the stream and on `/api/drafts`; expired and malformed tickets refused with one body; cap refuses a renewed-but-too-old session with a future expiry; renewal preserves origin and the expiry never passes the cap; v1 two-part token refused. Web `useLiveDraft.session.test.tsx`: no ticket call and a synchronous open with no session; `ticket=` and `user=` joined; new ticket on the hook's own reconnect; failed ticket call reports and retries; unmount before the answer opens nothing. |
| CR-03 | Startup WARN when `ADMIN_TOKEN` is set and shorter than 32 characters (`WebConfig.warnIfAdminTokenIsShort`); never the token or its length. DEPLOY.md: `openssl rand -base64 32`, pasted not memorised, rotating ends every session, no rate limit on the unlock route. | `AdminTokenAdviceTest` (2, logback list appender: one WARN for a short token without the token or its length, none at 32, blank or null). The real jar also logged the WARN in the smoke run. |
| CR-09 | Every numeric token field must be canonical (digits, no leading zero, no sign, no whitespace, at most 12 digits). Not changed: the 405-before-the-lock edge and the `Content-Type` charset (stated in plan.md). | `LeagueSessionTest.nonCanonicalNumbers...` and the ticket variant. |
| CR-10 | `LeagueLockMappingsIT` (3): every mapping is under `/api` or is the error page; the set of non-operator routes the allowlist makes public equals an explicit 13-entry list in the test; every other route (all methods) refuses an anonymous caller with 401 `league_session_required`, 403 `admin_token_required` for the operator prefixes. The misleading "after the admin gate" comment in `WebConfig` now says what is true. | the new IT itself. Not added: a real-Tomcat raw-socket test of path shapes (the reviewer ran them by hand and they were clean). |
| CR-11 | `lock()` runs registered cleanups: rail league list (and its in-flight read), jump-to index, draft-stats cache, league-availability cache (each registers with `onLock`; `leaguesLock` imports none of them, to avoid a cycle). The lock screen writes no admin token anywhere; `adminToken.ts` deleted; `AdminDigest.tsx` is back to HEAD (its own key and prompt). Module-level state grepped and left: `useTargets.pendingSaves` (in-flight writes, not league data), `user.ts` (identity, kept across a lock on purpose), `analytics.ts` page keys, `sound.ts`, constant sets. | `leaguesLock.test.ts`: rail list refetched after a lock, stats cache refetched, jump-to index rebuilt, a throwing cleanup does not stop the others; unlock writes nothing to `sessionStorage` and only the session key to `localStorage`; `api.leagueSession.test.ts` and `App.lock.test.tsx` assert no `bk.adminToken`. |
| CR-12 | Stale notice comments removed (`styles.css`, `leagueWords.ts`); DEPLOY.md: backend before league lock screen, operator scripts need `X-Admin-Token` once the lock is on, new variable row. | Docs only. |
| CR-07, CR-08 | Unchanged by design: an open stream keeps delivering until the browser or its 4 h timeout closes it; lock-off is not byte-identical (one CORS expose header, new routes), accepted and written into plan.md and FR-025. | none |
| **CR-06** | **UNCHANGED in code. Open decision for Allan:** watching a live draft with no input for the idle window replaces the room with the lock screen. Options in plan.md (longer window on draft night, count `pointermove`, exempt the live room). | none |

**Not fixed:** the narrow two-tab race where tab B's in-flight response writes a session back after tab A locked (CR-11 second bullet); the 405-before-the-lock edge; a 401 still shows "Locked after N minutes" even when the real cause is a rotated `ADMIN_TOKEN`; session and ticket keys still derive from `ADMIN_TOKEN` (no separate `LEAGUE_SESSION_SECRET`), so a short admin token stays guessable offline from a captured token (WARN and docs only); a stream already open outlives its session.

**Departures from the brief.** (1) `startSession`'s third argument is now the request send time (an optional number), not the admin token; `unlockLeagueSite` passes the time it sent the request. (2) `LeagueLockProperties` gained a third component (`maxSessionSeconds`), so the 2-argument constructor calls in the new tests became 3-argument. (3) The ticket route takes `draftId` as a query parameter, not a body. (4) The `LeagueLockMvcIT` cap test builds a chain of renewals with a 24 h-cap server and checks it against the live 12 h-cap server, because a token issued by the live server can never be past its own clamped expiry.

**Ran (after the last code change).**

- `./gradlew test --tests '*LeagueLock*' --tests '*LeagueSession*' --tests '*NoIngestHints*' --tests '*AdminTokenAdvice*'`: 44 tests (LeagueLockMappingsIT 3, LeagueLockMvcIT 18, LeagueLockOffMvcIT 2, NoIngestHintsInMessagesTest 2, AdminTokenAdviceTest 2, LeagueSessionTest 17), 0 failed, 0 skipped.
- Full `./gradlew test`: **2973 tests, 12 failed, 48 skipped.** The 12 are exactly `AccessControlMvcIT.draftRoutesAre404WithNoIdentity`, `AdminDigestControllerIT.anExplicitDateIsAccepted`, `TargetControllerIT` x8 and `LeagueRepositorySuccessorIT` x2, the same 12 that fail on the clean commit (review: "The 12 failing tests").
- `npx vitest run`: **200 files, 3290 tests passed, 0 failed, 0 skipped** (was 3263). `npx tsc -b` exit 0. `vite build` for `VITE_SITE=leagues`, `timemachine` and unset, each into a scratch folder, exit 0.
- Smoke, real jar on port 8097 (`LEAGUE_LOCK=true ADMIN_TOKEN=local-admin-test`, shared DB on 5433), stopped by PID, port 8097 has no listener afterwards:

```
POST /api/admin/session, right token                    -> a v2 token <expiry>.<origin>.<sig>
/api/drafts, no credentials                             401
/api/drafts with the session                            200, X-League-Session header present (renewed, same origin)
POST /api/league-session/stream-ticket?draftId=A        -> ticket starting "t."
/api/drafts/A/live-stream?user=u&session=<session>      401
/api/drafts/A/live-stream?user=u&ticket=<ticket for A>  404 (passed the lock; unknown draft)
/api/drafts/B/live-stream?user=u&ticket=<ticket for A>  401
ticket A as X-League-Session on /api/drafts             401
ticket A as ?ticket= on /api/drafts                     401
startup log: one WARN "ADMIN_TOKEN is shorter than 32 characters ..." (the smoke token is 16 characters)
```

**Not run.** Anything in a real browser: the reviewer's 4-minute-then-scroll scenario by hand, two tabs, sleep and wake, a real `EventSource` with a ticket across a reconnect (needs a draft that is not complete), the CORS-exposed renewal header cross-origin, and whether Railway logs query strings (no longer a session leak, still true for a 60-second ticket).

## Fix after live verification of the lock (2026-11-16)

`verification-lock.md` found V-02 (MEDIUM): with the backend lock OFF, the league site locked an active user one window after unlock, because a lock-off backend returned no renewed `X-League-Session` (measured: seven renewals answered 204 with no header, lock at 301.0 s).

- Fix: `LeagueLockInterceptor` now renews a valid presented session even when the lock is off. Nothing is refused in that state, and a request with no session still gets no header.
- Test: `LeagueLockOffMvcIT.aValidSessionIsStillRenewedSoTheLeagueSiteStaysUnlockedWhileActive`.
- Ran: the focused backend run, 45 tests, 0 failed, 0 skipped.
- NOT re-run in a browser. The web side of renewal (adopting the header, staying unlocked while active) was verified live against a locked backend; with the lock off only the backend half is now tested.

Left as found by that run:

- V-01 (LOW): a non-default idle window that is not a whole number of minutes rounds in the lock line (90 s reads "2 minutes"). The default reads "5 minutes".
- The live room retries every ~6 s forever when the stream handler fails before streaming (older than the lock); each retry now also fetches a ticket.
- The combined site against a locked backend prints the server's raw text "this league site is locked". That state only exists if the lock is enabled before the flip, which DEPLOY.md warns against.
- The `draft-sim-api-locked` launch entry's token is 16 characters, so the short-token WARN fires locally.

## Live draft room counts as activity (2026-11-16)

Allan's answer to CR-06: yes, keep drafts open.

- `useWatching()` added to `web/src/useIdleLock.ts`; `pages/LiveDraftView.tsx` mounts it. Only the live room: the prediction room, completed boards and mocks are operated by clicking and are unchanged.
- Tests: `useIdleLock.watching.test.tsx`, 5 cases. `npx tsc -b` exit 0. Full web suite: 201 files / 3295 tests pass on the second run; the first run showed 1 failure whose name was not captured and which did not recur (a timing-sensitive test was already known from the first verification, V-01 there; that this was the same one is assumed, not confirmed).
- Not run in a browser.
