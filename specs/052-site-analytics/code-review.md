# Code review: spec 052, site analytics

Bug-hunting review of the uncommitted diff on `052-site-analytics`, plus the untracked `web/src/analytics.ts`
and its three test files. The diff was judged against `spec.md`, `plan.md` (including "Amended after review"),
`data-model.md` and `contracts/analytics-module.md`. Reviewed 2026-10-28.

**How this was checked.** Every touched test file passes: 9 files, 133 tests. `npx tsc -b` is clean. To test
beyond the suite, I wrote throwaway probes in the session scratchpad. They used a scratch vitest config rooted at
`web/` and imported the real modules. No source file in the worktree was edited. The probes:

- drove the real `AppShell` with the real, unmocked `analytics.ts` and a fake `window.umami`;
- ran a patched scratch copy of `analytics.ts` to check the proposed fix for finding 1;
- ran a mutated scratch copy of `AppShell.tsx` against a copy of `AppShell.analytics.test.tsx`.

"Verified" below means I ran it or read the exact code. "Inferred" means I reasoned about it and did not run it.

## Findings

### 1. HIGH: the `league-view` latch is never re-armed, so a return visit to a league page sends nothing (the known bug, confirmed)

- **Evidence:**
  - `web/src/analytics.ts:104` declares `lastLeagueKey`.
  - Only `trackLeagueView` (`:243-245`) writes it, and only `__resetAnalyticsForTests` (`:112`) clears it.
  - `trackPageView` (`:222-224`) moves `lastPageKey` on every route change but leaves `lastLeagueKey` alone.
- **Failure scenario (verified):** I ran this through the real `AppShell` and real `analytics.ts`. The route was
  `/leagues/L1/power`, then `/managers`, then `/leagues/L1/power`. The sends were:
  `["pv:/leagues/:league/power","league-view:/leagues/:league/power","pv:/managers","pv:/leagues/:league/power"]`.
  The second visit to L1 got a page view but no `league-view`.
  - On `/managers`, `useRailLeague` sets the rail to `null` (`railLeague.ts:219-221`).
  - On the way back, the rail resolves again and the effect calls `trackLeagueView` again.
  - The module drops that call because the key equals the stale latch.
- **Impact:** the moves that trigger this are routine: league, then Home, then back to the league, or league, then
  `/managers`, then back. Each one loses a league view. US3's per-league counts are therefore systematically low.
  Because `data-model.md` defines "no league" as page views minus league views, every lost league view is also
  misfiled as "no league".
- **Why the tests miss it:** `AppShell.analytics.test.tsx` mocks `../analytics`. It checks that AppShell *calls*
  `trackLeagueView`, which it does, twice. `analytics.test.ts` never interleaves `trackPageView` and
  `trackLeagueView`. Both suites test their own half, and the bug sits between the halves.
- **Fix:** re-arm the latch whenever the page changes:

  ```ts
  // analytics.ts, trackPageView
  if (key === lastPageKey) return
  lastPageKey = key
  lastLeagueKey = null // a new page view re-arms the league-view latch
  ```

  - **Ordering:** this relies on AppShell's page-view effect (`AppShell.tsx:86-88`) being declared before the
    league-view effect (`:94-115`). React runs effects in declaration order, so the reset lands first. Add a
    comment saying the order is load-bearing.
  - **What it keeps:** same-page de-dupe still holds. A rail re-resolve on the same path, or a StrictMode double
    effect, does not reset the latch, because the page key has not changed.
  - **Fix verified on a scratch copy:** the same sequence gave
    `[..., "pv:/leagues/:league/power", "league-view:/leagues/:league/power"]`.
- **Tests to add:**
  - A unit test in `analytics.test.ts`: page view A, league view A, page view B, page view A, league view A. Expect
    two `league-view` sends.
  - An AppShell test that does **not** mock `../analytics`: use a fake `window.umami` with the host and env
    stubbed, as `analytics.test.ts` does, and drive A, then `/managers`, then A.

### 2. MEDIUM: signing in from a league deep link never sends a `league-view` for that page, and the rail stays empty

- **Evidence:**
  - `useRailLeague`'s effect depends only on `[pathname, hintLeagueId]` (`web/src/railLeague.ts:258`).
  - Signed out, `GET /api/drafts` returns `[]` (`LeagueController.java:90-96`: absent header gives `List.of()`).
  - That `[]` is cached at module scope (`railLeague.ts:81-97`). The resolver then refetches once with
    `force=true`, still signed out, gets `[]` again, and sets the rail to `null`.
  - Sign-in changes `user` but not `pathname`, so the rail never resolves again on that page.
- **Failure scenario (verified, probe):** a signed-out visitor opens a shared `/leagues/L1/power` link and signs
  in. The sends were `["pv:/(landing)/leagues/:league/power","pv:/leagues/:league/power"]`, with
  `getDrafts` called twice, both signed out. No `league-view` is sent, and finding 1's fix does not change that.
  This is exactly spec 050's shared-link funnel: someone opens a league link and signs in. US3 files it as
  "no league".
- **The rail bug predates this spec:** the League section of the rail is also empty on that first signed-in page.
  It recovers on the next navigation, because then the cache misses and a signed-in refetch runs. Spec 052 is what
  turns it into a counting error.
- **Fix:** make the rail resolve again when the user changes:
  1. Call `invalidateRailLeagues()` inside `setUser`/`clearUser` (`user.ts:46-64`), or from AppShell when
     `user?.sleeperUserId` changes.
  2. Add the user id to `useRailLeague`'s inputs and its effect deps.

  Then add an AppShell test: start signed out on `/leagues/L1/power` with `getDrafts` returning `[]` while signed
  out, `act(() => setUser(...))`, and expect one `league-view` for L1.

### 3. LOW: "page views minus league views = no league" overstates "no league" even after fixes 1 and 2 (inferred)

- **Evidence:** `data-model.md` "League view" section says the difference is "no league" (US3 scenario 2). But
  AppShell (`:94-115`) sends a `league-view` only when the rail resolves *and* matches the path. These signed-in
  league-route views send none:
  - a league the signed-in user is not a member of (`/api/drafts` is scoped to the user, so the rail cannot
    resolve it);
  - a `/drafts/:draft` view that redirects to `/adp` (auction) before the rail resolves. That is the documented
    double page view, and its first half gets no league view;
  - a draft id missing from `lineage.seasons`. `railLeague.ts:238` falls back to `lineage.current`, and the
    `sleeperDraftId` comparison at `AppShell.tsx:104-107` then fails.
- **Fix:** document this in DEPLOY.md's Analytics section. "No league" is better read off the route templates:
  anything not under `/leagues/`, `/drafts/` or `/mock/:session`, plus `/(landing)/*`. Page views minus league
  views is an upper bound, not the count.

### 4. LOW: the route-coverage test only sees double-quoted `path="..."`, so a new route written another way slips through

- **Evidence:** `web/src/analytics.test.ts:198` uses `/\bpath="([^"]+)"/g`. In a probe, I appended
  ``<Route path={`/leagues/:sleeperLeagueId/new-page`} …/>`` and `<Route path='/single' …/>` to the `App.tsx`
  source. The match count stayed at 29 (verified).
- **What it does catch:** every route in today's `App.tsx` uses `path="…"`, including the multi-line ones at
  `:165-191`. All 29 are found and all 29 have templates. A new double-quoted route without a template fails the
  test as intended.
- **Failure scenario:** someone adds a route as `path={ROUTE}` or `path={'/x'}`. The test passes, `routeTemplate`
  falls through to `*`, and that page is counted as `/(not-found)` with no warning.
- **Fix:** match `path=\{?\s*['"`]([^'"`]+)['"`]\s*\}?`. Also assert that the number of matched paths equals the
  number of `<Route` occurrences, so a path held in a variable fails loudly.

### 5. LOW: a non-HTTP referrer is sent as the string `"null"` (verified locally; the server-side effect is inferred)

- **Evidence:** `web/src/analytics.ts:206-216` returns `new URL(document.referrer).origin` for any parseable
  referrer that is not our own host. For a non-special scheme the origin is opaque: running
  `new URL('android-app://com.slack/').origin` gives `"null"`.
- **Failure scenario:** Android Chrome sets `document.referrer` to `android-app://<package>/` when a link is opened
  from an app. That covers Slack, Discord, Gmail and similar apps, which is how league links get shared. The
  arrival view then carries `referrer: "null"`. At best the source is lost. If Umami's server fails to parse it,
  the arrival view itself could be dropped (inferred; I did not read the server code).
- **Fix:** return `u.origin` only when `u.protocol` is `http:` or `https:`. Otherwise omit the referrer, or send
  the scheme plus host if app attribution is wanted (`android-app://com.slack`, which carries no identity). Add a
  test case.

### 6. LOW: one assertion in `AppShell.analytics.test.tsx` is a tautology

- **Evidence:** `web/src/components/AppShell.analytics.test.tsx:144-146`. For any call whose path is not
  `/leagues/L2/power`, it asserts `'L1' === 'L1'`, so the league argument of the L1 call is never checked. The
  `toHaveBeenCalledTimes(2)` on `:147` and the `waitFor` on `:141` mostly cover for it.
- **The guard itself is tested (verified by mutation):** I removed the `expected`/`matches` guard
  (`AppShell.tsx:103-108`) in a scratch copy. Two tests in this file failed, "the rail still holding league L1…"
  and "stale rail state…". An unmutated control copy passed 8/8.
- **Fix:** replace the loop with
  `expect(trackLeagueView.mock.calls).toEqual([['/leagues/L1/power', true, 'nfl', 'L1'], ['/leagues/L2/power', true, 'nba', 'L2']])`.

### 7. LOW: `league-added` (via `id`) also counts re-adding a league that is already there (inferred)

- **Evidence:** `web/src/pages/DraftPicker.tsx:199-200` fires after any successful `ingestLeague`. Ingest is
  idempotent (AGENTS.md: "idempotent; do this before trusting any board/sim"), so pasting an existing league's
  id, for example to refresh it, succeeds and counts as a league added.
- **Fix:** before the call, check whether the id is already in `drafts`
  (`drafts.some(d => d.sleeperLeagueId === id)`) and skip the event if so, or send `via: 'id-existing'`. At
  minimum, document it. The one-click path (`:152-177`) and ESPN (`EspnLeagueSetup.tsx:70-72`, gated on
  `result.written`) do not have this problem.

### 8. LOW: the FR-012 disclosure does not mention the key actions

- **Evidence:** `web/src/landingCopy.ts:41-42` says "We count page visits anonymously: which pages and which
  league, never your username, and no cookies." FR-012 asks the site to say "what is and is not collected". The
  site also records `sign-in`, `league-added` and `mock-started`, the sport, and an outside referrer's origin.
- **Fix:** a copy change only, for example "We count page visits and a few actions (signing in, adding a league,
  starting a mock) anonymously: which pages and which league, never your username, and no cookies." It must still
  pass the banned-words guard in `allCopyStrings()`. Both copies pick it up automatically.

## Checked, no finding

**Umami tracker behaviour**, read from the fetched `script.js` (8,575 bytes), so verified by reading:

- **`window.umami` exists with `data-auto-track="false"`.** It is assigned inside `if(!e.umami){… e.umami={track:H,…}}`.
  That runs whatever `I` (auto-track) is set to. Auto-track only gates `G()`: the initial page view, the
  `pushState`/`replaceState` hooks, the click listener and the performance observer.
- **`track(object)` sends the object as given.** `H` is
  `"object"==typeof e ? {...e} : …`, then `F(payload,"event")`. That is a shallow copy with nothing added or
  overridden. The string form is `{...B(),name,data}`, and `B()` includes `document.title` and `url: W(location.href)`,
  the full href. So plan F4 is right, and `analytics.ts` only ever uses the object form.
- **`data-domains` is checked against `location.hostname`.** The disabled check `q()` includes `N&&!D.includes(h)`,
  where `h` is `location.hostname`. It is an exact match, and both `www.ballknowers.co` and `ballknowers.co` are
  listed.
- **Nothing is sent on load:**
  - no page view, because auto-track is off;
  - no identify, because `j&&Q(j)` needs `data-distinct-id`;
  - no performance data, because `R&&X()` sits inside `G()`;
  - no error reports, because that needs `data-errors="true"`. `analytics.test.ts:120-122` asserts it is absent.
- **No fields are missing from our payload.** The tracker's own defaults (`B()`) are `website, screen, language,
  title, hostname, url, referrer, tag, id`. We omit `tag` and `id`, which are optional, and `referrer` except on
  the first view. Our `url` is a relative template while the tracker sends a full href. I believe the server
  resolves `url` against `hostname`, and Umami's docs show a relative `url` in object-form `track` (recalled, not
  re-checked). Confirm this in the production payload capture.
- **The tracker stores nothing.** `localStorage` is only read (`getItem("umami.disabled")`), and the session cache
  lives in memory and travels as an `x-umami-cache` header. FR-005 holds.
- **The opt-out checks differ, harmlessly.** The tracker's check is truthy and ours is `!= null`, so ours is
  stricter: an empty-string value opts out here but not in the tracker.

**CSP** (`web/serve.json`): `script-src https://cloud.umami.is` and `connect-src https://gateway.umami.is` match
the default endpoint `P = "https://gateway.umami.is/api/send"` when `data-host-url` is unset. `curl -I` on the
script URL returned a direct 200 with no redirect (verified). The policy is still Report-Only, so it cannot block
anything yet.

**Queue and load race:** both listeners are attached before `appendChild` (`analytics.ts:187-198`). For a classic
script, `load` fires after it runs, so `window.umami` exists when the queue flushes. `deliver` uses `?.`, so a
stubbed or blocked script that still fires `load` is a no-op. The cap (20) and drop-on-`error` behaviour are both
tested.

**Double or missed page views:**
- **StrictMode and Keyed\* remounts:** the module-level `lastPageKey` de-dupes StrictMode, and the Keyed\*
  wrappers sit below AppShell, which never remounts.
- **Sign-out:** `clearUser()` and `navigate('/')` run in one click handler, so React batches them into one render
  and one landing view (inferred).
- **Replace navigations:** all of them are search-param-only (`LiveDraftView.tsx:762`, `:1063`, and the
  `setParams` calls), so they send nothing. The exception is the auction redirect (`DraftView.tsx:81`,
  `LiveDraftView.tsx:139`), which sends two views, as documented in DEPLOY.md.
- **SC-004, an hour in the live room:** the live room's polling changes neither `pathname` nor `signedIn`.

**Key-action call sites:**
- **Success only:** every call comes after its `await` and before `navigate`.
- **Retry:** a retried one-click setup that succeeds counts once.
- **No double clicks:** the buttons are disabled while a request is in flight (`MockSetup.tsx:307`
  `disabled={creating}`, `LiveDraftView.tsx:825` `disabled: forking …`).
- **`sign-in`:** it fires after `setUser`, and its page is the landing template whatever React does next
  (`analytics.ts:259`).

**Landing prerender and SignIn copy:** both render `analyticsLine` from `landingCopy.ts`, in the same position
after the no-password note. `landingHtml.test.tsx` asserts that both copies contain it.

**Dockerfile:** `ARG VITE_UMAMI_WEBSITE_ID` and `ENV` come before `npm run build`, the same pattern as
`VITE_API_BASE`. If the variable is absent, the `ENV` is empty, `websiteId()` returns `''`, and analytics is off.

**Ids, usernames or full URLs leaving the browser:**
- `url` and `title` always come from `routeTemplate`, including the unmatched case (`/(not-found)`).
- The referrer is reduced to its origin (see finding 5 for the opaque case).
- `data` carries only `route`, `sport`, `league`, `via` and `from`.
- No call site passes anything else.
- `league` is the current-season league id. For ESPN leagues that is ESPN's league id, which `data-model.md`
  allows.

**Tests that pass for the wrong reason:**
- **The enabled path really runs.** `analytics.test.ts`'s "sending" block asserts `track` was called with full
  payloads, so a disabled module would fail it.
- **One environment dependency:** the "sending" tests would turn into `console.debug` calls if a developer's `.env`
  set `VITE_ANALYTICS_DEBUG=1`. No such file exists today; `web/.env.example` has only the API variables.
- **The AppShell guard tests are meaningful.** See the mutation result in finding 6.

## Fixes applied (2026-10-28)

1. **HIGH, latch never re-armed.** `trackPageView` now sets `lastLeagueKey = null` right after
   `lastPageKey = key` (`web/src/analytics.ts`); AppShell carries a comment that the page-view effect
   must stay declared before the league-view effect. Tests: `analytics.test.ts` "a return visit to a
   league page is a new league view" and "a same-page repeat does not re-arm the latch"; and
   `AppShell.analytics.real.test.tsx` (real, unmocked analytics module with a fake `window.umami`),
   league A -> /managers -> A sends two league views. That AppShell test is a new file, because
   `AppShell.analytics.test.tsx` mocks `../analytics` at file level.
2. **MEDIUM, sign-in on a league deep link.** AppShell calls `invalidateRailLeagues()` when the user id
   changes (sign-in or sign-out), in an effect declared before `useRailLeague`, and passes the user id
   to `useRailLeague` as a third argument that is added to its effect deps (`railLeague.ts`). Its other
   behaviour, including "not cleared first", is unchanged. Test: `AppShell.analytics.test.tsx`
   "signing in on a league deep link resolves the rail and sends one league view".
3. **LOW, "no league" overstated.** Documented in DEPLOY.md's Analytics section.
4. **LOW, route-coverage regex.** It now accepts `path="..."`, `path='...'`, `path={'...'}` and
   `path={"..."}`, and asserts the extracted count equals the number of `<Route` occurrences
   (`analytics.test.ts`, "has a template for every <Route path>"). A path held in a variable or a
   template literal makes that count assertion fail.
5. **LOW, non-HTTP referrer.** `outsideReferrer` keeps a referrer only for `http:` or `https:`. Test:
   `analytics.test.ts` "a non-web referrer (an Android app) is dropped", with `android-app://com.slack/`.
6. **LOW, tautological assertion.** Replaced with an exact `toEqual` on `trackLeagueView.mock.calls`
   (`AppShell.analytics.test.tsx`, "the rail still holding league L1...").
7. **LOW, `league-added` via id counts a re-paste.** Documented in DEPLOY.md.
8. **LOW, disclosure copy.** `analyticsLine` now reads "We count page visits and a few actions (signing
   in, adding a league, starting a mock) anonymously: which pages and which league, never your username,
   and no cookies." The tests import the constant, so `landingHtml.test.tsx` and `SignIn.test.tsx` pick
   it up unchanged and still pass.

**Differences from the review:** for #2 the invalidation lives in AppShell, not in `setUser`/`clearUser`,
because `user.ts` is imported by `api.ts` and `railLeague.ts` imports `api.ts`, so calling into
`railLeague` from `user.ts` would be a circular import. For #5 the referrer is omitted, with no
scheme-plus-host fallback.

**Verification:** `npx tsc -b`, `npm test` (156 files, 2724 tests) and `npm run build` all pass.
