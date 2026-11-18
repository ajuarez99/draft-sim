# Adversarial plan review: 076 Usage analytics we own

**Date**: 2026-11-17 · **Reviewer**: adversarial plan-review pass (read cold, no 076 code exists)

**Inputs read**: `AGENTS.md`; `specs/076-usage-analytics/` `spec.md` (with its "Amended at plan time" block),
`plan.md`, `research.md`, `data-model.md`, `contracts/usage-api.md`, `quickstart.md`,
`checklists/requirements.md`; `specs/052-site-analytics/plan-review.md` (style reference). Code, all in this
worktree at d6a2ac4: `web/src/analytics.ts`, `analytics.test.ts`, `components/AppShell.tsx`,
`AppShell.analytics.real.test.tsx`, `TimeMachineShell.tsx`, `App.tsx`, `site.ts`, `siteEnv.ts`, `api.ts`
(request plumbing and the historical calls), `pages/AdminDigest.tsx`, `HistoricalDraft.tsx`, `SeasonPage.tsx`,
`components/HistoricalResults.tsx`, `DailyCards.tsx`, `draftSlot.parity.test.ts`, `web/Dockerfile`,
`web/serve.json`, `web/tsconfig.json`, `web/package.json`; backend `historical/HistoricalGameRepository.java`,
`HistoricalGameService.java`, `HistoricalDailyService.java`, `Identity.java`, `api/HistoricalGameController.java`,
`ApiTokenFilter.java`, `LeagueLockInterceptor.java`, `AdminGateInterceptor.java`, `config/WebConfig.java`,
`application.yml`, migrations V43, V46, V48, V49, V50, `LeagueLockMappingsIT.java`; `railway.toml`, `DEPLOY.md`.

**Checks I ran** (read-only, nothing written to any database or tracked file):
- Highest migration on every local and remote branch (`git ls-tree` loop) and in every sibling worktree's
  working tree: V50 everywhere, no V51 anywhere.
- `psql` SELECTs against `localhost:5433/draftsim`: Flyway version, `\d league`, league indexes, the planned
  funnel query, and the `at time zone 'America/New_York'` day bucketing on both 2026 DST edges.
- `grep` for every delete, truncate and insert on `historical_game` in `backend/src` (main and test).
- Railway's public-networking "specs and limits" page, fetched, for the headers its proxy sets.

**VERIFIED** = I ran or read something that shows it. **REASONED** = argument only.

## Summary

| ID | Sev | Area | One line |
|---|---|---|---|
| F1 | HIGH | Migration | `started_as_guest` NOT NULL with the default dropped breaks game creation for every backend that does not know the column: the old container during a deploy, a rollback, and every other branch's ITs on the shared 5433 |
| F2 | HIGH | Vocabulary | The vocabulary is "what `routesFor(site)` exports", which has no `/(landing)…` templates, so every signed-out view on the league and combined sites is refused |
| F3 | HIGH | Abuse limit | The limiter keys on the first `X-Forwarded-For` hop; Railway documents `X-Real-IP`, the leftmost XFF value is normally the client's to set, and what happens at the 10,000 cap is unspecified |
| F4 | MEDIUM | Honesty | "Abandoned" is only an explicit button press and nothing ever expires a `DRAFTING` row, so walk-aways read as "still drafting" forever; "finished" includes one-press auto-finish; SC-002's guest script is only possible one way |
| F5 | MEDIUM | Honesty | "The server has no opt-out signal" for the owner's games is false: `draftsim.owner.sleeper-user-id` exists |
| F6 | MEDIUM | Scope | FR-006 ("the new events go to both") is not amended but the plan sends two of three to Umami only, a sink that has never been on; a resumed daily game cannot be told from a new one in the client |
| F7 | MEDIUM | Abuse limit | R5 says the limit counts accepted sends, the contract checks it before parsing; refused sends write to the database; no global ceiling against a 10-connection pool; quickstart step 6 cannot give "rises by 60" as written |
| F8 | MEDIUM | Vocabulary | The copy's guard is a vitest test that nothing runs automatically (no CI, not in the Docker build), and a bad JSON resource can stop the whole API from starting |
| F9 | MEDIUM | analytics.ts | The two-sink split leaves the three early guards, debug mode, and the existing tests' stubbed production host unspecified; FR-010 names the existing debug switch, the plan adds a new one |
| F10 | LOW | Contract | "Always 204" is not true upstream of the controller (401 from the token filter, 403 from CORS) |
| F11 | LOW | Contract | Three spellings of a sport on one page; data-model and contract disagree on the guest and daily funnel fields |
| F12 | LOW | Privacy | A same-origin send carries the full page address in `Referer` (dev and the combined site), which SC-004's capture will find |
| F13 | LOW | Verification | ITs and live verification share one database: IT game rows and IT usage rows land in the same day's numbers; `countingSince` includes `rejected` rows |
| F14 | LOW | Doc drift | Wrong test name, files missing from the structure list, a false reason in R7, the opt-out is per origin |

Checked and **OK** (no finding):
- **Migration number.** V50 is the highest on `main`, on every branch and in every worktree's working tree. V51 is
  free. Spec 075 has no branch or worktree. (VERIFIED)
- **No production code deletes or reuses a game row.** The only `delete from historical_game` statements are IT
  clean-ups by `it-…` prefix. Starting a game never abandons or overwrites another. (VERIFIED, grep and read)
- **`created_at` means "started".** Set from `OffsetDateTime.now()` in the same transaction as the insert
  (`HistoricalGameService.java:256-258`); a refused or failed create rolls back, so US2 scenario 2 holds. (VERIFIED)
- **Claim does not merge or delete.** `moveOwner` updates `sleeper_user_id` only
  (`HistoricalGameRepository.java:267-277`); daily games it cannot move stay under the guest key. A column set at
  insert survives it. (VERIFIED)
- **`guest:` is the real marker and a helper exists**: `Identity.isGuest` / `Identity.GUEST_PREFIX`
  (`Identity.java:20,75`). A Sleeper id starting with `guest:` is refused, so the backfill's `like 'guest:%'` is exact
  for rows not yet claimed. (VERIFIED)
- **One insert site.** `HistoricalGameRepository.create` (line 84) is the only `insert into historical_game` in main
  or test code. Deriving the flag inside it changes no signature, so the 12-argument callers in
  `HistoricalRepositoryIT:187` and `SportCoexistenceIT:174` keep compiling. (VERIFIED) The runtime problem is F1.
- **The funnel SQL runs.** Executed against 5433: grouped by `(created_at at time zone 'America/New_York')::date`
  and sport, it returned rows. (VERIFIED)
- **Time zone.** `at time zone 'America/New_York'` on a `timestamptz` and `LocalDate.now(clock.withZone(ZONE))`
  agree, including both DST edges (2026-03-08 04:59:59Z is 03-07, 05:00:00Z is 03-08; 2026-11-01 05:30Z is 11-01).
  The session zone is `America/Chicago` locally and does not affect the result. (VERIFIED)
- **League lookup.** `league.sleeper_id` has a unique index, so `leagueSport(leagueId)` is at most one row. The
  client sends the lineage's current league id (`AppShell.tsx:127`), one id per league. (VERIFIED)
- **CORS** already allows `POST`, `Content-Type` and `Authorization` on `/api/**` (`WebConfig.java:60-80`), and
  `ApiTokenFilter` passes `OPTIONS`. `connect-src` lists the API host, and the policy is report-only anyway. (VERIFIED)
- **Admin gate.** `/api/admin/**` is behind `AdminGateInterceptor` and on the lock's public list, and
  `LeagueLockMappingsIT.everyOtherRouteRefusesACallerWithNoCredentials` will walk the new GET without being told. (VERIFIED)
- **Admin page reachability.** `/admin/digest` sits inside `leaguePages`, so on the league site it needs the unlock,
  a Sleeper sign-in and the token typed on the page. `/admin/usage` beside it inherits exactly that; the Redraft tree
  forwards `/admin/*`. (VERIFIED, `App.tsx`)
- **No attacker text on the owner page**, provided props are rebuilt from validated values as the data model says:
  site, route, name and prop values all come from the closed vocabulary and the league id from the table. Rows are
  bounded by vocabulary × days × leagues. Counts are not bounded, which the plan says. (REASONED)
- **Idle lock.** A bare `fetch` outside `apiFetch` sends no session header, so a background send cannot keep a league
  session alive. (VERIFIED by reading `api.ts:242-262`)
- **Share call site.** `ShareButton` renders only for the owner's own page (`HistoricalResults.tsx:447,507`), never
  on `SharedResult`. Sport, daily (`game.dailyDate`) and guest-or-member (`useUser()`) are all knowable there. (VERIFIED)
- **Own sends off localhost and preview hosts**: `OWN_HOSTS` is the three production names only. (VERIFIED)

## Findings

### F1 (HIGH, VERIFIED for the mechanism, REASONED for the deploy timing) - dropping the default on `started_as_guest` breaks every backend that does not know the column

- **Where**: `data-model.md` "New column" ("then the default is dropped so every insert must say");
  `HistoricalGameRepository.java:84-88` (the insert names its columns and does not name this one).
- **Why it fails**: after V51, any process running pre-076 code inserts a game row with no value for a NOT NULL
  column that has no default, and Postgres refuses it. Three places that happens:
  1. **Deploy.** The backend has a health check (`railway.toml`), so the old container keeps serving until the new
     one is healthy, and `DEPLOY.md:559` already records containers overlapping. Flyway runs at the start of the new
     container's boot. For the rest of that boot, every "start a game" on the public site is a 500.
  2. **Rollback.** Redeploying the previous build after V51 leaves Redraft unable to start any game until someone
     writes a fix-forward migration.
  3. **The shared local database.** Quickstart step 1 applies V51 to `localhost:5433/draftsim`, which every other
     session uses (it is at V49 today). From then on every other branch's `HistoricalGameMvcIT`, `NflGameFlowIT`,
     `RedraftGuestMvcIT` and the rest fail on their first create.
- **Precedent, not a defence**: V46 did the same to `sport`. That was before the game was the public product.
- **Change**: make the column **nullable with no default**. Backfill existing rows as planned. A row written by old
  code gets null, which means "not recorded", and the page shows it as its own small bucket instead of calling it
  signed-in. Add an IT that creates one guest and one signed-in game through the service and asserts the stored
  value is non-null and right; that test is what "every insert must say" was for. If a default is preferred, keep
  `default false` permanently and say on the page that the split undercounts guests for rows written by old code.
  Either way, do not drop a default in the same deploy that adds the column.

### F2 (HIGH, VERIFIED) - the vocabulary as defined refuses every signed-out view on the league and combined sites

- **Where**: `plan.md` Web section ("asserts `usage-vocabulary.json` equals what `analytics.ts` exports (routes per
  site…)"); research R4 ("Routes are defined once, in `routesFor(site)`"); `analytics.ts:125-133`.
- **Why it fails**: `routesFor(site)` returns the table entries. What is actually sent is `routeTemplate()`'s output,
  and for a signed-out visitor on any site but Redraft that is `/(landing)` or `/(landing)` + the entry's url
  (`analytics.ts:131-132`). None of those strings is in any table. `AppShell` fires `trackPageView` signed out on
  purpose (`AppShell.tsx:91-102`). So the landing page of the league site and every shared link opened signed out go
  to the `rejected` counter. Those are the views spec 052's F1 was written to keep.
- **Also unspecified**: whether a "route" is the entry's `path` (`/leagues/:sleeperLeagueId/power`) or its `url`
  (`/leagues/:league/power`). Only `url` is ever sent.
- **Change**: export from `analytics.ts` the closed set of strings `routeTemplate` can return per site: every `url`,
  `/(not-found)`, and for `leagues` and `combined` also `/(landing)` and `/(landing)<url>` for every entry. Generate
  the JSON from that. Add a test that walks every table entry × `signedIn` true/false × site, calls
  `routeTemplate`, and asserts the result is in the vocabulary. Say in the data model that the wire `route` is the
  `url` form.

### F3 (HIGH, VERIFIED that the header is undocumented and nothing is configured; REASONED for the spoof) - the limiter's address source is a guess, and the wrong guess fails in either direction

- **Where**: research R5 ("first `X-Forwarded-For` hop behind Railway's proxy, else the socket address");
  `plan.md` `UsageLimiter` ("map capped at 10,000, swept on access").
- **Evidence**: Railway's "specs and limits" page lists `X-Real-IP` "for identifying client's remote IP",
  `X-Forwarded-Proto`, `X-Forwarded-Host`, `X-Railway-Edge`, `X-Railway-Request-Id` and `X-Request-Start`. It does
  not mention `X-Forwarded-For`. No `forward-headers-strategy`, `X-Forwarded` or `getRemoteAddr` appears anywhere in
  `backend/src/main` or `application.yml` today, so nothing in this code base has ever read a client address in
  production.
- **Why it fails**:
  - If the proxy passes a client-supplied `X-Forwarded-For` through and appends to it (the usual behaviour), the
    first hop is whatever the sender typed. One script, one header per request, and the limit never applies. Each
    send is then a database write (F7).
  - If the header is absent, every visitor shares the proxy's socket address and the site as a whole is capped at
    60 sends a minute, silently.
  - SC-006's burst check passes locally (socket address) whichever of these is true in production.
- **At the cap**: "capped at 10,000" has no stated behaviour. Refusing new addresses lets a spoofer lock real
  visitors out for a minute at a time; evicting lets the limit be reset at will. An IPv6 sender owns more than
  10,000 addresses without spoofing anything.
- **Change**: key on `X-Real-IP`, falling back to the socket address. Before trusting it, check it once on the real
  deployment (the verification pass can compare the limiter's behaviour from two networks; nothing needs logging).
  State the at-cap rule: new addresses are refused until the window turns. Add a **global** ceiling on accepted
  writes per minute (hand-set, labelled) so the worst case is bounded whatever the address source does. Say on the
  owner page that the per-address limit is best effort.

### F4 (MEDIUM, VERIFIED) - "abandoned" and "still drafting" will not mean what the owner reads them as

- **Where**: spec amendment ("It also shows abandoned and still-drafting", "SC-002 gains: 1 abandoned");
  `plan.md` layout item 5; `HistoricalGameService.java:445-456`.
- **What the rows say**:
  - `ABANDONED` is written only by the abandon route, which the player has to press. The game of the day refuses it
    (`:451-454`). Nothing expires a `DRAFTING` row.
  - So a visitor who closes the tab is "still drafting" for ever. For any start day but today, "still drafting" is
    the walk-away count and "abandoned" is the much rarer "pressed the button" count. A reader will assume the
    reverse.
  - `COMPLETE` includes a game finished by one press of auto-finish (`auto(…, FINISH)`, `:412`). The row cannot tell
    that from a hand draft: auto picks are stored with seat type `USER`.
- **SC-002 as amended**: a guest may own one non-daily game ever, abandoned ones included
  (`createFor`, `:158-165`), plus one daily game per sport per day, which cannot be abandoned. So "2 NBA games, 1
  finished, 1 abandoned, all guest" is possible in exactly one way: finish the daily, abandon the chosen season.
  Quickstart step 3 does not say so, and a second run on the same day needs a new guest id.
- **Change**: on the page, show "not finished" (abandoned + drafting) as the headline next to finished, with the two
  parts as detail, and word them "gave up (pressed abandon)" and "left open". Say that finished includes
  auto-finished drafts. Write the one workable order into quickstart step 3.

### F5 (MEDIUM, VERIFIED that the setting exists; not checked whether production sets it) - the owner's games can be told apart

- **Where**: `plan.md` "Not being built" ("Excluding the owner's own games from the funnel: the server has no
  opt-out signal"); `application.yml` `draftsim.owner.sleeper-user-id` (`APP_OWNER_SLEEPER_USER_ID`),
  `config/OwnerProperties.java:16`.
- **Why it matters**: traffic is unknown and may be tiny. Until launch every game row is the owner's or a
  verification run's, so a funnel that "goes back to spec 053's launch" is mostly his own play. A caveat line does
  not stop the totals reading as usage.
- **Change**: when the owner id is configured, report his signed-in games as their own column (or leave them out and
  say how many were left out). When it is blank, say on the page that it is not set. His guest-mode games stay
  indistinguishable; say that too. This is a choice about what the number is, so it is Allan's.

### F6 (MEDIUM, VERIFIED) - FR-006 still says the new events go to both sinks; two of them are planned for a sink that has never run

- **Where**: spec FR-006 ("The new Redraft events go to both"), not touched by the amendment block; research R2 and
  `plan.md` ("the two game events: Umami only"); `data-model.md` "Hosted service additions".
- **Why it is a problem**:
  - Spec and plan contradict each other, and the amendment block lists what changed without listing this.
  - `game-started` and `draft-finished` need new call sites in four files and new once-only logic ("a game this tab
    saw as `DRAFTING` turns `COMPLETE`"), for a service with no website id. Nothing in the verification pass can
    observe them except the debug console. That is this repo's "has no caller yet is not the same as works" class.
  - The client cannot tell a new daily game from a resumed one: `POST /api/history/daily` answers 201 or 200
    (`HistoricalGameController.java:204-205`) and `startHistoricalDaily` throws the status away (`api.ts:4925-4930`).
    As planned, every press of Play on a started daily game is a `game-started`.
  - If Umami is ever switched on, it will show a `game-started` total that differs from the owner page's "started",
    under the same name. R2 rejects that for our own store and then builds it for the other one.
- **Change (Allan's call)**: either drop the two Umami-only events from v1 and amend FR-006 to say only
  `result-shared` is new, or keep them and specify: fire `game-started` only when the response was a 201 (carry the
  status through `startHistoricalDaily`), and name the Umami events differently from the owner page's labels.

### F7 (MEDIUM, VERIFIED for the contradiction, REASONED for the load) - what the limiter counts, and what a refused send costs

- **Where**: research R5 ("60 **accepted** sends per minute"); `contracts/usage-api.md` order of checks
  (crawler → burst → parse → vocabulary → league → upsert, with a refused send adding to `rejected`);
  `application.yml` (`maximum-pool-size` 10); quickstart step 6.
- **Why it fails**:
  - The two documents describe different limiters. If only accepted sends count, junk is unlimited and each junk
    send with a known site is an upsert on the `rejected` row. If every send counts (the contract's order), a
    visitor behind a shared address can have their real views crowded out by someone else's junk.
  - Every send, good or refused, takes a connection from the same 10-connection pool the game and the league pages
    use. With F3 open there is no ceiling on that.
  - Quickstart step 6 sends 1,000 junk requests and then 200 valid ones from the same address. Under the contract's
    order the junk has already used the window, so the valid burst adds fewer than 60. With a fixed window, a burst
    that crosses a minute boundary adds up to 120. "Rises by 60" is not a result this design gives reliably.
- **Change**: say the limiter counts every send that reaches it. Keep the `rejected` counter in memory and write it
  at most once a minute, or write it under the same global ceiling as F3. Take a `Clock` in the test and assert the
  cap there; in the quickstart, wait a minute between the junk and the burst and accept "between 60 and 120" or
  drive it through the IT instead.

### F8 (MEDIUM, VERIFIED) - the vocabulary copy's only guard runs when someone remembers to run it

- **Where**: research R4; `plan.md` `usageVocabulary.test.ts`; `web/Dockerfile` (build is `npm run build`, which is
  `tsc -b && vite build`, no tests; build context is `web/`, so `backend/` is not even present);
  `.github/workflows/` (only `daily-refresh.yml`).
- **Why it matters**: nothing between a commit and production runs vitest. A route added to `analytics.ts` without
  the JSON ships, and the result is refused sends, visible only as a number on a page the owner has to open. That is
  the plan's own "known cost", but the plan presents the test as what prevents it.
- **Two traps in writing the test** (from `draftSlot.parity.test.ts:11-18`, the one existing test that reads a file
  this way): the web project has no `@types/node`, so a static `import … from 'node:fs'` fails `tsc -b` and with it
  the production build; and that test skips when its file is missing, which here would turn a moved or renamed JSON
  into a silent pass. vitest runs with cwd `web/`, so the path is `../backend/src/main/resources/usage-vocabulary.json`.
- **Backend side**: a resource that fails to parse at bean construction stops the application context, and with it
  the whole API, over an analytics file. No backend test is planned for it.
- **Change**: the vitest test must fail, not skip, when the file is absent, and load `node:fs` dynamically as the
  parity test does. Add a JUnit test that loads the real resource and asserts every site has routes and every event
  has its props. Make `UsageVocabulary` fail soft (log once, refuse everything) rather than fail startup. State in
  the plan and `DEPLOY.md` that the guard is local, and add "run vitest" to the deploy steps for any route change.

### F9 (MEDIUM, VERIFIED) - where the two-sink split of `enabled()` breaks unless the plan says more

- **Where**: `plan.md` Web section, first bullet; `analytics.ts:171-175, 198-211, 261-309`; spec FR-010.
- **Points the plan leaves open**:
  1. **The early guards.** `trackPageView`, `trackLeagueView` and `trackAction` each start with
     `if (!enabled()) return` (`:263, :287, :301`), before the latches are touched. They must become "neither sink
     is on". If they stay tied to Umami, our store records nothing in production, which is today's state.
  2. **Debug mode.** `enabled()` returns true under `VITE_ANALYTICS_DEBUG` and `send` then logs and returns
     (`:173, :201-204`). The plan does not say whether the own sink sends, logs or stays quiet under that switch.
  3. **FR-010 against R6.** The spec says non-production hosts send nothing "apart from the existing local debug
     switch". The plan adds a second switch, `VITE_USAGE_COLLECT`. One of the two documents has to change, and the
     new switch must be gated on `import.meta.env.DEV` as `debugMode()` is, or a production build with the variable
     set would send from any host.
  4. **Existing tests.** `analytics.test.ts:24-26, 56-57` and `AppShell.analytics.real.test.tsx:32` stub the
     hostname to `www.ballknowers.co`. After the split every one of them reaches a real `fetch`. The case "without
     a website id: no script, window.umami untouched" (`:55`) stays true but no longer means "nothing is sent". The
     plan lists no test changes for these files.
  5. **`basePayload`** carries `website`, `hostname`, `language` and `screen` (`:179-188`). The own body must be
     built separately, not by trimming that object, or a later field added for Umami leaks into ours. "Builds its
     record once and hands it to both sinks" reads as the opposite.
  6. **The module's header comment** (`:6-21`) says this is the only module that knows the service exists and that
     it is off unless a website id is set. Both statements become false and the comment is what the next reader
     trusts.
- **What holds**: the opt-out is read in one function, so it covers both sinks if both `umamiEnabled()` and
  `ownEnabled()` call it. Sharing the latches is right. The Umami queue and `firstViewSent` stay Umami-only.
- **Change**: add these six to the plan as explicit decisions, and a test list: own sink on with no website id; both
  on; opt-out stops both; localhost sends nothing without the dev switch; a stubbed `fetch` in the two existing
  test files.

### F10 (LOW, VERIFIED) - "always 204" is only true once the request reaches the controller

- **Where**: `contracts/usage-api.md` ("Always answers `204 No Content`… Never 4xx for content, never a body").
- **Evidence**: with `API_TOKEN` set, a request without the bearer gets 401 and a JSON body from
  `ApiTokenFilter.java:51-56` before any controller runs. A request with an `Origin` outside `CORS_ORIGINS` gets
  Spring's 403. Neither can be changed from `UsageController`.
- **Change**: word the contract as "every request that reaches the handler answers 204". The browser side is
  unaffected: both are swallowed.

### F11 (LOW, VERIFIED) - three spellings of a sport, and two documents that disagree on the funnel fields

- `historical_game.sport` is `NBA` / `NFL`; `league.sport` is `nfl` (read from 5433); the `result-shared` prop is
  `nba` / `nfl`. The page puts shares "beside" the funnel per sport and league views in a third table. Pick one form
  in the report record and convert in one place on the server.
- `data-model.md` says the funnel row has "the same four [started, finished, abandoned, drafting] restricted to
  guest starts, and to games of the day". The contract has only `guestStarted`, `guestFinished`, `dailyStarted`,
  `dailyFinished`. With F1's nullable column a "not recorded" count is needed as well.

### F12 (LOW, REASONED) - `Referer` on a same-origin send is the full page address

- `web/serve.json` sets `strict-origin-when-cross-origin`, so production (cross-origin to the API) sends only the
  origin. Under the vite proxy, and on the combined site, the send is same-origin and the browser attaches the whole
  address, game id included. Quickstart step 4 is run in exactly that setup, and SC-004 says the capture holds no
  real page address.
- **Change**: pass `referrerPolicy: 'no-referrer'` on the send. One line, and it makes SC-004 true everywhere.

### F13 (LOW, VERIFIED) - tests and live verification share one database and one "today"

- The backend ITs run against `localhost:5433/draftsim`, the same database the quickstart reads. One of the three
  `historical_game` rows there today is an IT's (`it-…` owner). A suite run during verification adds and removes
  dozens of games on today's date, and the harness IT leaves its own.
- New usage ITs that call the real upsert with the real clock will add to today's `usage_daily` rows, which is what
  SC-001 and SC-007 count.
- `countingSince` is `min(day)` over the whole table, `rejected` rows included, so one junk send before the first
  real view sets the "counting since" date.
- **Change**: usage ITs take a `Clock` fixed to a far-past day (as `HistoricalDailyMvcIT` does with dates before
  1900) and delete their rows. Verification states the funnel before and after and reports the difference.
  `countingSince` reads `view`, `league-view` and `event` rows only.

### F14 (LOW, VERIFIED) - doc drift

- The test is `LeagueLockMappingsIT`, not `LeagueLockMappingsTest` (plan, R7). The edit it needs is `/api/usage` in
  `EXPECTED_PUBLIC` (`:33-36`). It skips when Postgres is down, so check the skip count.
- R7 says the public-path entry is needed because "a locked league site still counts its landing view". It does
  not: locked, `LeaguesTree` renders `LeaguesLock` in place of `AppShell` and no view is sent (`App.tsx`,
  `@routes:leagues`). The entry is needed because the send carries no session header and the Redraft site never has
  one.
- "Project Structure" omits files the design section changes: `HistoricalDraft.tsx`, `SeasonPage.tsx`,
  `components/DailyCards.tsx` (the three start call sites), `landingCopy.ts:46` and `redraftCopy.ts:220` (the two
  privacy lines), `LeagueLockInterceptor.java`, `HistoricalGameRepository.java`, the two existing analytics test
  files.
- The owner opt-out is per origin and per browser (`DEPLOY.md:387`). The caveat block on the page should say that it
  has to be set on both sites, and that it never applies to the funnel.
- A route removed from `analytics.ts` has no label left to show; the page needs a fallback to the stored template
  (spec edge case "its past counts stay under the old name").
- The local database is at V49, so quickstart step 1 applies V50 as well as V51.

## To the parent session

**Count**: 0 CRITICAL, 3 HIGH, 6 MEDIUM, 5 LOW.

**Need Allan's decision** (they change what the feature is or what a number means):
- **F5**: split out or exclude the owner's own games now that a signal exists.
- **F6**: drop the two Umami-only game events from v1, or keep them and amend FR-006.
- **F4**: how "abandoned" and "still drafting" are presented, since the rows cannot say "walked away".
- **F1** is a design fix, not a product choice, but it applies a migration to the shared 5433 database, so the
  quickstart's first step should wait for the fix.

The rest (F2, F3, F7 to F14) fold into the plan with a dated "amended after review" note.
