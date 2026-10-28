# Plan review: Site analytics (spec 052)

Adversarial review of `plan.md`, `research.md`, `data-model.md`, `contracts/analytics-module.md`
and `quickstart.md`, read cold against the code on branch `052-site-analytics` (2026-10-28),
before any code exists.

**Verified** below means I read the code at the cited lines. **Inferred** means it comes from
memory of Umami's tracker source or Railway's docs, or from reasoning, and was not executed.
Every finding says which.

## Findings

### F1: CRITICAL. Signed out, every path renders the landing page, so templates mislabel it and the spec 050 landing goes uncounted

- **Evidence (verified)**: `App.tsx:121-204` renders `<Routes>` only when `user` is set. Signed
  out, `<SignIn />` is the whole screen at **any** pathname. A shared link like
  `/leagues/123/power` opened by a signed-out visitor shows the landing page. `AppShell.tsx:155`
  returns its children early when signed out, but every hook above that line runs first, so a
  pathname effect would still fire.
- **Impact**: under the plan, `routeTemplate(location.pathname)` reports that visit as "League
  power rankings". Shared links from spec 050 are exactly what the owner wants to measure, and
  they would show up as league-page views by people who never saw a league page. Real landing
  views would only be counted at `/`.
- **Recommend**: make the template depend on sign-in state. When `user == null`, every pathname
  maps to the landing page: `url: '/'`, title `Landing`. A deep link maps to
  `/(landing)<template>` (for example `/(landing)/leagues/:league/power`). That keeps FR-003
  grouping and also answers "do shared links bring people in". Pass `signedIn` into
  `trackPageView` and `routeTemplate`. Add a test: signed out at `/leagues/123/power` gives the
  landing template, not the power-rankings one.

### F2: HIGH. Signing in changes the page without changing the pathname, and the planned de-dupe swallows that view

- **Evidence (verified)**: `SignIn.tsx:43-44` says no navigation happens: "App.tsx renders the
  picker as soon as a user exists". The pathname stays `/` (or the deep link), and only the
  rendered page changes. `contracts/analytics-module.md` de-dupes on "last template for the same
  pathname", and `AppShell` re-runs only on `location.pathname`.
- **Impact**: the first signed-in page (Home, or the deep-linked league page) is never counted
  after a sign-in. Sign-out has the reverse problem: `AppShell.tsx:107` replaces to `/`, and from
  `/` that is the same pathname again.
- **Recommend**: run the effect on `[user != null, location.pathname]` and de-dupe on
  `${signedIn}|${pathname}`. Add a test: signed out at `/`, then `setUser`, gives two page views
  (landing, then home).

### F3: HIGH. A stale `railLeague` gives the next page the previous page's league

- **Evidence (verified)**: `railLeague.ts:248-250` says "Not cleared first". On navigation the
  hook keeps returning the **previous** route's league until `resolve()` lands. Even the
  `setValue(null)` for a route with no league (`railLeague.ts:219-221`) happens in an effect,
  after the first render on the new path.
- **Impact**: plan step 3 fires `trackLeagueView` "once per pathname" from an effect on
  `railLeague`. Its first run after a navigation sees the new pathname with the old league. Three
  cases: league A → league B sends `league-view{league: A}` for B's route; league A →
  `/managers` sends a `league-view` for a non-league route; and with a once-per-pathname latch,
  the correct value that lands next is then suppressed. That is wrong attribution with no error
  anywhere, which is US3's whole point.
- **Recommend**: fire only when the resolved value provably belongs to the current path. Compute
  `leagueRefFromPath(pathname)` (or the hint ref) synchronously, and require
  `railLeague.season.sleeperLeagueId === ref.id` (or `.sleeperDraftId` for a draft ref) before
  sending. Alternatively, have `useRailLeague` return the pathname it resolved for. Add a test:
  navigating from league A to league B, with B resolving late, gives exactly one `league-view`
  for B and none for A on B's route.

### F4: HIGH. The contract doesn't require the object form for **events**, and the string form leaks the raw URL

- **Evidence (inferred, Umami tracker source from memory; verify by reading
  `cloud.umami.is/script.js`)**: `umami.track('name', data)` sends
  `{...getPayload(), name, data}`, and `getPayload()` includes `url` (the current
  `location`-derived path, with ids in it), `title: document.title` and `referrer`. Only
  `umami.track({...})` sends exactly what it is given. R2 says "object form" for page views.
  `data-model.md`'s key-action and league-view tables list only properties, with no `url` or
  `title`, and the contract's `trackAction(name, props)` signature mirrors the string form.
- **Impact**: the obvious implementation of `trackAction` (`umami.track(name, props)`) sends
  `/drafts/<id>/live` and a page title on every key action, which breaks FR-006 and SC-005.
- **Recommend**: state in the contract that every send, page view or event, goes through one
  internal `send(payload)` built as `{website, hostname, language, screen, url: template,
  title: label, name?, data?}`. Add a test that stubs `window.umami.track` and asserts it is only
  ever called with a single object argument, whose `url` matches `/^\/[a-z(]/` and contains no
  `\d{6,}`.

### F5: HIGH. `live-draft-followed` is mapped to the manual Refresh button, not to following a draft

- **Evidence (verified)**: `LiveStatusBar.tsx:61-77,156-160` show `track()`/`trackDraft` is the
  **Refresh** button ("Refreshing…"/"Refresh"), a force re-poll that is optional and repeatable.
  The comment at line 81 says the room updates on its own over SSE. Following a live draft means
  opening `/drafts/:draftId/live` (`useLiveDraft` at `LiveDraftView.tsx:129`), which is already a
  page view.
- **Impact**: the event counts Refresh clicks: zero for most followers and several for one
  impatient one. It is the double-count R5 itself warns about. It does not meet US2 scenario 1
  ("count of 1, not one per…").
- **Recommend**: drop `live-draft-followed` from v1. The `/drafts/:draft/live` template's page
  views already answer it, and per-draft distinct counts would need the draft id, which FR-006
  forbids. If a Refresh-usage signal is wanted, name it honestly (`live-refresh`) and say it's a
  click count. Amend the spec's US2 list in place.

### F6: HIGH. `league-added` misses the two main add paths

- **Evidence (verified)**: there are three success points.
  - `DraftPicker.tsx:152-177` `setupLeague`: the staged one-click setup from "From Sleeper", the
    onboarding path (multi-user onboarding plan). Success is the end of the stage loop, line 168.
  - `DraftPicker.tsx:190-203` `addDraft`: the pasted id/link. This is the only one the plan names
    (`:196`).
  - `EspnLeagueSetup.tsx:68-74`: the ESPN add, `onAdded?.()` after `addEspnLeague`, mounted at
    `DraftPicker.tsx:630`.
- **Impact**: most league adds would never be counted.
- **Recommend**: fire after the loop in `setupLeague` (with `sport: l.sport`), after `addDraft`'s
  ingest, and in `EspnLeagueSetup` after a successful add (`sport` from the request, plus
  `platform: 'espn'`, which carries no identity). Fire `setupLeague` only on full success, since
  an early `return` on a failed stage must not count (US2 scenario 2).

### F7: MEDIUM. The disclosure line will break `landingHtml.test.tsx`, and the plan doesn't list the static landing

- **Evidence (verified)**: `SignIn.tsx:54-59` says the words must stay in step with
  `landingHtml.ts` `staticBody()`. `landingHtml.ts:109-110` hard-codes the `.signin-note` text.
  `landingHtml.test.tsx:63-66` compares `.signin-note` (and other selectors) between the static
  HTML and the React render. The plan names only `SignIn.tsx` and `SignIn.test.tsx`.
- **Impact**: editing `.signin-note` in SignIn alone fails the FR-018 test. Putting the sentence in
  a new element passes the test, but crawlers and the pre-mount landing don't show the
  disclosure.
- **Recommend**: put the sentence in `landingCopy.ts` as a `privacyLine` constant. Render it in
  both `SignIn.tsx` and `staticBody()` with a class that `landingHtml.test.tsx`'s selector list
  includes. Add `landingCopy.ts`, `landingHtml.ts` and `landingHtml.test.tsx` to the plan's
  source list. `SignIn.test.tsx:106`'s existing assertion survives if the old sentence is kept
  unchanged.

### F8: MEDIUM. FR-008 and the owner opt-out rest on tracker internals the plan marks "assumed", with no fallback in the code

- **Evidence**: R8 relies on `data-domains`, and R6 on `localStorage['umami.disabled']`. Both are
  checks inside Umami's script (inferred, from memory). R6 is explicitly "not re-read". FR-014's
  "off without id" is in our code; the hostname guard is not.
- **Recommend**: put both checks in `analytics.ts` as well. Do nothing unless `location.hostname`
  is in `['www.ballknowers.co','ballknowers.co']`, and do nothing when
  `localStorage['umami.disabled']` (or our own `bk.analytics.off`) is set, inside try/catch. Both
  are unit-testable. Keep `data-domains` as a second layer. The verification pass then confirms
  rather than discovers.

### F9: MEDIUM. The collection host (R7) and real payload capture (SC-005) can only be seen after a production deploy

- **Evidence**: `quickstart.md` local step 1 admits that `data-domains` blocks sends on
  localhost, so the only network-level check is in production. R7 says Umami Cloud "may route
  through another host" (inferred: the cloud script has historically posted to a separate
  gateway host, not `cloud.umami.is`). If so, the CSP fix needs a second deploy.
  `serve.json:8` is report-only, so nothing breaks in the meantime, but FR-011 ("same change")
  would be missed.
- **Recommend**: before building, `curl https://cloud.umami.is/script.js` and read the send
  endpoint, or set `data-host-url` explicitly. Put that host in `connect-src` in the same change.
  For SC-005, allow a dev-only override, such as `VITE_UMAMI_DOMAINS`, that defaults to the two
  production hosts. A local run against a throwaway Umami website can then capture the real
  requests in DevTools, not only a console stub.

### F10: MEDIUM. SC-002's test can't pass as written, and bots aren't addressed

- **Evidence (inferred: Umami derives a session from a salted hash of IP and User-Agent, with
  no cookie)**: "two different browsers" on one machine with the same browser engine (two Chrome
  profiles, or Chrome plus incognito) share IP and UA and count as **one** visitor. Separately,
  the spec's bots edge case ("crawlers … must not be counted") has no line in the plan. Googlebot
  executes JS on the landing page, so the tracker runs. Filtering then depends on Umami's
  server-side bot check (inferred).
- **Recommend**: in `quickstart.md`, require two different UAs (Chrome and Firefox) or two
  networks (desktop and phone on cellular) for SC-002, and don't set the R6 opt-out in either. In
  `research.md`, add an R9 that says "bots: rely on Umami's server-side filter (assumed);
  verification checks the dashboard after Google recrawls, not before". Optionally, skip
  `initAnalytics` when `navigator.webdriver` is true, which is cheap.

### F11: LOW. Route-table matching order

- **Evidence (verified)**: `App.tsx:132-133` has `/mock/new` beside `/mock/:sessionId`, and
  `:159,161` has `/stats` beside `/stats/calculator`. React Router ranks routes by specificity. A
  hand-written first-match loop of `matchPath` calls would send `/mock/new` to the mock-room
  template if the order is wrong.
- **Recommend**: build the table as route objects and use `matchRoutes` (ranked), or add explicit
  test cases for `/mock/new`, `/stats/calculator` and trailing-slash paths. The planned App.tsx
  coverage test checks that each path is present, not that each one wins.

### F12: LOW. Two views on one auction deep link

- **Evidence (verified)**: `DraftView.tsx:81` and `LiveDraftView.tsx:138` call
  `navigate(auctionRoute, { replace: true })` (an NBA auction league goes to
  `/leagues/:id/adp`). A direct `/drafts/<id>` visit counts a draft-room view and then an ADP
  view. In-app links already point at ADP (`destinations.ts:128`), so this only affects old
  bookmarks.
- **Recommend**: accept it and note it in DEPLOY's Analytics section, or skip a view whose
  pathname is replaced within the same tick. Not worth more.

### F13: LOW. Small omissions in the plan's file list and contract

- `web/src/vite-env.d.ts` has to declare `VITE_UMAMI_WEBSITE_ID` (verified: it lists only
  `VITE_API_BASE` and `VITE_API_TOKEN`), or `npx tsc -b` with strict typing reads it as
  `any`/unknown.
- Read the env var inside `initAnalytics` (or pass it as a parameter), not at module scope, and
  export a `resetForTests()` for the de-dupe/queue state. Otherwise `analytics.test.ts` can't
  test the on and off cases in one file.
- The referrer "not our own" check should treat `ballknowers.co`, `www.ballknowers.co` and any
  `*.ballknowers.co` as own, since the apex 302s to www.
- `lineage.current.sleeperLeagueId` changes when the next season is ingested (NFL 2027), so one
  league's series splits at the rollover. Document it in DEPLOY's Analytics section. The root id
  has the mirror problem when deeper history gets ingested, so there is no free fix.

## Checked and found sound

- **AppShell wraps every route**, signed in and signed out, NotFound and `/admin/digest`
  included (`App.tsx:120-205`). It sits outside `<Routes>` and the Keyed* remounts. Its hooks run
  before the signed-out early return (`AppShell.tsx:54-150` vs `:155`). Verified. The problem is
  what it sees signed out (F1/F2), not whether it's mounted.
- **Query-string changes don't move `pathname`**: `LiveDraftView.tsx:754,1055`,
  `WeeklyReport.tsx:86,97`, `PlayerCompare.tsx:98`, `StatLeaderboard.tsx:173`,
  `StatCalculator.tsx:188`, `PlayerPage.tsx:767` all use `setSearchParams`. The SSE live room
  doesn't navigate. SC-004 holds under a pathname-keyed effect. Verified by reading. The mock
  modal's `navigate('/', {state})` (`AppShell.tsx:144`) and `DraftPicker.tsx:121`'s
  replace-to-`/` keep the pathname, so they add no views.
- **`DraftSummary.sport`** exists as `Sport = 'nfl' | 'nba'` (`api.ts:58,350`), and
  `LeagueLineage.current` is a `DraftSummary` (`leagueLineage.ts:19-24`). Verified.
- **The mock call sites are correct**: `MockSetup.tsx:120` and `LiveDraftView.tsx:359` are
  user-clicked, awaited and followed by a navigation. These are the only callers of
  `createMockSession`/`createMockSessionFromDraft`. Verified by grep.
- **CSP**: `web/serve.json:8` is the only CSP. Neither `index.html` nor `landingHtml.ts` has a
  meta CSP. Verified by grep. Since the tracker element is created from bundled JS,
  `script-src 'self' https://cloud.umami.is` suffices.
- **The `Referer` header to the tracker origin is origin-only in production**, because
  `serve.json:11` sets `Referrer-Policy: strict-origin-when-cross-origin`, so the browser can't
  leak a full path there. Verified for the header. This doesn't cover payload fields (see F4).
- **Dockerfile `ARG`/`ENV`** before `npm run build` follows the existing `VITE_API_BASE` pattern
  (`Dockerfile:12-14`), which works in production. That Railway passes service variables as build
  args to a declared `ARG` is inferred from that working precedent, not re-read in Railway's docs.
- **Spec coverage**: FR-001 to FR-014 each map to a plan step, with the F1-F7 corrections above.
  FR-009 and FR-010 are amended in place with a visible note, which is correct. The plan builds
  nothing the spec doesn't ask for.
